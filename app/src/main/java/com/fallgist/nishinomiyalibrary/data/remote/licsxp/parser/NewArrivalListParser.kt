package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 1ジャンル分の新着資料一覧ページをパースする。
 * ジャンル情報は保持せず、書誌行のみを取り出す(名寄せ・統合は呼び出し側)。
 */
object NewArrivalListParser {
    private const val screen = "new_arrival_list"

    fun parse(html: String): List<NewArrival> {
        val document = Jsoup.parse(html)
        ParserSupport.requireHeading(document, screen, "新着資料")
        // summary属性は当てにならないため、見出し列で結果テーブルを特定する。
        val table = document.select("table.list").firstOrNull { candidate ->
            val headerText = candidate.select("thead th").joinToString("/") { it.text() }
            headerText.contains("書名") && headerText.contains("出版者")
        } ?: throw ParseException(screen, "新着資料一覧表が見つかりません")

        val headers = ParserSupport.headers(table)
        val titleColumn = ParserSupport.requireHeader(headers, screen, "書名")
        val volumeColumn = ParserSupport.requireHeader(headers, screen, "巻次")
        val authorColumn = ParserSupport.requireHeader(headers, screen, "著者")
        val publisherColumn = ParserSupport.requireHeader(headers, screen, "出版者")
        val publishedColumn = ParserSupport.requireHeader(headers, screen, "出版年月")
        val classificationColumn = ParserSupport.requireHeader(headers, screen, "分類")
        val lendableColumn = ParserSupport.requireHeader(headers, screen, "貸出")

        return table.select("tbody > tr").map { row ->
            val titleCell = row.cellElement(titleColumn, screen, "書名")
            NewArrival(
                tilcod = tilcodOf(titleCell)
                    ?: throw ParseException(screen, "タイトルコードが見つかりません"),
                title = ParserSupport.run { titleCell.text().normalized() },
                volume = row.cellText(volumeColumn),
                author = row.cellText(authorColumn),
                publisher = row.cellText(publisherColumn),
                publishedYearMonth = row.cellText(publishedColumn),
                classification = row.cellText(classificationColumn),
                lendable = lendableOf(row.cellText(lendableColumn)),
            )
        }
    }

    private fun tilcodOf(cell: Element): String? {
        val link = cell.selectFirst("a[href*=tilcod], a[onclick*=infoNext]") ?: return null
        val source = link.attr("href") + " " + link.attr("onclick")
        return TILCOD_REGEX.find(source)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun lendableOf(text: String): Boolean? = when {
        text.contains('○') || text.contains('◯') -> true
        text.contains('×') || text.contains('✕') -> false
        else -> null
    }

    private fun Element.cellElement(index: Int, screen: String, label: String): Element =
        children().filter { it.tagName() == "th" || it.tagName() == "td" }.getOrNull(index)
            ?: throw ParseException(screen, "$label のセルが見つかりません")

    private fun Element.cellText(index: Int): String =
        children().filter { it.tagName() == "th" || it.tagName() == "td" }.getOrNull(index)?.text()
            ?.let { ParserSupport.run { it.normalized() } }
            .orEmpty()

    private val TILCOD_REGEX = Regex("(?:tilcod=|infoNext\\(')(\\d+)")
}
