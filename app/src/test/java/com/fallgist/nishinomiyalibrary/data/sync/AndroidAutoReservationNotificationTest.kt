package com.fallgist.nishinomiyalibrary.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import com.fallgist.nishinomiyalibrary.ui.AutoReservationNotificationNavigation
import com.fallgist.nishinomiyalibrary.ui.MainActivity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidAutoReservationNotificationTest {
    @Test
    fun `return reminderは従来channelと会員別payloadを維持する`() = runTest {
        val context = RuntimeEnvironment.getApplication() as Context
        val manager = context.getSystemService(NotificationManager::class.java)

        assertTrue(
            AndroidNotificationSink(context).postReturnReminder(
                ReturnReminderPlan(
                    itemsByMember = listOf(
                        MemberLoanItems(7L, "会員A", listOf("返却本A", "返却本B")),
                    ),
                    hasOverdue = true,
                    earliestDueDate = LocalDate.of(2026, 7, 19),
                    title = "返却期限が過ぎた本があります",
                ),
            ),
        )

        val notification = Shadows.shadowOf(manager).getAllNotifications().single()
        assertEquals(AndroidNotificationSink.CH_RETURN_REMINDER, notification.channelId)
        assertTrue(notification.extras.getCharSequence("android.text").toString().contains("会員A"))
        val lines = notification.extras.getCharSequenceArray("android.textLines").orEmpty()
        assertTrue(lines.any { it.toString().contains("会員A: 返却本A") })
        assertTrue(lines.any { it.toString().contains("会員A: 返却本B") })
    }

    @Test
    fun `pickup readyは従来channelと予約payloadを維持する`() = runTest {
        val context = RuntimeEnvironment.getApplication() as Context
        val manager = context.getSystemService(NotificationManager::class.java)

        assertTrue(
            AndroidNotificationSink(context).postPickupReady(
                PickupReadyPlan(
                    listOf(
                        PickupReadyItem(
                            reservationId = 9L,
                            memberName = "会員B",
                            title = "受取本A",
                            pickupLibrary = "中央図書館",
                            holdExpiryDate = LocalDate.of(2030, 1, 5),
                        ),
                    ),
                ),
            ),
        )

        val notification = Shadows.shadowOf(manager).getAllNotifications().single()
        assertEquals(AndroidNotificationSink.CH_PICKUP_READY, notification.channelId)
        val lines = notification.extras.getCharSequenceArray("android.textLines").orEmpty()
        assertTrue(lines.any { it.toString().contains("会員B: 受取本A") })
        assertTrue(lines.any { it.toString().contains("1/5") })
    }

    @Test
    fun `専用channelと固定IDの集計通知を作りHOME PendingIntentを設定する`() = runTest {
        val context = RuntimeEnvironment.getApplication() as Context
        val manager = context.getSystemService(NotificationManager::class.java)
        val sink = AndroidNotificationSink(context)

        assertTrue(sink.postAutoReservation(AutoReservationNotificationSummary(2, 1, 3)))

        val notification = requireNotNull(
            Shadows.shadowOf(manager).getNotification(AndroidNotificationSink.NOTIFICATION_ID_AUTO_RESERVATION),
        )
        assertEquals(
            "確保済み2件／見送り1件／エラー3件",
            notification.extras.getCharSequence("android.text").toString(),
        )
        assertEquals(
            AutoReservationNotificationNavigation.ACTION_OPEN_HOME,
            Shadows.shadowOf(notification.contentIntent).getSavedIntent().action,
        )
        val flags = Shadows.shadowOf(notification.contentIntent).getSavedIntent().flags
        assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(AndroidNotificationSink.CH_AUTO_RESERVATION).importance,
        )
    }

    @Test
    fun `専用channelがOFFなら通知しない`() = runTest {
        val context = RuntimeEnvironment.getApplication() as Context
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                AndroidNotificationSink.CH_AUTO_RESERVATION,
                "自動予約",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )

        val posted = AndroidNotificationSink(context).postAutoReservation(
            AutoReservationNotificationSummary(1, 0, 0),
        )

        assertFalse(posted)
        assertEquals(0, Shadows.shadowOf(manager).size())
    }

    @Test
    fun `return reminderはアプリを開くだけのPendingIntentを持ちタップで消える`() = runTest {
        val context = RuntimeEnvironment.getApplication() as Context

        assertTrue(
            AndroidNotificationSink(context).postReturnReminder(
                ReturnReminderPlan(
                    itemsByMember = listOf(
                        MemberLoanItems(7L, "会員A", listOf("返却本A")),
                    ),
                    hasOverdue = false,
                    earliestDueDate = LocalDate.of(2026, 7, 21),
                    title = "明日返却の本があります",
                ),
            ),
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = requireNotNull(
            Shadows.shadowOf(manager).getNotification(AndroidNotificationSink.NOTIFICATION_ID_RETURN_REMINDER),
        )
        val contentIntent = requireNotNull(notification.contentIntent)
        val savedIntent = Shadows.shadowOf(contentIntent).getSavedIntent()
        assertEquals(
            ComponentName(context, MainActivity::class.java),
            savedIntent.component,
        )
        assertEquals(null, savedIntent.action)
        val flags = savedIntent.flags
        assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `pickup readyはアプリを開くだけのPendingIntentを持ちタップで消える`() = runTest {
        val context = RuntimeEnvironment.getApplication() as Context

        assertTrue(
            AndroidNotificationSink(context).postPickupReady(
                PickupReadyPlan(
                    listOf(
                        PickupReadyItem(
                            reservationId = 9L,
                            memberName = "会員B",
                            title = "受取本A",
                            pickupLibrary = "中央図書館",
                            holdExpiryDate = LocalDate.of(2030, 1, 5),
                        ),
                    ),
                ),
            ),
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = requireNotNull(
            Shadows.shadowOf(manager).getNotification(AndroidNotificationSink.NOTIFICATION_ID_PICKUP_READY),
        )
        val contentIntent = requireNotNull(notification.contentIntent)
        val savedIntent = Shadows.shadowOf(contentIntent).getSavedIntent()
        assertEquals(
            ComponentName(context, MainActivity::class.java),
            savedIntent.component,
        )
        assertEquals(null, savedIntent.action)
        val flags = savedIntent.flags
        assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }
}
