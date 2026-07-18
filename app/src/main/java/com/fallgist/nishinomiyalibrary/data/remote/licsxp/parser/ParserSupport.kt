package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

const val UNASSIGNED_MEMBER_ID: Long = 0L

class ParseException(
    val screen: String,
    val reason: String,
) : Exception("$screen: $reason")

data class PageTokens(val hash: String, val gamenId: String)

internal object ParserSupport {
    private val fullDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu/MM/dd")
    private val shortDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("uu/MM/dd")

    fun requireHeading(document: Document, screen: String, expected: String) {
        val heading = document.selectFirst("h1")?.text()?.normalized()
            ?: throw ParseException(screen, "h1 が見つかりません")
        if (!heading.contains(expected)) {
            throw ParseException(screen, "想定外の h1 です: $heading")
        }
    }

    fun requireTable(document: Document, screen: String, summary: String): Element =
        document.selectFirst("table[summary=$summary]")
            ?: throw ParseException(screen, "summary=$summary の table が見つかりません")

    fun headers(table: Element): Map<String, Int> {
        val headerCells = table.select("thead tr").lastOrNull()?.select("th") ?: emptyList()
        return headerCells.mapIndexed { index, cell -> cell.text().normalized() to index }.toMap()
    }

    fun requireHeader(headers: Map<String, Int>, screen: String, label: String): Int =
        headers.entries.firstOrNull { (header, _) -> header == label || header.contains(label) }?.value
            ?: throw ParseException(screen, "ヘッダー $label が見つかりません")

    fun parseFullDate(value: String, screen: String, label: String): LocalDate =
        runCatching { LocalDate.parse(value.normalized(), fullDateFormatter) }
            .getOrElse { throw ParseException(screen, "$label の日付形式が不正です: $value") }

    fun parseShortDate(value: String, screen: String, label: String): LocalDate =
        runCatching { LocalDate.parse(shortDateText(value, screen, label), shortDateFormatter) }
            .getOrElse { throw ParseException(screen, "$label の日付形式が不正です: $value") }

    fun parseShortDateOrNull(value: String, screen: String, label: String): LocalDate? =
        if (SHORT_DATE_REGEX.containsMatchIn(value)) parseShortDate(value, screen, label) else null

    fun parseShortDateWithYear(value: String, baseDate: LocalDate, screen: String, label: String): LocalDate {
        val normalized = value.normalized()
        if (normalized.isEmpty()) return throw ParseException(screen, "$label が空です")
        val parts = normalized.split('/')
        if (parts.size != 2) throw ParseException(screen, "$label の日付形式が不正です: $value")
        val date = runCatching { LocalDate.of(baseDate.year, parts[0].toInt(), parts[1].toInt()) }
            .getOrElse { throw ParseException(screen, "$label の日付形式が不正です: $value") }
        return if (date.isBefore(baseDate)) date.plusYears(1) else date
    }

    private fun shortDateText(value: String, screen: String, label: String): String =
        SHORT_DATE_REGEX.find(value)?.value
            ?: throw ParseException(screen, "$label の日付形式が不正です: $value")

    private val SHORT_DATE_REGEX = Regex("\\d{2}/\\d{2}/\\d{2}")

    fun String.normalized(): String = replace('\u00a0', ' ').replace('\u3000', ' ').replace(Regex("\\s+"), " ").trim()
}

internal fun Element.cell(index: Int, screen: String, label: String): String =
    children().filter { it.tagName() == "th" || it.tagName() == "td" }.getOrNull(index)?.text()
        ?.let { ParserSupport.run { it.normalized() } }
        ?: throw ParseException(screen, "$label のセルが見つかりません")

internal fun Element.rawCell(index: Int, screen: String, label: String): String =
    children().filter { it.tagName() == "th" || it.tagName() == "td" }.getOrNull(index)?.text()?.trim()
        ?: throw ParseException(screen, "$label のセルが見つかりません")
