package com.fallgist.nishinomiyalibrary.ui.loans

import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionOutcome
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("返却期限を延長しました（新しい期限: 2026/08/19）", result.message)
        assertTrue(result.succeeded)
    }

    @Test
    fun resultMessage_unknown_doesNotSucceed() {
        val result = LoanExtensionContentBuilder.resultMessage(LoanExtensionOutcome.Unknown)
        assertEquals("延長できたか確認できません。しばらくしてから貸出状況をご確認ください", result.message)
        // Unknownを成功として集計してはならない(唯一の判定点)。
        assertFalse(result.succeeded)
    }

    @Test
    fun resultMessage_failure_neverSucceedsForAnyReason() {
        for (reason in FailureReason.entries) {
            val result = LoanExtensionContentBuilder.resultMessage(LoanExtensionOutcome.Failure(reason))
            assertFalse("reason=$reason", result.succeeded)
            assertTrue("reason=$reason", result.message.isNotBlank())
        }
    }
}
