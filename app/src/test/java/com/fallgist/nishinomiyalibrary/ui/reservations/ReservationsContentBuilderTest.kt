package com.fallgist.nishinomiyalibrary.ui.reservations

import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationPickupSubmissionRecord
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.ui.detail.BookDetailCancelTarget
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun rowsCarryTilcodForDetailNavigation() {
        val reservations = listOf(
            Reservation(papa.id, "詳細あり", "本", "中央", today, 1, ReservationState.WAITING, null, "1000000000001"),
        )

        val rows = ReservationsContentBuilder.build(members, reservations, selectedMemberId = null)

        assertEquals("1000000000001", rows.single().tilcod)
    }

    @Test
    fun blankPickupLibraryAndNoSubmissionRecordOmitsPickupLabel() {
        // 表示規則3: サイトが未定・アプリの送信記録も無い(旧データ等) → 項目自体を出さない(nullで捏造しない)
        val reservations = listOf(
            Reservation(papa.id, "割当前の本", "本", "", today, 1, ReservationState.WAITING, null),
        )

        val rows = ReservationsContentBuilder.build(members, reservations, selectedMemberId = null)

        assertNull(rows.single().pickupLabel)
    }

    // ------------------------------------------------------------------
    // 受取館「未定」の表示規則(`docs/ui-design.md`「方針: 一覧画面の行レイアウト統一」6番)
    // 3通り(サイトが実館名／未定+記録あり／未定+記録なし)を並べて固定する。
    // ------------------------------------------------------------------

    @Test
    fun pickupLabel_サイトの受取館が実館名ならそれを最優先する() {
        // サイトの値が最優先(送信記録があっても無視する)。中央図書館=コード001。
        val reservations = listOf(
            Reservation(papa.id, "確定済み", "本", "北口図書館", today, 1, ReservationState.WAITING, null, "1000001"),
        )
        val submissions = listOf(
            ReservationPickupSubmissionRecord(memberId = papa.id, tilcod = "1000001", pickupLibraryCode = "001"),
        )

        val rows = ReservationsContentBuilder.build(members, reservations, selectedMemberId = null, submissions)

        assertEquals("北口図書館", rows.single().pickupLabel)
    }

    @Test
    fun pickupLabel_サイトが未定でも送信記録があればその館名を出す() {
        val reservations = listOf(
            Reservation(papa.id, "未定+記録あり", "本", "", today, 1, ReservationState.WAITING, null, "1000002"),
        )
        val submissions = listOf(
            ReservationPickupSubmissionRecord(memberId = papa.id, tilcod = "1000002", pickupLibraryCode = "106"),
        )

        val rows = ReservationsContentBuilder.build(members, reservations, selectedMemberId = null, submissions)

        assertEquals("高須分室", rows.single().pickupLabel)
    }

    @Test
    fun pickupLabel_サイトが未定で送信記録も無ければ項目を出さない() {
        val reservations = listOf(
            Reservation(papa.id, "未定+記録なし", "本", "", today, 1, ReservationState.WAITING, null, "1000003"),
        )

        val rows = ReservationsContentBuilder.build(members, reservations, selectedMemberId = null, emptyList())

        assertNull(rows.single().pickupLabel)
    }

    @Test
    fun pickupLabel_未知の館コードの記録では館名を捏造せず項目を出さない() {
        val reservations = listOf(
            Reservation(papa.id, "未知コード", "本", "", today, 1, ReservationState.WAITING, null, "1000004"),
        )
        val submissions = listOf(
            ReservationPickupSubmissionRecord(memberId = papa.id, tilcod = "1000004", pickupLibraryCode = "999"),
        )

        val rows = ReservationsContentBuilder.build(members, reservations, selectedMemberId = null, submissions)

        assertNull(rows.single().pickupLabel)
    }

    @Test
    fun pickupLabel_解決してもReservationのpickupLibraryは書き換わらない() {
        // Repository/ドメイン側でサイトの値が保たれることを固定する(サイトが未定の値を保持したまま)。
        val reservation = Reservation(papa.id, "未定+記録あり", "本", "", today, 1, ReservationState.WAITING, null, "1000005")
        val submissions = listOf(
            ReservationPickupSubmissionRecord(memberId = papa.id, tilcod = "1000005", pickupLibraryCode = "106"),
        )

        ReservationsContentBuilder.build(members, listOf(reservation), selectedMemberId = null, submissions)

        assertEquals("", reservation.pickupLibrary)
    }

    @Test
    fun countByMember_countsAllReservationsRegardlessOfSelection() {
        val reservations = listOf(
            Reservation(papa.id, "パパ本1", "本", "中央", today, 1, ReservationState.WAITING, null),
            Reservation(papa.id, "パパ本2", "本", "中央", today, 2, ReservationState.WAITING, null),
            Reservation(hana.id, "はな本", "本", "中央", today, null, ReservationState.READY, null),
        )

        val counts = ReservationsContentBuilder.countByMember(members, reservations)

        assertEquals(mapOf(papa.id to 2, hana.id to 1), counts)
    }

    @Test
    fun countByMember_omitsMembersWithZeroReservations() {
        val reservations = listOf(
            Reservation(papa.id, "パパ本", "本", "中央", today, 1, ReservationState.WAITING, null),
        )

        val counts = ReservationsContentBuilder.countByMember(members, reservations)

        assertEquals(mapOf(papa.id to 1), counts)
        assertFalse(counts.containsKey(hana.id))
    }

    @Test
    fun cancelCodeが空の行はcancellableがfalseになる() {
        val reservations = listOf(
            Reservation(papa.id, "取消可能", "本", "中央", today, 1, ReservationState.WAITING, null, "1000001", "cancel-1"),
            Reservation(papa.id, "提供可能", "本", "中央", today, null, ReservationState.READY, null, "1000002", ""),
        )

        val rows = ReservationsContentBuilder.build(members, reservations, selectedMemberId = null)

        assertTrue(rows.single { it.title == "取消可能" }.cancellable)
        assertFalse(rows.single { it.title == "提供可能" }.cancellable)
    }

    @Test
    fun tilcodが空ならcancelCodeがあってもcancellableはfalse() {
        val reservations = listOf(
            Reservation(papa.id, "旧データ", "本", "中央", today, 1, ReservationState.WAITING, null, "", "cancel-1"),
        )

        val rows = ReservationsContentBuilder.build(members, reservations, selectedMemberId = null)

        assertFalse(rows.single().cancellable)
    }

    @Test
    fun cancelCandidatesは選択済みかつ取消可能な行だけを候補にする() {
        val cancellableRow = ReservationRow(
            memberId = papa.id,
            memberName = "パパ",
            memberColorHex = "#111111",
            title = "取消可能",
            isReady = false,
            statusLabel = "順番待ち",
            pickupLabel = "中央",
            queueLabel = null,
            holdExpiryLabel = null,
            tilcod = "1000001",
            cancelCode = "cancel-1",
        )
        val notCancellableRow = cancellableRow.copy(title = "取消不可", tilcod = "1000002", cancelCode = "")
        val unselectedRow = cancellableRow.copy(title = "未選択", tilcod = "1000003", cancelCode = "cancel-3")
        val rows = listOf(cancellableRow, notCancellableRow, unselectedRow)

        val candidates = ReservationsContentBuilder.cancelCandidates(
            rows,
            selectedKeys = setOf(cancellableRow.cancelKey, notCancellableRow.cancelKey),
        )

        assertEquals(listOf("取消可能"), candidates.map { it.title })
        assertEquals(papa.id, candidates.single().target.memberId)
        assertEquals("1000001", candidates.single().target.tilcod)
        assertEquals("cancel-1", candidates.single().target.cancelCode)
    }

    // 経路3(予約中一覧から開いた書誌詳細): 取消可能な行だけ取消対象を渡す。

    @Test
    fun cancelTargetForDetailは取消可能な行では対象を返す() {
        val reservations = listOf(
            Reservation(papa.id, "取消可能", "本", "中央", today, 1, ReservationState.WAITING, null, "1000001", "cancel-1"),
        )
        val row = ReservationsContentBuilder.build(members, reservations, selectedMemberId = null).single()

        val target = ReservationsContentBuilder.cancelTargetForDetail(row)

        assertEquals(BookDetailCancelTarget(memberId = papa.id, cancelCode = "cancel-1"), target)
    }

    @Test
    fun cancelTargetForDetailはcancelCodeが空の行ではnullを返す() {
        val reservations = listOf(
            Reservation(papa.id, "提供可能", "本", "中央", today, null, ReservationState.READY, null, "1000002", ""),
        )
        val row = ReservationsContentBuilder.build(members, reservations, selectedMemberId = null).single()

        assertNull(ReservationsContentBuilder.cancelTargetForDetail(row))
    }

    @Test
    fun cancelTargetForDetailはtilcodが空の行ではnullを返す() {
        val reservations = listOf(
            Reservation(papa.id, "旧データ", "本", "中央", today, 1, ReservationState.WAITING, null, "", "cancel-1"),
        )
        val row = ReservationsContentBuilder.build(members, reservations, selectedMemberId = null).single()

        assertNull(ReservationsContentBuilder.cancelTargetForDetail(row))
    }
}
