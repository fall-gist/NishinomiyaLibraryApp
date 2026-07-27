package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationCancelFormParserTest {
    @Test
    fun `一覧フォームを一意に特定しyoykcodeの重複をDOM順で保持する`() {
        val form = ReservationCancelFormParser.parse(fixture("usrrsv.html"))

        // 実測: 19行すべてに行ごとの隠しフィールド yoykcode (常に空値) が並ぶため、
        // 同名フィールドをDOM順・重複込みで19件保持していることを確認する。
        assertEquals(19, form.fieldNames.count { it == "yoykcode" })
        // 制御用の yoycod はフォーム内にちょうど1個しか無い(そうでなければ一意特定に失敗する)。
        assertEquals(1, form.fieldNames.count { it == "yoycod" })
    }

    @Test
    fun `buildFormはyoycodだけを上書きし他のフィールドはそのまま送る`() {
        val form = ReservationCancelFormParser.parse(fixture("usrrsv.html"))
        val body = form.buildForm("1013074729")
        val fields = (0 until body.size).map { index -> body.name(index) to body.value(index) }

        assertEquals(1, fields.count { it.first == "yoycod" })
        assertEquals("1013074729", fields.single { it.first == "yoycod" }.second)
        // gamenidなどサイト発行値はそのまま素通しされる。
        assertTrue(fields.contains("gamenid" to "tiles.WUsrRsvList"))
        // 行ごとのyoykcodeは19件とも空値のまま(制御対象ではない)。
        assertTrue(fields.filter { it.first == "yoykcode" }.all { it.second == "" })
        // DOM順を保持していること(hashがgamenidより先に現れる)。
        assertTrue(fields.indexOfFirst { it.first == "hash" } < fields.indexOfFirst { it.first == "gamenid" })
    }

    @Test
    fun `一覧上に無い取消コードはParseExceptionになる`() {
        val form = ReservationCancelFormParser.parse(fixture("usrrsv.html"))
        assertParseError("reservation-cancel") {
            form.buildForm("9999999999999")
        }
    }

    @Test
    fun `LBFormが特定できないHTMLはParseExceptionになる`() = assertParseError("reservation-cancel") {
        ReservationCancelFormParser.parse("<form name=\"LBForm\"></form>")
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
