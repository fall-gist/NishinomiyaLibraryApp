package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class ReservationCancelConfirmationFormParserTest {
    @Test
    fun `prevRequestFormをDOM順で送り実際のOK field名を末尾へ加える`() {
        val form = ReservationCancelConfirmationFormParser.parse(
            html = confirmationHtml(
                fields = listOf("second" to "2", "first" to "1", "second" to "3"),
                okCodesName = "approvedCodes",
            ),
            expectedStage1Fields = listOf("first" to "1", "second" to "2", "second" to "3").map(::field),
        )

        assertEquals("WOpacUsrRsvCancelAction.do", form.action)
        val body = form.buildForm()
        assertEquals(
            listOf("second" to "2", "first" to "1", "second" to "3", "approvedCodes" to "OPACUSR001"),
            (0 until body.size).map { body.name(it) to body.value(it) },
        )
    }

    @Test
    fun `prevRequestFormの項目multisetが1段階目送信と異なれば拒否する`() = assertParseError {
        ReservationCancelConfirmationFormParser.parse(
            html = confirmationHtml(fields = listOf("first" to "1", "second" to "different")),
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `prevRequestFormが複数なら拒否する`() = assertParseError {
        ReservationCancelConfirmationFormParser.parse(
            html = confirmationHtml() + "<form name=\"prevRequestForm\" action=\"WOpacUsrRsvCancelAction.do\"></form>",
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `OK_CODES_NAMEが曖昧なら拒否する`() = assertParseError {
        ReservationCancelConfirmationFormParser.parse(
            html = confirmationHtml() + "<script>let OK_CODES_NAME = 'otherCodes';</script>",
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `同じ値でもOK_CODES_NAME代入が複数なら拒否する`() = assertParseError {
        ReservationCancelConfirmationFormParser.parse(
            html = confirmationHtml() + "<script>OK_CODES_NAME = 'okCodes';</script>",
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `正規表現内の偽OK_CODES_NAME代入は抽出しない`() {
        listOf(
            "if (true) /OK_CODES_NAME = \"evilCodes\";/;",
            "if (false) {} else /OK_CODES_NAME = \"evilCodes\";/;",
            "const ignored = () => /OK_CODES_NAME = \"evilCodes\";/;",
            "if (false) {} /OK_CODES_NAME = \"evilCodes\";/;",
        ).forEach { fakeAssignment ->
            val form = ReservationCancelConfirmationFormParser.parse(
                html = confirmationHtml() + "<script>$fakeAssignment</script>",
                expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
            )
            assertEquals("okCodes", form.buildForm().name(form.buildForm().size - 1))
        }
    }

    @Test
    fun `コメント文字列template内の偽OK_CODES_NAME代入は抽出しない`() {
        listOf(
            "// OK_CODES_NAME = \"evilCodes\";",
            "const ignored = \"OK_CODES_NAME = 'evilCodes';\";",
            "const ignored = `OK_CODES_NAME = 'evilCodes';`;",
        ).forEach { fakeAssignment ->
            val form = ReservationCancelConfirmationFormParser.parse(
                html = confirmationHtml() + "<script>$fakeAssignment</script>",
                expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
            )
            assertEquals("okCodes", form.buildForm().name(form.buildForm().size - 1))
        }
    }

    @Test
    fun `OK_CODES_NAMEが既存prevRequestForm項目名と衝突すれば拒否する`() = assertParseError {
        ReservationCancelConfirmationFormParser.parse(
            html = confirmationHtml(fields = listOf("first" to "1", "okCodes" to "existing")),
            expectedStage1Fields = listOf("first" to "1", "okCodes" to "existing").map(::field),
        )
    }

    private fun confirmationHtml(
        fields: List<Pair<String, String>> = listOf("first" to "1", "second" to "2"),
        okCodesName: String = "okCodes",
    ): String = """
        <html><body>
          <form name="prevRequestForm" action="WOpacUsrRsvCancelAction.do">
            ${fields.joinToString("\n") { (name, value) -> "<input type=\"hidden\" name=\"$name\" value=\"$value\">" }}
          </form>
          <script>const OK_CODES_NAME = "$okCodesName";</script>
        </body></html>
    """.trimIndent()

    private fun assertParseError(block: () -> Unit) {
        val error = try {
            block()
            throw AssertionError("ParseException が送出されませんでした")
        } catch (exception: ParseException) {
            exception
        }
        assertEquals("reservation-cancel-confirmation", error.screen)
    }

    private fun field(pair: Pair<String, String>): ReservationCancelConfirmationField =
        ReservationCancelConfirmationField(pair.first, pair.second)
}
