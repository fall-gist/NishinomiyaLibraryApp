package com.fallgist.nishinomiyalibrary.data.sync

import javax.inject.Inject
import javax.inject.Singleton

/** 画面の純Kotlin Controllerから自動同期の設定を開始するための、Android非依存の境界。 */
interface SyncScheduleStarter {
    suspend fun scheduleFromSettings()
}

/** UIの純Kotlin Controllerから既存のWorkManager設定を呼び出すアダプター。 */
@Singleton
class WorkManagerSyncScheduleStarter @Inject constructor(
    private val syncScheduler: SyncScheduler,
) : SyncScheduleStarter {
    override suspend fun scheduleFromSettings() {
        syncScheduler.scheduleFromSettings()
    }
}
