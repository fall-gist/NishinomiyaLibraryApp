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

    /**
     * \u6539\u884c\u30fb\u9023\u7d9a\u7a7a\u767d\u30fb\u524d\u5f8c\u7a7a\u767d\u306e\u5dee\u7570\u3092\u5438\u53ce\u3057\u3066\u6bd4\u8f03\u3059\u308b\u305f\u3081\u306e\u6b63\u898f\u5316\u3002
     * ShelfParser \u304c\u8868\u793a\u30e1\u30e2\u3092\u4f5c\u308b\u969b\u306b\u4f7f\u3046 normalized() \u3068\u540c\u3058\u30ed\u30b8\u30c3\u30af\u3092\u3001
     * \u62e1\u5f35\u95a2\u6570\u306e `run {}` \u306a\u3057\u3067\u4ed6\u30d1\u30c3\u30b1\u30fc\u30b8\u304b\u3089\u3082\u547c\u3079\u308b\u3088\u3046\u306b\u3057\u305f\u3082\u306e\u3002
     * \u6bd4\u8f03\u5c02\u7528\u3067\u3042\u308a\u3001\u9001\u4fe1\u5024\u305d\u306e\u3082\u306e\u306e\u6b63\u898f\u5316\u306b\u306f\u4f7f\u308f\u306a\u3044\u3002
     */
    fun normalizeWhitespace(value: String): String = value.normalized()
}

internal fun Element.cell(index: Int, screen: String, label: String): String =
    children().filter { it.tagName() == "th" || it.tagName() == "td" }.getOrNull(index)?.text()
        ?.let { ParserSupport.run { it.normalized() } }
        ?: throw ParseException(screen, "$label のセルが見つかりません")

internal fun Element.rawCell(index: Int, screen: String, label: String): String =
    children().filter { it.tagName() == "th" || it.tagName() == "td" }.getOrNull(index)?.text()?.trim()
        ?: throw ParseException(screen, "$label のセルが見つかりません")
