package com.fallgist.nishinomiyalibrary.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fallgist.nishinomiyalibrary.data.local.dao.SyncLogDao
import com.fallgist.nishinomiyalibrary.data.local.entity.SyncLogEntity
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SyncResult
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import kotlinx.coroutines.CancellationException

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
            when (val result = dependencies.statusRepository().syncAll(SyncTrigger.SCHEDULED)) {
                is SyncResult.SkippedCooldown -> Result.success()
                is SyncResult.Completed -> {
                    when (syncWorkerDecision(result, runAttemptCount)) {
                        SyncWorkerDecision.SUCCESS -> Result.success()
                        SyncWorkerDecision.RETRY -> Result.retry()
                        SyncWorkerDecision.FAILURE -> Result.failure()
                    }
                }
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

fun syncWorkerDecision(result: SyncResult.Completed, runAttemptCount: Int): SyncWorkerDecision = when {
    result.isCompleteSuccess -> SyncWorkerDecision.SUCCESS
    runAttemptCount < MAX_RETRY_COUNT -> SyncWorkerDecision.RETRY
    else -> SyncWorkerDecision.FAILURE
}

private const val MAX_RETRY_COUNT = 2

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncWorkerEntryPoint {
    fun statusRepository(): StatusRepository

    fun syncLogDao(): SyncLogDao

    fun clock(): Clock
}
