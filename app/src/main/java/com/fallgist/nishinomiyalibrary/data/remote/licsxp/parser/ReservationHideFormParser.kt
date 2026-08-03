package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** ライブ非表示診断だけで使うLBFormパーサー。取消フォームとは分離する。 */
internal object ReservationHideFormParser {
    private const val SCREEN = "reservation-hide-diagnostic"
    private val hideCodeRegex = Regex("""\s*(?:javascript:\s*)?yoykHihyoji\('(\d+)'\)\s*;?\s*""")

    fun parse(html: String): ReservationHideForm {
        val document = Jsoup.parse(html)
        val forms = document.select("form").filter(::isTargetForm)
        if (forms.size != 1) throw ParseException(SCREEN, "予約一覧フォームを一意に特定できません")
        val form = forms.single()
        if (document.select("[form]").isNotEmpty() || form.select("fieldset[disabled] [name], select[multiple], optgroup[disabled] option").isNotEmpty()) {
            throw ParseException(SCREEN, "安全に扱えないフォーム構造です")
        }
        val fields = form
            .select("input[name]:not([disabled]), select[name]:not([disabled]), textarea[name]:not([disabled])")
            .mapNotNull(::successfulField)
        return ReservationHideForm(fields, extractHideCodes(document))
    }

    private fun isTargetForm(form: Element): Boolean =
        // fixtureは name=LBForm でありid属性を持たない。nameは補助条件で、idは一切仮定しない。
        (form.attr("name").isBlank() || form.attr("name") == "LBForm") &&
            form.select("input[type=hidden][name=gamenid]:not([disabled])").singleOrNull()?.attr("value") == "tiles.WUsrRsvList" &&
            form.select("input[type=hidden][name=yoycod]:not([disabled])").size == 1

    private fun extractHideCodes(document: org.jsoup.nodes.Document): Set<String> {
        val buttons = document.select("input[onclick*=yoykHihyoji]")
        if (buttons.isEmpty()) throw ParseException(SCREEN, "非表示ボタンがありません")
        val codes = buttons.map { button ->
            hideCodeRegex.matchEntire(button.attr("onclick"))?.groupValues?.get(1)
                ?: throw ParseException(SCREEN, "非表示ボタンの形式が不正です")
        }
        if (codes.size != codes.toSet().size) throw ParseException(SCREEN, "非表示コードが重複しています")
        return codes.toSet()
    }

    private fun successfulField(element: Element): HideFormField? = when (element.tagName()) {
        "input" -> {
            val type = element.attr("type").ifBlank { "text" }.lowercase()
            if (type in setOf("button", "submit", "reset", "image", "file") ||
                (type in setOf("checkbox", "radio") && !element.hasAttr("checked"))
            ) null else HideFormField(element.attr("name"), element.attr("value"))
        }
        "select" -> {
            val selected = element.select("option[selected]:not([disabled])")
            val option = when {
                selected.size == 1 -> selected.single()
                selected.size > 1 -> throw ParseException(SCREEN, "selected optionが一意ではありません")
                else -> element.selectFirst("option:not([disabled])")
                    ?: throw ParseException(SCREEN, "選択可能なoptionがありません")
            }
            if (!option.hasAttr("value")) throw ParseException(SCREEN, "option valueがありません")
            HideFormField(element.attr("name"), option.attr("value"))
        }
        "textarea" -> HideFormField(element.attr("name"), element.`val`())
        else -> null
    }
}

internal class ReservationHideForm(
    private val fields: List<HideFormField>,
    private val allowedHideCodes: Set<String>,
) {
    internal val fieldNames: List<String> get() = fields.map { it.name }

    fun buildForm(hideCode: String): FormBody {
        if (hideCode !in allowedHideCodes) {
            throw ParseException("reservation-hide-diagnostic", "対象の非表示コードを検証できません")
        }
        return FormBody.Builder().apply {
            fields.forEach { field -> add(field.name, if (field.name == "yoycod") hideCode else field.value) }
        }.build()
    }
}

internal data class HideFormField(val name: String, val value: String)
