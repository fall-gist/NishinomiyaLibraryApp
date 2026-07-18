package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import com.fallgist.nishinomiyalibrary.domain.model.UserSummary
import org.jsoup.Jsoup

object SummaryParser {
    private const val screen = "summary"

    fun parse(html: String, memberId: Long = UNASSIGNED_MEMBER_ID): UserSummary {
        val document = Jsoup.parse(html)
        val container = document.selectFirst("#stat-login")
            ?: throw ParseException(screen, "#stat-login が見つかりません")
        fun count(id: String): Int {
            val value = container.selectFirst("#$id .value")?.text()?.let { ParserSupport.run { it.normalized() } }
                ?: throw ParseException(screen, "#$id .value が見つかりません")
            return value.toIntOrNull() ?: throw ParseException(screen, "#$id の件数が数値ではありません")
        }
        return UserSummary(
            memberId = memberId,
            shelfCount = count("stat-shlf"),
            loanCount = count("stat-lent"),
            reservationCount = count("stat-resv"),
            cartCount = count("stat-cart"),
        )
    }
}
