package com.fallgist.nishinomiyalibrary.data.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Androidの通知機構だけを担当するNotificationSink実装。 */
@Singleton
class AndroidNotificationSink @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationSink {
    override suspend fun postReturnReminder(plan: ReturnReminderPlan): Boolean {
        return try {
            if (!canPost(CH_RETURN_REMINDER)) return false
            val summary = plan.itemsByMember.joinToString("・") { "${it.memberName} ${it.titles.size}冊" }
            val lines = plan.itemsByMember.flatMap { member ->
                member.titles.map { title -> "${member.memberName}: $title" }
            }
            val style = Notification.InboxStyle().also { style -> lines.forEach(style::addLine) }
            val notification = Notification.Builder(context, CH_RETURN_REMINDER)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("明日返却の本があります")
                .setContentText(if (plan.hasOverdue) "$summary・期限超過あり" else summary)
                .setStyle(style)
                .setAutoCancel(true)
                .build()
            notificationManager().notify(NOTIFICATION_ID_RETURN_REMINDER, notification)
            true
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun postPickupReady(plan: PickupReadyPlan): Boolean {
        return try {
            if (!canPost(CH_PICKUP_READY)) return false
            val style = Notification.InboxStyle().also { style ->
                plan.items.forEach { item ->
                    val expiry = item.holdExpiryDate?.format(DATE_FORMATTER)?.let { "（取置期限: $it）" }.orEmpty()
                    style.addLine("${item.memberName}: ${item.title} $expiry")
                }
            }
            val notification = Notification.Builder(context, CH_PICKUP_READY)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("予約資料を受け取れます")
                .setContentText("${plan.items.size}件の予約資料が受取可能です")
                .setStyle(style)
                .setAutoCancel(true)
                .build()
            notificationManager().notify(NOTIFICATION_ID_PICKUP_READY, notification)
            true
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            false
        }
    }

    private fun canPost(channelId: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = notificationManager()
        if (!manager.areNotificationsEnabled()) return false
        ensureChannels(manager)
        return manager.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun ensureChannels(manager: NotificationManager) {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CH_RETURN_REMINDER,
                    "返却期限リマインダー",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                NotificationChannel(
                    CH_PICKUP_READY,
                    "予約受取可能",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            ),
        )
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    companion object {
        const val CH_RETURN_REMINDER = "return_reminder"
        const val CH_PICKUP_READY = "pickup_ready"

        private const val NOTIFICATION_ID_RETURN_REMINDER = 1001
        private const val NOTIFICATION_ID_PICKUP_READY = 1002
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")
    }
}
