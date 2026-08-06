package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 予約取消の1段階目応答にある`prevRequestForm`を、ブラウザのOK後の再送用として解析する。
 *
 * 2段階目はこのフォームのDOM順をそのまま送り、ページ側scriptが追加するOKコードだけを末尾へ加える。
 * 確認コードは貸出延長(`LoanExtensionConfirmationFormParser`)と同型の字句走査で都度抽出し、
 * 想定値`OPACUSR001`と照合する(`docs/design/reservation-cancel-hardening.md` 修正方針A)。
 * ハードコードした値を無条件に送るのではなく、抽出値が想定値と一致しない場合はfail-closeする。
 */
object ReservationCancelConfirmationFormParser {
    private const val SCREEN = "reservation-cancel-confirmation"

    /**
     * 確認コードの想定値(実測フラグメント`reservation_cancel_confirmation_live_fragment.js`
     * 23行目 `okArray[okArray.length] = "OPACUSR001";` で実測済み)。値そのものは都度JS代入から
     * 抽出し、この定数とは**照合にのみ**使う。
     */
    private const val EXPECTED_CONFIRMATION_CODE = "OPACUSR001"

    fun parse(
        html: String,
        expectedStage1Fields: List<ReservationCancelConfirmationField>,
    ): ReservationCancelConfirmationForm {
        val document = Jsoup.parse(html)
        val forms = document.select("form[name=prevRequestForm]")
        if (forms.size != 1) throw ParseException(SCREEN, "prevRequestFormを一意に特定できません")
        val form = forms.single()
        // 11回目のライブ診断(2026-07-28)で、実サイトのprevRequestFormにaction属性が存在しない
        // （属性はname/method=postのみ）ことが判明した。HTML標準ではaction省略時の送信先は
        // 現在のドキュメントURLであり、以前のように空文字列を即ParseExceptionにはしない。
        // 呼出し側が「明示action」と「現在のドキュメントURL」を取り違えないよう型で分ける。
        val actionAttribute = form.attr("action").trim()
        val action = if (actionAttribute.isEmpty()) {
            ReservationCancelConfirmationAction.SameAsCurrentDocument
        } else {
            ReservationCancelConfirmationAction.Explicit(actionAttribute)
        }
        val fields = form.select("input[name]:not([disabled]), select[name]:not([disabled]), textarea[name]:not([disabled])")
            .mapNotNull(::toSuccessfulField)
        if (!sameFieldMultiset(fields, expectedStage1Fields)) {
            throw ParseException(SCREEN, "prevRequestFormの項目が1段階目送信内容と一致しません")
        }
        val okCodesFieldName = extractOkCodesFieldName(document)
        if (fields.any { it.name == okCodesFieldName }) {
            throw ParseException(SCREEN, "OK_CODES_NAMEがprevRequestFormの既存項目と衝突しています")
        }
        // 確認コードもOK_CODES_NAMEと同じ字句走査で都度抽出する。ハードコードしない。
        val confirmationCode = extractConfirmationCode(document)
        return ReservationCancelConfirmationForm(action, fields, okCodesFieldName, confirmationCode)
    }

    /**
     * OK_CODES_NAME代入値の抽出。可視性はinternal。
     * ReservationGateway.kt の診断（cancel-reservation-prevform）が、判定に使うのと同じ抽出結果を
     * 読み取り専用で参照するために公開している。抽出ロジック自体は変更していない。
     */
    internal fun extractOkCodesFieldName(document: org.jsoup.nodes.Document): String {
        val values = document.select("script")
            .filter(::isInlineJavaScript)
            .flatMap { extractOkCodesAssignments(it.data()) }
        if (values.size != 1) throw ParseException(SCREEN, "OK_CODES_NAMEを一意に特定できません")
        return values.single()
    }

    /**
     * `okArray[okArray.length] = "OPACUSR001";`という配列要素代入から確認コードを抽出する
     * (`OK_CODES_NAME`抽出と同じ字句走査基盤)。抽出値が1件でない、または想定値と一致しない場合は
     * fail-closeする。
     */
    private fun extractConfirmationCode(document: org.jsoup.nodes.Document): String {
        val values = document.select("script")
            .filter(::isInlineJavaScript)
            .flatMap { extractConfirmationCodeAssignments(it.data()) }
        if (values.size != 1) throw ParseException(SCREEN, "確認コードを一意に特定できません")
        val value = values.single()
        if (value != EXPECTED_CONFIRMATION_CODE) {
            throw ParseException(SCREEN, "確認コードが想定外です")
        }
        return value
    }

    private fun isInlineJavaScript(script: Element): Boolean {
        if (script.hasAttr("src")) return false
        if (!script.hasAttr("type")) return true
        return script.attr("type").trim().lowercase() in STANDARD_JAVASCRIPT_MIME_TYPES
    }

    private fun extractOkCodesAssignments(source: String): List<String> {
        val values = mutableListOf<String>()
        val completed = scanJavaScriptIdentifiers(source) { token ->
            if (token.name == "OK_CODES_NAME" && !token.precededByDot) {
                parseSimpleAssignment(source, token.end)?.let(values::add)
            }
        }
        return if (completed) values else emptyList()
    }

    /**
     * `okArray[<添字>] = "VALUE";`という配列要素代入を検出する。`OK_CODES_NAME`抽出と同じ
     * 字句走査基盤(`scanJavaScriptIdentifiers`)の上に、`[`〜対応する`]`のスキップだけを追加する。
     * 添字の中身(`okArray.length`等)は検証しない。コメント・文字列・template literal・正規表現の
     * 内部は候補にならない(走査基盤側で除外済み)。
     */
    private fun extractConfirmationCodeAssignments(source: String): List<String> {
        val values = mutableListOf<String>()
        val completed = scanJavaScriptIdentifiers(source) { token ->
            if (token.name == "okArray" && !token.precededByDot) {
                parseArrayElementAssignment(source, token.end)?.let(values::add)
            }
        }
        return if (completed) values else emptyList()
    }

    /** `token.end`(識別子直後)から`[...]`をスキップし、続く`= "VALUE";`を[parseSimpleAssignment]で読む。 */
    private fun parseArrayElementAssignment(source: String, afterName: Int): String? {
        var index = skipTrivia(source, afterName) ?: return null
        if (source.getOrNull(index) != '[') return null
        index = skipMatchingBracket(source, index) ?: return null
        return parseSimpleAssignment(source, index)
    }

    /** `[`(startIndexが指す位置)から対応する`]`の直後まで読み進める。内部の文字列は[skipString]で無視する。 */
    private fun skipMatchingBracket(source: String, startIndex: Int): Int? {
        var depth = 0
        var index = startIndex
        while (index < source.length) {
            when (source[index]) {
                '[' -> {
                    depth += 1
                    index += 1
                }
                ']' -> {
                    depth -= 1
                    index += 1
                    if (depth == 0) return index
                }
                '\'', '"' -> index = skipString(source, index) ?: return null
                '`' -> return null
                else -> index += 1
            }
        }
        return null
    }

    /** [scanJavaScriptIdentifiers]が通知する識別子トークン。`precededByDot`は直前の意味のある文字が`.`だったか。 */
    private class JsIdentifierToken(val name: String, val end: Int, val precededByDot: Boolean)

    /**
     * script本文から識別子トークンを順に取り出す共通の字句走査。コメント・通常文字列・
     * template literal・正規表現の内部は候補にしない。template literal・未終端の文字列/コメント/
     * 正規表現・括弧不一致に出会ったら走査を打ち切り、`false`を返す(呼び出し側は「抽出できない」
     * として扱うこと)。挙動は改修前の`extractOkCodesAssignments`インライン実装と同じ。
     */
    private fun scanJavaScriptIdentifiers(source: String, onIdentifier: (JsIdentifierToken) -> Unit): Boolean {
        val parentheses = ArrayDeque<Boolean>()
        var index = 0
        var canStartRegex = true
        var previousCodeSignificant: Char? = null
        var controlConditionAwaitingParenthesis = false
        while (index < source.length) {
            when {
                source[index].isWhitespace() -> index += 1
                source.startsWith("//", index) -> {
                    index = source.indexOf('\n', index).let { if (it < 0) source.length else it + 1 }
                }
                source.startsWith("/*", index) -> {
                    val end = source.indexOf("*/", index + 2)
                    if (end < 0) return false
                    index = end + 2
                }
                source[index] == '`' -> return false
                source[index] == '\'' || source[index] == '"' -> {
                    index = skipString(source, index) ?: return false
                    canStartRegex = false
                    previousCodeSignificant = '"'
                }
                source[index] == '/' && canStartRegex -> {
                    index = skipRegex(source, index) ?: return false
                    canStartRegex = false
                    previousCodeSignificant = '/'
                }
                source[index].isJavaScriptIdentifierPart() -> {
                    val end = identifierEnd(source, index)
                    val token = source.substring(index, end)
                    onIdentifier(JsIdentifierToken(token, end, previousCodeSignificant == '.'))
                    controlConditionAwaitingParenthesis = token in CONTROL_CONDITION_KEYWORDS
                    canStartRegex = token in EXPRESSION_PREFIX_KEYWORDS
                    previousCodeSignificant = token.last()
                    index = end
                }
                else -> {
                    when (source[index]) {
                        '(' -> {
                            parentheses.addLast(controlConditionAwaitingParenthesis)
                            controlConditionAwaitingParenthesis = false
                            canStartRegex = true
                        }
                        ')' -> {
                            val wasControlCondition = parentheses.removeLastOrNull() ?: return false
                            canStartRegex = wasControlCondition
                        }
                        '[', '{' -> canStartRegex = true
                        ']', '}' -> canStartRegex = source[index] == '}'
                        ';', ',', ':', '?', '=', '!', '&', '|', '+', '-', '*', '%', '~' -> {
                            canStartRegex = true
                            controlConditionAwaitingParenthesis = false
                        }
                        '.' -> {
                            canStartRegex = false
                            controlConditionAwaitingParenthesis = false
                        }
                        else -> {
                            canStartRegex = false
                            controlConditionAwaitingParenthesis = false
                        }
                    }
                    if (source.startsWith("=>", index)) {
                        canStartRegex = true
                        controlConditionAwaitingParenthesis = false
                        previousCodeSignificant = '>'
                        index += 2
                        continue
                    }
                    previousCodeSignificant = source[index]
                    index += 1
                }
            }
        }
        return true
    }

    private fun parseSimpleAssignment(source: String, afterName: Int): String? {
        var index = skipTrivia(source, afterName) ?: return null
        if (source.getOrNull(index) != '=') return null
        index = skipTrivia(source, index + 1) ?: return null
        val quote = source.getOrNull(index)
        if (quote != '\'' && quote != '"') return null
        val end = skipString(source, index) ?: return null
        val value = source.substring(index + 1, end - 1)
        if (!SAFE_FIELD_NAME.matches(value)) return null
        val afterValue = skipTrivia(source, end) ?: return null
        if (source.getOrNull(afterValue) != ';') return null
        return value
    }

    private fun skipTrivia(source: String, start: Int): Int? {
        var index = start
        while (index < source.length) {
            if (source[index].isWhitespace()) {
                index += 1
            } else if (source.startsWith("//", index)) {
                index = source.indexOf('\n', index).let { if (it < 0) source.length else it + 1 }
            } else if (source.startsWith("/*", index)) {
                val end = source.indexOf("*/", index + 2)
                if (end < 0) return null
                index = end + 2
            } else {
                return index
            }
        }
        return null
    }

    private fun skipString(source: String, start: Int): Int? {
        val quote = source[start]
        var index = start + 1
        while (index < source.length) {
            when (source[index]) {
                '\\' -> index += 2
                quote -> return index + 1
                '\n', '\r' -> return null
                else -> index += 1
            }
        }
        return null
    }

    private fun skipRegex(source: String, start: Int): Int? {
        var index = start + 1
        var inCharacterClass = false
        while (index < source.length) {
            when (source[index]) {
                '\\' -> index += 2
                '[' -> {
                    inCharacterClass = true
                    index += 1
                }
                ']' -> {
                    inCharacterClass = false
                    index += 1
                }
                '/' -> if (!inCharacterClass) {
                    index += 1
                    while (source.getOrNull(index)?.isLetter() == true) index += 1
                    return index
                } else {
                    index += 1
                }
                '\n', '\r' -> return null
                else -> index += 1
            }
        }
        return null
    }

    private fun identifierEnd(source: String, start: Int): Int {
        var index = start + 1
        while (source.getOrNull(index)?.isJavaScriptIdentifierPart() == true) index += 1
        return index
    }

    private fun sameFieldMultiset(
        actual: List<ReservationCancelConfirmationField>,
        expected: List<ReservationCancelConfirmationField>,
    ): Boolean = actual.groupingBy { it }.eachCount() == expected.groupingBy { it }.eachCount()

    private fun toSuccessfulField(element: Element): ReservationCancelConfirmationField? = when (element.tagName()) {
        "input" -> {
            val type = element.attr("type").ifBlank { "text" }.lowercase()
            if (type in setOf("button", "submit", "reset", "image", "file")) null
            else if (type in setOf("checkbox", "radio") && !element.hasAttr("checked")) null
            else ReservationCancelConfirmationField(element.attr("name"), element.attr("value"))
        }
        "select" -> {
            val selected = element.select("option[selected]:not([disabled])")
            val option = when {
                selected.size == 1 -> selected.single()
                selected.size > 1 -> return null
                else -> element.selectFirst("option:not([disabled])") ?: return null
            }
            ReservationCancelConfirmationField(element.attr("name"), option.attr("value"))
        }
        "textarea" -> ReservationCancelConfirmationField(element.attr("name"), element.`val`())
        else -> null
    }

    private val SAFE_FIELD_NAME = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val CONTROL_CONDITION_KEYWORDS = setOf("if", "while", "for", "with", "switch", "catch")
    private val EXPRESSION_PREFIX_KEYWORDS = setOf(
        "return", "throw", "case", "delete", "typeof", "void", "new", "in", "of", "yield", "await", "else", "do", "try", "finally",
        "break", "continue", "debugger",
    )
    private val STANDARD_JAVASCRIPT_MIME_TYPES = setOf("text/javascript", "application/javascript", "text/ecmascript", "application/ecmascript")
}

/**
 * 取消確認フォーム(prevRequestForm)の送信先。
 *
 * 11回目のライブ診断(2026-07-28)で、実サイトのprevRequestFormにaction属性が存在しないことが確認できた。
 * HTML標準ではaction省略時の送信先は「現在のドキュメントURL」であり、1段階目はリダイレクトしない
 * （実測: status=200 redirect=-）ため、この「現在のドキュメントURL」は1段階目に実際に送ったURL
 * （クエリ付き `WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1`）と同一になる。
 * 呼出し側（[com.fallgist.nishinomiyalibrary.data.remote.licsxp.LicsXpSession.resolveReservationCancelAction]）
 * がこの2つを取り違えないよう、空文字列をそのまま渡す曖昧な扱いにはせず型で区別する。
 */
sealed interface ReservationCancelConfirmationAction {
    /** action属性が明示されている場合の、trim済みの生の値。 */
    data class Explicit(val value: String) : ReservationCancelConfirmationAction

    /** action属性が省略されている場合。送信先は現在のドキュメントURL（＝1段階目に実際に送ったURL）。 */
    data object SameAsCurrentDocument : ReservationCancelConfirmationAction
}

class ReservationCancelConfirmationForm internal constructor(
    val action: ReservationCancelConfirmationAction,
    private val fields: List<ReservationCancelConfirmationField>,
    private val okCodesFieldName: String,
    private val confirmationCode: String,
) {
    fun buildForm(): FormBody = FormBody.Builder().apply {
        fields.forEach { field -> add(field.name, field.value) }
        add(okCodesFieldName, confirmationCode)
    }.build()
}

data class ReservationCancelConfirmationField(val name: String, val value: String)

private fun Char.isJavaScriptIdentifierPart(): Boolean = isLetterOrDigit() || this == '_' || this == '$'
