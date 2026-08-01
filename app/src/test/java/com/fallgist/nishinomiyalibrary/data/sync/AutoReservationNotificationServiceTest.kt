package com.fallgist.nishinomiyalibrary.data.sync

import com.fallgist.nishinomiyalibrary.data.repository.AutomaticReservationItemResult
import com.fallgist.nishinomiyalibrary.data.repository.AutomaticReservationRunResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReservationNotificationServiceTest {
    @Test
    fun `完了履歴を確保見送りエラーへ分類する`() {
        val summary = AutoReservationNotificationPlanner.plan(
            completed(
                "SUCCESS",
                "ALREADY_RESERVED",
                "ALL_MEMBERS_LIMITED",
                "SETTINGS_MISSING",
                "RULE_DISABLED",
                "SITE_STOP",
                "NETWORK",
            ),
        )

        assertEquals(AutoReservationNotificationSummary(2, 3, 2), summary)
        assertEquals("確保済み2件／見送り3件／エラー2件", summary?.body)
    }

    @Test
    fun `本文に書名会員規則tilcodを含めない`() {
        val secretTilcod = "SECRET-TILCOD"
        val secretTitle = "SECRET-TITLE"
        val summary = AutoReservationNotificationPlanner.plan(
            AutomaticReservationRunResult.Completed(
                listOf(AutomaticReservationItemResult(secretTilcod, secretTitle, "SUCCESS")),
            ),
        )

        val body = requireNotNull(summary).body
        assertFalse(body.contains(secretTilcod))
        assertFalse(body.contains(secretTitle))
        assertEquals("確保済み1件／見送り0件／エラー0件", body)
    }

    @Test
    fun `空履歴通常除外のみOFFのみは通知しない`() {
        assertNull(AutoReservationNotificationPlanner.plan(completed()))
        assertNull(AutoReservationNotificationPlanner.plan(completed("EXCLUDED_READ", "EXCLUDED_LOANED")))
        assertNull(AutoReservationNotificationPlanner.plan(completed("RULE_DISABLED", "RULE_DISABLED")))
        assertNull(AutoReservationNotificationPlanner.plan(AutomaticReservationRunResult.NoMatch))
    }

    @Test
    fun `OFF混在時は見送りへ数えて通知する`() {
        assertEquals(
            AutoReservationNotificationSummary(1, 1, 0),
            AutoReservationNotificationPlanner.plan(completed("RULE_DISABLED", "SUCCESS")),
        )
    }

    @Test
    fun `一回の完了につき通知は一回で例外も呼出元へ漏らさない`() = runTest {
        val sink = RecordingSink()
        val service = AutoReservationNotificationService(sink)
        val result = completed("SUCCESS", "REJECTED")

        service.notifyCompletion(result)
        assertEquals(1, sink.calls)
        assertEquals(AutoReservationNotificationSummary(1, 0, 1), sink.lastSummary)

        val failure = IllegalStateException("notification failure")
        val rejecting = RecordingSink(failure)
        AutoReservationNotificationService(rejecting).notifyCompletion(result)
        assertEquals(1, rejecting.calls)
        assertSame(failure, rejecting.failure)
    }

    private fun completed(vararg outcomes: String) = AutomaticReservationRunResult.Completed(
        outcomes.mapIndexed { index, outcome ->
            AutomaticReservationItemResult("tilcod-$index", "title-$index", outcome)
        },
    )

    private class RecordingSink(
        val failure: Exception? = null,
    ) : AutoReservationNotificationSink {
        var calls = 0
        var lastSummary: AutoReservationNotificationSummary? = null

        override suspend fun postAutoReservation(summary: AutoReservationNotificationSummary): Boolean {
            calls += 1
            lastSummary = summary
            failure?.let { throw it }
            return true
        }
    }
}
