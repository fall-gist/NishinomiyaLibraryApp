package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import okhttp3.FormBody

/** 予約確認フォームのsuccessful controlsをDOM順で保持し、確定時だけ制御値を上書きする。 */
class DirectReservationConfirmationPage internal constructor(
    private val fields: List<ConfirmationFormField>,
    val pickupLibraryCodes: Set<String>,
    /** 確認画面がselected属性で明示している受取館コード。明示が無ければ null。 */
    internal val explicitPickupLibraryCode: String?,
    /** 確認画面がselected属性で明示している連絡方法コード。明示が無ければ null。 */
    internal val explicitContactCode: String?,
    /** 確認フォームのhidden hashが空でないか。値そのものは保持しない。 */
    internal val hasNonEmptyHash: Boolean,
) {
    /** 既存の画面発行hiddenの検証・テスト用。順序は元フォームと同じ。 */
    val hiddenFields: List<Pair<String, String>> = fields
        .filter { it.kind == ConfirmationFieldKind.HIDDEN }
        .map { it.name to it.value }

    /** 診断で送信項目名だけを確認するための内部公開。値は含めない。 */
    internal val fieldNames: List<String> get() = fields.map { it.name }

    /**
     * 受取館・連絡方法を元DOM位置で一度だけ上書きする。
     * 重複制御項目はパース時に拒否するため、ここで末尾追加することはない。
     * contactdirectweb はサイト発行値をそのまま送る（値は未取得のため上書きしない）。
     *
     * hashOverride が非nullで、かつこのページの hash が空のときだけ、元DOM位置のまま
     * hash をhashOverrideへ上書きする。サイトが非空のhashを発行している場合は絶対に上書きしない。
     */
    fun buildForm(pickupLibraryCode: String, hashOverride: String? = null): FormBody {
        require(pickupLibraryCode in pickupLibraryCodes) { "受取館コードが確認画面にありません" }
        val controlledValues = mapOf(
            "receivename" to pickupLibraryCode,
            "contact" to "4",
        )
        return FormBody.Builder().apply {
            fields.forEach { field ->
                val value = when {
                    controlledValues.containsKey(field.name) -> controlledValues.getValue(field.name)
                    field.name == "hash" && field.value.isEmpty() && hashOverride != null -> hashOverride
                    else -> field.value
                }
                add(field.name, value)
            }
        }.build()
    }

    /**
     * メール選択の再表示POST（WOpacTifDirectYoyDispAction.do?webrak=1）診断専用。
     * contactdirectwebだけを元DOM位置で上書きし、receivenameとcontactはサイト発行値のまま送る。
     * このPOSTの目的はcontactdirectwebの効果だけを見ることであり、受取館・連絡方法の効果を混ぜない。
     */
    internal fun buildFormWithContactDirectWeb(value: String): FormBody =
        FormBody.Builder().apply {
            fields.forEach { field ->
                add(field.name, if (field.name == "contactdirectweb") value else field.value)
            }
        }.build()
}

internal data class ConfirmationFormField(
    val name: String,
    val value: String,
    val kind: ConfirmationFieldKind,
)

internal enum class ConfirmationFieldKind { HIDDEN, CONTROLLED_SELECT, OTHER }

/**
 * 予約確認画面が発行したsuccessful controlsを改変せず、確定POSTに渡す。
 * ブラウザの submit() と同じsuccessful controlsをDOM順で送る。hidden以外を捨てると確定POSTがサイト側で拒否される。
 *
 * 2026-07-25のライブdry-run診断で実測したフィールド構成:
 * hidden 12個（bmtime_hide, contactFocus, contactdirectweb, gamenFlag, gamenid, hash,
 * loginshuflag, receivenameFocus, returnValue, returnid, tilcod, watsptcodFocus）と
 * select 2個（contact, receivename）のみ。text/textarea/radio/checkbox/buttonは存在しない。
 * `contactweb` というフィールドは実在せず、正しくは `contactdirectweb` である。
 * `contactdirectweb` の値は未取得のため、値の検証・上書きは行わずサイト発行値をそのまま送る。
 */
object DirectReservationConfirmParser {
    private const val SCREEN = "reservation-confirm"

    fun parse(html: String, expectedTilcod: String): DirectReservationConfirmationPage {
        val forms = Jsoup.parse(html).select("form").filter(::hasExpectedControls)
        if (forms.size != 1) throw ParseException(SCREEN, "予約確認フォームを一意に特定できません")
        val form = forms.single()
        validateHiddenValue(form, "gamenid", "tiles.WYoyConfirm", "gamenidが予約確認画面ではありません")
        validateHiddenValue(form, "tilcod", expectedTilcod, "tilcodが要求値と一致しません")
        val hashValue = if (form.select("input[type=hidden][name=hash]:not([disabled])").isNotEmpty()) {
            uniqueHiddenValue(form, "hash")
        } else {
            null
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
            explicitPickupLibraryCode = explicitSelectedValue(pickupSelect),
            explicitContactCode = explicitSelectedValue(contactSelect),
            hasNonEmptyHash = !hashValue.isNullOrEmpty(),
        )
    }

    private fun hasExpectedControls(form: Element): Boolean =
        hasExactlyOneHidden(form, "gamenid") &&
            hasExactlyOneHidden(form, "tilcod") &&
            hasExactlyOneHidden(form, "contactdirectweb") &&
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
            "input" -> inputField(element)
            "select" -> {
                val name = element.attr("name")
                val kind = if (name == "receivename" || name == "contact") {
                    ConfirmationFieldKind.CONTROLLED_SELECT
                } else {
                    ConfirmationFieldKind.OTHER
                }
                ConfirmationFormField(name, selectedOptionValue(element), kind)
            }
            "textarea" -> ConfirmationFormField(element.attr("name"), element.`val`(), ConfirmationFieldKind.OTHER)
            else -> null
        }
    }

    private fun inputField(input: Element): ConfirmationFormField? {
        val type = input.attr("type").ifBlank { "text" }.lowercase()
        if (type in setOf("button", "submit", "reset", "image", "file")) return null
        if (type in setOf("checkbox", "radio") && !input.hasAttr("checked")) return null
        val name = input.attr("name")
        val kind = if (type == "hidden") ConfirmationFieldKind.HIDDEN else ConfirmationFieldKind.OTHER
        return ConfirmationFormField(name, input.attr("value"), kind)
    }

    /** selected属性を持つoptionが一意に存在する場合だけその値を返す。診断専用で、selectedOptionValueと違い例外は投げない。 */
    private fun explicitSelectedValue(select: Element): String? =
        select.select("option[selected]").singleOrNull()?.attr("value")

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
