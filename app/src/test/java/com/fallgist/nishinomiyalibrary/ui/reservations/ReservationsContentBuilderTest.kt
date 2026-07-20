package com.fallgist.nishinomiyalibrary.ui.reservations

import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationsContentBuilderTest {
    private val today = LocalDate.of(2026, 7, 20)
    private val papa = Member(id = 1, name = "パパ", colorHex = "#3D6DB5", cardNumber = "", sortOrder = 0)
    private val hana = Member(id = 2, name = "はな", colorHex = "#5FA05A", cardNumber = "", sortOrder = 1)
    private val members = listOf(papa, hana)

    @Test
    fun readyReservationsComeFirstSortedByHoldExpiry() {
        val reservations = listOf(
            Reservation(hana.id, "順番待ち本", "本", "中央", today, 3, ReservationState.WAITING, null),
            Reservation(hana.id, "期限なし受取", "本", "北口", today, null, ReservationState.READY, null),
            Reservation(papa.id, "早い期限受取", "本", "高須分室", today, null, ReservationState.READY, today.plusDays(3)),
        )

        val rows = ReservationsContentBuilder.build(members, reservations, selectedMemberId = null)

        assertEquals(listOf("早い期限受取", "期限なし受取", "順番待ち本"), rows.map { it.title })
        assertTrue(rows[0].isReady)
        assertEquals("受取可能", rows[0].statusLabel)
        assertEquals("取置期限 7/23(木) まで", rows[0].holdExpiryLabel)
        // 受取可能で期限未設定(Email連絡前)は「未定」を明示する
        assertEquals("取置期限 未定", rows[1].holdExpiryLabel)
        assertFalse(rows[2].isReady)
        assertEquals("予約順位 3番目", rows[2].queueLabel)
        // 順番待ちは期限行を出さない
        assertEquals(null, rows[2].holdExpiryLabel)
    }

    @Test
    fun blankPickupLibraryShownAsUndecided() {
        val reservations = listOf(
            Reservation(papa.id, "割当前の本", "本", "", today, 1, ReservationState.WAITING, null),
        )

        val rows = ReservationsContentBuilder.build(members, reservations, selectedMemberId = null)

        assertEquals("未定", rows.single().pickupLabel)
    }
}
