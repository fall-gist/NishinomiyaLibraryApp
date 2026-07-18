package com.fallgist.nishinomiyalibrary.data.sync

import com.fallgist.nishinomiyalibrary.ui.debug.SyncScheduleStarter
import javax.inject.Inject
import javax.inject.Singleton

/** UIの純Kotlin Controllerから既存のWorkManager設定を呼び出すアダプター。 */
@Singleton
class WorkManagerSyncScheduleStarter @Inject constructor(
    private val syncScheduler: SyncScheduler,
) : SyncScheduleStarter {
    override suspend fun scheduleFromSettings() {
        syncScheduler.scheduleFromSettings()
    }
}
