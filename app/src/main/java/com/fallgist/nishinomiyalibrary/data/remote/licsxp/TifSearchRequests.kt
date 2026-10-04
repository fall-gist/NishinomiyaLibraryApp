package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ParseException
import com.fallgist.nishinomiyalibrary.domain.model.SearchQuery
import com.fallgist.nishinomiyalibrary.domain.model.StockFilter
import okhttp3.FormBody
import org.jsoup.Jsoup

/**
 * 詳細検索(`WOpacTifSchCmpd`)の送信本文の組み立て(docs/design/search-sort-filter.md §2.1・§3.2)。
 * 通信は行わない。FormBody は UTF-8 で符号化する(サイトへ Shift_JIS 等で送ると別の結果になる)。
 */
internal object TifSearchRequests {
    /** サイトの語の項目コード(全項目7・書名0・著者1・出版者2)。 */
    private const val ITEM_ALL = "7"
    private const val ITEM_TITLE = "0"
    private const val ITEM_AUTHOR = "1"
    private const val ITEM_PUBLISHER = "2"

    /** 語の欄(condition1・2・4・5)。3は分類専用。 */
    private val WORD_SLOTS = listOf(1, 2, 4, 5)

    private const val CLASSIFICATION_ITEM = "5"

    /** `POST WOpacTifSchCmpdExecAction.do?tifschcmpd=1` の本文。 */
    fun executeForm(query: SearchQuery): FormBody {
        // キーワード→書名→著者→出版者の順に、指定されたものだけを先頭の欄から詰める。
        val words = listOf(
            ITEM_ALL to query.keyword,
            ITEM_TITLE to query.title,
            ITEM_AUTHOR to query.author,
            ITEM_PUBLISHER to query.publisher,
        ).map { (item, text) -> item to text.trim() }.filter { (_, text) -> text.isNotEmpty() }

        val kinds = query.materialKinds.sortedBy { it.siteCode.toInt() }
        val builder = FormBody.Builder()
            .add("chu_search_ini", "1")
            .add("hash", "")
            .add("jin", "0")
            .add("returnid", "")
            .add("gamenid", "tiles.WTifSchCmpd")
            .add("chkflg", if (kinds.isEmpty()) "nocheck" else "check")
            .add("loccodschkflg", "nocheck")
            .add("langcodschkflg", "nocheck")
            .add("targetsChkflg", "nocheck")
            .add("targetsAvChkflg", "nocheck")
            .add("tifKanrabtn", "")
            .add("tilkbncodschkflg", "nocheck")
        WORD_SLOTS.forEachIndexed { index, slot ->
            val word = words.getOrNull(index)
            builder.add("condition$slot", word?.first.orEmpty())
            builder.add("condition${slot}Text", word?.second.orEmpty())
            builder.add("range$slot", "0")
            builder.add("mixing$slot", "0")
        }
        builder
            .add("condition3", CLASSIFICATION_ITEM)
            .add("condition3Text", query.classification.trim())
            .add("range3", "0")
            .add("dispmaxnum", "20")
            .add("disporder", "0")
        kinds.forEach { builder.add("mngshus", it.siteCode) }
        val published = query.published
        builder
            .add("yearselect", "0")
            .add("yearstart", published.fromYear?.toString().orEmpty())
            .add("monthstart", published.fromMonth?.toString().orEmpty())
            .add("yearend", published.toYear?.toString().orEmpty())
            .add("monthend", published.toMonth?.toString().orEmpty())
        return builder.build()
    }

    /**
     * 在庫状況の絞り込み `POST WOpacWebTifTilListStockStateSearchAction.do` の本文。
     * 結果画面の `LBForm` の hidden と select をそのまま集め、`stockState` だけ差し替える。
     */
    fun stockStateForm(resultHtml: String, stock: StockFilter): FormBody {
        val form = Jsoup.parse(resultHtml).selectFirst("form[name=LBForm]")
            ?: throw ParseException("tif_search_result", "form[name=LBForm] が見つかりません")
        val builder = FormBody.Builder()
        var stockStateAdded = false
        for (element in form.select("input[type=hidden][name], select[name]")) {
            val name = element.attr("name")
            if (name == "stockState") {
                if (!stockStateAdded) builder.add(name, stock.siteCode)
                stockStateAdded = true
                continue
            }
            val value = if (element.tagName() == "select") {
                // 選択中の option。無ければ先頭(ブラウザの既定と同じ)。
                val options = element.select("option")
                (options.firstOrNull { it.hasAttr("selected") } ?: options.firstOrNull())?.attr("value").orEmpty()
            } else {
                element.attr("value")
            }
            builder.add(name, value)
        }
        if (!stockStateAdded) throw ParseException("tif_search_result", "select[name=stockState] が見つかりません")
        return builder.build()
    }
}
