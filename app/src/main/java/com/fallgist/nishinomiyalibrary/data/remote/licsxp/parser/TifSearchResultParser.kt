package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import com.fallgist.nishinomiyalibrary.domain.model.SearchHit
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import com.fallgist.nishinomiyalibrary.domain.model.SearchSort
import com.fallgist.nishinomiyalibrary.domain.model.SearchSortKey
import com.fallgist.nishinomiyalibrary.domain.model.SortDirection
import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 詳細検索(`WOpacTifSchCmpd`)の結果「検索結果書誌一覧」の表の解析器
 * (docs/design/search-sort-filter.md §3.3)。`tif_search_result*.html` で固定する。
 */
object TifSearchResultParser {
    private const val screen = "tif_search_result"
    private const val ASCENDING_MARK = '▲'
    private const val DESCENDING_MARK = '▼'

    /** 結果画面の `LBForm` の hidden `sortKey`・`isAsc`(並べ替えの状態の正本)。 */
    data class HiddenSort(val sortKey: String, val isAsc: String) {
        /** hidden が [sort] の項目と向き(1=昇順、0=降順)を指しているか。 */
        fun matches(sort: SearchSort): Boolean =
            sortKey == sort.key.siteKey && isAsc == if (sort.direction == SortDirection.ASCENDING) "1" else "0"
    }

    /**
     * 0件のときサイトは結果の表ではなく詳細検索の入力画面(h1「詳細検索」)を返し、
     * スクリプト内に「該当する書誌がありません。」が出る(`tif_search_zero.html`)。
     */
    fun isZeroResult(html: String): Boolean {
        if (!html.contains("該当する書誌がありません")) return false
        val heading = Jsoup.parse(html).selectFirst("h1")?.text().orEmpty()
        return heading.contains("詳細検索")
    }

    /** hidden の並べ替えの状態。LBForm か hidden が無ければ null。 */
    fun parseHiddenSort(html: String): HiddenSort? {
        val form = Jsoup.parse(html).selectFirst("form[name=LBForm]") ?: return null
        val sortKey = form.selectFirst("input[type=hidden][name=sortKey]")?.attr("value") ?: return null
        val isAsc = form.selectFirst("input[type=hidden][name=isAsc]")?.attr("value") ?: return null
        return HiddenSort(sortKey, isAsc)
    }

    /** 在庫状況 select の選択中の値。select が無ければ null。 */
    fun parseStockState(html: String): String? {
        val select = Jsoup.parse(html).selectFirst("form[name=LBForm] select[name=stockState]") ?: return null
        val options = select.select("option")
        return (options.firstOrNull { it.hasAttr("selected") } ?: options.firstOrNull())?.attr("value")
    }

    fun parse(html: String): SearchPage {
        if (isZeroResult(html)) return SearchPage(emptyList(), 0, false)
        val document = Jsoup.parse(html)
        ParserSupport.requireHeading(document, screen, "検索結果")
        val totalCount = parseTotalCount(document)
        val table = document.selectFirst("table.list")
        val hits = if (table == null) {
            // 該当0件のときは表が無い可能性がある。件数が1以上なのに表が無いなら構造の変更とみなす。
            if (totalCount > 0) throw ParseException(screen, "結果の表 table.list が見つかりません")
            emptyList()
        } else {
            parseRows(table)
        }
        return SearchPage(
            hits = hits,
            totalCount = totalCount,
            hasNext = document.select(".paging a").any { it.text().contains("次へ") },
            currentSort = parseCurrentSort(document),
        )
    }

    /** 見出しの並べ替え表示だけを読む。表示が無ければ null。 */
    fun parseSort(html: String): SearchSort? = parseCurrentSort(Jsoup.parse(html))

    private fun parseTotalCount(document: Document): Int {
        val countText = document.select("li").firstOrNull { it.text().contains("該当件数は") }?.text()
            ?: throw ParseException(screen, "該当件数の表示が見つかりません")
        return Regex("該当件数は\\s*(\\d+)").find(countText)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw ParseException(screen, "該当件数が数値で見つかりません")
    }

    private fun parseRows(table: org.jsoup.nodes.Element): List<SearchHit> {
        val headers = ParserSupport.headers(table)
        val typeIndex = ParserSupport.requireHeader(headers, screen, "書誌種別")
        val titleIndex = ParserSupport.requireHeader(headers, screen, "書名")
        val authorIndex = ParserSupport.requireHeader(headers, screen, "著者")
        val publisherIndex = ParserSupport.requireHeader(headers, screen, "出版者")
        val publishedIndex = ParserSupport.requireHeader(headers, screen, "出版年月")
        val classificationIndex = ParserSupport.requireHeader(headers, screen, "分類")
        val lendIndex = ParserSupport.requireHeader(headers, screen, "貸出")
        return table.select("tbody > tr").map { row ->
            val titleCell = row.children().filter { it.tagName() == "th" || it.tagName() == "td" }
                .getOrNull(titleIndex)
                ?: throw ParseException(screen, "書名のセルが見つかりません")
            val link = titleCell.selectFirst("a[href*='tilcod=']")
                ?: throw ParseException(screen, "書名リンクが見つかりません")
            val tilcod = queryValue(link.attr("href"), "tilcod")?.takeIf { it.isNotEmpty() }
                ?: throw ParseException(screen, "タイトルコード tilcod が見つかりません")
            SearchHit(
                tilcod = tilcod,
                title = ParserSupport.run { link.text().normalized() },
                writerLine = row.cell(authorIndex, screen, "著者"),
                materialType = row.cell(typeIndex, screen, "書誌種別"),
                publisher = row.cell(publisherIndex, screen, "出版者"),
                publishedYearMonth = row.cell(publishedIndex, screen, "出版年月"),
                classification = row.cell(classificationIndex, screen, "分類"),
                lendable = lendableOf(row.cell(lendIndex, screen, "貸出")),
            )
        }
    }

    private fun lendableOf(text: String): Boolean? = when (text) {
        "○" -> true
        "×" -> false
        else -> null
    }

    /** 見出しの `<a id="listTable.<sortKey>">…▲/▼</a>` から、今の並べ替えを読む。 */
    private fun parseCurrentSort(document: Document): SearchSort? {
        for (anchor in document.select("table.list thead a[id^=listTable.]")) {
            val text = ParserSupport.run { anchor.text().normalized() }
            val direction = when {
                text.endsWith(ASCENDING_MARK) -> SortDirection.ASCENDING
                text.endsWith(DESCENDING_MARK) -> SortDirection.DESCENDING
                else -> continue
            }
            val key = SearchSortKey.fromSiteKey(anchor.id().removePrefix("listTable."))
                ?: throw ParseException(screen, "未知の並べ替え項目です: ${anchor.id()}")
            return SearchSort(key, direction)
        }
        return null
    }

    private fun queryValue(href: String, name: String): String? =
        runCatching {
            URI("https://example.invalid/$href").rawQuery
                ?.split('&')
                ?.firstOrNull { it.substringBefore('=') == name }
                ?.substringAfter('=')
        }.getOrNull()
}
