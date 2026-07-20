package com.fallgist.nishinomiyalibrary.ui.calendar

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarContentBuilderTest {
    private val today: LocalDate = LocalDate.of(2026, 7, 20)

    @Test
    fun `当月から3ヶ月分を日曜始まりで組み立てる`() {
        val months = CalendarContentBuilder.months(today, closedDays = emptySet())

        assertEquals(listOf("2026年7月", "2026年8月", "2026年9月"), months.map { it.label })
        val july = months[0]
        // 2026-07-01は水曜: 先頭週は日月火の3空セル+1〜4日
        assertEquals(7, july.weeks[0].size)
        assertEquals(listOf(null, null, null, 1, 2, 3, 4), july.weeks[0].map { it.dayOfMonth })
        // どの週も7マスで、月末の後は空セルで埋まる
        assertTrue(july.weeks.all { it.size == 7 })
        assertEquals(31, july.weeks.flatten().mapNotNull { it.dayOfMonth }.size)
    }

    @Test
    fun `休館日ときょうと曜日のフラグが立つ`() {
        val months = CalendarContentBuilder.months(
            today,
            closedDays = setOf(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 8, 3)),
        )

        val july = months[0].weeks.flatten().filter { it.dayOfMonth != null }
        val day20 = july.first { it.dayOfMonth == 20 }
        assertTrue(day20.isClosed)
        assertTrue(day20.isToday)
        val day19 = july.first { it.dayOfMonth == 19 }
        assertTrue(day19.isSunday)
        assertFalse(day19.isClosed)
        val day18 = july.first { it.dayOfMonth == 18 }
        assertTrue(day18.isSaturday)

        val august = months[1].weeks.flatten()
        assertTrue(august.first { it.dayOfMonth == 3 }.isClosed)
        // きょうのフラグは当月にしか立たない
        assertTrue(august.none { it.isToday })
    }

    @Test
    fun `翌月以降の休館日は対象月にだけ反映される`() {
        val months = CalendarContentBuilder.months(today, closedDays = setOf(LocalDate.of(2026, 9, 7)))

        assertTrue(months[0].weeks.flatten().none { it.isClosed })
        assertTrue(months[1].weeks.flatten().none { it.isClosed })
        assertTrue(months[2].weeks.flatten().first { it.dayOfMonth == 7 }.isClosed)
    }
}
