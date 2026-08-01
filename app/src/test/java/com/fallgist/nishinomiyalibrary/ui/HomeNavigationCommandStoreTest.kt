package com.fallgist.nishinomiyalibrary.ui

import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class HomeNavigationCommandStoreTest {
    @Test
    fun `通知PendingIntentのactionはStoreで消去されone shotコマンドになる`() {
        val context = RuntimeEnvironment.getApplication() as Context
        val pendingIntent = AutoReservationNotificationNavigation.pendingIntent(context)
        val delivered = Shadows.shadowOf(pendingIntent).getSavedIntent()
        val store = HomeNavigationCommandStore()

        assertEquals(AutoReservationNotificationNavigation.ACTION_OPEN_HOME, delivered.action)
        assertTrue(store.accept(delivered))
        assertNull(delivered.action)
        val commandId = requireNotNull(store.commandId.value)

        store.consume(commandId + 1)
        assertEquals(commandId, store.commandId.value)
        store.consume(commandId)
        assertNull(store.commandId.value)
        assertFalse(store.accept(delivered))
    }

    @Test
    fun `起動時と再受信時のIntentを同じHOMEコマンドとして一回ずつ受理する`() {
        val store = HomeNavigationCommandStore()
        val launchIntent = homeIntent()

        assertTrue(store.accept(launchIntent))
        val first = requireNotNull(store.commandId.value)
        assertFalse(store.accept(launchIntent))
        store.consume(first)
        assertNull(store.commandId.value)

        assertTrue(store.accept(homeIntent()))
        val second = requireNotNull(store.commandId.value)
        assertTrue(second > first)
        store.consume(second)
        assertNull(store.commandId.value)
    }

    @Test
    fun `無関係なIntentはコマンドへ変換しない`() {
        val store = HomeNavigationCommandStore()
        assertFalse(store.accept(Intent("other")))
        assertNull(store.commandId.value)
    }

    private fun homeIntent() = Intent(AutoReservationNotificationNavigation.ACTION_OPEN_HOME)
}
