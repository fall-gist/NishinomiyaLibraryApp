package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class LoanExtensionConfirmationFormParserTest {
    @Test
    fun `prevRequestFormをDOM順で送り実際のOK field名と固定確認コードを末尾へ加える`() {
        val form = LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml(
                fields = listOf("second" to "2", "first" to "1", "second" to "3"),
                okCodesName = "approvedCodes",
            ),
            expectedStage1Fields = listOf("first" to "1", "second" to "2", "second" to "3").map(::field),
        )

        val body = form.buildForm()
        assertEquals(
            listOf("second" to "2", "first" to "1", "second" to "3", "approvedCodes" to "OPACUSR005"),
            (0 until body.size).map { body.name(it) to body.value(it) },
        )
    }

    @Test
    fun `固定actionと一致しないprevRequestFormは拒否する`() = assertParseError {
        LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml(action = "/licsxp-opac/WOpacUsrRsvCancelAction.do"),
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `action属性が省略されたprevRequestFormは拒否する`() = assertParseError {
        // 予約取消と異なり、貸出延長は「action省略時は現在のドキュメントURル」という曖昧さを許容しない。
        LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml(action = null),
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `prevRequestFormの項目multisetが1段階目送信と異なれば拒否する`() = assertParseError {
        LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml(fields = listOf("first" to "1", "second" to "different")),
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `prevRequestFormが複数なら拒否する`() = assertParseError {
        LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml() + confirmationFormOnly(),
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `OK_CODES_NAMEが曖昧なら拒否する`() = assertParseError {
        LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml() + "<script>let OK_CODES_NAME = 'otherCodes';</script>",
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `同じ値でもOK_CODES_NAME代入が複数なら拒否する`() = assertParseError {
        LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml() + "<script>OK_CODES_NAME = 'okCodes';</script>",
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `正規表現内の偽OK_CODES_NAME代入は抽出しない`() {
        val form = LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml() + "<script>if (true) /OK_CODES_NAME = \"evilCodes\";/;</script>",
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
        assertEquals("okCodes", form.buildForm().name(form.buildForm().size - 1))
    }

    @Test
    fun `コメント文字列template内の偽OK_CODES_NAME代入は抽出しない`() {
        val form = LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml() + "<script>// OK_CODES_NAME = \"evilCodes\";</script>",
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
        assertEquals("okCodes", form.buildForm().name(form.buildForm().size - 1))
    }

    @Test
    fun `OK_CODES_NAMEが既存prevRequestForm項目名と衝突すれば拒否する`() = assertParseError {
        LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml(fields = listOf("first" to "1", "okCodes" to "existing")),
            expectedStage1Fields = listOf("first" to "1", "okCodes" to "existing").map(::field),
        )
    }

    private fun confirmationHtml(
        fields: List<Pair<String, String>> = listOf("first" to "1", "second" to "2"),
        okCodesName: String = "okCodes",
        action: String? = "/licsxp-opac/WOpacUsrLendListExtendAction.do",
    ): String {
        val actionAttribute = if (action == null) "" else " action=\"$action\""
        return """
        <html><body>
          <form name="prevRequestForm" method="post"$actionAttribute>
            ${fields.joinToString("\n") { (name, value) -> "<input type=\"hidden\" name=\"$name\" value=\"$value\">" }}
          </form>
          <script>const OK_CODES_NAME = "$okCodesName";</script>
        </body></html>
        """.trimIndent()
    }

    private fun confirmationFormOnly(): String =
        "<form name=\"prevRequestForm\" method=\"post\" action=\"/licsxp-opac/WOpacUsrLendListExtendAction.do\"></form>"

    private fun assertParseError(block: () -> Unit) {
        val error = try {
            block()
            throw AssertionError("ParseException が送出されませんでした")
        } catch (exception: ParseException) {
            exception
        }
        assertEquals("loan-extension-confirmation", error.screen)
    }

    private fun field(pair: Pair<String, String>): LoanExtensionConfirmationField =
        LoanExtensionConfirmationField(pair.first, pair.second)
}
