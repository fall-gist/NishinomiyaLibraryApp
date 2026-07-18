package com.fallgist.nishinomiyalibrary.data.sync

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.fallgist.nishinomiyalibrary.data.local.AppSettings
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
) {
    suspend fun scheduleFromSettings() {
        schedule(settingsStore.settings.first())
    }

    fun schedule(settings: AppSettings) {
        val request = createDailySyncWorkRequest(clock, settings)
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "daily_library_sync"
    }
}

fun createDailySyncWorkRequest(clock: Clock, settings: AppSettings) =
    PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        )
        .setInitialDelay(nextScheduleDelay(clock, settings.syncHour, settings.syncMinute).toMillis(), TimeUnit.MILLISECONDS)
        .build()

/** 次の指定時刻までの待機時間を、境界条件を含めて決定する純粋関数。 */
fun nextScheduleDelay(clock: Clock, hour: Int, minute: Int): Duration {
    require(hour in 0..23) { "同期時刻の時は0から23で指定してください" }
    require(minute in 0..59) { "同期時刻の分は0から59で指定してください" }
    val now = ZonedDateTime.now(clock)
    var scheduled = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
    if (!scheduled.isAfter(now)) scheduled = scheduled.plusDays(1)
    return Duration.between(now, scheduled)
}
