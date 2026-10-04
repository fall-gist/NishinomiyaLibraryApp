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

    fun parse(html: String): SearchPage {
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
