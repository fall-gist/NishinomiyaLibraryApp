package com.fallgist.nishinomiyalibrary.ui.settings

import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.repository.SyncLog
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationForm
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationValidation
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationValidator
import java.time.ZonedDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsContentBuilderTest {
    @Test
    fun `カード番号は下4桁だけを表示する`() {
        assertEquals("****1234", SettingsContentBuilder.maskCardNumber("0009991234"))
        assertEquals("****123", SettingsContentBuilder.maskCardNumber("123"))
    }

    @Test
    fun `メンバー一覧は端の行の並び替えを無効にする`() {
        val members = listOf(
            Member(1, "パパ", "#3D6DB5", "00001111", 0),
            Member(2, "ママ", "#C25278", "00002222", 1),
            Member(3, "たろう", "#D98E2B", "00003333", 2),
        )

        val rows = SettingsContentBuilder.memberRows(members)

        assertFalse(rows[0].canMoveUp)
        assertTrue(rows[0].canMoveDown)
        assertTrue(rows[1].canMoveUp && rows[1].canMoveDown)
        assertTrue(rows[2].canMoveUp)
        assertFalse(rows[2].canMoveDown)
        assertEquals("****1111", rows[0].maskedCardNumber)
    }

    @Test
    fun `最終同期は日時を整形し未同期はその旨を表示する`() {
        assertEquals("まだ同期していません", SettingsContentBuilder.lastSyncText(null))

        val startedAt = ZonedDateTime.of(2026, 7, 20, 18, 0, 0, 0, ZoneId.of("Asia/Tokyo"))
        val log = SyncLog(
            id = 1,
            startedAtEpochMillis = startedAt.toInstant().toEpochMilli(),
            finishedAtEpochMillis = null,
            trigger = SyncTrigger.SCHEDULED,
            succeeded = true,
            details = "",
        )

        assertEquals("7/20(月) 18:00", SettingsContentBuilder.lastSyncText(log))
    }

    @Test
    fun `編集時はパスワード空欄を許し新規は必須のまま`() {
        val form = RegistrationForm(name = "パパ", colorHex = "#3D6DB5", cardNumber = "12345", password = "")

        assertTrue(RegistrationValidator.validate(form, requirePassword = false) is RegistrationValidation.Valid)
        assertTrue(RegistrationValidator.validate(form) is RegistrationValidation.Invalid)
    }
}
