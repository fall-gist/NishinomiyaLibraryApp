package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 予約取消の1段階目応答にある`prevRequestForm`を、ブラウザのOK後の再送用として解析する。
 *
 * 2段階目はこのフォームのDOM順をそのまま送り、ページ側scriptが追加するOKコードだけを末尾へ加える。
 */
object ReservationCancelConfirmationFormParser {
    private const val SCREEN = "reservation-cancel-confirmation"

    fun parse(
        html: String,
        expectedStage1Fields: List<ReservationCancelConfirmationField>,
    ): ReservationCancelConfirmationForm {
        val document = Jsoup.parse(html)
        val forms = document.select("form[name=prevRequestForm]")
        if (forms.size != 1) throw ParseException(SCREEN, "prevRequestFormを一意に特定できません")
        val form = forms.single()
        val action = form.attr("action").trim()
        if (action.isEmpty()) throw ParseException(SCREEN, "prevRequestFormのactionがありません")
        val fields = form.select("input[name]:not([disabled]), select[name]:not([disabled]), textarea[name]:not([disabled])")
            .mapNotNull(::toSuccessfulField)
        if (!sameFieldMultiset(fields, expectedStage1Fields)) {
            throw ParseException(SCREEN, "prevRequestFormの項目が1段階目送信内容と一致しません")
        }
        val okCodesFieldName = extractOkCodesFieldName(document)
        if (fields.any { it.name == okCodesFieldName }) {
            throw ParseException(SCREEN, "OK_CODES_NAMEがprevRequestFormの既存項目と衝突しています")
        }
        return ReservationCancelConfirmationForm(action, fields, okCodesFieldName)
    }

    private fun extractOkCodesFieldName(document: org.jsoup.nodes.Document): String {
        val values = document.select("script")
            .filter(::isInlineJavaScript)
            .flatMap { extractOkCodesAssignments(it.data()) }
        if (values.size != 1) throw ParseException(SCREEN, "OK_CODES_NAMEを一意に特定できません")
        return values.single()
    }

    private fun isInlineJavaScript(script: Element): Boolean {
        if (script.hasAttr("src")) return false
        if (!script.hasAttr("type")) return true
        return script.attr("type").trim().lowercase() in STANDARD_JAVASCRIPT_MIME_TYPES
    }

    /** コメント・通常文字列・template literal・正規表現の内部を候補にしない単純代入の字句走査。 */
    private fun extractOkCodesAssignments(source: String): List<String> {
        val values = mutableListOf<String>()
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
                    if (end < 0) return emptyList()
                    index = end + 2
                }
                source[index] == '`' -> return emptyList()
                source[index] == '\'' || source[index] == '"' -> {
                    index = skipString(source, index) ?: return emptyList()
                    canStartRegex = false
                    previousCodeSignificant = '"'
                }
                source[index] == '/' && canStartRegex -> {
                    index = skipRegex(source, index) ?: return emptyList()
                    canStartRegex = false
                    previousCodeSignificant = '/'
                }
                source[index].isJavaScriptIdentifierPart() -> {
                    val end = identifierEnd(source, index)
                    val token = source.substring(index, end)
                    if (token == "OK_CODES_NAME" && previousCodeSignificant != '.') {
                        parseSimpleAssignment(source, end)?.let(values::add)
                    }
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
                            val wasControlCondition = parentheses.removeLastOrNull() ?: return emptyList()
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
        return values
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

class ReservationCancelConfirmationForm internal constructor(
    val action: String,
    private val fields: List<ReservationCancelConfirmationField>,
    private val okCodesFieldName: String,
) {
    fun buildForm(): FormBody = FormBody.Builder().apply {
        fields.forEach { field -> add(field.name, field.value) }
        add(okCodesFieldName, "OPACUSR001")
    }.build()
}

data class ReservationCancelConfirmationField(val name: String, val value: String)

private fun Char.isJavaScriptIdentifierPart(): Boolean = isLetterOrDigit() || this == '_' || this == '$'
