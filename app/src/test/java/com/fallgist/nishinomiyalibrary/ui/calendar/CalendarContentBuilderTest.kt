package com.fallgist.nishinomiyalibrary.ui.calendar

import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Member
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarContentBuilderTest {
    private val today: LocalDate = LocalDate.of(2026, 7, 20)

    private fun member(id: Long, colorHex: String, sortOrder: Int) = Member(id, "member$id", colorHex, "card$id", sortOrder)

    private fun loan(memberId: Long, dueDate: LocalDate) = Loan(
        memberId = memberId,
        title = "本",
        materialType = "図書",
        lendingLibrary = "中央図書館",
        loanDate = dueDate.minusDays(14),
        dueDate = dueDate,
        status = "貸出中",
    )

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

    @Test
    fun `期限日のセルにだけdueMemberColorsが入り他の日は空である`() {
        val papa = member(1, "#3D6DB5", 0)
        val dueDate = LocalDate.of(2026, 7, 25)
        val dueColors = CalendarContentBuilder.dueMemberColorsByDate(listOf(loan(papa.id, dueDate)), listOf(papa))

        val months = CalendarContentBuilder.months(today, closedDays = emptySet(), dueMemberColorsByDate = dueColors)

        val july = months[0].weeks.flatten().filter { it.dayOfMonth != null }
        val day25 = july.first { it.dayOfMonth == 25 }
        assertEquals(listOf("#3D6DB5"), day25.dueMemberColors)
        assertTrue(july.filter { it.dayOfMonth != 25 }.all { it.dueMemberColors.isEmpty() })
    }

    @Test
    fun `同一メンバーが同じ日に複数冊借りていても色は1個に畳まれる`() {
        val papa = member(1, "#3D6DB5", 0)
        val dueDate = LocalDate.of(2026, 7, 25)
        val dueColors = CalendarContentBuilder.dueMemberColorsByDate(
            listOf(loan(papa.id, dueDate), loan(papa.id, dueDate)),
            listOf(papa),
        )

        assertEquals(listOf("#3D6DB5"), dueColors[dueDate])
    }

    @Test
    fun `複数メンバーの色がsortOrder順に並ぶ`() {
        val papa = member(1, "#3D6DB5", 2)
        val mama = member(2, "#C25278", 0)
        val taro = member(3, "#D98E2B", 1)
        val dueDate = LocalDate.of(2026, 7, 25)
        val dueColors = CalendarContentBuilder.dueMemberColorsByDate(
            listOf(loan(papa.id, dueDate), loan(mama.id, dueDate), loan(taro.id, dueDate)),
            listOf(papa, mama, taro),
        )

        assertEquals(listOf("#C25278", "#D98E2B", "#3D6DB5"), dueColors[dueDate])
    }

    @Test
    fun `在籍メンバーに無いmemberIdの貸出は無視される`() {
        val papa = member(1, "#3D6DB5", 0)
        val dueDate = LocalDate.of(2026, 7, 25)
        val dueColors = CalendarContentBuilder.dueMemberColorsByDate(
            listOf(loan(papa.id, dueDate), loan(99, dueDate)),
            listOf(papa),
        )

        assertEquals(listOf("#3D6DB5"), dueColors[dueDate])
    }

    @Test
    fun `表示3ヶ月の範囲外の期限日はどのマスにも現れない`() {
        val papa = member(1, "#3D6DB5", 0)
        val outOfRangeDate = LocalDate.of(2026, 11, 1)
        val dueColors = CalendarContentBuilder.dueMemberColorsByDate(listOf(loan(papa.id, outOfRangeDate)), listOf(papa))

        val months = CalendarContentBuilder.months(today, closedDays = emptySet(), dueMemberColorsByDate = dueColors)

        assertTrue(months.flatMap { it.weeks.flatten() }.all { it.dueMemberColors.isEmpty() })
    }

    @Test
    fun `日付のあるマスにdateが入り空セルはdateがnullである`() {
        val months = CalendarContentBuilder.months(today, closedDays = emptySet())

        val july = months[0].weeks.flatten()
        val day20 = july.first { it.dayOfMonth == 20 }
        assertEquals(LocalDate.of(2026, 7, 20), day20.date)
        val emptyCell = july.first { it.dayOfMonth == null }
        assertNull(emptyCell.date)
    }

    @Test
    fun `休館日ときょうと返却期限が同一日でも3つのフラグ値が同時に立つ`() {
        val papa = member(1, "#3D6DB5", 0)
        val dueColors = CalendarContentBuilder.dueMemberColorsByDate(listOf(loan(papa.id, today)), listOf(papa))

        val months = CalendarContentBuilder.months(today, closedDays = setOf(today), dueMemberColorsByDate = dueColors)

        val day = months[0].weeks.flatten().first { it.dayOfMonth == today.dayOfMonth }
        assertTrue(day.isClosed)
        assertTrue(day.isToday)
        assertEquals(listOf("#3D6DB5"), day.dueMemberColors)
    }
}
