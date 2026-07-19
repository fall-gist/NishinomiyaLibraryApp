package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import com.fallgist.nishinomiyalibrary.domain.model.Loan
import org.jsoup.Jsoup

object LoanListParser {
    private const val screen = "loan_list"

    fun parse(html: String, memberId: Long = UNASSIGNED_MEMBER_ID): List<Loan> {
        val document = Jsoup.parse(html)
        ParserSupport.requireHeading(document, screen, "貸出状況一覧")
        val table = ParserSupport.requireTable(document, screen, "貸出状況一覧表")
        val headers = ParserSupport.headers(table)
        val title = ParserSupport.requireHeader(headers, screen, "資料名")
        val materialType = ParserSupport.requireHeader(headers, screen, "書誌種別")
        val library = ParserSupport.requireHeader(headers, screen, "貸出館")
        val loanDate = ParserSupport.requireHeader(headers, screen, "貸出日")
        val dueDate = ParserSupport.requireHeader(headers, screen, "返却期日")
        val status = ParserSupport.requireHeader(headers, screen, "状態")
        return table.select("tbody > tr").map { row ->
            Loan(
                memberId = memberId,
                title = row.rawCell(title, screen, "資料名"),
                materialType = row.cell(materialType, screen, "書誌種別"),
                lendingLibrary = row.cell(library, screen, "貸出館"),
                loanDate = ParserSupport.parseFullDate(row.cell(loanDate, screen, "貸出日"), screen, "貸出日"),
                dueDate = ParserSupport.parseFullDate(row.cell(dueDate, screen, "返却期日"), screen, "返却期日"),
                status = row.cell(status, screen, "状態"),
                tilcod = row.selectFirst("a[href*=para], a[href*=tilcod]")
                    ?.let(::titleCodeFromLink)
                    .orEmpty(),
            )
        }
    }

    /** 貸出詳細リンクのpara（またはtilcod）から書誌コードを取り出す。 */
    private fun titleCodeFromLink(link: org.jsoup.nodes.Element): String {
        val value = link.attr("href") + " " + link.attr("onclick")
        return TITLE_CODE_REGEX.find(value)?.groupValues?.get(1).orEmpty()
    }

    private val TITLE_CODE_REGEX = Regex("(?:[?&](?:para|tilcod)=|toDetail\\(\\\")(\\d+)")
}
