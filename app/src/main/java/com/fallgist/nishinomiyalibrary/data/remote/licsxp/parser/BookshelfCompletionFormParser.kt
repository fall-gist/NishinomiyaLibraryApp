package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** UPDATE確定後に実測された、表示画面へ遷移する一回限りのPOSTを検証する。 */
internal object BookshelfCompletionFormParser {
    private const val screen = "bookshelf-completion"
    private const val displayAction = "/licsxp-opac/WOpacSdiBookListDispAction.do"
    private val prefixNames = listOf("hash", "returnid", "gamenid", "tilcod", "dispflg", "otherbook", "listname", "commnt")
    private val rowNames = listOf("bookcmnt", "eachcmnt", "sortno", "eachsortno")

    fun parse(html: String, expectedStage2Fields: List<BookshelfFormField>): BookshelfCompletionForm {
        if (!matchesFormContract(expectedStage2Fields)) {
            throw ParseException(screen, "2段階目のUPDATE項目が第3POSTの実測契約と一致しません")
        }
        val document = Jsoup.parse(html)
        val forms = document.select("form[name=prevRequestForm]")
        if (forms.size != 1) throw ParseException(screen, "prevRequestFormを一意に特定できません")
        val form = forms.single()
        if (form.hasAttr("action")) throw ParseException(screen, "prevRequestFormのHTML actionは受理しません")
        val fields = form.bookshelfFields()
        if (!matchesFormContract(fields) || !matchesStage2Fields(fields, expectedStage2Fields)) {
            throw ParseException(screen, "第3POSTフォームが2段階目送信内容または実測契約と一致しません")
        }
        if (!hasOnlyExpectedSubmit(document.select("script"))) {
            throw ParseException(screen, "表示遷移scriptが実測契約と一致しません")
        }
        return BookshelfCompletionForm(fields)
    }

    /**
     * prefix8 + 資料行4項目(N件) + okCodes の実測契約。N=0の空棚も受理する。
     * 異なる項目名の並びはサイト側で変化し得るが、許可するのはその並び替えだけである。
     */
    private fun matchesFormContract(fields: List<BookshelfFormField>): Boolean {
        if (fields.size < prefixNames.size + 1 || fields.take(prefixNames.size).map(BookshelfFormField::name) != prefixNames ||
            fields.last() != BookshelfFormField("okCodes", "OPACSDI011")
        ) return false
        val rowFields = fields.subList(prefixNames.size, fields.lastIndex)
        if (rowFields.any { it.name !in rowNames }) return false
        val counts = rowNames.map { name -> rowFields.count { it.name == name } }
        val itemCount = counts.firstOrNull() ?: return false
        return counts.all { it == itemCount }
    }

    /**
     * 確認フォームと同じく、項目名ごとの値列は完全一致させる。
     * よって未知項目、項目の追加・削除、同名項目内の値改変または並び替えは受理しない。
     */
    private fun matchesStage2Fields(
        fields: List<BookshelfFormField>,
        expectedStage2Fields: List<BookshelfFormField>,
    ): Boolean = fields.valuesByName() == expectedStage2Fields.valuesByName()

    private fun List<BookshelfFormField>.valuesByName(): Map<String, List<String>> =
        groupBy({ it.name }, { it.value })

    /**
     * 共通scriptは無関係なら許容する。対象のnamed関数とonload接続は全script中でそれぞれ一意で、
     * その無属性inline scriptのトップレベルは指定の定義と接続だけに固定する。関数本体の画面表示等は
     * 実行しないため許容する一方、prevRequestFormの参照は指定の代入・submitだけへ完全に限定する。
     */
    private fun hasOnlyExpectedSubmit(scripts: List<Element>): Boolean {
        val candidates = scripts.mapNotNull { script ->
            val source = script.data()
            // 実サイト共通scriptは解析対象外。対象識別子を含むものだけを字句検証する。
            if (!source.contains("createConfirmDialog")) return@mapNotNull null
            val code = maskNonCode(source) ?: return false
            val functions = codeMatches(source, code, functionPattern)
            val onloads = codeMatches(source, code, onloadPattern)
            ScriptCandidate(script, source, code, functions, onloads)
        }
        val functionCandidates = candidates.flatMap { candidate -> candidate.functions.map { match -> candidate to match } }
        val onloadCandidates = candidates.flatMap { candidate -> candidate.onloads.map { match -> candidate to match } }
        if (functionCandidates.size != 1 || onloadCandidates.size != 1) return false
        val (candidate, function) = functionCandidates.single()
        val (onloadCandidate, onload) = onloadCandidates.single()
        if (candidate !== onloadCandidate || candidate.script.attributes().asList().isNotEmpty()) return false
        if (braceDepth(candidate.code, function.range.first) != 0 || braceDepth(candidate.code, onload.range.first) != 0) return false
        val bodyEnd = matchingBrace(candidate.code, function.range.last) ?: return false
        if (onload.range.first <= bodyEnd) return false
        val bodyStart = function.range.last + 1
        val bodySource = candidate.source.substring(bodyStart, bodyEnd)
        val bodyCode = candidate.code.substring(bodyStart, bodyEnd)
        val action = codeMatches(bodySource, bodyCode, actionPattern).singleOrNull() ?: return false
        val submit = codeMatches(bodySource, bodyCode, submitPattern).singleOrNull() ?: return false
        if (action.groupValues[1] != displayAction || action.range.first >= submit.range.first) return false
        if (braceDepth(bodyCode, action.range.first) != 0 || braceDepth(bodyCode, submit.range.first) != 0) return false
        val references = codeReferences(bodySource, bodyCode)
        if (references.size != 2 ||
            references[0].range.first != action.range.first ||
            references[1].range.first != submit.range.first
        ) return false

        val topLevel = candidate.code.toCharArray()
        erase(topLevel, function.range.first, bodyEnd + 1)
        erase(topLevel, onload.range.first, onload.range.last + 1)
        return topLevel.all(Char::isWhitespace)
    }

    private fun erase(chars: CharArray, start: Int, endExclusive: Int) {
        for (index in start until endExclusive) chars[index] = ' '
    }

    private fun codeMatches(source: String, code: String, pattern: Regex): List<MatchResult> =
        pattern.findAll(source).filter { match -> codeStart(code, match.range.first) }.toList()

    /** 接頭辞付き参照も含め、コード上の全prevRequestForm参照を拾う。 */
    private fun codeReferences(source: String, code: String): List<MatchResult> =
        prevRequestFormReference.findAll(source).filter { match ->
            match.range.first in code.indices && code[match.range.first] != ' '
        }.toList()

    /** 非コード部分を空白化して位置を保持する。template literal・未閉鎖構文はfail-closedで拒否する。 */
    private fun maskNonCode(source: String): String? {
        val masked = source.toCharArray()
        fun hide(from: Int, until: Int) { for (index in from until until) masked[index] = ' ' }
        var index = 0
        var regexAllowed = true
        val parentheses = ArrayDeque<Boolean>()
        var controlConditionAwaitingParenthesis = false
        while (index < source.length) when {
            source.startsWith("//", index) -> {
                val end = source.indexOf('\n', index + 2).let { if (it < 0) source.length else it }
                hide(index, end); index = end
            }
            source.startsWith("/*", index) -> {
                val end = source.indexOf("*/", index + 2)
                if (end < 0) return null
                hide(index, end + 2); index = end + 2
            }
            source[index] in "'\"" -> {
                val end = quotedEnd(source, index) ?: return null
                hide(index, end); index = end; regexAllowed = false
            }
            source[index] == '`' -> return null
            source[index] == '/' && regexAllowed -> {
                val end = regexEnd(source, index) ?: return null
                hide(index, end); index = end; regexAllowed = false
            }
            source[index].isLetterOrDigit() || source[index] in "_$" -> {
                val tokenStart = index
                while (index < source.length && (source[index].isLetterOrDigit() || source[index] in "_$")) index++
                val token = source.substring(tokenStart, index)
                controlConditionAwaitingParenthesis = token in controlConditionKeywords
                regexAllowed = token in expressionPrefixKeywords
            }
            source[index].isWhitespace() -> index++
            else -> when (source[index]) {
                '(' -> {
                    parentheses.addLast(controlConditionAwaitingParenthesis)
                    controlConditionAwaitingParenthesis = false
                    regexAllowed = true
                    index++
                }
                ')' -> {
                    regexAllowed = parentheses.removeLastOrNull() ?: return null
                    index++
                }
                '[', '{' -> { regexAllowed = true; index++ }
                ']', '}' -> { regexAllowed = source[index] == '}'; index++ }
                ';', ',', ':', '?', '=', '!', '&', '|', '+', '-', '*', '%', '~' -> {
                    regexAllowed = true
                    controlConditionAwaitingParenthesis = false
                    index++
                }
                '.' -> {
                    regexAllowed = false
                    controlConditionAwaitingParenthesis = false
                    index++
                }
                else -> {
                    regexAllowed = false
                    controlConditionAwaitingParenthesis = false
                    index++
                }
            }
        }
        return String(masked)
    }

    private fun quotedEnd(source: String, start: Int): Int? {
        val quote = source[start]; var index = start + 1
        while (index < source.length) when (source[index]) {
            '\\' -> index += 2
            quote -> return index + 1
            '\n', '\r' -> return null
            else -> index++
        }
        return null
    }

    private fun regexEnd(source: String, start: Int): Int? {
        var index = start + 1; var bracket = false
        while (index < source.length) when (source[index]) {
            '\\' -> index += 2
            '[' -> { bracket = true; index++ }
            ']' -> { bracket = false; index++ }
            '/' -> if (!bracket) { index++; while (index < source.length && source[index].isLetter()) index++; return index } else index++
            '\n', '\r' -> return null
            else -> index++
        }
        return null
    }

    private fun codeStart(code: String, start: Int): Boolean {
        if (start !in code.indices || code[start] == ' ') return false
        var index = start - 1
        while (index >= 0 && code[index].isWhitespace()) index--
        return index < 0 || code[index] != '.'
    }
    private fun braceDepth(code: String, target: Int): Int? {
        var depth = 0
        for (index in 0 until target) when (code[index]) { '{' -> depth++; '}' -> if (--depth < 0) return null }
        return depth
    }
    private fun matchingBrace(code: String, start: Int): Int? {
        var depth = 0
        for (index in start until code.length) when (code[index]) { '{' -> depth++; '}' -> if (--depth == 0) return index }
        return null
    }

    private data class ScriptCandidate(
        val script: Element,
        val source: String,
        val code: String,
        val functions: List<MatchResult>,
        val onloads: List<MatchResult>,
    )

    private val functionPattern = Regex("\\bfunction\\s+createConfirmDialog\\s*\\(\\s*\\)\\s*\\{")
    private val onloadPattern = Regex("\\bwindow\\s*\\.\\s*onload\\s*=\\s*createConfirmDialog\\s*;")
    private val actionPattern = Regex("\\bdocument\\s*\\.\\s*prevRequestForm\\s*\\.\\s*action\\s*=\\s*['\"]([^'\"]+)['\"]\\s*;")
    private val submitPattern = Regex("\\bdocument\\s*\\.\\s*prevRequestForm\\s*\\.\\s*submit\\s*\\(\\s*\\)\\s*;")
    private val prevRequestFormReference = Regex("\\bdocument\\s*\\.\\s*prevRequestForm\\b")
    private val controlConditionKeywords = setOf("if", "while", "for", "with", "switch", "catch")
    private val expressionPrefixKeywords = setOf(
        "return", "throw", "case", "delete", "typeof", "void", "new", "in", "of", "yield", "await", "else", "do", "try", "finally",
        "break", "continue", "debugger",
    )
}

internal class BookshelfCompletionForm(private val fields: List<BookshelfFormField>) {
    fun buildForm(): FormBody = fields.toFormBody()
}
