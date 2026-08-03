package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import java.time.LocalDate
import org.jsoup.Jsoup

object ReservationListParser {
    private const val screen = "reservation_list"
    private val shortDateRegex = Regex("\\d{2}/\\d{2}/\\d{2}")
    private val contactMethodRegex = Regex("Ｅｍａｉｌ|Email|メール|電話|連絡不要")

    fun parse(html: String, memberId: Long = UNASSIGNED_MEMBER_ID): List<Reservation> =
        parseRows(html, memberId).map { it.reservation }

    /**
     * 取消の成否判定に必要な追加情報（「非表示」ボタン(`yoykHihyoji`)の有無）を含めて解析する。
     * 12回目のライブ実測(2026-07-28)で、取消後は対象行が一覧から消えず、予約状態が「取消」になり
     * 取消ボタン(`yoykCancel`)が非表示ボタン(`yoykHihyoji`)へ置き換わることが判明した。この判定に
     * 必要な最小限の情報だけをRoomへ永続化しない内部型[ReservationListRow]として追加する。
     * `parse()`の戻り値・挙動はこのメソッドの結果を`map`しているだけで変えていない。
     */
    internal fun parseRows(html: String, memberId: Long = UNASSIGNED_MEMBER_ID): List<ReservationListRow> {
        val document = Jsoup.parse(html)
        ParserSupport.requireHeading(document, screen, "予約状況一覧")
        val table = ParserSupport.requireTable(document, screen, "予約状況一覧表")
        val headers = ParserSupport.headers(table)
        val title = ParserSupport.requireHeader(headers, screen, "資料名")
        val materialType = ParserSupport.requireHeader(headers, screen, "書誌種別")
        val pickupLibraryColumn = ParserSupport.requireHeader(headers, screen, "受取館")
        val reservedDate = ParserSupport.requireHeader(headers, screen, "予約日")
        val queue = ParserSupport.requireHeader(headers, screen, "順位")
        val state = ParserSupport.requireHeader(headers, screen, "予約状態")
        val holdExpiry = ParserSupport.requireHeader(headers, screen, "取置期限")
        return table.select("tbody > tr").map { row ->
            val dates = parseReservationDates(row.cell(reservedDate, screen, "予約日"))
            val reserved = dates.first()
            val allocated = dates.getOrNull(1)
            val stateText = row.cell(state, screen, "予約状態")
            val expiryText = row.cell(holdExpiry, screen, "取置期限")
            val reservation = Reservation(
                memberId = memberId,
                title = row.cell(title, screen, "資料名"),
                tilcod = titleCodeOf(row),
                cancelCode = cancelCodeOf(row),
                materialType = row.cell(materialType, screen, "書誌種別"),
                pickupLibrary = pickupLibrary(row, row.cell(pickupLibraryColumn, screen, "受取館")),
                reservedDate = reserved,
                queuePosition = row.cell(queue, screen, "順位").filter(Char::isDigit).toIntOrNull(),
                state = stateOf(stateText),
                holdExpiryDate = expiryText.takeIf { it.isNotEmpty() }
                    ?.let { ParserSupport.parseShortDateWithYear(it, allocated ?: reserved, screen, "取置期限") },
            )
            val hideCode = hideCodeOf(row)
            ReservationListRow(
                reservation = reservation,
                hideButtonPresent = hideCode != null,
                // この値はライブ診断の送信直前比較にだけ使う。ログ・Room・公開parse結果へは出さない。
                hideCode = hideCode,
            )
        }
    }

    /**
     * 実測(2026-07-28)済みの状態文字列を[ReservationState]へ写す。
     * 「予約中」「提供可能」「取消」「移送中」以外は未知の状態としてUNKNOWNのままにする。
     */
    private fun stateOf(stateText: String): ReservationState = when (stateText) {
        "予約中" -> ReservationState.WAITING
        "提供可能" -> ReservationState.READY
        "取消" -> ReservationState.CANCELLED
        "移送中" -> ReservationState.IN_TRANSIT
        else -> ReservationState.UNKNOWN
    }

    /** 資料名セルの書誌詳細リンク(hTilcod / toTilInfoDetail)からタイトルコードを取り出す。 */
    private fun titleCodeOf(row: org.jsoup.nodes.Element): String {
        val link = row.selectFirst("a[href*=hTilcod], a[onclick*=toTilInfoDetail]") ?: return ""
        val source = link.attr("href") + " " + link.attr("onclick")
        return TITLE_CODE_REGEX.find(source)?.groupValues?.get(1).orEmpty()
    }

    private val TITLE_CODE_REGEX = Regex("(?:hTilcod=|toTilInfoDetail\\(')(\\d+)")

    /**
     * 行内の取消ボタン(onclick="javascript:yoykCancel('コード')")からコードを取り出す。
     * 「提供可能」（取置済み）の行には取消ボタンが無いため、状態文字列ではなくボタンの有無で判断する
     * (サイト仕様の変化に追随するため)。ボタンが無ければ空文字列。
     */
    private fun cancelCodeOf(row: org.jsoup.nodes.Element): String {
        val button = row.selectFirst("input[onclick*=yoykCancel]") ?: return ""
        return CANCEL_CODE_REGEX.find(button.attr("onclick"))?.groupValues?.get(1).orEmpty()
    }

    private val CANCEL_CODE_REGEX = Regex("""yoykCancel\('(\d+)'\)""")

    /**
     * 行内の非表示ボタン(onclick="javascript:yoykHihyoji('コード')")の有無。
     * 12回目のライブ実測(2026-07-28)どおり、取消後は取消ボタンがこのボタンへ置き換わる。
     * コード値そのものは判定に不要（非表示機能の実装は未依頼）のため保持せず、有無だけを返す。
     */
    /** 完全一致する `yoykHihyoji('<数字>')` だけを許可する。曖昧なonclickは安全側で除外する。 */
    private fun hideCodeOf(row: org.jsoup.nodes.Element): String? {
        val buttons = row.select("input[onclick*=yoykHihyoji]")
        if (buttons.size != 1) return null
        return HIDE_CODE_REGEX.matchEntire(buttons.single().attr("onclick"))?.groupValues?.get(1)
    }

    private val HIDE_CODE_REGEX = Regex("""\s*(?:javascript:\s*)?yoykHihyoji\('(\d+)'\)\s*;?\s*""")

    private fun parseReservationDates(value: String): List<LocalDate> =
        shortDateRegex.findAll(value).map { match ->
            ParserSupport.parseShortDate(match.value, screen, "予約日")
        }.toList().ifEmpty {
            throw ParseException(screen, "予約日の日付形式が不正です: $value")
        }

    private fun pickupLibrary(row: org.jsoup.nodes.Element, cellText: String): String {
        if (row.selectFirst("select[name=dropwatspt]") != null) return ""
        return cellText.replace(contactMethodRegex, "").replace(Regex("[\\s\\u3000]+"), "")
    }
}

/**
 * 取消の成否判定専用の1行分。Roomへは永続化しない（[hideButtonPresent]はEntityへ持たせない）。
 * [ReservationListParser.parseRows]の戻り値としてのみ使う内部型。
 */
internal data class ReservationListRow(
    val reservation: Reservation,
    /** 非表示ボタン(yoykHihyoji)の有無。コード値はライブ診断内部だけで保持する。 */
    val hideButtonPresent: Boolean,
    /** 非表示コード。ライブ診断内部専用で、永続化・ログ出力は禁止。 */
    val hideCode: String?,
)
