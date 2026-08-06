package com.fallgist.nishinomiyalibrary.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionMessagesTest {
    @Test fun `NotRequiredでは何も表示しない`() {
        assertFalse(NotificationPermissionMessages.shouldShowNotice(NotificationPermissionUiState.NotRequired))
        assertNull(NotificationPermissionMessages.message(NotificationPermissionUiState.NotRequired))
        assertNull(NotificationPermissionMessages.buttonLabel(NotificationPermissionUiState.NotRequired))
    }

    @Test fun `Grantedでは何も表示しない`() {
        assertFalse(NotificationPermissionMessages.shouldShowNotice(NotificationPermissionUiState.Granted))
        assertNull(NotificationPermissionMessages.message(NotificationPermissionUiState.Granted))
        assertNull(NotificationPermissionMessages.buttonLabel(NotificationPermissionUiState.Granted))
    }

    @Test fun `Missingでは警告と許可ボタンを表示する`() {
        assertTrue(NotificationPermissionMessages.shouldShowNotice(NotificationPermissionUiState.Missing))
        assertEquals(
            "通知が許可されていません。返却期限や受取可能のお知らせが届きません。",
            NotificationPermissionMessages.message(NotificationPermissionUiState.Missing),
        )
        assertEquals("通知を許可する", NotificationPermissionMessages.buttonLabel(NotificationPermissionUiState.Missing))
    }

    @Test fun `Deniedでは警告と設定アプリ誘導ボタンを表示する`() {
        assertTrue(NotificationPermissionMessages.shouldShowNotice(NotificationPermissionUiState.Denied))
        assertEquals(
            "通知が許可されていません。端末の設定から通知をオンにしてください。",
            NotificationPermissionMessages.message(NotificationPermissionUiState.Denied),
        )
        assertEquals(
            "アプリの通知設定を開く",
            NotificationPermissionMessages.buttonLabel(NotificationPermissionUiState.Denied),
        )
    }
}
