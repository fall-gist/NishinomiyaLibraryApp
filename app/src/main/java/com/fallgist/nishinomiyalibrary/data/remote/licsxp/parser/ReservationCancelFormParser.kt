package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 予約状況一覧(LBForm)から、予約取消フォームを解析する。
 *
 * ブラウザのJS(yoykCancel)は、行ごとの取消ボタン押下時に共通の隠しフィールド yoycod へ
 * 対象コードを代入してから同じLBFormを送信するだけであり、確認ダイアログは無い。
 * 行ごとに並ぶ `id=yoycod<コード>` の hidden(name=yoykcode、常に空値)はこの yoycod とは別物で、
 * 実際に送信される制御値ではない。ブラウザのsuccessful controlsと同じ規則で、DOM順・同名重複を
 * 保持したまま抽出し、yoycod だけを元のDOM位置で上書きして送る。
 */
object ReservationCancelFormParser {
    private const val SCREEN = "reservation-cancel"

    fun parse(html: String): ReservationCancelForm {
        val document = Jsoup.parse(html)
        val forms = document.select("form").filter(::isTargetForm)
        if (forms.size != 1) throw ParseException(SCREEN, "予約状況一覧フォームを一意に特定できません")
        val form = forms.single()
        return ReservationCancelForm(
            fields = form.select("input[name]:not([disabled]), select[name]:not([disabled]), textarea[name]:not([disabled])")
                .mapNotNull(::toSuccessfulField),
            cancellableCodes = extractCancellableCodes(html),
        )
    }

    /**
     * fixture usrrsv.html で実測済みの特定条件:
     * LBForm は hidden gamenid=tiles.WUsrRsvList を持ち、かつ hidden yoycod がちょうど1個だけ存在する
     * (行ごとに並ぶ yoykcode という別名のhiddenは複数存在するため、yoycod の一意性で区別する)。
     */
    private fun isTargetForm(form: Element): Boolean =
        hasExactlyOneHiddenValue(form, "gamenid", "tiles.WUsrRsvList") &&
            form.select("input[type=hidden][name=yoycod]:not([disabled])").size == 1

    private fun hasExactlyOneHiddenValue(form: Element, name: String, value: String): Boolean =
        form.select("input[type=hidden][name=$name]:not([disabled])").singleOrNull()?.attr("value") == value

    private val CANCEL_BUTTON_CODE_REGEX = Regex("""yoykCancel\('(\d+)'\)""")

    /** ページ内の全ての取消ボタン(yoykCancel('コード'))からコードを集める。画面と対象の食い違い検出に使う。 */
    private fun extractCancellableCodes(html: String): Set<String> =
        CANCEL_BUTTON_CODE_REGEX.findAll(html).map { it.groupValues[1] }.toSet()

    private fun toSuccessfulField(element: Element): CancelFormField? = when (element.tagName()) {
        "input" -> inputField(element)
        "select" -> CancelFormField(element.attr("name"), selectedOptionValue(element))
        "textarea" -> CancelFormField(element.attr("name"), element.`val`())
        else -> null
    }

    private fun inputField(input: Element): CancelFormField? {
        val type = input.attr("type").ifBlank { "text" }.lowercase()
        if (type in setOf("button", "submit", "reset", "image", "file")) return null
        if (type in setOf("checkbox", "radio") && !input.hasAttr("checked")) return null
        return CancelFormField(input.attr("name"), input.attr("value"))
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

class ReservationCancelForm internal constructor(
    private val fields: List<CancelFormField>,
    private val cancellableCodes: Set<String>,
) {
    /** 診断・テスト用に送信項目名をDOM順・重複込みで確認する。 */
    internal val fieldNames: List<String> get() = fields.map { it.name }

    /**
     * yoycod だけを元のDOM位置で cancelCode へ上書きする。他のフィールドはサイト発行値のまま送る。
     * cancelCode が画面上の取消ボタンのいずれとも一致しなければ、画面と対象の食い違いとして
     * ParseException にする。
     */
    fun buildForm(cancelCode: String): FormBody {
        if (cancelCode !in cancellableCodes) {
            throw ParseException(
                "reservation-cancel",
                "指定された予約は一覧画面上で取消できません(取消ボタンが見つかりません)",
            )
        }
        return FormBody.Builder().apply {
            fields.forEach { field ->
                val value = if (field.name == "yoycod") cancelCode else field.value
                add(field.name, value)
            }
        }.build()
    }
}

internal data class CancelFormField(val name: String, val value: String)
