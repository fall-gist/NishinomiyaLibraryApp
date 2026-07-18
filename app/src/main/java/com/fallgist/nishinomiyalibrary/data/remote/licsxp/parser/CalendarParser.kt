package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import java.time.LocalDate
import org.jsoup.Jsoup

object CalendarParser {
    private const val screen = "calendar"

    fun parse(html: String): List<LocalDate> {
        val document = Jsoup.parse(html)
        ParserSupport.requireHeading(document, screen, "図書館カレンダー")
        val scriptText = document.select("script").joinToString("\\n") { it.data() }
        val hasCalendarStructure = document.select("h2").any { it.text().contains("休館日カレンダー") } ||
            document.selectFirst("table[summary=カレンダーレイアウト]") != null ||
            scriptText.contains("holiday")
        if (!hasCalendarStructure) {
            throw ParseException(screen, "休館日カレンダーの画面構造が見つかりません")
        }
        val holidays = Regex("(?:var\\s+)?holiday\\s*=\\s*[\\\"'](\\d{4}-\\d{2}-\\d{2})")
            .findAll(scriptText)
            .map { match ->
                runCatching { LocalDate.parse(match.groupValues[1]) }
                    .getOrElse { throw ParseException(screen, "休館日の形式が不正です") }
            }
            .toList()
        return holidays.distinct().sorted()
    }
}
