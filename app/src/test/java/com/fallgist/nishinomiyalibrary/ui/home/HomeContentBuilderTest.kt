package com.fallgist.nishinomiyalibrary.ui.home

import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.repository.SyncLog
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeContentBuilderTest {
    private val today = LocalDate.of(2026, 7, 20)
    private val papa = Member(id = 1, name = "パパ", colorHex = "#3D6DB5", cardNumber = "", sortOrder = 0)
    private val hana = Member(id = 2, name = "はな", colorHex = "#5FA05A", cardNumber = "", sortOrder = 1)
    private val taro = Member(id = 3, name = "たろう", colorHex = "#D98E2B", cardNumber = "", sortOrder = 2)
    private val members = listOf(papa, hana, taro)

    private fun loan(memberId: Long, title: String, dueDate: LocalDate) = Loan(
        memberId = memberId,
        title = title,
        materialType = "本",
        lendingLibrary = "高須分室",
        loanDate = today.minusDays(14),
        dueDate = dueDate,
        status = "貸出中",
    )

    @Test
    fun dueGroups_areGroupedAndFoldedByMockRules() {
        val loans = listOf(
            loan(papa.id, "期限切れの本", today.minusDays(2)),
            loan(hana.id, "きょうの本", today),
            loan(papa.id, "7日以内の本", today.plusDays(5)),
            loan(papa.id, "遠い本A", today.plusDays(10)),
            loan(hana.id, "遠い本B", today.plusDays(10)),
        )

        val content = HomeContentBuilder.build(
            members = members,
            loans = loans,
            reservations = emptyList(),
            lastSync = null,
            selectedMemberId = null,
            today = today,
        )

        assertEquals(4, content.dueGroups.size)

        val overdue = content.dueGroups[0]
        assertEquals("期限切れ", overdue.headerLabel)
        assertTrue(overdue.overdue)
        assertTrue(overdue.expanded)
        assertEquals(1, overdue.count)
        assertTrue(overdue.books.single().overdue)

        val todayGroup = content.dueGroups[1]
        assertTrue(todayGroup.headerLabel.startsWith("きょう"))
        assertTrue(todayGroup.expanded)

        val withinWeek = content.dueGroups[2]
        assertTrue(withinWeek.headerLabel.contains("7/25"))
        assertTrue(withinWeek.expanded)

        val faraway = content.dueGroups[3]
        assertTrue(faraway.headerLabel.contains("7/30"))
        assertFalse(faraway.expanded)
        assertEquals(2, faraway.count)
        assertEquals("パパ1冊・はな1冊", faraway.foldedSummary)
    }

    @Test
    fun memberFilter_limitsLoansToSelectedMember() {
        val loans = listOf(
            loan(papa.id, "パパの期限切れ", today.minusDays(1)),
            loan(hana.id, "はなのきょう", today),
        )

        val content = HomeContentBuilder.build(
            members = members,
            loans = loans,
            reservations = emptyList(),
            lastSync = null,
            selectedMemberId = hana.id,
            today = today,
        )

        assertEquals(1, content.dueGroups.size)
        val group = content.dueGroups.single()
        assertFalse(group.overdue)
        assertEquals("はな", group.books.single().memberName)
    }

    @Test
    fun readyGroups_onlyReadyGroupedByHoldExpiryUndatedLast() {
        val reservations = listOf(
            Reservation(hana.id, "予約待ち", "本", "中央", today, 3, ReservationState.WAITING, null),
            Reservation(hana.id, "期限なし受取", "本", "北口", today, null, ReservationState.READY, null),
            Reservation(papa.id, "早い期限受取", "本", "高須分室", today, null, ReservationState.READY, today.plusDays(3)),
            Reservation(taro.id, "同じ期限の本", "本", "中央", today, null, ReservationState.READY, today.plusDays(3)),
        )

        val content = HomeContentBuilder.build(
            members = members,
            loans = emptyList(),
            reservations = reservations,
            lastSync = null,
            selectedMemberId = null,
            today = today,
        )

        // WAITING は除外。期限あり(7/23)グループが先頭、期限未定が末尾。
        assertEquals(2, content.readyGroups.size)
        assertEquals(3, content.readyGroups.sumOf { it.items.size })

        val dated = content.readyGroups[0]
        assertFalse(dated.undated)
        assertTrue(dated.headerLabel.contains("7/23"))
        assertEquals(2, dated.items.size)
        assertEquals("早い期限受取", dated.items[0].title) // papa(sortOrder 0) が先
        assertEquals("同じ期限の本", dated.items[1].title) // taro(sortOrder 2)

        val undated = content.readyGroups[1]
        assertTrue(undated.undated)
        assertEquals("期限なし受取", undated.items.single().title)
    }

    @Test
    fun lastSync_nullAndFailedAreReflected() {
        val nullContent = HomeContentBuilder.build(members, emptyList(), emptyList(), null, null, today)
        assertEquals("まだ同期していません", nullContent.lastSyncText)
        assertFalse(nullContent.lastSyncFailed)

        val failedLog = SyncLog(
            id = 1,
            startedAtEpochMillis = 0L,
            finishedAtEpochMillis = 0L,
            trigger = SyncTrigger.SCHEDULED,
            succeeded = false,
            details = "",
        )
        val failedContent = HomeContentBuilder.build(members, emptyList(), emptyList(), failedLog, null, today)
        assertTrue(failedContent.lastSyncFailed)
        assertTrue(failedContent.lastSyncText.contains("一部失敗"))
    }
}
