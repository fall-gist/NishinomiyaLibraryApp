package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import okhttp3.FormBody

/** 通常書誌詳細LBFormを、ブラウザのsuccessful controlsと同じ順序で予約確認表示へ送る。 */
object BookDetailReservationFormParser {
    private const val SCREEN = "reservation-detail"

    fun parse(html: String, expectedTilcod: String): BookDetailReservationForm {
        val forms = Jsoup.parse(html).select("form").filter { form ->
            form.id() == "LBForm" &&
                hasExactlyOneHiddenValue(form, "gamenid", "tiles.WTifTilDetail") &&
                hasExactlyOneHiddenValue(form, "tilcod", expectedTilcod) &&
                hasExactlyOneHidden(form, "kensakuFlg") &&
                hasExactlyOneHidden(form, "kensaku")
        }
        if (forms.size != 1) throw ParseException(SCREEN, "通常書誌詳細フォームを一意に特定できません")
        val form = forms.single()
        return BookDetailReservationForm(
            form.select("input[name]:not([disabled]), select[name]:not([disabled]), textarea[name]:not([disabled])")
                .mapNotNull(::toSuccessfulField),
        )
    }

    private fun hasExactlyOneHiddenValue(form: Element, name: String, value: String): Boolean =
        form.select("[name=$name]:not([disabled])").singleOrNull()?.let { input ->
            input.tagName() == "input" &&
                input.attr("type").equals("hidden", ignoreCase = true) &&
                input.attr("value") == value
        } == true

    private fun hasExactlyOneHidden(form: Element, name: String): Boolean =
        form.select("[name=$name]:not([disabled])").singleOrNull()?.let { input ->
            input.tagName() == "input" && input.attr("type").equals("hidden", ignoreCase = true)
        } == true

    private fun toSuccessfulField(element: Element): DetailFormField? = when (element.tagName()) {
        "input" -> inputField(element)
        "select" -> DetailFormField(element.attr("name"), selectedOptionValue(element))
        "textarea" -> DetailFormField(element.attr("name"), element.`val`())
        else -> null
    }

    private fun inputField(input: Element): DetailFormField? {
        val type = input.attr("type").ifBlank { "text" }.lowercase()
        if (type in setOf("button", "submit", "reset", "image", "file")) return null
        if (type in setOf("checkbox", "radio") && !input.hasAttr("checked")) return null
        return DetailFormField(input.attr("name"), input.attr("value"))
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

class BookDetailReservationForm internal constructor(
    private val fields: List<DetailFormField>,
) {
    /**
     * hashOverride が非nullで、かつこのページの hash が空のときだけ、元DOM位置のまま
     * hash をhashOverrideへ上書きする。サイトが非空のhashを発行している場合は絶対に上書きしない。
     */
    fun buildForm(hashOverride: String? = null): FormBody = FormBody.Builder().apply {
        fields.forEach { field ->
            val value = if (field.name == "hash" && field.value.isEmpty() && hashOverride != null) {
                hashOverride
            } else {
                field.value
            }
            add(field.name, value)
        }
    }.build()
}

internal data class DetailFormField(val name: String, val value: String)
