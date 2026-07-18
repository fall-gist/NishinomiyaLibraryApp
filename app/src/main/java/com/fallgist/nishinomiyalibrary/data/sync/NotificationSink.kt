package com.fallgist.nishinomiyalibrary.data.sync

/** 通知のOS送信を差し替えるポート。falseは未送信を表す。 */
interface NotificationSink {
    suspend fun postReturnReminder(plan: ReturnReminderPlan): Boolean

    suspend fun postPickupReady(plan: PickupReadyPlan): Boolean
}

/** 全メンバーの同期が成功した後だけ呼び出す通知処理のポート。 */
interface PostSyncNotifier {
    suspend fun notifyAfterSuccessfulSync()
}
