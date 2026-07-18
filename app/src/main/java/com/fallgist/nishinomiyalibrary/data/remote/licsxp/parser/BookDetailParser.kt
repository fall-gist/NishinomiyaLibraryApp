package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.domain.model.Holding
import org.jsoup.Jsoup

object BookDetailParser {
    private const val screen = "book_detail"

    fun parse(html: String): BookDetail {
        val document = Jsoup.parse(html)
        ParserSupport.requireHeading(document, screen, "検索結果書誌詳細")
        val stockTable = ParserSupport.requireTable(document, screen, "蔵書情報")
        val detailTables = document.select("table[summary=詳細情報]")
        if (detailTables.isEmpty()) throw ParseException(screen, "summary=詳細情報 の table が見つかりません")
        val holdingsTable = ParserSupport.requireTable(document, screen, "資料情報２")

        val stock = stockTable.select("th").associate { header ->
            val value = header.nextElementSibling()?.text()?.let { ParserSupport.run { it.normalized() } }
                ?: throw ParseException(screen, "${header.text()} の値が見つかりません")
            ParserSupport.run { header.text().normalized() } to value
        }
        val fields = linkedMapOf<String, String>()
        detailTables.flatMap { it.select("tr") }.forEach { row ->
            val label = row.selectFirst("th")?.text()?.let { ParserSupport.run { it.normalized() } } ?: return@forEach
            val value = row.selectFirst("td")?.text()?.let { ParserSupport.run { it.normalized() } } ?: return@forEach
            if (label.isNotEmpty() && value.isNotEmpty()) fields.putIfAbsent(label, value)
        }
        val tilcod = fields["タイトルコード"]?.takeIf { it.isNotEmpty() }
            ?: throw ParseException(screen, "タイトルコードが見つかりません")
        val headers = ParserSupport.headers(holdingsTable)
        ParserSupport.requireHeader(headers, screen, "No.")
        ParserSupport.requireHeader(headers, screen, "所蔵館")
        ParserSupport.requireHeader(headers, screen, "資料番号")
        val materialTypeColumn = ParserSupport.requireHeader(headers, screen, "資料種別")
        val callNumberColumn = ParserSupport.requireHeader(headers, screen, "請求記号")
        val locationColumn = ParserSupport.requireHeader(headers, screen, "配架場所")
        val lendableColumn = ParserSupport.requireHeader(headers, screen, "帯出区分")
        val statusColumn = ParserSupport.requireHeader(headers, screen, "状態")
        val libraryColumn = ParserSupport.requireHeader(headers, screen, "所蔵館")
        val holdings = holdingsTable.select("tbody > tr").map { row ->
            Holding(
                library = row.cell(libraryColumn, screen, "所蔵館"),
                materialType = row.cell(materialTypeColumn, screen, "資料種別"),
                callNumber = row.cell(callNumberColumn, screen, "請求記号"),
                location = row.cell(locationColumn, screen, "配架場所"),
                lendable = row.cell(lendableColumn, screen, "帯出区分"),
                status = row.cell(statusColumn, screen, "状態"),
            )
        }
        return BookDetail(
            tilcod = tilcod,
            fields = fields,
            isbn = fields["ISBN"]?.replace(Regex("\\D"), "")?.takeIf { it.isNotEmpty() },
            holdings = holdings,
            holdingCount = stock.requiredInt("所蔵数"),
            availableCount = stock.requiredInt("在庫数"),
            reservationCount = stock.requiredInt("予約数"),
        )
    }

    private fun Map<String, String>.requiredInt(label: String): Int =
        this[label]?.toIntOrNull() ?: throw ParseException(screen, "$label が数値ではありません")
}
