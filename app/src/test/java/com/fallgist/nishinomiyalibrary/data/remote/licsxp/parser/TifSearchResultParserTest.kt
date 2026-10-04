package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import com.fallgist.nishinomiyalibrary.domain.model.SearchSort
import com.fallgist.nishinomiyalibrary.domain.model.SearchSortKey
import com.fallgist.nishinomiyalibrary.domain.model.SortDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 詳細検索の結果の表(docs/design/search-sort-filter.md §3.3)。採取した実HTMLで固定する。 */
class TifSearchResultParserTest {
    @Test
    fun `既定の結果は20件で件数と各列と貸出可否を読む`() {
        val page = TifSearchResultParser.parse(fixture("tif_search_result.html"))

        assertEquals(49, page.totalCount)
        assertEquals(20, page.hits.size)
        assertTrue(page.hasNext)
        // 既定の並びはサイトの見出しが「書名▲」で表示される。
        assertEquals(SearchSort(SearchSortKey.TITLE, SortDirection.ASCENDING), page.currentSort)

        val first = page.hits.first()
        assertEquals("1000001045325", first.tilcod)
        assertEquals("心にひびくマンガの名言 第2期1 困難を乗り越える", first.title)
        assertEquals("児童図書", first.materialType)
        assertEquals("", first.writerLine)
        assertEquals("学研プラス", first.publisher)
        assertEquals("2016/02", first.publishedYearMonth)
        assertEquals("15", first.classification)
        assertEquals(true, first.lendable)

        val last = page.hits.last()
        assertEquals("コミック", last.materialType)
        assertEquals("鳥山明／著", last.writerLine)
        assertEquals("726ﾄﾘ", last.classification)

        // 月不明は /00 のまま保つ
        assertEquals("1998/00", page.hits[2].publishedYearMonth)
        assertEquals(18, page.hits.count { it.lendable == true })
        assertEquals(2, page.hits.count { it.lendable == false })
    }

    @Test
    fun `出版年月の昇順と降順は見出しの三角から読む`() {
        val asc = TifSearchResultParser.parse(fixture("tif_search_result_pubymd_asc.html"))
        val desc = TifSearchResultParser.parse(fixture("tif_search_result_pubymd_desc.html"))

        assertEquals(SearchSort(SearchSortKey.PUBLISHED, SortDirection.ASCENDING), asc.currentSort)
        assertEquals(SearchSort(SearchSortKey.PUBLISHED, SortDirection.DESCENDING), desc.currentSort)
        assertEquals("1985/09", asc.hits.first().publishedYearMonth)
        assertEquals("2022/06", desc.hits.first().publishedYearMonth)
        assertEquals(false, desc.hits.first().lendable)
        // 軽量版(手順用)も同じ結果になる
        assertEquals(asc.currentSort, TifSearchResultParser.parseSort(fixture("tif_search_result_pubymd_asc.html")))
        assertEquals(desc.currentSort, TifSearchResultParser.parseSort(fixture("tif_search_result_pubymd_desc.html")))
    }

    @Test
    fun `2ページ目は降順が保たれ21件目から読む`() {
        val page = TifSearchResultParser.parse(fixture("tif_search_result_pubymd_desc_page2.html"))

        assertEquals(49, page.totalCount)
        assertEquals(20, page.hits.size)
        assertTrue(page.hasNext)
        assertEquals(SearchSort(SearchSortKey.PUBLISHED, SortDirection.DESCENDING), page.currentSort)
        assertEquals("1992/06", page.hits.first().publishedYearMonth)
        assertEquals(1, page.hits.count { it.lendable == false })
    }

    @Test
    fun `在庫状況の絞り込み後は43件で全て貸出可`() {
        val page = TifSearchResultParser.parse(fixture("tif_search_result_stock_lendable.html"))

        assertEquals(43, page.totalCount)
        assertEquals(20, page.hits.size)
        assertTrue(page.hits.all { it.lendable == true })
        assertEquals(SearchSort(SearchSortKey.PUBLISHED, SortDirection.DESCENDING), page.currentSort)
    }

    @Test
    fun `貸出列が丸バツ以外ならnullで次ページ表示が無ければhasNextはfalse`() {
        val html = fixture("tif_search_result.html")
            .replace("""<td class="a-center">""", """<td class="a-center">△""")
            .replace("次へ", "")
        val page = TifSearchResultParser.parse(html)

        assertTrue(page.hits.all { it.lendable == null })
        assertFalse(page.hasNext)
    }

    @Test
    fun `並べ替えの表示が無ければcurrentSortはnull`() {
        val html = fixture("tif_search_result.html").replace("書名▲", "書名")
        assertNull(TifSearchResultParser.parse(html).currentSort)
        assertNull(TifSearchResultParser.parseSort(html))
    }

    @Test
    fun `該当0件で表が無ければ空の結果になる`() {
        val html = """
            <html><body><h1>検索結果書誌一覧</h1>
            <ul><li>該当件数は <span>0</span> 件です。</li></ul></body></html>
        """.trimIndent()
        val page = TifSearchResultParser.parse(html)

        assertEquals(0, page.totalCount)
        assertTrue(page.hits.isEmpty())
        assertFalse(page.hasNext)
    }

    @Test
    fun `件数があるのに表が無ければParseException`() {
        val html = """
            <html><body><h1>検索結果書誌一覧</h1>
            <ul><li>該当件数は <span>5</span> 件です。</li></ul></body></html>
        """.trimIndent()
        assertParseError { TifSearchResultParser.parse(html) }
    }

    @Test
    fun `見出し・件数・列見出し・書名リンクが無ければParseException`() {
        val valid = fixture("tif_search_result.html")
        assertParseError { TifSearchResultParser.parse("<html><body><h1>別の画面</h1></body></html>") }
        assertParseError { TifSearchResultParser.parse(valid.replace("該当件数は", "件数は")) }
        assertParseError { TifSearchResultParser.parse(valid.replace(">出版年月", ">出版月")) }
        assertParseError { TifSearchResultParser.parse(valid.replace("tilcod=", "code=")) }
    }

    @Test
    fun `未知の並べ替え項目の三角はParseException`() {
        val html = fixture("tif_search_result.html")
            .replace("listTable.SLSTITL.TITLE_RD,SLSTITL.VOLUME_NUM_ST", "listTable.SLSTITL.UNKNOWN")
        assertParseError { TifSearchResultParser.parse(html) }
    }

    private fun assertParseError(block: () -> Unit) {
        try {
            block()
            fail("ParseException が送出されませんでした")
        } catch (expected: ParseException) {
            assertEquals("tif_search_result", expected.screen)
        }
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader).getResource("fixtures/$name")!!.readText()
}
