package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoanExtensionRequestFormParserTest {
    @Test
    fun `一覧フォームを一意に特定しbtnflgとcheckflagの重複をDOM順で保持する`() {
        val form = LoanExtensionRequestFormParser.parse(fixture("usrlend.html"))

        // 実測(site-research.md §10): LBFormにはbtnflg・checkflagが2回ずつ並ぶ。DOM順・重複込みで保持する。
        assertEquals(2, form.fieldNames.count { it == "btnflg" })
        assertEquals(2, form.fieldNames.count { it == "checkflag" })
        // paraはフォーム内にちょうど1個しか無い(そうでなければ一意特定に失敗する)。
        assertEquals(1, form.fieldNames.count { it == "para" })
    }

    @Test
    fun `buildFormはparaだけを上書きし他のフィールドはそのまま送る`() {
        val form = LoanExtensionRequestFormParser.parse(fixture("usrlend.html"))
        val body = form.buildForm("222057515")
        val fields = (0 until body.size).map { index -> body.name(index) to body.value(index) }

        assertEquals(1, fields.count { it.first == "para" })
        assertEquals("222057515", fields.single { it.first == "para" }.second)
        // gamenidなどサイト発行値はそのまま素通しされる。
        assertTrue(fields.contains("gamenid" to "tiles.WUsrLendList"))
        assertTrue(fields.contains("mngFlg1" to "1"))
        // DOM順を保持していること(hashがgamenidより先に現れる)。
        assertTrue(fields.indexOfFirst { it.first == "hash" } < fields.indexOfFirst { it.first == "gamenid" })
    }

    @Test
    fun `一覧上に無い延長コードはParseExceptionになる`() {
        val form = LoanExtensionRequestFormParser.parse(fixture("usrlend.html"))
        assertParseError("loan-extension-request") {
            form.buildForm("9999999999999")
        }
    }

    @Test
    fun `LBFormが特定できないHTMLはParseExceptionになる`() = assertParseError("loan-extension-request") {
        LoanExtensionRequestFormParser.parse("<form name=\"LBForm\"></form>")
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
