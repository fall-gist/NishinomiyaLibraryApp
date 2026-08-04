package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 貸出状況一覧(LBForm)から、貸出延長の1段階目送信フォームを解析する。
 *
 * ブラウザのJS(extend)は、行ごとの延長ボタン押下時に共通の隠しフィールドparaへ
 * 対象mngcodを代入してから同じLBFormを送信するだけである(`docs/design/loan-extension.md` §4.2)。
 * `BookDetailReservationFormParser`・`ReservationCancelFormParser`と同じ「ホワイトリストにしない・
 * DOM順・重複込みで全送信する」方針を踏襲し、paraだけを元のDOM位置で上書きする。
 */
internal object LoanExtensionRequestFormParser {
    private const val SCREEN = "loan-extension-request"

    fun parse(html: String): LoanExtensionRequestForm {
        val document = Jsoup.parse(html)
        val forms = document.select("form").filter(::isTargetForm)
        if (forms.size != 1) throw ParseException(SCREEN, "貸出状況一覧フォームを一意に特定できません")
        val form = forms.single()
        return LoanExtensionRequestForm(
            fields = form.select("input[name]:not([disabled]), select[name]:not([disabled]), textarea[name]:not([disabled])")
                .mapNotNull(::toSuccessfulField),
            extendableCodes = extractExtendableCodes(html),
        )
    }

    /**
     * LBFormはhidden gamenid=tiles.WUsrLendListを持ち、かつhidden paraがちょうど1個だけ存在する
     * ことで一意特定する(フィクスチャusrlend.html・site-research.md §10で実測済みの構造)。
     */
    private fun isTargetForm(form: Element): Boolean =
        hasExactlyOneHiddenValue(form, "gamenid", "tiles.WUsrLendList") &&
            form.select("input[type=hidden][name=para]:not([disabled])").size == 1

    private fun hasExactlyOneHiddenValue(form: Element, name: String, value: String): Boolean =
        form.select("input[type=hidden][name=$name]:not([disabled])").singleOrNull()?.attr("value") == value

    private val EXTEND_BUTTON_CODE_REGEX = Regex("""extend\(\s*"(\d+)"\s*\)""")

    /** ページ内の全ての延長ボタン(extend("コード"))からコードを集める。画面と対象の食い違い検出に使う。 */
    private fun extractExtendableCodes(html: String): Set<String> =
        EXTEND_BUTTON_CODE_REGEX.findAll(html).map { it.groupValues[1] }.toSet()

    private fun toSuccessfulField(element: Element): LoanExtensionRequestField? = when (element.tagName()) {
        "input" -> inputField(element)
        "select" -> LoanExtensionRequestField(element.attr("name"), selectedOptionValue(element))
        "textarea" -> LoanExtensionRequestField(element.attr("name"), element.`val`())
        else -> null
    }

    private fun inputField(input: Element): LoanExtensionRequestField? {
        val type = input.attr("type").ifBlank { "text" }.lowercase()
        if (type in setOf("button", "submit", "reset", "image", "file")) return null
        if (type in setOf("checkbox", "radio") && !input.hasAttr("checked")) return null
        return LoanExtensionRequestField(input.attr("name"), input.attr("value"))
    }

    private fun selectedOptionValue(select: Element): String {
        val selected = select.select("option[selected]")
        val option = when {
            selected.size == 1 && !selected.single().hasAttr("disabled") -> selected.single()
            selected.size > 1 -> throw ParseException(SCREEN, "${select.attr("name")} のselected optionが一意ではありません")
            selected.size == 1 -> throw ParseException(SCREEN, "${select.attr("name")} のselected optionが無効です")
            else -> select.select("option:not([disabled])").firstOrNull()
                ?: throw ParseException(SCREEN, "${select.attr("name")} の選択肢がありません")
        }
        return option.attr("value")
    }
}

internal class LoanExtensionRequestForm(
    private val fields: List<LoanExtensionRequestField>,
    private val extendableCodes: Set<String>,
) {
    /** 診断・テスト用に送信項目名をDOM順・重複込みで確認する。 */
    internal val fieldNames: List<String> get() = fields.map { it.name }

    /**
     * paraだけを元のDOM位置でrenewalCodeへ上書きする。他のフィールドはサイト発行値のまま送る。
     * renewalCodeが画面上の延長ボタンのいずれとも一致しなければ、画面と対象の食い違いとして
     * ParseExceptionにする(フェイルクローズ)。
     */
    fun buildForm(renewalCode: String): FormBody {
        if (renewalCode !in extendableCodes) {
            throw ParseException(
                "loan-extension-request",
                "指定された貸出は一覧画面上で延長できません(延長ボタンが見つかりません)",
            )
        }
        return FormBody.Builder().apply {
            fields.forEach { field ->
                val value = if (field.name == "para") renewalCode else field.value
                add(field.name, value)
            }
        }.build()
    }
}

internal data class LoanExtensionRequestField(val name: String, val value: String)
