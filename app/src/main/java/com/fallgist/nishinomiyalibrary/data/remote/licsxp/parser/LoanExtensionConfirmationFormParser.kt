package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 貸出延長1段階目の応答にある`prevRequestForm`を、確認ダイアログOK後の再送用として解析する。
 *
 * 2段階目はこのフォームのDOM順をそのまま送り、ページ側scriptが追加するOKコードだけを末尾へ加える。
 * 予約取消(`ReservationCancelConfirmationFormParser`)と同じ`prevRequestForm` + `OK_CODES_NAME` +
 * 固定確認コードの機構だが、フィールド名(para)・確認コード値(OPACUSR005)・action検証方針が異なる
 * ため型は分離する(`docs/design/loan-extension.md` §3)。
 *
 * 予約取消と異なり、貸出延長の2段階目送信先は常に明示指定である
 * (`docs/site-research.md` §10)。ただし**HTMLの`action`属性としては存在しない**。実サイトの
 * `prevRequestForm`は`<form name="prevRequestForm" method="post">`のみで、送信先はページ内
 * スクリプトが`document.prevRequestForm.action = "..."`と実行時に代入する。そのため`action`属性の
 * 有無をそもそも検証条件にせず、`OK_CODES_NAME`と同じ字句走査でこのJS代入から値を抽出し、
 * 固定origin・固定path・クエリ無しの完全一致だけを受理する(§5.1のfail-close要件)。
 */
internal object LoanExtensionConfirmationFormParser {
    private const val SCREEN = "loan-extension-confirmation"
    private const val EXPECTED_ACTION = "/licsxp-opac/WOpacUsrLendListExtendAction.do"

    /**
     * @param expectedStage1Fields 1段階目の**queryとbodyを合わせた**全パラメータ(DOM順・同名重複込み)。
     *   `prevRequestForm`の項目はbodyだけでなくqueryも含めた多重集合と一致する
     *   (`mngFlg1_handan`はqueryだが`prevRequestForm`には含まれる。`docs/site-research.md` §10参照)。
     *   bodyだけを渡すと必ず不一致になるため、呼び出し側はqueryとbodyを合わせて渡すこと。
     */
    fun parse(
        html: String,
        expectedStage1Fields: List<LoanExtensionConfirmationField>,
    ): LoanExtensionConfirmationForm {
        val document = Jsoup.parse(html)
        val forms = document.select("form[name=prevRequestForm]")
        if (forms.size != 1) throw ParseException(SCREEN, "prevRequestFormを一意に特定できません")
        val form = forms.single()

        // action属性は実サイトに存在しないため検証条件にしない。送信先はJS代入から抽出し検証する(fail-close)。
        // 段階3への申し送り(設計 §5.1)どおり、ここで検証した送信先をそのままGatewayへ渡す。
        // Gateway側に送信先の文字列定数は置かず、この戻り値だけを送信に使う。
        val action = extractPrevRequestFormAction(document)

        val fields = form.select("input[name]:not([disabled]), select[name]:not([disabled]), textarea[name]:not([disabled])")
            .mapNotNull(::toSuccessfulField)
        if (!sameFieldMultiset(fields, expectedStage1Fields)) {
            throw ParseException(SCREEN, "prevRequestFormの項目が1段階目送信内容と一致しません")
        }

        val okCodesFieldName = extractOkCodesFieldName(document)
        if (fields.any { it.name == okCodesFieldName }) {
            throw ParseException(SCREEN, "OK_CODES_NAMEがprevRequestFormの既存項目と衝突しています")
        }
        return LoanExtensionConfirmationForm(action, fields, okCodesFieldName)
    }

    /** OK_CODES_NAME代入値の抽出。予約取消と同じ字句走査(コメント・文字列・template literal・正規表現の内部は候補にしない)。 */
    private fun extractOkCodesFieldName(document: org.jsoup.nodes.Document): String {
        val values = document.select("script")
            .filter(::isInlineJavaScript)
            .flatMap { extractOkCodesAssignments(it.data()) }
        if (values.size != 1) throw ParseException(SCREEN, "OK_CODES_NAMEを一意に特定できません")
        return values.single()
    }

    /**
     * `document.prevRequestForm.action = "...";`というJS代入から送信先を抽出し、固定origin・
     * 固定path・クエリ無しの完全一致だけを受理する(§5.1)。`action`属性はHTMLに存在しないため
     * 検証条件にしない(`docs/site-research.md` §10)。
     */
    private fun extractPrevRequestFormAction(document: org.jsoup.nodes.Document): String {
        val values = document.select("script")
            .filter(::isInlineJavaScript)
            .flatMap { extractPrevRequestFormActionAssignments(it.data()) }
        if (values.size != 1) throw ParseException(SCREEN, "prevRequestFormの送信先(action)を一意に特定できません")
        val action = values.single()
        if (action != EXPECTED_ACTION) {
            throw ParseException(SCREEN, "prevRequestFormのaction代入が想定外です")
        }
        return action
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
                parseSimpleAssignment(source, token.end, SAFE_FIELD_NAME)?.let(values::add)
            }
        }
        return if (completed) values else emptyList()
    }

    /**
     * `document.prevRequestForm.action = "...";`の識別子連鎖(ドット区切り)を検出する。
     * OK_CODES_NAME抽出と同じ字句走査基盤を使うため、コメント・文字列・template literal・
     * 正規表現の内部は候補にならない。
     */
    private fun extractPrevRequestFormActionAssignments(source: String): List<String> {
        val values = mutableListOf<String>()
        var chain = 0 // 0=未一致, 1=documentまで一致, 2=document.prevRequestFormまで一致
        val completed = scanJavaScriptIdentifiers(source) { token ->
            chain = when {
                !token.precededByDot && token.name == "document" -> 1
                chain == 1 && token.precededByDot && token.name == "prevRequestForm" -> 2
                chain == 2 && token.precededByDot && token.name == "action" -> {
                    parseSimpleAssignment(source, token.end, SAFE_URL_PATH)?.let(values::add)
                    0
                }
                else -> 0
            }
        }
        return if (completed) values else emptyList()
    }

    /** [scanJavaScriptIdentifiers]が通知する識別子トークン。`precededByDot`は直前の意味のある文字が`.`だったか。 */
    private class JsIdentifierToken(val name: String, val end: Int, val precededByDot: Boolean)

    /**
     * script本文から識別子トークンを順に取り出す共通の字句走査。予約取消の`OK_CODES_NAME`抽出と
     * 同じ規則で、コメント・文字列・template literal・正規表現の内部は候補にしない。
     * template literal・未終端の文字列/コメント/正規表現・括弧不一致に出会ったら走査を打ち切り、
     * `false`を返す(呼び出し側は「抽出できない」として扱うこと)。
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

    private fun parseSimpleAssignment(source: String, afterName: Int, valuePattern: Regex): String? {
        var index = skipTrivia(source, afterName) ?: return null
        if (source.getOrNull(index) != '=') return null
        index = skipTrivia(source, index + 1) ?: return null
        val quote = source.getOrNull(index)
        if (quote != '\'' && quote != '"') return null
        val end = skipString(source, index) ?: return null
        val value = source.substring(index + 1, end - 1)
        if (!valuePattern.matches(value)) return null
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
        actual: List<LoanExtensionConfirmationField>,
        expected: List<LoanExtensionConfirmationField>,
    ): Boolean = actual.groupingBy { it }.eachCount() == expected.groupingBy { it }.eachCount()

    private fun toSuccessfulField(element: Element): LoanExtensionConfirmationField? = when (element.tagName()) {
        "input" -> {
            val type = element.attr("type").ifBlank { "text" }.lowercase()
            if (type in setOf("button", "submit", "reset", "image", "file")) null
            else if (type in setOf("checkbox", "radio") && !element.hasAttr("checked")) null
            else LoanExtensionConfirmationField(element.attr("name"), element.attr("value"))
        }
        "select" -> {
            val selected = element.select("option[selected]:not([disabled])")
            val option = when {
                selected.size == 1 -> selected.single()
                selected.size > 1 -> return null
                else -> element.selectFirst("option:not([disabled])") ?: return null
            }
            LoanExtensionConfirmationField(element.attr("name"), option.attr("value"))
        }
        "textarea" -> LoanExtensionConfirmationField(element.attr("name"), element.`val`())
        else -> null
    }

    private val SAFE_FIELD_NAME = Regex("[A-Za-z][A-Za-z0-9_]*")

    /** action代入値の許容文字集合。パス表記(`/`・`.`)を含むため`SAFE_FIELD_NAME`とは別に定義する。 */
    private val SAFE_URL_PATH = Regex("[A-Za-z0-9/_.\\-]+")
    private val CONTROL_CONDITION_KEYWORDS = setOf("if", "while", "for", "with", "switch", "catch")
    private val EXPRESSION_PREFIX_KEYWORDS = setOf(
        "return", "throw", "case", "delete", "typeof", "void", "new", "in", "of", "yield", "await", "else", "do", "try", "finally",
        "break", "continue", "debugger",
    )
    private val STANDARD_JAVASCRIPT_MIME_TYPES = setOf("text/javascript", "application/javascript", "text/ecmascript", "application/ecmascript")
}

/**
 * [action] は[LoanExtensionConfirmationFormParser.parse]が固定origin・固定path・クエリ無しの
 * 完全一致まで検証済みの送信先(絶対パス文字列)である。Gatewayはこの値をそのまま使って送信し、
 * 自前の送信先定数を持たない(設計 §5.1 段階3への申し送り)。
 */
internal class LoanExtensionConfirmationForm(
    val action: String,
    private val fields: List<LoanExtensionConfirmationField>,
    private val okCodesFieldName: String,
) {
    fun buildForm(): FormBody = FormBody.Builder().apply {
        fields.forEach { field -> add(field.name, field.value) }
        add(okCodesFieldName, OK_CODE)
    }.build()
}

internal data class LoanExtensionConfirmationField(val name: String, val value: String)

/** HARで実測済みの固定確認コード(`docs/site-research.md` §10)。okCodesの値として常にこれを送る。 */
private const val OK_CODE = "OPACUSR005"

private fun Char.isJavaScriptIdentifierPart(): Boolean = isLetterOrDigit() || this == '_' || this == '$'
