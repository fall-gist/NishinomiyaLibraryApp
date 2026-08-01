package com.fallgist.nishinomiyalibrary.ui.autoreservation

import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationLatestItem
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationLatestRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReservationRunPresentationTest {
    @Test
    fun `成功 予約済み 成否不明を別文言で表示する`() {
        val view = AutoReservationRunPresentationBuilder.build(
            AutoReservationLatestRun(1, 0, "{\"success\":2,\"skipped\":0,\"error\":1}", false, listOf(
                item("SUCCESS"), item("ALREADY_RESERVED"), item("UNKNOWN_AFTER_POST"),
            )),
        )

        assertEquals(1L, view.runId)
        assertEquals("確保済み2件／見送り0件／エラー1件", view.summaryText)
        assertEquals("予約を確保しました", view.items[0].outcomeText)
        assertEquals("すでに予約済みです", view.items[1].outcomeText)
        assertEquals("予約送信後の成否を確認できません", view.items[2].outcomeText)
    }

    @Test
    fun `壊れたJSONと未知結果は安全な文言へ倒す`() {
        val view = AutoReservationRunPresentationBuilder.build(
            AutoReservationLatestRun(1, 0, "broken", false, listOf(AutoReservationLatestItem(1, "x", "書名", "[", "{", "FUTURE"))),
        )

        assertEquals("詳細を読み取れません", view.summaryText)
        assertEquals("結果を読み取れません", view.items.single().outcomeText)
        assertTrue(view.items.single().matchedTermsText.contains("詳細を読み取れません"))
    }

    @Test
    fun `既知結果と試行結果を日本語化する`() {
        val outcomes = listOf("SUCCESS", "ALREADY_RESERVED", "UNKNOWN_AFTER_POST", "ALL_MEMBERS_LIMITED", "ALL_MEMBERS_PRE_SUBMIT_FAILED", "SETTINGS_MISSING", "RULE_DISABLED", "CIRCULATION_UNAVAILABLE", "REJECTED", "SITE_STOP")
        val view = AutoReservationRunPresentationBuilder.build(AutoReservationLatestRun(1, 0, "{\"success\":0,\"skipped\":0,\"error\":0}", false, outcomes.map(::item)))
        assertTrue(view.items.none { it.outcomeText == "結果を読み取れません" })

        val attempts = "{\"trials\":[{\"memberName\":\"太郎\",\"result\":\"RESERVATION_LIMIT_EXCEEDED\"},{\"memberName\":\"花子\",\"result\":\"NETWORK\"}]}"
        val attemptView = AutoReservationRunPresentationBuilder.build(AutoReservationLatestRun(2, 0, "{\"success\":0,\"skipped\":0,\"error\":0}", false, listOf(AutoReservationLatestItem(2, "x", "書名", "[]", attempts, "SUCCESS"))))
        assertTrue(!attemptView.items.single().attemptsText.contains("RESERVATION_LIMIT_EXCEEDED"))
        assertTrue(!attemptView.items.single().attemptsText.contains("NETWORK"))
    }

    private fun item(outcome: String) = AutoReservationLatestItem(
        runId = 1,
        tilcod = outcome,
        title = "書名",
        matchedRulesJson = "[{\"includeTerms\":[\"語\"],\"excludeTerms\":[\"除外\"]}]",
        attemptedMembersJson = "{\"trials\":[{\"memberName\":\"太郎\",\"result\":\"SUCCESS\"}],\"assignedMember\":{\"name\":\"太郎\"}",
        outcome = outcome,
    )
}
