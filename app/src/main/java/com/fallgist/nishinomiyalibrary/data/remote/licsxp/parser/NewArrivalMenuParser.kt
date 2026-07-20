package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import java.net.URI
import org.jsoup.Jsoup

/** 新着資料のジャンル一覧ページから、各ジャンルの newMenuCode を取り出す。 */
object NewArrivalMenuParser {
    private const val screen = "new_arrival_menu"

    fun parseGenreCodes(html: String): List<String> {
        val document = Jsoup.parse(html)
        ParserSupport.requireHeading(document, screen, "新着資料")
        val codes = document.select("a[href*=newMenuCode]")
            .mapNotNull { link -> queryValue(link.attr("href"), "newMenuCode") }
            .filter { it.isNotBlank() }
            .distinct()
        if (codes.isEmpty()) throw ParseException(screen, "ジャンルのリンクが見つかりません")
        return codes
    }

    private fun queryValue(href: String, name: String): String? =
        runCatching {
            URI("https://example.invalid/$href").rawQuery
                ?.split('&')
                ?.firstOrNull { it.substringBefore('=') == name }
                ?.substringAfter('=')
        }.getOrNull()
}
