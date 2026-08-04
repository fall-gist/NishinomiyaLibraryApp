package com.fallgist.nishinomiyalibrary.ui.loans

import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionOutcome
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoanExtensionContentBuilderTest {
    @Test
    fun formatDueDate_usesYyyyMmDdSlash() {
        assertEquals("2026/08/19", LoanExtensionContentBuilder.formatDueDate(LocalDate.of(2026, 8, 19)))
    }

    @Test
    fun resultMessage_extended_includesNewDueDateAndSucceeds() {
        val result = LoanExtensionContentBuilder.resultMessage(LoanExtensionOutcome.Extended(LocalDate.of(2026, 8, 19)))
        assertEquals("延長しました", result.title)
        assertEquals("返却期限を延長しました（新しい期限: 2026/08/19）", result.message)
        assertEquals(LoanExtensionResultKind.EXTENDED, result.kind)
        assertTrue(result.succeeded)
    }

    @Test
    fun resultMessage_unknown_doesNotSucceed() {
        val result = LoanExtensionContentBuilder.resultMessage(LoanExtensionOutcome.Unknown)
        assertEquals("延長できたか確認できません。しばらくしてから貸出状況をご確認ください", result.message)
        assertEquals(LoanExtensionResultKind.UNKNOWN, result.kind)
        // Unknownを成功として集計してはならない(唯一の判定点)。
        assertFalse(result.succeeded)
    }

    @Test
    fun resultMessage_failure_neverSucceedsForAnyReason() {
        for (reason in FailureReason.entries) {
            val result = LoanExtensionContentBuilder.resultMessage(LoanExtensionOutcome.Failure(reason))
            assertEquals("reason=$reason", LoanExtensionResultKind.FAILED, result.kind)
            assertFalse("reason=$reason", result.succeeded)
            assertTrue("reason=$reason", result.message.isNotBlank())
        }
    }

    /**
     * ダブルレビューで判明した欠陥の回帰テスト(`docs/handoff.md`進行指示15と同じ考え方)。
     * Unknown(成否不明)は「延長できませんでした」等、Failure(失敗の断定)と同じ・または紛らわしい
     * タイトルを持ってはならない。延長が既に成立している可能性がある状態を「失敗」と断定すると、
     * 利用者が再送してしまい、「送信は一回限り」という不変条件が最後のUIで破られる。
     * 3値を並べて固定することで、タイトルの由来がComposable側の`succeeded`分岐(2値)へ
     * 戻る変更を検出できるようにする。
     */
    @Test
    fun resultMessage_titlesDifferAcrossExtendedUnknownAndFailure() {
        val extended = LoanExtensionContentBuilder.resultMessage(LoanExtensionOutcome.Extended(LocalDate.of(2026, 8, 19)))
        val unknown = LoanExtensionContentBuilder.resultMessage(LoanExtensionOutcome.Unknown)
        val failure = LoanExtensionContentBuilder.resultMessage(LoanExtensionOutcome.Failure(FailureReason.NETWORK))

        // 3種別ともタイトルが異なること。
        assertNotEquals(extended.title, unknown.title)
        assertNotEquals(extended.title, failure.title)
        assertNotEquals(unknown.title, failure.title)

        // Unknownのタイトルは、失敗を断定する文言(「できませんでした」等)であってはならない。
        assertFalse(
            "Unknownのタイトルが失敗断定の文言になっている: ${unknown.title}",
            unknown.title.contains("できませんでした"),
        )
        // Unknownの本文は設計§6の指定どおり変更しない。「再度お試しください」も促さない
        // (延長が既に成立している可能性があるため、再送を促す文言は不変条件に反する)。
        assertFalse(
            "Unknownの本文が再送を促している: ${unknown.message}",
            unknown.message.contains("再度") || unknown.message.contains("もう一度"),
        )

        assertEquals(LoanExtensionResultKind.EXTENDED, extended.kind)
        assertEquals(LoanExtensionResultKind.UNKNOWN, unknown.kind)
        assertEquals(LoanExtensionResultKind.FAILED, failure.kind)
    }
}
