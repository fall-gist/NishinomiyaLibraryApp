package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import org.jsoup.Jsoup

object ShelfParser {
    private const val screen = "shelf"

    fun parse(html: String, memberId: Long = UNASSIGNED_MEMBER_ID): List<ShelfItem> {
        val document = Jsoup.parse(html)
        ParserSupport.requireHeading(document, screen, "マイ本棚")
        val table = ParserSupport.requireTable(document, screen, "リスト詳細")
        return table.select("tbody > tr").map { row ->
            val titleText = row.selectFirst(".title")?.text()?.let { ParserSupport.run { it.normalized() } }
                ?: throw ParseException(screen, ".title が見つかりません")
            val match = Regex("^(\\d{13})\\s*(.*)$").matchEntire(titleText)
                ?: throw ParseException(screen, "タイトルコードまたはタイトルが不正です: $titleText")
            val memo = row.selectFirst(".memo")?.clone()?.apply { select(".skip").remove() }?.text()
                ?.let { ParserSupport.run { it.normalized() } }
                ?: throw ParseException(screen, ".memo が見つかりません")
            val registered = Regex("登録日:\\s*(\\d{4}/\\d{2}/\\d{2})")
                .find(row.text())?.groupValues?.get(1)
                ?: throw ParseException(screen, "登録日が見つかりません")
            ShelfItem(
                memberId = memberId,
                tilcod = match.groupValues[1],
                title = match.groupValues[2].trim(),
                memo = memo,
                registeredDate = ParserSupport.parseFullDate(registered, screen, "登録日"),
            )
        }
    }
}
