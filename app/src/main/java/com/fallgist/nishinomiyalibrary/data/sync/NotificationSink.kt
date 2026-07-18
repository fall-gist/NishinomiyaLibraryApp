package com.fallgist.nishinomiyalibrary.data.sync

/** 通知のOS送信を差し替えるポート。falseは未送信を表す。 */
interface NotificationSink {
    suspend fun postReturnReminder(plan: ReturnReminderPlan): Boolean

    suspend fun postPickupReady(plan: PickupReadyPlan): Boolean
}

/** 同期に成功したメンバーだけを対象にする通知処理のポート。 */
interface PostSyncNotifier {
    suspend fun notifyAfterSuccessfulSync(successfulMemberIds: Set<Long>)
}
