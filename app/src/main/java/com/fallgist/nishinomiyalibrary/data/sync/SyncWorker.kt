package com.fallgist.nishinomiyalibrary.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fallgist.nishinomiyalibrary.data.local.dao.SyncLogDao
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.local.entity.SyncLogEntity
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdateCoordinator
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdateResult
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdateTrigger
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SyncResult
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Hilt Worker依存を使わず、EntryPoint経由で同期処理を解決するWorker。 */
class SyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext,
            SyncWorkerEntryPoint::class.java,
        )
        return try {
            // SyncResultはCompleted一種類のみになった(SkippedCooldownは撤廃済み)。
            val execution = executeScheduledSync(
                autoReservationEnabled = dependencies.settingsStore().settings.first().autoReservationEnabled,
                sync = { dependencies.statusRepository().syncAll(SyncTrigger.SCHEDULED) as SyncResult.Completed },
                update = { dependencies.newArrivalUpdateCoordinator().refresh(NewArrivalUpdateTrigger.SCHEDULED) },
            )
            val decision = execution.updateResult?.let { updateResult ->
                syncWorkerDecision(execution.syncResult, updateResult, runAttemptCount)
            } ?: syncWorkerDecision(execution.syncResult, runAttemptCount)
            when (decision) {
                SyncWorkerDecision.SUCCESS -> Result.success()
                SyncWorkerDecision.RETRY -> Result.retry()
                SyncWorkerDecision.FAILURE -> Result.failure()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            if (runAttemptCount >= MAX_RETRY_COUNT) {
                recordFatalFailure(dependencies.syncLogDao(), dependencies.clock())
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    private suspend fun recordFatalFailure(syncLogDao: SyncLogDao, clock: Clock) {
        val now = clock.millis()
        syncLogDao.insert(
            SyncLogEntity(
                startedAtEpochMillis = now,
                finishedAtEpochMillis = now,
                trigger = SyncTrigger.SCHEDULED.name,
                succeeded = false,
                details = "不明なエラー",
            ),
        )
    }

}

enum class SyncWorkerDecision { SUCCESS, RETRY, FAILURE }

data class ScheduledSyncExecution(
    val syncResult: SyncResult.Completed,
    val updateResult: NewArrivalUpdateResult?,
)

suspend fun executeScheduledSync(
    autoReservationEnabled: Boolean,
    sync: suspend () -> SyncResult.Completed,
    update: suspend () -> NewArrivalUpdateResult,
): ScheduledSyncExecution {
    val syncResult = sync()
    val updateResult = if (autoReservationEnabled) update() else null
    return ScheduledSyncExecution(syncResult, updateResult)
}

fun syncWorkerDecision(result: SyncResult.Completed, runAttemptCount: Int): SyncWorkerDecision = when {
    result.isCompleteSuccess -> SyncWorkerDecision.SUCCESS
    runAttemptCount < MAX_RETRY_COUNT -> SyncWorkerDecision.RETRY
    else -> SyncWorkerDecision.FAILURE
}

fun syncWorkerDecision(
    syncResult: SyncResult.Completed,
    updateResult: NewArrivalUpdateResult,
    runAttemptCount: Int,
): SyncWorkerDecision = when (updateResult) {
    NewArrivalUpdateResult.RefreshFailed ->
        if (runAttemptCount < MAX_RETRY_COUNT) SyncWorkerDecision.RETRY else SyncWorkerDecision.FAILURE
    is NewArrivalUpdateResult.AutomaticFailed -> SyncWorkerDecision.FAILURE
    is NewArrivalUpdateResult.Completed,
    NewArrivalUpdateResult.AlreadyRunning,
    NewArrivalUpdateResult.FreshnessSkipped,
    -> SyncWorkerDecision.SUCCESS
}

private const val MAX_RETRY_COUNT = 2

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncWorkerEntryPoint {
    fun statusRepository(): StatusRepository

    fun settingsStore(): SettingsStore

    fun newArrivalUpdateCoordinator(): NewArrivalUpdateCoordinator

    fun syncLogDao(): SyncLogDao

    fun clock(): Clock
}
