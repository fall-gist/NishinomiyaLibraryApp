package com.fallgist.nishinomiyalibrary.ui.loans

import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Member
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoansContentBuilderTest {
    private val today = LocalDate.of(2026, 7, 20)
    private val papa = Member(id = 1, name = "パパ", colorHex = "#3D6DB5", cardNumber = "", sortOrder = 0)
    private val hana = Member(id = 2, name = "はな", colorHex = "#5FA05A", cardNumber = "", sortOrder = 1)
    private val members = listOf(papa, hana)

    private fun loan(memberId: Long, title: String, dueDate: LocalDate) = Loan(
        memberId = memberId,
        title = title,
        materialType = "本",
        lendingLibrary = "高須分室",
        loanDate = today.minusDays(14),
        dueDate = dueDate,
        status = "貸出中",
        tilcod = "T-$title",
    )

    @Test
    fun rows_carryTilcodForDetailNavigation() {
        val rows = LoansContentBuilder.build(
            members,
            listOf(loan(papa.id, "本A", today.plusDays(1))),
            selectedMemberId = null,
            today = today,
        )
        assertEquals("T-本A", rows.single().tilcod)
    }

    @Test
    fun rows_sortedByDueDateWithOverdueAndSoonFlags() {
        val loans = listOf(
            loan(hana.id, "遠い本", today.plusDays(10)),
            loan(papa.id, "超過本", today.minusDays(2)),
            loan(papa.id, "もうすぐ本", today.plusDays(2)),
            loan(hana.id, "きょう本", today),
        )

        val rows = LoansContentBuilder.build(members, loans, selectedMemberId = null, today = today)

        assertEquals(listOf("超過本", "きょう本", "もうすぐ本", "遠い本"), rows.map { it.title })
        assertTrue(rows[0].overdue)
        assertTrue(rows[0].dueLabel.contains("超過"))
        assertTrue(rows[1].dueLabel.startsWith("きょう"))
        assertTrue(rows[2].dueSoon)
        assertFalse(rows[3].overdue)
        assertFalse(rows[3].dueSoon)
    }

    @Test
    fun memberFilter_limitsToSelectedMember() {
        val loans = listOf(
            loan(papa.id, "パパ本", today.plusDays(1)),
            loan(hana.id, "はな本", today.plusDays(1)),
        )

        val rows = LoansContentBuilder.build(members, loans, selectedMemberId = hana.id, today = today)

        assertEquals(1, rows.size)
        assertEquals("はな", rows.single().memberName)
    }

    @Test
    fun countByMember_countsAllLoansRegardlessOfSelection() {
        val loans = listOf(
            loan(papa.id, "パパ本1", today.plusDays(1)),
            loan(papa.id, "パパ本2", today.plusDays(2)),
            loan(hana.id, "はな本", today.plusDays(1)),
        )

        val counts = LoansContentBuilder.countByMember(members, loans)

        assertEquals(mapOf(papa.id to 2, hana.id to 1), counts)
    }

    @Test
    fun countByMember_omitsMembersWithZeroLoans() {
        val loans = listOf(loan(papa.id, "パパ本", today.plusDays(1)))

        val counts = LoansContentBuilder.countByMember(members, loans)

        assertEquals(mapOf(papa.id to 1), counts)
        assertFalse(counts.containsKey(hana.id))
    }
}
