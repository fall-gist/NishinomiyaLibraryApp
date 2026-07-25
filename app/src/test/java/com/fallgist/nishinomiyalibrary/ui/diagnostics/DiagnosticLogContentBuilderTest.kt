package com.fallgist.nishinomiyalibrary.ui.diagnostics

import com.fallgist.nishinomiyalibrary.data.diagnostics.DiagnosticLogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticLogContentBuilderTest {
    private fun entry(epochMillis: Long, category: String, message: String) =
        DiagnosticLogEntry(epochMillis = epochMillis, category = category, message = message)

    @Test
    fun `availableCategoriesは出現順で重複を除いた一覧を返す`() {
        val entries = listOf(
            entry(1, "request", "a"),
            entry(2, "response", "b"),
            entry(3, "request", "c"),
            entry(4, "page", "d"),
        )
        assertEquals(listOf("request", "response", "page"), DiagnosticLogContentBuilder.availableCategories(entries))
    }

    @Test
    fun `検索語が空ならカテゴリ未選択時は全件を新しい順で返す`() {
        val entries = listOf(
            entry(1, "request", "GET /a"),
            entry(2, "response", "status 200"),
        )
        val filtered = DiagnosticLogContentBuilder.filter(entries, query = "", selectedCategories = emptySet())
        assertEquals(listOf(entries[1], entries[0]), filtered)
    }

    @Test
    fun `検索語は部分一致かつ大文字小文字を区別しない`() {
        val entries = listOf(
            entry(1, "request", "GET /Foo/bar"),
            entry(2, "response", "status 200 redirect=-"),
        )
        val filtered = DiagnosticLogContentBuilder.filter(entries, query = "foo", selectedCategories = emptySet())
        assertEquals(listOf(entries[0]), filtered)
    }

    @Test
    fun `カテゴリ選択があれば選択したカテゴリだけに絞られる`() {
        val entries = listOf(
            entry(1, "request", "a"),
            entry(2, "response", "b"),
            entry(3, "page", "c"),
        )
        val filtered = DiagnosticLogContentBuilder.filter(
            entries,
            query = "",
            selectedCategories = setOf("response", "page"),
        )
        assertEquals(listOf(entries[2], entries[1]), filtered)
    }

    @Test
    fun `検索語とカテゴリ選択は両方満たすものだけに絞られる`() {
        val entries = listOf(
            entry(1, "request", "GET /foo"),
            entry(2, "response", "GET /foo status=200"),
            entry(3, "response", "status=500"),
        )
        val filtered = DiagnosticLogContentBuilder.filter(
            entries,
            query = "foo",
            selectedCategories = setOf("response"),
        )
        assertEquals(listOf(entries[1]), filtered)
    }

    @Test
    fun `検索語はカテゴリ名にも一致する`() {
        val entries = listOf(
            entry(1, "script-actions", "abc"),
            entry(2, "response", "def"),
        )
        val filtered = DiagnosticLogContentBuilder.filter(entries, query = "script", selectedCategories = emptySet())
        assertEquals(listOf(entries[0]), filtered)
    }

    @Test
    fun `formatForCopyは各行をtimeLabel category messageの形式で連結する`() {
        val rows = listOf(
            DiagnosticLogRow(timeLabel = "09:00:00.000", category = "request", message = "GET /a", copyText = "09:00:00.000 [request] GET /a"),
            DiagnosticLogRow(timeLabel = "09:00:01.000", category = "response", message = "status=200", copyText = "09:00:01.000 [response] status=200"),
        )
        assertEquals(
            "09:00:00.000 [request] GET /a\n09:00:01.000 [response] status=200",
            DiagnosticLogContentBuilder.formatForCopy(rows),
        )
    }

    @Test
    fun `persistedBytesLabelはB_KB_MBを適切な単位で整形する`() {
        assertEquals("512B", DiagnosticLogContentBuilder.persistedBytesLabel(512))
        assertEquals("2.0KB", DiagnosticLogContentBuilder.persistedBytesLabel(2048))
        assertEquals("1.5MB", DiagnosticLogContentBuilder.persistedBytesLabel((1.5 * 1024 * 1024).toLong()))
    }
}
