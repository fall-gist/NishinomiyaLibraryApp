package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** 読書履歴一覧の1ページ分。次ページがなければ [nextStartIndex] はnull。 */
data class UsrReadPage(
    val records: List<ReadingRecord>,
    val nextStartIndex: Int?,
)

/**
 * 読書履歴は閉じtr/tbodyが不揃いなため、Jsoupが復元した行とセルだけを参照する。
 * 削除列・削除ボタンは一切参照しない。
 */
object UsrReadListParser {
    private const val screen = "usr_read_list"

    fun parse(
        html: String,
        startIndex: Int = 0,
        memberId: Long = UNASSIGNED_MEMBER_ID,
    ): UsrReadPage {
        val document = Jsoup.parse(html)
        ParserSupport.requireHeading(document, screen, "読書履歴")
        val table = ParserSupport.requireTable(document, screen, "読書履歴一覧表")
        val headers = ParserSupport.headers(table)
        val bibliographic = ParserSupport.requireHeader(headers, screen, "書誌情報")
        val loanDate = ParserSupport.requireHeader(headers, screen, "貸出日")
        val library = ParserSupport.requireHeader(headers, screen, "貸出館")
        val records = table.select("tr.ItemNo").map { row ->
            val titleCell = row.cellElement(bibliographic, screen, "書誌情報")
            val link = titleCell.selectFirst("a[href*=tilcod], a[href*=para]")
                ?: throw ParseException(screen, "書誌情報のリンクが見つかりません")
            val tilcod = titleCodeFromLink(link)
                ?: throw ParseException(screen, "タイトルコードが見つかりません")
            ReadingRecord(
                memberId = memberId,
                tilcod = tilcod,
                title = titleCell.text().let { ParserSupport.run { it.normalized() } }
                    .takeIf { it.isNotEmpty() }
                    ?: throw ParseException(screen, "書誌情報が空です"),
                loanDate = ParserSupport.parseFullDate(row.cell(loanDate, screen, "貸出日"), screen, "貸出日"),
                library = row.cell(library, screen, "貸出館"),
            )
        }
        return UsrReadPage(records, nextStartIndex(document, startIndex))
    }

    private fun nextStartIndex(document: org.jsoup.nodes.Document, currentStartIndex: Int): Int? = document
        .select("a[href*=WOpacUsrReadListAction]")
        .mapNotNull { link -> START_INDEX_REGEX.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
        .filter { index -> index > currentStartIndex }
        .minOrNull()

    private fun titleCodeFromLink(link: Element): String? {
        val text = link.attr("href") + " " + link.attr("onclick")
        return TITLE_CODE_REGEX.find(text)?.groupValues?.get(1)
    }

    private val TITLE_CODE_REGEX = Regex("(?:[?&](?:tilcod|para)=|toDetail\\(\\\")(\\d+)")
    private val START_INDEX_REGEX = Regex("[?&]startIndex=(\\d+)")
}

private fun Element.cellElement(index: Int, screen: String, label: String): Element =
    children().filter { it.tagName() == "th" || it.tagName() == "td" }.getOrNull(index)
        ?: throw ParseException(screen, "$label のセルが見つかりません")
