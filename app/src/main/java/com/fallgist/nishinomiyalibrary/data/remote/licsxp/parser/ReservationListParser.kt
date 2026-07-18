package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import org.jsoup.Jsoup

object ReservationListParser {
    private const val screen = "reservation_list"

    fun parse(html: String, memberId: Long = UNASSIGNED_MEMBER_ID): List<Reservation> {
        val document = Jsoup.parse(html)
        ParserSupport.requireHeading(document, screen, "予約状況一覧")
        val table = ParserSupport.requireTable(document, screen, "予約状況一覧表")
        val headers = ParserSupport.headers(table)
        val title = ParserSupport.requireHeader(headers, screen, "資料名")
        val materialType = ParserSupport.requireHeader(headers, screen, "書誌種別")
        val reservedDate = ParserSupport.requireHeader(headers, screen, "予約日")
        val allocatedDate = ParserSupport.requireHeader(headers, screen, "割当日")
        val queue = ParserSupport.requireHeader(headers, screen, "順位")
        val state = ParserSupport.requireHeader(headers, screen, "予約状態")
        val holdExpiry = ParserSupport.requireHeader(headers, screen, "取置期限")
        return table.select("tbody > tr").map { row ->
            val reserved = ParserSupport.parseShortDate(row.cell(reservedDate, screen, "予約日"), screen, "予約日")
            val allocated = ParserSupport.parseShortDateOrNull(
                row.cell(allocatedDate, screen, "割当日"),
                screen,
                "割当日",
            )
            val stateText = row.cell(state, screen, "予約状態")
            val expiryText = row.cell(holdExpiry, screen, "取置期限")
            Reservation(
                memberId = memberId,
                title = row.cell(title, screen, "資料名"),
                materialType = row.cell(materialType, screen, "書誌種別"),
                pickupLibrary = row.selectFirst("select[name=dropwatspt] option[selected]")?.text()
                    ?.let { ParserSupport.run { it.normalized() } }.orEmpty(),
                reservedDate = reserved,
                queuePosition = row.cell(queue, screen, "順位").filter(Char::isDigit).toIntOrNull(),
                state = when (stateText) {
                    "予約中" -> ReservationState.WAITING
                    "提供可能" -> ReservationState.READY
                    else -> ReservationState.UNKNOWN
                },
                holdExpiryDate = expiryText.takeIf { it.isNotEmpty() }
                    ?.let { ParserSupport.parseShortDateWithYear(it, allocated ?: reserved, screen, "取置期限") },
            )
        }
    }
}
