package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import okhttp3.FormBody

/** 予約確認フォームのsuccessful controlsをDOM順で保持し、確定時だけ制御値を上書きする。 */
class DirectReservationConfirmationPage internal constructor(
    private val fields: List<ConfirmationFormField>,
    val pickupLibraryCodes: Set<String>,
) {
    /** 既存の画面発行hiddenの検証・テスト用。順序は元フォームと同じ。 */
    val hiddenFields: List<Pair<String, String>> = fields
        .filter { it.kind == ConfirmationFieldKind.HIDDEN }
        .map { it.name to it.value }

    /**
     * 受取館・連絡方法・Web連絡を元DOM位置で一度だけ上書きする。
     * 重複制御項目はパース時に拒否するため、ここで末尾追加することはない。
     */
    fun buildForm(pickupLibraryCode: String): FormBody {
        require(pickupLibraryCode in pickupLibraryCodes) { "受取館コードが確認画面にありません" }
        val controlledValues = mapOf(
            "receivename" to pickupLibraryCode,
            "contact" to "4",
            "contactweb" to "4",
        )
        return FormBody.Builder().apply {
            fields.forEach { field ->
                add(field.name, controlledValues[field.name] ?: field.value)
            }
        }.build()
    }
}

internal data class ConfirmationFormField(
    val name: String,
    val value: String,
    val kind: ConfirmationFieldKind,
)

internal enum class ConfirmationFieldKind { HIDDEN, CONTROLLED_SELECT, CONTROLLED_HIDDEN }

/** 予約確認画面が発行したsuccessful controlsを改変せず、確定POSTに渡す。 */
object DirectReservationConfirmParser {
    private const val SCREEN = "reservation-confirm"
    private val CONTROLLED_FIELDS = setOf("receivename", "contact", "contactweb")

    fun parse(html: String, expectedTilcod: String): DirectReservationConfirmationPage {
        val forms = Jsoup.parse(html).select("form").filter(::hasExpectedControls)
        if (forms.size != 1) throw ParseException(SCREEN, "予約確認フォームを一意に特定できません")
        val form = forms.single()
        validateHiddenValue(form, "gamenid", "tiles.WYoyConfirm", "gamenidが予約確認画面ではありません")
        validateHiddenValue(form, "tilcod", expectedTilcod, "tilcodが要求値と一致しません")
        validateHiddenValue(form, "contactweb", "4", "contactwebがEmail固定値ではありません")
        if (form.select("input[type=hidden][name=hash]:not([disabled])").isNotEmpty()) {
            uniqueHiddenValue(form, "hash")
        }

        val pickupSelect = requireSingleSelect(form, "receivename")
        val pickup = pickupSelect.select("option[value]:not([disabled])")
            .map { it.attr("value") }
            .filter(String::isNotBlank)
            .toSet()
        if (pickup.isEmpty()) throw ParseException(SCREEN, "受取館の選択肢がありません")
        val contactSelect = requireSingleSelect(form, "contact")
        if (contactSelect.select("option[value=4]:not([disabled])").isEmpty()) {
            throw ParseException(SCREEN, "Email連絡方法が選択できません")
        }

        return DirectReservationConfirmationPage(
            fields = form.select("input[name]:not([disabled]), select[name]:not([disabled]), textarea[name]:not([disabled])")
                .mapNotNull(::toSuccessfulField),
            pickupLibraryCodes = pickup,
        )
    }

    private fun hasExpectedControls(form: Element): Boolean =
        hasExactlyOneHidden(form, "gamenid") &&
            hasExactlyOneHidden(form, "tilcod") &&
            hasExactlyOneHidden(form, "contactweb") &&
            hasExactlyOneSelect(form, "receivename") &&
            hasExactlyOneSelect(form, "contact")

    private fun hasExactlyOneHidden(form: Element, name: String): Boolean =
        form.select("[name=$name]:not([disabled])").singleOrNull()?.let { input ->
            input.tagName() == "input" && input.attr("type").equals("hidden", ignoreCase = true)
        } == true

    private fun hasExactlyOneSelect(form: Element, name: String): Boolean =
        form.select("[name=$name]:not([disabled])").singleOrNull()?.tagName() == "select"

    private fun requireSingleSelect(form: Element, name: String): Element =
        form.select("select[name=$name]:not([disabled])").singleOrNull()
            ?: throw ParseException(SCREEN, "$name のselectが一意ではありません")

    private fun validateHiddenValue(form: Element, name: String, expected: String, reason: String) {
        if (uniqueHiddenValue(form, name) != expected) throw ParseException(SCREEN, reason)
    }

    private fun uniqueHiddenValue(form: Element, name: String): String {
        val inputs = form.select("input[type=hidden][name=$name]:not([disabled])")
        if (inputs.size != 1) throw ParseException(SCREEN, "$name が一意ではありません")
        return inputs.single().attr("value")
    }

    private fun toSuccessfulField(element: Element): ConfirmationFormField? {
        return when (element.tagName()) {
            "input" -> {
                if (!element.attr("type").equals("hidden", ignoreCase = true)) return null
                val name = element.attr("name")
                when (name) {
                    "contactweb" -> ConfirmationFormField(name, element.attr("value"), ConfirmationFieldKind.CONTROLLED_HIDDEN)
                    in CONTROLLED_FIELDS -> null
                    else -> ConfirmationFormField(name, element.attr("value"), ConfirmationFieldKind.HIDDEN)
                }
            }
            "select" -> when (element.attr("name")) {
                "receivename", "contact" -> ConfirmationFormField(
                    name = element.attr("name"),
                    value = selectedOptionValue(element),
                    kind = ConfirmationFieldKind.CONTROLLED_SELECT,
                )
                else -> null
            }
            // 予約確認で未知のtextareaを送ることは許可しない。
            else -> null
        }
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
