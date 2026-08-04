package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoanExtensionListParserTest {
    @Test
    fun `貸出一覧フィクスチャから全行をtilcod・返却期日付きで抽出し延長ボタンの無い行はrenewalCode nullになる`() {
        val rows = LoanExtensionListParser.parse(fixture("usrlend.html"))

        // usrlend.htmlは延長ボタンあり7件・なし5件(計12件)。実測どおりドキュメント順で並ぶ。
        // 延長ボタンの無い行も送信後の照合に必要なため、結果から除外してはならない(修正B)。
        assertEquals(12, rows.size)
        assertEquals(
            listOf(
                "1000000817183", "1001000252300", "1000000114178", "1001000381992",
                "1000000375113", "1000001556048", "1000001559709", "1000001990541",
                "1000001396511", "1000001684487", "1000001990533", "1000001246421",
            ),
            rows.map { it.tilcod },
        )
        assertEquals(
            listOf(
                "222057515", "426047783", "323176487", "221291099", "222093130", "426110003", null, null,
                "125411678", null, null, null,
            ),
            rows.map { it.renewalCode },
        )
        val nonNullCodes = rows.mapNotNull { it.renewalCode }
        assertEquals(7, nonNullCodes.size)
        assertTrue(nonNullCodes.toSet().size == nonNullCodes.size)
    }

    @Test
    fun `延長ボタンの無い一覧はrenewalCodeが全てnullの行として抽出される`() {
        val rows = LoanExtensionListParser.parse(loanExtensionHtml(listOf("", "")))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.renewalCode == null })
    }

    @Test
    fun `行が無い一覧は0件になる`() {
        assertTrue(LoanExtensionListParser.parse(loanExtensionHtml(emptyList())).isEmpty())
    }

    @Test
    fun `延長ボタンonclickの引数が空値ならParseExceptionになる`() = assertParseError("loan_extension_list") {
        LoanExtensionListParser.parse(loanExtensionHtml(listOf(extendButton(""))))
    }

    @Test
    fun `延長ボタンonclickが未知構文(クォート無し)ならParseExceptionになる`() = assertParseError("loan_extension_list") {
        LoanExtensionListParser.parse(
            loanExtensionHtml(listOf("<input type=\"button\" class=\"button exec\" value=\"延長\" onclick='javascript:extend(123)'>")),
        )
    }

    @Test
    fun `同一行に延長ボタンが複数あればParseExceptionになる`() = assertParseError("loan_extension_list") {
        LoanExtensionListParser.parse(loanExtensionHtml(listOf(extendButton("111") + extendButton("222"))))
    }

    @Test
    fun `異なる行の延長コードが重複すればParseExceptionになる`() = assertParseError("loan_extension_list") {
        LoanExtensionListParser.parse(
            loanExtensionHtml(listOf(extendButton("999999999"), extendButton("999999999")), sameTilcod = false),
        )
    }

    @Test
    fun `貸出一覧の不正HTMLはParseExceptionになる`() = assertParseError("loan_extension_list") {
        LoanExtensionListParser.parse("<h1>貸出状況一覧</h1>")
    }

    private fun extendButton(code: String): String =
        "<input type=\"button\" class=\"button exec\" value=\"延長\" onclick='javascript:extend(\"$code\")'>"

    /**
     * `buttons`の要素数だけ行を作る。各行はtilcod・返却期日が異なるダミー値を持つ
     * (LoanExtensionRowが行と1対1で対応することを確認する目的)。
     */
    private fun loanExtensionHtml(buttons: List<String>, sameTilcod: Boolean = false): String {
        val theadHtml = "<h1>貸出状況一覧</h1><table summary='貸出状況一覧表'>" +
            "<thead><tr><th>資料名</th><th>書誌種別</th><th>貸出館</th><th>貸出日</th><th>返却期日</th><th>状態</th></tr></thead><tbody>"
        val rowsHtml = buttons.mapIndexed { index, button ->
            val tilcod = if (sameTilcod) "1000000000000" else "100000000000$index"
            """
            <tr>
                <td><a href='?para=$tilcod'>資料$index</a></td>
                <td>図書</td><td>本館</td><td>2026/07/01</td><td>2026/07/1${index + 1}</td><td>貸出中</td>
                <td>$button</td>
            </tr>
            """.trimIndent()
        }.joinToString("\n")
        return "$theadHtml$rowsHtml</tbody></table>"
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader).getResource("fixtures/$name")!!.readText()

    private fun assertParseError(screen: String, block: () -> Unit) {
        val error = try {
            block()
            throw AssertionError("ParseException が送出されませんでした")
        } catch (exception: ParseException) {
            exception
        }
        assertEquals(screen, error.screen)
    }
}
