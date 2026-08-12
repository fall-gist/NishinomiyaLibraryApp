package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** UPDATE確定後に実測された、表示画面へ遷移する一回限りのPOSTを検証する。 */
internal object BookshelfCompletionFormParser {
    private const val screen = "bookshelf-completion"
    private const val displayAction = "/licsxp-opac/WOpacSdiBookListDispAction.do"
    private const val redirectName = "jp.co.necsoft.licsxp.base.util.validation.MessageUtil.CONFIRM_DIALOG_SEND_REDIRECT"
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
        if (fields.any { it.name == redirectName }) {
            throw ParseException(screen, "redirect項目がDOMフォームに含まれています")
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
        if (hasUnconditionalControlFlow(bodyCode)) return false
        val redirectDeclaration = codeMatches(candidate.source, candidate.code, redirectDeclarationPattern)
            .filter { braceDepth(candidate.code, it.range.first) == 0 }
            .singleOrNull() ?: return false
        if (redirectDeclaration.groupValues[2] != redirectName) return false
        val redirectNameDeclarationOffset = redirectDeclaration.range.first +
            redirectDeclaration.value.indexOf("CONFIRM_DIALOG_SEND_REDIRECT_NAME")
        val input = directMatches(bodySource, bodyCode, inputPattern).singleOrNull() ?: return false
        val inputName = input.groupValues[1]
        if (directMatches(bodySource, bodyCode, propertyAssignmentPattern(inputName, "type")).size != 1 ||
            directMatches(bodySource, bodyCode, propertyAssignmentPattern(inputName, "name")).size != 1 ||
            directMatches(bodySource, bodyCode, propertyAssignmentPattern(inputName, "value")).size != 1
        ) return false
        val type = directMatches(bodySource, bodyCode, propertyPattern(inputName, "type", "hidden")).singleOrNull() ?: return false
        val name = directMatches(bodySource, bodyCode, propertyVariablePattern(inputName, "name", "CONFIRM_DIALOG_SEND_REDIRECT_NAME")).singleOrNull() ?: return false
        val value = directMatches(bodySource, bodyCode, propertyPattern(inputName, "value", "true")).singleOrNull() ?: return false
        val append = directMatches(bodySource, bodyCode, appendPattern(inputName)).singleOrNull() ?: return false
        val redirectNameReferenceOffset = bodyStart + name.range.first +
            bodySource.substring(name.range).indexOf("CONFIRM_DIALOG_SEND_REDIRECT_NAME")
        if (!hasOnlyExpectedRedirectNameReferences(
                scripts = scripts,
                candidate = candidate,
                declarationOffset = redirectNameDeclarationOffset,
                inputNameReferenceOffset = redirectNameReferenceOffset,
            )
        ) return false
        val action = codeMatches(bodySource, bodyCode, actionPattern).singleOrNull() ?: return false
        val submit = codeMatches(bodySource, bodyCode, submitPattern).singleOrNull() ?: return false
        if (braceDepth(bodyCode, action.range.first) != 0 || braceDepth(bodyCode, submit.range.first) != 0) return false
        if (action.groupValues[1] != displayAction || append.range.first >= action.range.first ||
            !isStrictRedirectStatementSequence(bodyCode, input, type, name, value, append) ||
            !isStrictRedirectStatementSequence(bodyCode, action, submit)
        ) return false
        val allowedInputReferenceRanges = listOf(input.range, type.range, name.range, value.range, append.range)
        if (codeMatches(bodySource, bodyCode, identifierPattern(inputName)).any { reference ->
                allowedInputReferenceRanges.none { range -> reference.range.first in range }
            }
        ) return false
        if (codeOnlyMatches(bodyCode, propertyWritePattern(inputName)).size != 3) return false

        val canonicalSubmitOffset = bodyStart + submit.range.first +
            (anySubmitPattern.find(submit.value)?.range?.first ?: return false)
        if (!hasOnlyCanonicalSubmit(scripts, candidate, canonicalSubmitOffset)) return false
        if (!hasNoPrevRequestFormEscapeHatches(scripts)) return false
        val allowedReferences = codeMatches(bodySource, bodyCode, appendAnyPattern).map { bodyStart + it.range.first }.toSet() +
            setOf(bodyStart + action.range.first, bodyStart + submit.range.first)
        return hasOnlyExpectedPrevRequestFormReferences(scripts, candidate, allowedReferences)
    }

    /** 固定redirect名は宣言とredirect inputのname代入以外では参照させず、実行時の変異経路を閉じる。 */
    private fun hasOnlyExpectedRedirectNameReferences(
        scripts: List<Element>,
        candidate: ScriptCandidate,
        declarationOffset: Int,
        inputNameReferenceOffset: Int,
    ): Boolean = scripts.all { script ->
        val source = script.data()
        val code = if (script === candidate.script) candidate.code else maskNonCode(source) ?: return false
        if (rawCodeMatches(source, code, globalBracketIdentifierPattern("CONFIRM_DIALOG_SEND_REDIRECT_NAME")).isNotEmpty()) return false
        identifierPattern("CONFIRM_DIALOG_SEND_REDIRECT_NAME").findAll(code).all { reference ->
            script === candidate.script && reference.range.first in setOf(declarationOffset, inputNameReferenceOffset)
        }
    }

    /** document["prevRequestForm"]を含む別表記は受理せず、正規のdot参照だけを厳密に照合する。 */
    private fun hasOnlyExpectedPrevRequestFormReferences(
        scripts: List<Element>,
        candidate: ScriptCandidate,
        allowedReferenceOffsets: Set<Int>,
    ): Boolean = scripts.all { script ->
        val source = script.data()
        val code = if (script === candidate.script) candidate.code else maskNonCode(source) ?: return false
        if (rawCodeMatches(source, code, prevRequestFormBracketReference).isNotEmpty()) return false
        codeReferences(source, code).all { reference ->
            script === candidate.script && reference.range.first in allowedReferenceOffsets
        }
    }

    /** redirect生成連鎖内のreturn/throwは、送信に到達しない経路となるため拒否する。 */
    private fun hasUnconditionalControlFlow(code: String): Boolean =
        controlFlowExitPattern.containsMatchIn(code)

    /** redirect inputを生成してからsubmitするまでに制御文を挟む形は、順次実行契約に反する。 */
    private fun hasControlFlowBetween(code: String, first: MatchResult, last: MatchResult): Boolean =
        controlFlowPattern.findAll(code).any { control ->
            control.range.first in first.range.last + 1 until last.range.first && braceDepth(code, control.range.first) == 0
        }

    /**
     * 信頼済み同一originサイトの任意JSを形式証明するのではなく、stage2フォームの完全照合、固定field、
     * 固定action、redirect追加、対象フォームの一回submitという実測済み通信契約だけを検査する。
     * redirectの7文は関数body直下で直列に実行される必要があり、文間には空白またはコメントだけを許容する。
     */
    private fun isStrictRedirectStatementSequence(code: String, vararg statements: MatchResult): Boolean {
        if (!ordered(*statements) || !isStatementBoundaryBefore(code, statements.first().range.first)) return false
        return (1 until statements.size).all { index ->
            val previous = statements[index - 1]
            val next = statements[index]
            code.substring(previous.range.last + 1, next.range.first).all(Char::isWhitespace)
        }
    }

    /** `if (...) statement` や `else statement` の従属位置ではなく、独立した文の開始だけを許可する。 */
    private fun isStatementBoundaryBefore(code: String, start: Int): Boolean {
        var index = start - 1
        while (index >= 0 && code[index].isWhitespace()) index--
        return index < 0 || code[index] == ';' || code[index] == '}'
    }

    /** 正規submit以外の`.submit(`は、全script中の追加送信経路として拒否する。 */
    private fun hasOnlyCanonicalSubmit(
        scripts: List<Element>,
        candidate: ScriptCandidate,
        canonicalSubmitOffset: Int,
    ): Boolean = scripts.all { script ->
        val source = script.data()
        val code = if (script === candidate.script) candidate.code else maskNonCode(source) ?: return false
        val submits = codeOnlyMatches(code, anySubmitPattern)
        if (script === candidate.script) {
            submits.size == 1 && submits.single().range.first == canonicalSubmitOffset
        } else {
            submits.isEmpty()
        }
    }

    /**
     * dot参照以外でprevRequestFormを取り出す経路、forms集合、documentへの動的property定義を拒否する。
     * 文字列・コメント中の見かけの一致はmask位置で除外する。
     */
    private fun hasNoPrevRequestFormEscapeHatches(scripts: List<Element>): Boolean = scripts.all { script ->
        val source = script.data()
        val code = maskNonCode(source) ?: return false
        rawCodeMatches(source, code, prevRequestFormAnyBracketReference).isEmpty() &&
            rawCodeMatches(source, code, documentFormsReference).isEmpty() &&
            rawCodeMatches(source, code, documentPropertyDefinition).isEmpty()
    }

    private fun codeMatches(source: String, code: String, pattern: Regex): List<MatchResult> =
        pattern.findAll(source).filter { match -> codeStart(code, match.range.first) }.toList()

    private fun rawCodeMatches(source: String, code: String, pattern: Regex): List<MatchResult> =
        pattern.findAll(source).filter { match -> match.range.first in code.indices && code[match.range.first] != ' ' }.toList()

    private fun codeOnlyMatches(code: String, pattern: Regex): List<MatchResult> =
        pattern.findAll(code).filter { match -> codeStart(code, match.range.first) }.toList()

    private fun directMatches(source: String, code: String, pattern: Regex): List<MatchResult> =
        codeMatches(source, code, pattern).filter { braceDepth(code, it.range.first) == 0 }

    private fun ordered(vararg matches: MatchResult): Boolean =
        (1 until matches.size).all { index -> matches[index - 1].range.first < matches[index].range.first }

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
    private val redirectDeclarationPattern = Regex("\\bvar\\s+(CONFIRM_DIALOG_SEND_REDIRECT_NAME)\\s*=\\s*['\"]([^'\"]+)['\"]\\s*;")
    private val inputPattern = Regex("\\bvar\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*document\\s*\\.\\s*createElement\\s*\\(\\s*['\"]input['\"]\\s*\\)\\s*;")
    private fun identifierPattern(identifier: String) =
        Regex("\\b${Regex.escape(identifier)}\\b")
    private fun globalBracketIdentifierPattern(identifier: String) =
        Regex("\\b(?:window|globalThis|self|global)\\s*\\[\\s*['\"]${Regex.escape(identifier)}['\"]\\s*]")
    private fun propertyWritePattern(variable: String) =
        Regex("\\b${Regex.escape(variable)}\\s*(?:\\.\\s*[A-Za-z_$][A-Za-z0-9_$]*|\\[[^]\\r\\n]*])\\s*(?:=(?!=)|(?:\\+|-|\\*|/|%|&|\\||\\^)=|(?:<<|>>|>>>)=|\\*\\*=|&&=|\\|\\|=|\\?\\?=|\\+\\+|--)")
    private fun propertyPattern(variable: String, property: String, value: String) =
        Regex("\\b${Regex.escape(variable)}\\s*\\.\\s*${Regex.escape(property)}\\s*=\\s*['\"]${Regex.escape(value)}['\"]\\s*;")
    private fun propertyAssignmentPattern(variable: String, property: String) =
        Regex("\\b${Regex.escape(variable)}\\s*\\.\\s*${Regex.escape(property)}\\s*=")
    private fun propertyVariablePattern(variable: String, property: String, value: String) =
        Regex("\\b${Regex.escape(variable)}\\s*\\.\\s*${Regex.escape(property)}\\s*=\\s*${Regex.escape(value)}\\s*;")
    private fun appendPattern(variable: String) =
        Regex("\\bdocument\\s*\\.\\s*prevRequestForm\\s*\\.\\s*appendChild\\s*\\(\\s*${Regex.escape(variable)}\\s*\\)\\s*;")
    private val appendAnyPattern = Regex("\\bdocument\\s*\\.\\s*prevRequestForm\\s*\\.\\s*appendChild\\s*\\(")
    private val actionPattern = Regex("\\bdocument\\s*\\.\\s*prevRequestForm\\s*\\.\\s*action\\s*=\\s*['\"]([^'\"]+)['\"]\\s*;")
    private val submitPattern = Regex("\\bdocument\\s*\\.\\s*prevRequestForm\\s*\\.\\s*submit\\s*\\(\\s*\\)\\s*;")
    private val anySubmitPattern = Regex("\\.\\s*submit\\s*\\(")
    private val prevRequestFormReference = Regex("\\bdocument\\s*\\.\\s*prevRequestForm\\b")
    private val prevRequestFormBracketReference = Regex("\\bdocument\\s*\\[\\s*['\"]prevRequestForm['\"]\\s*]")
    private val prevRequestFormAnyBracketReference = Regex("\\[\\s*['\"]prevRequestForm['\"]\\s*]")
    private val documentFormsReference = Regex("\\bdocument\\s*\\.\\s*forms\\b")
    private val documentPropertyDefinition = Regex("\\b(?:Object|Reflect)\\s*\\.\\s*defineProperty\\s*\\(\\s*document\\s*,")
    private val controlFlowExitPattern = Regex("\\b(?:return|throw)\\b")
    private val controlFlowPattern = Regex("\\b(?:if|switch|while|for|with|try|catch|finally|return|throw)\\b")
    private val controlConditionKeywords = setOf("if", "while", "for", "with", "switch", "catch")
    private val expressionPrefixKeywords = setOf(
        "return", "throw", "case", "delete", "typeof", "void", "new", "in", "of", "yield", "await", "else", "do", "try", "finally",
        "break", "continue", "debugger",
    )
}

internal class BookshelfCompletionForm(private val fields: List<BookshelfFormField>) {
    fun buildForm(): FormBody = (fields + BookshelfFormField(redirectName, "true")).toFormBody()

    private companion object {
        const val redirectName = "jp.co.necsoft.licsxp.base.util.validation.MessageUtil.CONFIRM_DIALOG_SEND_REDIRECT"
    }
}
