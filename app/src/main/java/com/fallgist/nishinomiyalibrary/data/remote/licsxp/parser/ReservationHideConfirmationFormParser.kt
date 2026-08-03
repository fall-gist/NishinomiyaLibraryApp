package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** HARで確認した予約非表示の第2段階確認フォーム。確認署名は単一のinline JavaScript内でのみ受理する。 */
internal object ReservationHideConfirmationFormParser {
    private const val SCREEN = "reservation-hide-confirmation"
    private const val OK_CODE = "OPACUSR423"
    private const val OK_FIELD = "okCodes"
    private const val CANCEL_FIELD = "cancelCodes"
    private const val ACTION = "/licsxp-opac/WOpacUsrRsvHiddenAction.do"

    fun parse(html: String, expectedStage1: List<HideConfirmationField>): ReservationHideConfirmationForm {
        val document = Jsoup.parse(html)
        val forms = document.select("form[name=prevRequestForm]")
        if (forms.size != 1) throw ParseException(SCREEN, "prevRequestFormを一意に特定できません")
        val form = forms.single()
        if (!form.attr("method").equals("post", ignoreCase = true) || form.hasAttr("action") && form.attr("action").isNotBlank()) {
            throw ParseException(SCREEN, "prevRequestFormの属性が不正です")
        }
        if (document.select("[form]").any { it.attr("form").equals("prevRequestForm", ignoreCase = true) }) {
            throw ParseException(SCREEN, "form属性で関連付けられた外部コントロールは扱えません")
        }
        if (form.select("input[type=checkbox], input[type=radio], fieldset[disabled] [name], select[multiple], option:not([value]), optgroup[disabled], option[selected][disabled], [form]").isNotEmpty()) {
            throw ParseException(SCREEN, "安全に扱えないフォーム構造です")
        }
        val fields = form.select("input[name]:not([disabled]), select[name]:not([disabled]), textarea[name]:not([disabled])")
            .mapNotNull { element ->
                when (element.tagName()) {
                    "input" -> if (element.attr("type").lowercase() in setOf("button", "submit", "reset", "image", "file")) null else HideConfirmationField(element.attr("name"), element.attr("value"))
                    "textarea" -> HideConfirmationField(element.attr("name"), element.`val`())
                    "select" -> element.selectFirst("option[selected]:not([disabled]), option:not([disabled])")?.let { HideConfirmationField(element.attr("name"), it.attr("value")) }
                    else -> null
                }
            }
        if (fields.groupingBy { it }.eachCount() != expectedStage1.groupingBy { it }.eachCount()) {
            throw ParseException(SCREEN, "prevRequestFormのフィールド集合が一致しません")
        }
        val okField = extractSignature(document.select("script"))
        if (fields.any { it.name == okField }) throw ParseException(SCREEN, "確認フィールドが既存フィールドと重複しています")
        return ReservationHideConfirmationForm(fields, okField)
    }

    private fun extractSignature(scripts: List<Element>): String {
        val inline = scripts.filter(::isInlineJavaScript)
        val matches = inline.mapIndexedNotNull { index, script ->
            parseSignature(tokenize(script.data()) ?: return@mapIndexedNotNull null)?.let { index to it }
        }
        if (matches.size != 1) throw ParseException(SCREEN, "確認script署名を一意に特定できません")
        val (targetIndex, okField) = matches.single()
        if (inline.indices.any { it != targetIndex && containsSignatureToken(inline[it].data()) } ||
            scripts.filterNot(::isInlineJavaScript).any { containsSignatureToken(it.data()) }
        ) throw ParseException(SCREEN, "確認script署名が複数scriptに分散しています")
        return okField
    }

    private fun isInlineJavaScript(script: Element): Boolean {
        if (script.hasAttr("src")) return false
        if (!script.hasAttr("type")) return true
        return script.attr("type").trim().lowercase() in STANDARD_JAVASCRIPT_MIME_TYPES
    }

    /** コメント・文字列・template literal・正規表現の内部をトークン化しない。 */
    private fun tokenize(source: String): List<JsToken>? {
        val tokens = mutableListOf<JsToken>()
        var index = 0
        var canStartRegex = true
        while (index < source.length) {
            when {
                source[index].isWhitespace() -> index += 1
                source.startsWith("//", index) -> index = source.indexOf('\n', index).let { if (it < 0) source.length else it + 1 }
                source.startsWith("/*", index) -> {
                    val end = source.indexOf("*/", index + 2)
                    if (end < 0) return null
                    index = end + 2
                }
                source[index] == '`' -> {
                    index = skipTemplateLiteral(source, index) ?: return null
                    canStartRegex = false
                }
                source[index] == '\'' || source[index] == '"' -> {
                    val string = readString(source, index) ?: return null
                    tokens += JsToken(string.value, isString = true)
                    index = string.end
                    canStartRegex = false
                }
                source[index] == '/' && canStartRegex -> {
                    index = skipRegex(source, index) ?: return null
                    canStartRegex = false
                }
                source[index].isJavaScriptIdentifierPart() -> {
                    val end = identifierEnd(source, index)
                    val value = source.substring(index, end)
                    tokens += JsToken(value)
                    canStartRegex = value in EXPRESSION_PREFIX_KEYWORDS
                    index = end
                }
                source[index].isDigit() -> {
                    val end = source.indexOfFirst(index) { !it.isDigit() }.let { if (it < 0) source.length else it }
                    tokens += JsToken(source.substring(index, end))
                    index = end
                    canStartRegex = false
                }
                else -> {
                    val value = if (source.startsWith("++", index) || source.startsWith("+=", index) || source.startsWith("=>", index)) source.substring(index, index + 2) else source[index].toString()
                    tokens += JsToken(value)
                    index += value.length
                    canStartRegex = value in EXPRESSION_PREFIX_PUNCTUATION
                }
            }
        }
        return tokens
    }

    private fun parseSignature(tokens: List<JsToken>): String? {
        val depths = braceDepths(tokens) ?: return null
        val okNameStart = findUniqueAtDepth(tokens, depths, 0, OK_NAME_DECLARATION) ?: return null
        val cancelNameStart = findUniqueAtDepth(tokens, depths, 0, CANCEL_NAME_DECLARATION) ?: return null
        val create = findTopLevelFunction(tokens, depths, "createConfirmDialog") ?: return null
        val cancel = findTopLevelFunction(tokens, depths, "cancelDialog") ?: return null
        val onloadStart = findUniqueAtDepth(tokens, depths, 0, ONLOAD_ASSIGNMENT) ?: return null
        if (tokens.subList(cancel.open + 1, cancel.close) != CANCEL_DIALOG_BODY ||
            okNameStart >= create.start || cancelNameStart >= create.start || onloadStart <= cancel.close ||
            create.start <= cancel.close && cancel.start <= create.close
        ) return null

        val createBody = (create.open + 1) until create.close
        val functionDepth = depths[create.open] + 1
        val okArrayStart = findUniqueAtDepth(tokens, depths, functionDepth, OK_ARRAY_DECLARATION) ?: return null
        val cancelArrayStart = findUniqueAtDepth(tokens, depths, functionDepth, CANCEL_ARRAY_DECLARATION) ?: return null
        val abortStart = findUniqueAtDepth(tokens, depths, functionDepth, SHOULD_ABORT_BLOCK) ?: return null
        val abortEnd = consume(tokens, abortStart, SHOULD_ABORT_BLOCK) ?: return null
        val restStart = findUniqueAtDepth(tokens, depths, functionDepth, IF_REST_BLOCK) ?: return null
        val restEnd = consume(tokens, restStart, IF_REST_BLOCK) ?: return null
        val okLoopStart = findUniqueLoopAtDepth(tokens, depths, functionDepth, "okArray", "OK_CODES_NAME") ?: return null
        val okLoopEnd = consumeLoop(tokens, okLoopStart, "okArray", "OK_CODES_NAME") ?: return null
        val cancelLoopStart = findUniqueLoopAtDepth(tokens, depths, functionDepth, "cancelArray", "CANCEL_CODES_NAME") ?: return null
        val cancelLoopEnd = consumeLoop(tokens, cancelLoopStart, "cancelArray", "CANCEL_CODES_NAME") ?: return null
        val actionStart = findUniqueAtDepth(tokens, depths, functionDepth, ACTION_AND_SUBMIT) ?: return null
        if (okArrayStart >= abortStart || cancelArrayStart >= abortStart || abortEnd > restStart ||
            restEnd != okLoopStart || okLoopEnd != cancelLoopStart || cancelLoopEnd != actionStart || actionStart + ACTION_AND_SUBMIT.size != create.close ||
            !createBody.containsRange(okArrayStart until (okArrayStart + OK_ARRAY_DECLARATION.size)) ||
            !createBody.containsRange(cancelArrayStart until (cancelArrayStart + CANCEL_ARRAY_DECLARATION.size)) ||
            !createBody.containsRange(abortStart until abortEnd) ||
            !createBody.containsRange(restStart until restEnd) ||
            !createBody.containsRange(okLoopStart until okLoopEnd) ||
            !createBody.containsRange(cancelLoopStart until cancelLoopEnd) ||
            !createBody.containsRange(actionStart until (actionStart + ACTION_AND_SUBMIT.size)) ||
            arrayAssignmentCount(tokens, create.open + 1, create.close, "okArray") != 1 ||
            arrayAssignmentCount(tokens, create.open + 1, create.close, "cancelArray") != 0 ||
            hasArrayMutation(tokens, create.open + 1, create.close, "cancelArray") ||
            tokens.count { it == string(OK_CODE) } != 1 ||
            tokens.indices.count { it in createBody && tokens[it].value == "for" } != 2 ||
            tokens.indices.any { it in createBody && tokens[it].value in FORBIDDEN_CREATE_TOKENS } ||
            tokens.indices.any { index ->
                index in createBody && tokens[index] == id("return") &&
                    index !in (abortStart until abortEnd) && index !in (restStart until restEnd)
            }
        ) return null

        val allowedRanges = listOf(
            okNameStart until (okNameStart + OK_NAME_DECLARATION.size),
            cancelNameStart until (cancelNameStart + CANCEL_NAME_DECLARATION.size),
            okArrayStart until (okArrayStart + OK_ARRAY_DECLARATION.size),
            cancelArrayStart until (cancelArrayStart + CANCEL_ARRAY_DECLARATION.size),
            abortStart until abortEnd,
            restStart until restEnd,
            okLoopStart until okLoopEnd,
            cancelLoopStart until cancelLoopEnd,
            actionStart until (actionStart + ACTION_AND_SUBMIT.size),
        )
        if (tokens.indices.any { index -> tokens[index].value in SIGNATURE_TOKENS && allowedRanges.none { index in it } }) return null
        return OK_FIELD
    }

    private fun IntRange.containsRange(range: IntRange): Boolean = range.first in this && range.last in this

    private fun findTopLevelFunction(tokens: List<JsToken>, depths: IntArray, name: String): FunctionSpan? {
        val start = findUniqueAtDepth(tokens, depths, 0, functionHeader(name)) ?: return null
        val open = start + functionHeader(name).lastIndex
        val close = matchingBrace(tokens, open) ?: return null
        return FunctionSpan(start, open, close)
    }

    private fun findUniqueAtDepth(tokens: List<JsToken>, depths: IntArray, depth: Int, pattern: List<JsToken>): Int? =
        tokens.indices.filter { depths[it] == depth && consume(tokens, it, pattern) != null }.singleOrNull()

    private fun findUniqueLoopAtDepth(tokens: List<JsToken>, depths: IntArray, depth: Int, array: String, codeName: String): Int? =
        tokens.indices.filter { depths[it] == depth && consumeLoop(tokens, it, array, codeName) != null }.singleOrNull()

    private fun braceDepths(tokens: List<JsToken>): IntArray? {
        val depths = IntArray(tokens.size)
        var depth = 0
        tokens.forEachIndexed { index, token ->
            depths[index] = depth
            when (token.value) {
                "{" -> depth += 1
                "}" -> {
                    depth -= 1
                    if (depth < 0) return null
                }
            }
        }
        return depths.takeIf { depth == 0 }
    }

    private fun matchingBrace(tokens: List<JsToken>, open: Int): Int? {
        var depth = 0
        for (index in open until tokens.size) {
            when (tokens[index].value) {
                "{" -> depth += 1
                "}" -> if (--depth == 0) return index
            }
        }
        return null
    }

    private fun arrayAssignmentCount(tokens: List<JsToken>, start: Int, end: Int, array: String): Int =
        (start until end).count { index ->
            tokens.getOrNull(index) == id(array) && tokens.getOrNull(index + 1) == p("[") &&
                tokens.indexOfFirst(index + 2) { it == p("]") }.let { close -> close >= 0 && tokens.getOrNull(close + 1) == p("=") }
        }

    private fun hasArrayMutation(tokens: List<JsToken>, start: Int, end: Int, array: String): Boolean =
        (start until end).any { index ->
            tokens.getOrNull(index) == id(array) && tokens.getOrNull(index + 1) == p(".") &&
                tokens.getOrNull(index + 2)?.value?.let { it in setOf("push", "unshift", "splice") } == true
        }

    private fun consumeLoop(tokens: List<JsToken>, start: Int, array: String, codeName: String): Int? {
        var index = consume(tokens, start, forHeader(array)) ?: return null
        if (tokens.getOrNull(index) == id("var")) index += 1
        index = consume(tokens, index, createHiddenInput()) ?: return null
        index = consume(tokens, index, listOf(id("newHidden"), p("."), id("type"), p("="), string("hidden"), p(";"))) ?: return null
        index = consume(tokens, index, listOf(id("newHidden"), p("."), id("name"), p("="), id(codeName), p(";"))) ?: return null
        index = consume(tokens, index, listOf(id("newHidden"), p("."), id("value"), p("="), id(array), p("["), id("i"), p("]"), p(";"))) ?: return null
        return consume(tokens, index, listOf(id("document"), p("."), id("prevRequestForm"), p("."), id("appendChild"), p("("), id("newHidden"), p(")"), p(";"), p("}")))
    }

    private fun forHeader(array: String): List<JsToken> = listOf(
        id("for"), p("("), id("var"), id("i"), p("="), id("0"), p(";"), id("i"), p("<"), id(array), p("."), id("length"), p(";"), id("i"), p("++"), p(")"), p("{"),
    )

    private fun createHiddenInput(): List<JsToken> = listOf(
        id("newHidden"), p("="), id("document"), p("."), id("createElement"), p("("), string("input"), p(")"), p(";"),
    )

    private fun consume(tokens: List<JsToken>, start: Int, expected: List<JsToken>): Int? {
        if (start + expected.size > tokens.size) return null
        if (expected.indices.any { tokens[start + it] != expected[it] }) return null
        return start + expected.size
    }

    private fun containsSignatureToken(source: String): Boolean {
        val tokens = tokenize(source) ?: return source.contains("prevRequestForm")
        return tokens.any { it.value in SIGNATURE_TOKENS }
    }

    private fun readString(source: String, start: Int): StringToken? {
        val quote = source[start]
        val value = StringBuilder()
        var index = start + 1
        while (index < source.length) {
            when (source[index]) {
                '\\' -> if (index + 1 < source.length) { value.append(source[index + 1]); index += 2 } else return null
                quote -> return StringToken(value.toString(), index + 1)
                '\n', '\r' -> return null
                else -> { value.append(source[index]); index += 1 }
            }
        }
        return null
    }

    private fun skipTemplateLiteral(source: String, start: Int): Int? {
        var index = start + 1
        while (index < source.length) {
            when (source[index]) {
                '\\' -> index += 2
                '`' -> return index + 1
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
                '[' -> { inCharacterClass = true; index += 1 }
                ']' -> { inCharacterClass = false; index += 1 }
                '/' -> if (!inCharacterClass) {
                    index += 1
                    while (source.getOrNull(index)?.isLetter() == true) index += 1
                    return index
                } else index += 1
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

    private fun String.indexOfFirst(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start until length) if (predicate(this[index])) return index
        return -1
    }

    private fun List<JsToken>.indexOfFirst(start: Int, predicate: (JsToken) -> Boolean): Int {
        for (index in start until size) if (predicate(this[index])) return index
        return -1
    }

    private data class JsToken(val value: String, val isString: Boolean = false)
    private data class StringToken(val value: String, val end: Int)
    private data class FunctionSpan(val start: Int, val open: Int, val close: Int)

    private fun id(value: String) = JsToken(value)
    private fun p(value: String) = JsToken(value)
    private fun string(value: String) = JsToken(value, isString = true)

    private fun functionHeader(name: String) = listOf(id("function"), id(name), p("("), p(")"), p("{"))
    private val OK_NAME_DECLARATION = listOf(id("var"), id("OK_CODES_NAME"), p("="), string(OK_FIELD), p(";"))
    private val CANCEL_NAME_DECLARATION = listOf(id("var"), id("CANCEL_CODES_NAME"), p("="), string(CANCEL_FIELD), p(";"))
    private val OK_ARRAY_DECLARATION = listOf(id("var"), id("okArray"), p("="), id("new"), id("Array"), p("("), p(")"), p(";"))
    private val CANCEL_ARRAY_DECLARATION = listOf(id("var"), id("cancelArray"), p("="), id("new"), id("Array"), p("("), p(")"), p(";"))
    private val SHOULD_ABORT_BLOCK = listOf(
        id("var"), id("shouldAbort"), p("="), id("false"), p(";"),
        id("if"), p("("), id("shouldAbort"), p(")"), p("{"), id("return"), id("cancelDialog"), p("("), p(")"), p(";"), p("}"),
    )
    private val IF_REST_BLOCK = listOf(
        id("if"), p("("), id("rest"), p(")"), p("{"),
        id("okArray"), p("["), id("okArray"), p("."), id("length"), p("]"), p("="), string(OK_CODE), p(";"),
        id("submitFlg"), p("="), id("false"), p(";"), p("}"),
        id("else"), p("{"), id("return"), id("cancelDialog"), p("("), p(")"), p(";"), p("}"),
    )
    private val ACTION_AND_SUBMIT = listOf(
        id("document"), p("."), id("prevRequestForm"), p("."), id("action"), p("="), string(ACTION), p(";"),
        id("document"), p("."), id("prevRequestForm"), p("."), id("submit"), p("("), p(")"), p(";"),
    )
    private val ONLOAD_ASSIGNMENT = listOf(id("window"), p("."), id("onload"), p("="), id("createConfirmDialog"), p(";"))
    private val CANCEL_DIALOG_BODY = listOf(id("return"), id("false"), p(";"))
    private val SIGNATURE_TOKENS = setOf("OK_CODES_NAME", "CANCEL_CODES_NAME", "okArray", "cancelArray", "newHidden", "prevRequestForm", OK_CODE)
    private val FORBIDDEN_CREATE_TOKENS = setOf("throw", "while", "do", "switch", "try", "catch", "break", "continue", "function", "=>")
    private val EXPRESSION_PREFIX_KEYWORDS = setOf("return", "throw", "case", "delete", "typeof", "void", "new", "in", "of", "yield", "await", "else", "do", "try", "finally")
    private val EXPRESSION_PREFIX_PUNCTUATION = setOf("(", "[", "{", ";", ",", ":", "?", "=", "!", "&", "|", "+", "-", "*", "%", "~", "<", ">", "=>", "+=", "++")
    private val STANDARD_JAVASCRIPT_MIME_TYPES = setOf("text/javascript", "application/javascript", "text/ecmascript", "application/ecmascript")
}

internal class ReservationHideConfirmationForm(private val fields: List<HideConfirmationField>, private val okField: String) {
    fun buildForm(): FormBody = FormBody.Builder().apply { fields.forEach { add(it.name, it.value) }; add(okField, "OPACUSR423") }.build()
}

internal data class HideConfirmationField(val name: String, val value: String)

private fun Char.isJavaScriptIdentifierPart(): Boolean = isLetterOrDigit() || this == '_' || this == '$'
