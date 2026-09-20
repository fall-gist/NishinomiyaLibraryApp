package com.fallgist.nishinomiyalibrary.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/design/reading-records-search.md` §4 のテスト計画(1〜7)に対応する。
 */
class KeywordQueryTest {
    @Test
    fun `空文字 空白のみの入力では語が空リストになり matchesは常にtrue`() {
        assertEquals(emptyList<String>(), KeywordQuery.terms(""))
        assertEquals(emptyList<String>(), KeywordQuery.terms("   "))
        assertEquals(emptyList<String>(), KeywordQuery.terms("　　"))

        assertTrue(KeywordQuery.matches("かいけつゾロリの大金もち", KeywordQuery.terms("")))
        assertTrue(KeywordQuery.matches("", KeywordQuery.terms("   ")))
    }

    @Test
    fun `1語は部分一致する`() {
        val terms = KeywordQuery.terms("ゾロリ")
        assertTrue(KeywordQuery.matches("かいけつゾロリの大金もち", terms))
        assertFalse(KeywordQuery.matches("忍たま乱太郎", terms))
    }

    @Test
    fun `2語のANDは両方含むものだけヒットし語の順序は問わない`() {
        val termsA = KeywordQuery.terms("ゾロリ 大金")
        val termsB = KeywordQuery.terms("大金 ゾロリ")

        assertTrue(KeywordQuery.matches("かいけつゾロリの大金もち", termsA))
        assertTrue(KeywordQuery.matches("かいけつゾロリの大金もち", termsB))
    }

    @Test
    fun `片方しか含まないものはヒットしない`() {
        val terms = KeywordQuery.terms("ゾロリ 大金")

        assertFalse(KeywordQuery.matches("かいけつゾロリの初恋", terms))
        assertFalse(KeywordQuery.matches("忍たま乱太郎と大金もち", terms))
    }

    @Test
    fun `全角空白での区切りと半角全角混在 連続した空白を1つの区切りとして扱う`() {
        assertEquals(listOf("ゾロリ", "大金"), KeywordQuery.terms("ゾロリ　大金"))
        assertEquals(listOf("ゾロリ", "大金"), KeywordQuery.terms("ゾロリ 　大金"))
        assertEquals(listOf("ゾロリ", "大金"), KeywordQuery.terms("ゾロリ   大金"))
    }

    @Test
    fun `全角英数と半角英数 大文字小文字を同一視する`() {
        val terms = KeywordQuery.terms("ＡＢＣ")
        assertTrue(KeywordQuery.matches("abc入門", terms))

        val lowerTerms = KeywordQuery.terms("abc")
        assertTrue(KeywordQuery.matches("ＡＢＣ入門", lowerTerms))
    }

    @Test
    fun `対象側に空白が含まれる場合でも語がヒットする 現行の挙動を維持`() {
        val terms = KeywordQuery.terms("abc 著者")
        assertTrue(KeywordQuery.matches("ＡＢＣ　著者", terms))
    }
}
