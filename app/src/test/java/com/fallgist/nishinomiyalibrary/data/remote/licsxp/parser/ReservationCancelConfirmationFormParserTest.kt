package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class ReservationCancelConfirmationFormParserTest {
    @Test
    fun `prevRequestFormをDOM順で送り実際のOK field名と抽出した確認コードを末尾へ加える`() {
        val form = ReservationCancelConfirmationFormParser.parse(
            html = confirmationHtml(
                fields = listOf("second" to "2", "first" to "1", "second" to "3"),
                okCodesName = "approvedCodes",
            ),
            expectedStage1Fields = listOf("first" to "1", "second" to "2", "second" to "3").map(::field),
        )

        assertEquals(ReservationCancelConfirmationAction.Explicit("WOpacUsrRsvCancelAction.do"), form.action)
        val body = form.buildForm()
        assertEquals(
            listOf("second" to "2", "first" to "1", "second" to "3", "approvedCodes" to "OPACUSR001"),
            (0 until body.size).map { body.name(it) to body.value(it) },
        )
    }

    @Test
    fun `prevRequestFormにaction属性が無ければSameAsCurrentDocumentになる`() {
        // 11回目のライブ診断(2026-07-28)実測どおり、実サイトのprevRequestFormはaction属性を持たない。
        // 以前はここでParseExceptionにしていたが、HTML標準どおり「現在のドキュメントURLへ送る」ことを
        // 型で表現するよう改めた。
        val form = ReservationCancelConfirmationFormParser.parse(
            html = confirmationHtml(action = null),
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )

        assertEquals(ReservationCancelConfirmationAction.SameAsCurrentDocument, form.action)
    }

    @Test
    fun `実測フラグメント(reservation_cancel_confirmation_live_fragment_js)を埋め込んだ正常系で確認コードOPACUSR001が末尾へ付く`() {
        // 受入条件1(docs/design/reservation-cancel-hardening.md)。実測フラグメントを<script>として
        // 埋め込み、自作HTMLだけでは検出できない実装誤りを固定する。
        val form = ReservationCancelConfirmationFormParser.parse(
            html = confirmationHtml(
                fields = listOf("first" to "1", "second" to "2"),
                okCodesName = "okCodes",
                includeConfirmationCodeAssignment = false,
                extraScript = "<script>${fixture("reservation_cancel_confirmation_live_fragment.js")}</script>",
            ),
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )

        val body = form.buildForm()
        assertEquals(
            "okCodes" to "OPACUSR001",
            body.name(body.size - 1) to body.value(body.size - 1),
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

    @Test
    fun `確認コードのokArray代入が無ければ拒否する`() = assertParseError {
        ReservationCancelConfirmationFormParser.parse(
            html = confirmationHtml(includeConfirmationCodeAssignment = false),
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `確認コードのokArray代入が複数あれば拒否する`() = assertParseError {
        ReservationCancelConfirmationFormParser.parse(
            html = confirmationHtml() + "<script>okArray[okArray.length] = \"OPACUSR001\";</script>",
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `確認コードのokArray代入値が想定値OPACUSR001と異なれば拒否する`() = assertParseError {
        ReservationCancelConfirmationFormParser.parse(
            html = confirmationHtml(confirmationCodeAssignmentValue = "OPACUSR999"),
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `正規表現内の偽okArray代入は抽出しない`() = assertParseError {
        // 本物のokArray代入がコメント内に隠れており、抽出できないため拒否される
        // (ハードコードなら偶然通ってしまう欠陥をこのテストで検出する)。
        ReservationCancelConfirmationFormParser.parse(
            html = confirmationHtml(includeConfirmationCodeAssignment = false) +
                "<script>if (true) /okArray[okArray.length] = \"OPACUSR001\";/;</script>" +
                "<script>// okArray[okArray.length] = \"OPACUSR001\";</script>",
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `コメント文字列template内の偽okArray代入は抽出しない`() = assertParseError {
        ReservationCancelConfirmationFormParser.parse(
            html = confirmationHtml(includeConfirmationCodeAssignment = false) +
                "<script>const ignored = \"okArray[okArray.length] = 'OPACUSR001';\";</script>" +
                "<script>const ignored2 = `okArray[okArray.length] = 'OPACUSR001';`;</script>",
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    private fun confirmationHtml(
        fields: List<Pair<String, String>> = listOf("first" to "1", "second" to "2"),
        okCodesName: String = "okCodes",
        action: String? = "WOpacUsrRsvCancelAction.do",
        includeConfirmationCodeAssignment: Boolean = true,
        confirmationCodeAssignmentValue: String = "OPACUSR001",
        extraScript: String = "",
    ): String {
        val actionAttribute = if (action == null) "" else " action=\"$action\""
        // 実サイトの確認コードは`okArray[okArray.length] = "OPACUSR001";`という配列要素代入で現れる
        // (reservation_cancel_confirmation_live_fragment.js 23行目)。テスト用HTMLもこの構造を模す。
        val confirmationCodeScript = if (includeConfirmationCodeAssignment) {
            """
            <script>
              var okArray = new Array();
              okArray[okArray.length] = "$confirmationCodeAssignmentValue";
            </script>
            """.trimIndent()
        } else {
            ""
        }
        return """
        <html><body>
          <form name="prevRequestForm"$actionAttribute>
            ${fields.joinToString("\n") { (name, value) -> "<input type=\"hidden\" name=\"$name\" value=\"$value\">" }}
          </form>
          <script>const OK_CODES_NAME = "$okCodesName";</script>
          $confirmationCodeScript
          $extraScript
        </body></html>
        """.trimIndent()
    }

    /**
     * 「抽出した確認コードをそのまま送る」という不変条件を直接固定する。
     *
     * [ReservationCancelConfirmationFormParser.parse]経由では想定値照合(fail-close)により
     * 確認コードが常に`OPACUSR001`になるため、`buildForm()`が抽出値を送っていても
     * リテラルを送っていても外部から区別できない。実際、`buildForm()`をリテラルへ戻す劣化を
     * 入れても他の全テストは緑のままである(2026-08-06の破壊検証で確認)。
     * 将来この照合を緩めたときにリテラルが誤った値を送る事故を防ぐため、ここだけは
     * フォームを直接構築して、渡した確認コードがそのまま末尾へ付くことを固定する。
     */
    @Test
    fun `buildFormは渡された確認コードをそのまま末尾へ付ける`() {
        val form = ReservationCancelConfirmationForm(
            action = ReservationCancelConfirmationAction.SameAsCurrentDocument,
            fields = listOf("first" to "1", "second" to "2").map(::field),
            okCodesFieldName = "okCodes",
            confirmationCode = "NOT_THE_EXPECTED_CODE",
        )

        val body = form.buildForm()

        assertEquals(
            "okCodes" to "NOT_THE_EXPECTED_CODE",
            body.name(body.size - 1) to body.value(body.size - 1),
        )
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader).getResource("fixtures/$name")!!.readText()

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
