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
    fun `action属性を持たないprevRequestFormでもJS代入から送信先を抽出できれば受理する`() {
        // 実サイトのprevRequestFormにaction属性は存在しない(docs/site-research.md §10)。
        // これが実サイトの正常な構造であり、action属性の有無を検証条件にしてはならない。
        val form = LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml(),
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
        assertEquals("OPACUSR005", form.buildForm().value(form.buildForm().size - 1))
    }

    @Test
    fun `document_prevRequestForm_actionのJS代入が想定外のpathなら拒否する`() = assertParseError {
        LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml(actionAssignmentValue = "/licsxp-opac/WOpacUsrRsvCancelAction.do"),
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `document_prevRequestForm_actionのJS代入が無ければ拒否する`() = assertParseError {
        LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml(includeActionAssignment = false),
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `document_prevRequestForm_actionのJS代入が複数あれば拒否する`() = assertParseError {
        LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml() +
                "<script>document.prevRequestForm.action = \"/licsxp-opac/WOpacUsrLendListExtendAction.do\";</script>",
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `実サイト構造フィクスチャ(usrlend_extend_confirm_html)を正しくパースできる`() {
        // 実サイトのHARから起こした1段階目応答フィクスチャ(値はマスク済み)。action属性が無いこと、
        // hiddenが18項目(mngFlg1_handan先頭・btnflgとcheckflagが各2回)、同一ページに存在する
        // LBFormMF・LBFormからprevRequestFormを一意特定できることを併せて確認する。
        val fields = listOf(
            "mngFlg1_handan" to "1",
            "schkflg" to "",
            "allschkflg" to "",
            "hash" to "0000000000000000000000000000000000000000",
            "islogin" to "1",
            "btnflg" to "0",
            "btnflg" to "0",
            "checkflag" to "0",
            "checkflag" to "0",
            "booklistvalue" to "0",
            "commntvalue" to "メモ（任意）",
            "returnid" to "https://tosho.nishi.or.jp/?v=PC",
            "gamenid" to "tiles.WUsrLendList",
            "para" to "999999999",
            "mngFlg1" to "1",
            "sortkeyvalue" to "",
            "sortDefKey" to "0",
            "booklist" to "0",
        ).map(::field)

        val form = LoanExtensionConfirmationFormParser.parse(
            html = fixture("usrlend_extend_confirm.html"),
            expectedStage1Fields = fields,
        )

        val body = form.buildForm()
        assertEquals(19, body.size)
        assertEquals("okCodes" to "OPACUSR005", body.name(body.size - 1) to body.value(body.size - 1))
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
    fun `確認コードのokArray代入が無ければ拒否する`() = assertParseError {
        LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml(includeConfirmationCodeAssignment = false),
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `確認コードのokArray代入が複数あれば拒否する`() = assertParseError {
        LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml() +
                "<script>okArray[okArray.length] = \"OPACUSR005\";</script>",
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `確認コードのokArray代入値が想定値OPACUSR005と異なれば拒否する`() = assertParseError {
        LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml(confirmationCodeAssignmentValue = "OPACUSR999"),
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `正規表現内の偽okArray代入は抽出しない`() = assertParseError {
        // 本物のokArray代入がコメント内に隠れており、抽出できないため拒否される
        // (ハードコードなら偶然通ってしまう欠陥をこのテストで検出する)。
        LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml(includeConfirmationCodeAssignment = false) +
                "<script>if (true) /okArray[okArray.length] = \"OPACUSR005\";/;</script>" +
                "<script>// okArray[okArray.length] = \"OPACUSR005\";</script>",
            expectedStage1Fields = listOf("first" to "1", "second" to "2").map(::field),
        )
    }

    @Test
    fun `OK_CODES_NAMEが既存prevRequestForm項目名と衝突すれば拒否する`() = assertParseError {
        LoanExtensionConfirmationFormParser.parse(
            html = confirmationHtml(fields = listOf("first" to "1", "okCodes" to "existing")),
            expectedStage1Fields = listOf("first" to "1", "okCodes" to "existing").map(::field),
        )
    }

    /**
     * 実サイト構造を模したprevRequestFormを生成する。実サイトと同じく`action`属性は持たせず、
     * 送信先は`document.prevRequestForm.action = "..."`というJS代入で表現する
     * (`includeActionAssignment`でその代入自体の有無を、`actionAssignmentValue`で代入値を制御する)。
     */
    private fun confirmationHtml(
        fields: List<Pair<String, String>> = listOf("first" to "1", "second" to "2"),
        okCodesName: String = "okCodes",
        includeActionAssignment: Boolean = true,
        actionAssignmentValue: String = "/licsxp-opac/WOpacUsrLendListExtendAction.do",
        includeConfirmationCodeAssignment: Boolean = true,
        confirmationCodeAssignmentValue: String = "OPACUSR005",
    ): String {
        val actionAssignmentScript = if (includeActionAssignment) {
            "<script>document.prevRequestForm.action = \"$actionAssignmentValue\";</script>"
        } else {
            ""
        }
        // 実サイトの確認コードは`okArray[okArray.length] = "OPACUSR005";`という配列要素代入で現れる
        // (usrlend_extend_confirm.html 105行目付近)。テスト用HTMLもこの構造を模す。
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
          <form name="prevRequestForm" method="post">
            ${fields.joinToString("\n") { (name, value) -> "<input type=\"hidden\" name=\"$name\" value=\"$value\">" }}
          </form>
          <script>const OK_CODES_NAME = "$okCodesName";</script>
          $actionAssignmentScript
          $confirmationCodeScript
        </body></html>
        """.trimIndent()
    }

    private fun confirmationFormOnly(): String =
        "<form name=\"prevRequestForm\" method=\"post\"></form>"

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader).getResource("fixtures/$name")!!.readText()

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
