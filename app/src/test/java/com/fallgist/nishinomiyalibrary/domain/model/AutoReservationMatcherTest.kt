package com.fallgist.nishinomiyalibrary.domain.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReservationMatcherTest {
    @Test
    fun `書名だけをNFKC 空白除去 小文字化して含める語ANDと除外語で照合する`() {
        val rule = AutoReservationRule(
            enabled = true,
            sortOrder = 0,
            includeTerms = listOf("ＡＩ", " 入門 "),
            excludeTerms = listOf("改訂"),
        )

        assertTrue(AutoReservationMatcher.matches(rule, "ai　入門 第2版"))
        assertFalse(AutoReservationMatcher.matches(rule, "AI 実践"))
        assertFalse(AutoReservationMatcher.matches(rule, "AI入門 改訂版"))
    }

    @Test
    fun `保存時検証は正規化後の重複と除外語から含める語への包含だけを拒否する`() {
        assertTrue(runCatching {
            AutoReservationMatcher.validate(AutoReservationRule(enabled = true, sortOrder = 0, includeTerms = listOf("ＡＩ", "ai")))
        }.isFailure)
        assertTrue(runCatching {
            AutoReservationMatcher.validate(AutoReservationRule(enabled = true, sortOrder = 0, includeTerms = listOf("人工知能"), excludeTerms = listOf("知能")))
        }.isFailure)
        assertTrue(runCatching {
            AutoReservationMatcher.validate(AutoReservationRule(enabled = true, sortOrder = 0, includeTerms = listOf("知能"), excludeTerms = listOf("人工知能")))
        }.isSuccess)
    }

    @Test
    fun `候補順はルール順 出版年月降順 書名 巻次 タイトルコードで決まる`() {
        val arrivals = listOf(
            NewArrival("c", "z", "2", "", "", "不明", "", null),
            NewArrival("b", "z", "1", "", "", "2025/2", "", null),
            NewArrival("a", "a", "", "", "", "2025-02", "", null),
        )

        assertEquals(listOf("a", "b", "c"), arrivals.sortedWith(AutoReservationMatcher.candidateComparator(mapOf("a" to 0, "b" to 0, "c" to 0))).map { it.tilcod })
    }

    @Test
    fun `制御記録の二か月期限は暦月加算で末日と閏年を扱う`() {
        assertEquals(LocalDate.of(2024, 3, 29), LocalDate.of(2024, 1, 29).plusMonths(2))
        assertEquals(LocalDate.of(2024, 3, 31), LocalDate.of(2024, 1, 31).plusMonths(2))
        assertEquals(LocalDate.of(2025, 2, 28), LocalDate.of(2024, 12, 28).plusMonths(2))
    }
}
