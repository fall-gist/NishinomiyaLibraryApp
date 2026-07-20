package com.fallgist.nishinomiyalibrary.data.sync

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPlannerTest {
    private val today: LocalDate = LocalDate.of(2026, 7, 20)

    private fun loan(title: String, dueDate: LocalDate) = LoanNotificationSource(
        memberId = 1L,
        memberName = "パパ",
        title = title,
        dueDate = dueDate,
    )

    @Test
    fun `既定の1日前は明日返却と期限超過だけを対象にする`() {
        val plan = NotificationPlanner.returnReminder(
            today = today,
            loans = listOf(
                loan("明日返却", today.plusDays(1)),
                loan("明後日返却", today.plusDays(2)),
                loan("期限超過", today.minusDays(3)),
            ),
        )

        val titles = plan!!.itemsByMember.single().titles
        assertEquals(setOf("明日返却", "期限超過"), titles.toSet())
        assertTrue(plan.hasOverdue)
    }

    @Test
    fun `通知日数を増やすと期限がその日数以内の貸出まで対象になる`() {
        val plan = NotificationPlanner.returnReminder(
            today = today,
            daysBefore = 3,
            loans = listOf(
                loan("3日後返却", today.plusDays(3)),
                loan("4日後返却", today.plusDays(4)),
            ),
        )

        assertEquals(listOf("3日後返却"), plan!!.itemsByMember.single().titles)
        assertEquals(false, plan.hasOverdue)
    }

    @Test
    fun `対象が無ければ通知しない`() {
        val plan = NotificationPlanner.returnReminder(
            today = today,
            daysBefore = 7,
            loans = listOf(loan("まだ先", today.plusDays(8))),
        )

        assertNull(plan)
    }

    @Test
    fun `通知日数0以下は受け付けない`() {
        assertTrue(
            runCatching {
                NotificationPlanner.returnReminder(today, emptyList(), daysBefore = 0)
            }.isFailure,
        )
    }
}
