package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import java.time.LocalDate
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 貸出延長を実行する直前にだけ使うパーサー。通常同期の`LoanListParser`とは分担を分ける
 * (`docs/design/loan-extension.md` §4.2)。
 *
 * 各行の`tilcod`・返却期限日・延長ボタン(`extend(mngcod)`)由来の`renewalCode`を持つ
 * [LoanExtensionRow]のリストを返す。`renewalCode`はRoomへ保存せず、この結果はここでしか使わない。
 * 延長ボタンが無い行はそもそも送信対象になり得ないため、結果に含めない。
 */
internal object LoanExtensionListParser {
    private const val SCREEN = "loan_extension_list"

    /**
     * `extend(mngcod)`の抽出は完全一致のみを許可する(空値・複数候補・未知構文はParseException)。
     * 実サイト・フィクスチャとも引数はダブルクォートで囲まれる(`extend("222057515")`)。
     * 設計書(§4.2)の説明ではシングルクォート表記だが、これはHARの説明記述の揺れであり、
     * `docs/site-research.md` §10・実フィクスチャの実測はいずれもダブルクォートである。
     */
    private val EXTEND_CODE_REGEX = Regex("""\s*(?:javascript:\s*)?extend\(\s*"(\d+)"\s*\)\s*;?\s*""")

    fun parse(html: String): List<LoanExtensionRow> {
        val document = Jsoup.parse(html)
        ParserSupport.requireHeading(document, SCREEN, "貸出状況一覧")
        val table = ParserSupport.requireTable(document, SCREEN, "貸出状況一覧表")
        val headers = ParserSupport.headers(table)
        val dueDateIndex = ParserSupport.requireHeader(headers, SCREEN, "返却期日")

        val rows = table.select("tbody > tr").mapNotNull { row ->
            val buttons = row.select("input[type=button]").filter { it.attr("onclick").contains("extend(") }
            if (buttons.isEmpty()) return@mapNotNull null
            if (buttons.size > 1) throw ParseException(SCREEN, "延長ボタンを一意に特定できません")
            val renewalCode = EXTEND_CODE_REGEX.matchEntire(buttons.single().attr("onclick"))
                ?.groupValues
                ?.get(1)
                ?.takeIf { it.isNotBlank() }
                ?: throw ParseException(SCREEN, "延長ボタンの形式が不正です")
            val tilcod = row.selectFirst("a[href*=para], a[href*=tilcod]")
                ?.let(::titleCodeFromLink)
                ?.takeIf { it.isNotBlank() }
                ?: throw ParseException(SCREEN, "タイトルコードを取得できません")
            val dueDate = ParserSupport.parseFullDate(row.cell(dueDateIndex, SCREEN, "返却期日"), SCREEN, "返却期日")
            LoanExtensionRow(tilcod = tilcod, dueDate = dueDate, renewalCode = renewalCode)
        }

        val duplicatedCodes = rows.groupingBy { it.renewalCode }.eachCount().filterValues { it > 1 }
        if (duplicatedCodes.isNotEmpty()) throw ParseException(SCREEN, "延長コードが重複しています")

        return rows
    }

    /** 貸出詳細リンクのpara（またはtilcod）から書誌コードを取り出す。LoanListParserと同じ規則。 */
    private fun titleCodeFromLink(link: Element): String {
        val value = link.attr("href") + " " + link.attr("onclick")
        return TITLE_CODE_REGEX.find(value)?.groupValues?.get(1).orEmpty()
    }

    private val TITLE_CODE_REGEX = Regex("(?:[?&](?:para|tilcod)=|toDetail\\(\\\")(\\d+)")
}

/** 延長実行時にだけ使う1行分の情報。renewalCodeはLoanドメインモデルへ持たせない(§4.1)。 */
internal data class LoanExtensionRow(
    val tilcod: String,
    val dueDate: LocalDate,
    val renewalCode: String,
)
