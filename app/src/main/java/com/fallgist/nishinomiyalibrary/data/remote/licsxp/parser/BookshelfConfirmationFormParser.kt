package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import okhttp3.FormBody
import org.jsoup.Jsoup

/** 本棚の二段階操作専用。確認ページの実行時 JavaScript 構造まで検証してから確定送信を許可する。 */
internal object BookshelfConfirmationFormParser {
    private const val screen = "bookshelf-confirmation"
    private val contracts = mapOf(
        BookshelfConfirmationKind.CREATE to Contract("OPACSDI017", "WOpacSdiBookListExecAction.do"),
        BookshelfConfirmationKind.UPDATE to Contract("OPACSDI011", "WOpacSdiBookListUpdateAction.do"),
        BookshelfConfirmationKind.DELETE_ITEM to Contract("OPACSDI033", "WOpacSdiBookDelAction.do"),
        BookshelfConfirmationKind.DELETE_SHELF to Contract("OPACSDI010", "WOpacSdiBookListDelAction.do"),
    )

    fun parse(html: String, expectedStage1Fields: List<BookshelfFormField>, kind: BookshelfConfirmationKind): BookshelfConfirmationForm {
        val document = Jsoup.parse(html)
        val forms = document.select("form[name=prevRequestForm]")
        if (forms.size != 1) throw ParseException(screen, "prevRequestFormを一意に特定できません")
        val form = forms.single()
        if (form.hasAttr("action")) throw ParseException(screen, "prevRequestFormのHTML actionは受理しません")
        val fields = form.bookshelfFields()
        if (!matchesStage1Fields(fields, expectedStage1Fields)) throw ParseException(screen, "prevRequestFormが1段階目送信内容と一致しません")

        val contract = contracts.getValue(kind)
        val signatures = document.select("script")
            .filter { !it.hasAttr("src") && (!it.hasAttr("type") || it.attr("type").trim().lowercase() in javascriptMimeTypes) }
            .flatMap { script -> buildList {
                parseNamedSignature(script.data(), contract)?.let(::add)
                if (kind == BookshelfConfirmationKind.UPDATE) parseInlineUpdateSignature(script.data(), contract)?.let(::add)
            } }
        val signature = signatures.singleOrNull() ?: throw ParseException(screen, "確認送信scriptを一意に特定できません")
        if (fields.any { it.name == signature.okName }) throw ParseException(screen, "OK_CODES_NAMEが既存項目と衝突します")
        return BookshelfConfirmationForm(signature.action, fields, signature.okName, contract.code)
    }

    /**
     * 確認画面では異なる項目名どうしだけがサーバー側で並び替えられることがある。
     * 項目名の集合、各項目名の出現数、同名項目の値列は完全一致させるため、
     * 未知項目・追加削除・値改変・同名項目内の並び替えは受理しない。
     */
    private fun matchesStage1Fields(
        fields: List<BookshelfFormField>,
        expectedStage1Fields: List<BookshelfFormField>,
    ): Boolean = fields.valuesByName() == expectedStage1Fields.valuesByName()

    private fun List<BookshelfFormField>.valuesByName(): Map<String, List<String>> =
        groupBy({ it.name }, { it.value })

    /**
     * 実際に onload から起動される単一関数の内部だけを受理する。
     * `okArray.length` への唯一の追加と、同じ配列を走査するhidden生成ループの連鎖を静的に検証する。
     */
    private fun parseNamedSignature(script: String, contract: Contract): Signature? {
        val function = oneCodeMatch(script, createConfirmDialogFunction) ?: return null
        if (braceDepthAt(script, function.range.first) != 0) return null
        val bodyStart = function.range.last
        val bodyEnd = bodyStart.takeIf { it >= 0 }?.let { matchingBrace(script, it) } ?: return null
        val body = script.substring(bodyStart + 1, bodyEnd)
        val onload = codeMatches(script, windowOnload)
        if (onload.size != 1 || onload.single().range.first <= bodyEnd || braceDepthAt(script, onload.single().range.first) != 0) return null
        val okName = oneCodeMatch(script, okNameAssignment)?.groupValues?.get(1) ?: return null
        val code = oneCodeMatch(body, okCodeAssignment)?.groupValues?.get(1) ?: return null
        if (code != contract.code || codeMatches(script, okCodeAssignment).size != 1) return null
        val action = oneCodeMatch(body, actionAssignment)?.groupValues?.get(1) ?: return null
        if (!isExpectedAction(action, contract)) return null

        // 実測済みの共通 createConfirmDialog が、確認コードを for ループで hidden に転記する。
        // 末尾要素を直接代入する簡略形や、コード連鎖外の似た断片は受理しない。
        val loop = oneCodeMatch(body, linkedOkCodesLoop) ?: return null
        val submit = codeMatches(body, submitCall)
        val declaration = codeMatches(body, okArrayDeclaration)
        if (codeMatches(body, Regex("\\bOK_CODES_NAME\\b")).size != 1 || submit.size != 1 || declaration.size != 1 ||
            !hasOnlyLinkedOkArrayUses(body)
        ) return null
        if (braceDepthAt(script, oneCodeMatch(script, okNameAssignment)?.range?.first ?: return null) != 0 ||
            !allMatchesAreInside(bodyStart + 1, bodyEnd, script, okArrayDeclaration, linkedOkCodesLoop, actionAssignment, submitCall)
        ) return null
        val sequence = listOf(
                declaration.single().range.first, codeMatches(body, okCodeAssignment).single().range.first, loop.range.first,
                oneCodeMatch(body, actionAssignment)?.range?.first ?: return null, submit.single().range.first,
            )
        if (braceDepthAt(body, declaration.single().range.first) != 0 ||
            braceDepthAt(body, loop.range.first) != 0 ||
            braceDepthAt(body, oneCodeMatch(body, actionAssignment)?.range?.first ?: return null) != 0 ||
            braceDepthAt(body, submit.single().range.first) != 0 ||
            sequence != listOf(
                declaration.single().range.first, codeMatches(body, okCodeAssignment).single().range.first, loop.range.first,
                oneCodeMatch(body, actionAssignment)?.range?.first ?: return null, submit.single().range.first,
            ).sorted()
        ) return null
        return Signature(okName, contract.action)
    }

    /**
     * UPDATE画面専用の、関数・onloadを介さないトップレベル送信形式を受理する。
     * JSは実行せず肯定時のPOSTだけを再構成するため、cancelArray・else・肯定分岐外の画面内フラグは対象外とする。
     * ただし、肯定分岐の到達性は実測形状を検証する。
     */
    private fun parseInlineUpdateSignature(script: String, contract: Contract): Signature? {
        if (contract.code != "OPACSDI011" ||
            codeMatches(script, createConfirmDialogFunction).isNotEmpty() ||
            codeMatches(script, windowOnload).isNotEmpty()
        ) return null

        val okNameMatch = oneCodeMatch(script, okNameAssignment) ?: return null
        val declaration = oneCodeMatch(script, okArrayDeclaration) ?: return null
        val codeMatch = oneCodeMatch(script, okCodeAssignment) ?: return null
        val loop = oneCodeMatch(script, linkedOkCodesLoop) ?: return null
        val actionMatch = oneCodeMatch(script, actionAssignment) ?: return null
        val submit = oneCodeMatch(script, submitCall) ?: return null
        val action = actionMatch.groupValues[1]
        if (!isExpectedAction(action, contract) ||
            codeMatch.groupValues[1] != contract.code ||
            codeMatches(script, Regex("\\bOK_CODES_NAME\\b")).size != 2 ||
            !hasOnlyLinkedOkArrayUses(script)
        ) return null

        val restIf = oneCodeMatch(script, topLevelRestIf) ?: return null
        val restIfEnd = matchingBrace(script, restIf.range.last) ?: return null
        val positiveBranchStart = skipIgnorable(script, restIf.range.last + 1, restIfEnd) ?: return null
        if (codeMatch.range.first != positiveBranchStart) return null
        val submitFlgStart = skipIgnorable(script, codeMatch.range.last + 1, restIfEnd) ?: return null
        val submitFlg = codeMatches(script, submitFlgFalseAssignment)
            .singleOrNull { it.range.first == submitFlgStart } ?: return null
        val topLevel = listOf(okNameMatch, declaration, restIf, loop, actionMatch, submit)
        if (topLevel.any { braceDepthAt(script, it.range.first) != 0 } ||
            braceDepthAt(script, codeMatch.range.first) != 1 ||
            skipIgnorable(script, submitFlg.range.last + 1, restIfEnd) != restIfEnd ||
            listOf(okNameMatch, declaration, restIf, codeMatch, loop, actionMatch, submit).map { it.range.first } !=
                listOf(okNameMatch, declaration, restIf, codeMatch, loop, actionMatch, submit).map { it.range.first }.sorted()
        ) return null
        return Signature(okNameMatch.groupValues[1], contract.action)
    }

    /** 指定範囲の空白とコメントを読み飛ばす。未閉鎖コメントは不正なscriptとして拒否する。 */
    private fun skipIgnorable(source: String, start: Int, end: Int): Int? {
        var index = start
        while (index < end) when {
            source[index].isWhitespace() -> index++
            source.startsWith("//", index) -> {
                val lineEnd = source.indexOf('\n', index + 2)
                index = if (lineEnd < 0) end else lineEnd + 1
            }
            source.startsWith("/*", index) -> {
                val commentEnd = source.indexOf("*/", index + 2)
                if (commentEnd < 0 || commentEnd + 2 > end) return null
                index = commentEnd + 2
            }
            else -> return index
        }
        return index
    }

    private fun allMatchesAreInside(
        bodyStart: Int,
        bodyEnd: Int,
        script: String,
        vararg patterns: Regex,
    ): Boolean = patterns.all { pattern ->
        codeMatches(script, pattern).all { it.range.first in bodyStart until bodyEnd }
    }

    /** 固定契約の相対パスか、同一 origin のアプリケーション絶対パスだけを受理する。 */
    private fun isExpectedAction(action: String, contract: Contract): Boolean =
        action == contract.action || action == "/licsxp-opac/${contract.action}"

    /** 宣言、唯一の追加、同じ末尾要素の読取り以外の okArray 利用は変異を否定できないため拒否する。 */
    private fun hasOnlyLinkedOkArrayUses(body: String): Boolean = codeMatches(body, Regex("\\bokArray\\b")).size == 5

    private fun oneCodeMatch(source: String, pattern: Regex): MatchResult? = codeMatches(source, pattern).singleOrNull()
    private fun codeMatches(source: String, pattern: Regex): List<MatchResult> {
        val starts = codeStarts(source)
        return pattern.findAll(source).filter { match ->
            match.range.first in starts && isStandaloneIdentifierStart(source, match.range.first)
        }.toList()
    }

    /** 正規表現の語境界だけでは許してしまう `object.identifier` を候補から除外する。 */
    private fun isStandaloneIdentifierStart(source: String, start: Int): Boolean {
        var index = start - 1
        while (index >= 0) {
            var crossedLineBreak = false
            while (index >= 0 && source[index].isWhitespace()) {
                crossedLineBreak = crossedLineBreak || source[index] == '\n' || source[index] == '\r'
                index--
            }
            if (index < 0) return true
            if (index >= 1 && source[index - 1] == '*' && source[index] == '/') {
                val commentStart = source.lastIndexOf("/*", index - 1)
                if (commentStart < 0) return false
                index = commentStart - 1
                continue
            }
            if (crossedLineBreak) {
                val lineStart = maxOf(source.lastIndexOf('\n', index), source.lastIndexOf('\r', index)) + 1
                val commentStart = lineCommentStart(source, lineStart, index)
                if (commentStart != null) {
                    index = commentStart - 1
                    continue
                }
            }
            return source[index] != '.'
        }
        return true
    }

    private fun lineCommentStart(source: String, lineStart: Int, lineEnd: Int): Int? {
        var index = lineStart
        while (index <= lineEnd) when {
            source.startsWith("//", index) -> return index
            source.startsWith("/*", index) -> {
                val end = source.indexOf("*/", index + 2)
                if (end < 0 || end > lineEnd) return null
                index = end + 2
            }
            source[index] in "'\"" -> index = skipQuoted(source, index) ?: return null
            else -> index++
        }
        return null
    }

    /** コメント、文字列、template literal、正規表現内部を候補にしない最小字句走査。 */
    private fun codeStarts(source: String): Set<Int> {
        val result = mutableSetOf<Int>()
        val parentheses = ArrayDeque<Boolean>()
        var i = 0
        var regexAllowed = true
        var controlConditionAwaitingParenthesis = false
        while (i < source.length) when {
            source.startsWith("//", i) -> i = source.indexOf('\n', i).let { if (it < 0) source.length else it + 1 }
            source.startsWith("/*", i) -> { val end = source.indexOf("*/", i + 2); if (end < 0) return emptySet(); i = end + 2 }
            source[i] in "'\"" -> { i = skipQuoted(source, i) ?: return emptySet(); regexAllowed = false }
            source[i] == '`' -> return emptySet()
            source[i] == '/' && regexAllowed -> { i = skipRegex(source, i) ?: return emptySet(); regexAllowed = false }
            source[i].isLetter() || source[i] == '_' || source[i] == '$' -> {
                val tokenStart = i
                result += i; while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_' || source[i] == '$')) i++
                val token = source.substring(tokenStart, i)
                controlConditionAwaitingParenthesis = token in controlConditionKeywords
                regexAllowed = token in expressionPrefixKeywords
            }
            source[i].isWhitespace() -> i++
            else -> when (source[i]) {
                '(' -> {
                    parentheses.addLast(controlConditionAwaitingParenthesis)
                    controlConditionAwaitingParenthesis = false
                    regexAllowed = true
                    i++
                }
                ')' -> {
                    regexAllowed = parentheses.removeLastOrNull() ?: return emptySet()
                    i++
                }
                '[', '{' -> { regexAllowed = true; i++ }
                ']', '}' -> { regexAllowed = source[i] == '}'; i++ }
                ';', ',', ':', '?', '=', '!', '&', '|', '+', '-', '*', '%', '~' -> {
                    regexAllowed = true
                    controlConditionAwaitingParenthesis = false
                    i++
                }
                '.' -> {
                    regexAllowed = false
                    controlConditionAwaitingParenthesis = false
                    i++
                }
                else -> {
                    regexAllowed = false
                    controlConditionAwaitingParenthesis = false
                    i++
                }
            }
        }
        return result
    }
    private fun skipQuoted(source: String, start: Int): Int? { val quote = source[start]; var i = start + 1; while (i < source.length) when (source[i]) { '\\' -> i += 2; quote -> return i + 1; '\n', '\r' -> return null; else -> i++ }; return null }
    private fun skipRegex(source: String, start: Int): Int? { var i = start + 1; var bracket = false; while (i < source.length) when (source[i]) { '\\' -> i += 2; '[' -> { bracket = true; i++ }; ']' -> { bracket = false; i++ }; '/' -> if (!bracket) { i++; while (i < source.length && source[i].isLetter()) i++; return i } else i++; '\n', '\r' -> return null; else -> i++ }; return null }
    private fun matchingBrace(source: String, start: Int): Int? { var depth = 0; val parentheses = ArrayDeque<Boolean>(); var i = start; var regexAllowed = true; var controlConditionAwaitingParenthesis = false; while (i < source.length) when {
        source.startsWith("//", i) -> i = source.indexOf('\n', i).let { if (it < 0) source.length else it + 1 }
        source.startsWith("/*", i) -> { val end = source.indexOf("*/", i + 2); if (end < 0) return null; i = end + 2 }
        source[i] in "'\"" -> { i = skipQuoted(source, i) ?: return null; regexAllowed = false }
        source[i] == '`' -> return null
        source[i] == '/' && regexAllowed -> { i = skipRegex(source, i) ?: return null; regexAllowed = false }
        source[i] == '{' -> { depth++; i++; regexAllowed = true }
        source[i] == '}' -> { if (--depth == 0) return i; i++; regexAllowed = true }
        source[i].isLetter() || source[i] == '_' || source[i] == '$' -> { val tokenStart = i; while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_' || source[i] == '$')) i++; val token = source.substring(tokenStart, i); controlConditionAwaitingParenthesis = token in controlConditionKeywords; regexAllowed = token in expressionPrefixKeywords }
        source[i].isWhitespace() -> i++
        else -> when (source[i]) {
            '(' -> { parentheses.addLast(controlConditionAwaitingParenthesis); controlConditionAwaitingParenthesis = false; regexAllowed = true; i++ }
            ')' -> { regexAllowed = parentheses.removeLastOrNull() ?: return null; i++ }
            '[', '{' -> { regexAllowed = true; i++ }
            ']', '}' -> { regexAllowed = source[i] == '}'; i++ }
            ';', ',', ':', '?', '=', '!', '&', '|', '+', '-', '*', '%', '~' -> { regexAllowed = true; controlConditionAwaitingParenthesis = false; i++ }
            '.' -> { regexAllowed = false; controlConditionAwaitingParenthesis = false; i++ }
            else -> { regexAllowed = false; controlConditionAwaitingParenthesis = false; i++ }
        }
    }; return null }

    /** 候補が関数や条件分岐に埋め込まれていないことを確認するための字句走査。 */
    private fun braceDepthAt(source: String, target: Int): Int? { var depth = 0; val parentheses = ArrayDeque<Boolean>(); var i = 0; var regexAllowed = true; var controlConditionAwaitingParenthesis = false; while (i < target) when {
        source.startsWith("//", i) -> i = source.indexOf('\n', i).let { if (it < 0) source.length else it + 1 }
        source.startsWith("/*", i) -> { val end = source.indexOf("*/", i + 2); if (end < 0) return null; i = end + 2 }
        source[i] in "'\"" -> { i = skipQuoted(source, i) ?: return null; regexAllowed = false }
        source[i] == '`' -> return null
        source[i] == '/' && regexAllowed -> { i = skipRegex(source, i) ?: return null; regexAllowed = false }
        source[i] == '{' -> { depth++; i++; regexAllowed = true }
        source[i] == '}' -> { if (--depth < 0) return null; i++; regexAllowed = true }
        source[i].isLetter() || source[i] == '_' || source[i] == '$' -> { val tokenStart = i; while (i < target && (source[i].isLetterOrDigit() || source[i] == '_' || source[i] == '$')) i++; val token = source.substring(tokenStart, i); controlConditionAwaitingParenthesis = token in controlConditionKeywords; regexAllowed = token in expressionPrefixKeywords }
        source[i].isWhitespace() -> i++
        else -> when (source[i]) {
            '(' -> { parentheses.addLast(controlConditionAwaitingParenthesis); controlConditionAwaitingParenthesis = false; regexAllowed = true; i++ }
            ')' -> { regexAllowed = parentheses.removeLastOrNull() ?: return null; i++ }
            '[', '{' -> { regexAllowed = true; i++ }
            ']', '}' -> { regexAllowed = source[i] == '}'; i++ }
            ';', ',', ':', '?', '=', '!', '&', '|', '+', '-', '*', '%', '~' -> { regexAllowed = true; controlConditionAwaitingParenthesis = false; i++ }
            '.' -> { regexAllowed = false; controlConditionAwaitingParenthesis = false; i++ }
            else -> { regexAllowed = false; controlConditionAwaitingParenthesis = false; i++ }
        }
    }; return depth }

    private data class Contract(val code: String, val action: String)
    private data class Signature(val okName: String, val action: String)
    private val windowOnload = Regex("\\bwindow\\s*\\.\\s*onload\\s*=\\s*createConfirmDialog\\s*;")
    private val okNameAssignment = Regex("\\bOK_CODES_NAME\\s*=\\s*['\"]([A-Za-z][A-Za-z0-9_]*)['\"]\\s*;")
    private val okCodeAssignment = Regex("\\bokArray\\s*\\[\\s*okArray\\s*\\.\\s*length\\s*]\\s*=\\s*['\"]([^'\"]+)['\"]\\s*;")
    private val actionAssignment = Regex("\\bdocument\\s*\\.\\s*prevRequestForm\\s*\\.\\s*action\\s*=\\s*['\"]([^'\"]+)['\"]\\s*;")
    private val linkedOkCodesLoop = Regex("""\bfor\s*\(\s*var\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*0\s*;\s*\1\s*<\s*okArray\s*\.\s*length\s*;\s*\1\s*\+\+\s*\)\s*\{\s*(?:var\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*document\s*\.\s*createElement\s*\(\s*['\"]input['\"]\s*\)\s*;\s*\2\s*\.\s*type\s*=\s*['\"]hidden['\"]\s*;\s*\2\s*\.\s*name\s*=\s*OK_CODES_NAME\s*;\s*\2\s*\.\s*value\s*=\s*okArray\s*\[\s*\1\s*]\s*;\s*document\s*\.\s*prevRequestForm\s*\.\s*appendChild\s*\(\s*\2\s*\)\s*;\s*\}""")
    private val submitCall = Regex("\\bdocument\\s*\\.\\s*prevRequestForm\\s*\\.\\s*submit\\s*\\(\\s*\\)\\s*;")
    private val createConfirmDialogFunction = Regex("\\bfunction\\s+createConfirmDialog\\s*\\(\\s*\\)\\s*\\{")
    private val okArrayDeclaration = Regex("\\bvar\\s+okArray\\s*=\\s*(?:\\[\\s*]|new\\s+Array\\s*\\(\\s*\\))\\s*;")
    private val topLevelRestIf = Regex("\\bif\\s*\\(\\s*rest\\s*\\)\\s*\\{")
    private val submitFlgFalseAssignment = Regex("\\bsubmitFlg\\s*=\\s*false\\s*;")
    private val javascriptMimeTypes = setOf("text/javascript", "application/javascript", "text/ecmascript", "application/ecmascript")
    private val controlConditionKeywords = setOf("if", "while", "for", "with", "switch", "catch")
    private val expressionPrefixKeywords = setOf(
        "return", "throw", "case", "delete", "typeof", "void", "new", "in", "of", "yield", "await", "else", "do", "try", "finally",
        "break", "continue", "debugger",
    )
}

internal enum class BookshelfConfirmationKind { CREATE, UPDATE, DELETE_ITEM, DELETE_SHELF }

internal class BookshelfConfirmationForm(val action: String, private val fields: List<BookshelfFormField>, private val okName: String, private val code: String) {
    fun fieldsWithConfirmationCode(): List<BookshelfFormField> = fields + BookshelfFormField(okName, code)

    fun buildForm(): FormBody = fields.toFormBody().let { body ->
        FormBody.Builder().apply { for (i in 0 until body.size) add(body.name(i), body.value(i)); add(okName, code) }.build()
    }
}
