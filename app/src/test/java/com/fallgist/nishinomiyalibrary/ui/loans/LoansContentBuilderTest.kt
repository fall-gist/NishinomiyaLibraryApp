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

    private fun loan(
        memberId: Long,
        title: String,
        dueDate: LocalDate,
        tilcod: String = "T-$title",
        extendable: Boolean = false,
    ) = Loan(
        memberId = memberId,
        title = title,
        materialType = "本",
        lendingLibrary = "高須分室",
        loanDate = today.minusDays(14),
        dueDate = dueDate,
        status = "貸出中",
        tilcod = tilcod,
        extendable = extendable,
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
    fun rows_sortedByDueDateWithOverdueFlag() {
        // dueSoon(返却期限間近の色分岐用フラグ)は行レイアウト統一(2026-08-05)で色分岐を撤去した結果
        // 参照ゼロになったため削除した。overdue(alertBgの行背景色判定に使用)は維持する。
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
        assertTrue(rows[1].dueLabel.contains("きょう"))
        assertFalse(rows[2].overdue)
        assertFalse(rows[3].overdue)
    }

    // ------------------------------------------------------------------
    // レイアウト追い込み(第2次) 項目9: dueLabelの先頭に「返却期限」を付ける。
    // 「まで」は既存ロジックが付けているため、二重に付いていないことを4パターンすべてで固定する。
    // ------------------------------------------------------------------

    @Test
    fun dueLabel_prefixedWithReturnDeadlineAndDoesNotDoubleUpMade() {
        val loans = listOf(
            loan(papa.id, "通常本", today.plusDays(5)),
            loan(papa.id, "きょう本", today),
            loan(papa.id, "あす本", today.plusDays(1)),
            loan(papa.id, "超過本", today.minusDays(2)),
        )

        val rows = LoansContentBuilder.build(members, loans, selectedMemberId = null, today = today)
        val labelByTitle = rows.associate { it.title to it.dueLabel }

        // 各パターンとも先頭が「返却期限」で始まり、「まで」がちょうど1回だけ出現すること(二重付与の検出)。
        for ((title, label) in labelByTitle) {
            assertTrue("先頭に「返却期限」が無い: $title -> $label", label.startsWith("返却期限"))
            val madeCount = Regex("まで").findAll(label).count()
            assertEquals("「まで」が二重、または欠落している: $title -> $label", 1, madeCount)
        }

        assertTrue(labelByTitle.getValue("超過本").contains("(超過)"))
        assertTrue(labelByTitle.getValue("きょう本").contains("きょう"))
        assertTrue(labelByTitle.getValue("あす本").contains("あす"))
        assertFalse(labelByTitle.getValue("通常本").contains("きょう"))
        assertFalse(labelByTitle.getValue("通常本").contains("あす"))
        assertFalse(labelByTitle.getValue("通常本").contains("超過"))
    }

    @Test
    fun memberFilter_limitsToSelectedMember() {
        val loans = listOf(
            loan(papa.id, "パパ本", today.plusDays(1)),
            loan(hana.id, "はな本", today.plusDays(1)),
        )

        val rows = LoansContentBuilder.build(members, loans, selectedMemberId = hana.id, today = today)

        assertEquals(1, rows.size)
        assertEquals(hana.id, rows.single().memberId)
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

    // ------------------------------------------------------------------
    // 貸出延長(段階5): memberId・extendable・canExtendの算出
    // (`docs/design/loan-extension.md` §6・§9.1、設計§10「除外」項目)
    // ------------------------------------------------------------------

    @Test
    fun rows_carryMemberIdAndExtendableFromLoan() {
        val rows = LoansContentBuilder.build(
            members,
            listOf(loan(papa.id, "本A", today.plusDays(1), tilcod = "T-本A", extendable = true)),
            selectedMemberId = null,
            today = today,
        )

        val row = rows.single()
        assertEquals(papa.id, row.memberId)
        assertTrue(row.extendable)
    }

    @Test
    fun canExtend_isTrueOnlyWhenExtendableAndTilcodPresent() {
        val rows = LoansContentBuilder.build(
            members,
            listOf(
                loan(papa.id, "延長可", today.plusDays(1), tilcod = "T-延長可", extendable = true),
                loan(papa.id, "延長不可フラグ", today.plusDays(1), tilcod = "T-延長不可フラグ", extendable = false),
                // extendable=trueでもtilcodが空なら延長ボタンを起動できてはならない(設計§10「除外」項目)。
                loan(papa.id, "延長可だがtilcod無し", today.plusDays(1), tilcod = "", extendable = true),
            ),
            selectedMemberId = null,
            today = today,
        )

        val byTitle = rows.associateBy { it.title }
        assertTrue(byTitle.getValue("延長可").canExtend)
        assertFalse(byTitle.getValue("延長不可フラグ").canExtend)
        assertFalse(byTitle.getValue("延長可だがtilcod無し").canExtend)
    }
}
