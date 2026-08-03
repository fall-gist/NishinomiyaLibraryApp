package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ReservationHideFormParserTest {
    @Test
    fun `name LBFormのsuccessful controlsをDOM順と同名重複のまま保持しyoycodだけ上書きする`() {
        val form = ReservationHideFormParser.parse(page())
        val body = form.buildForm("123")
        assertEquals(listOf("gamenid", "first", "same", "yoycod", "same", "last"), (0 until body.size).map(body::name))
        assertEquals(listOf("tiles.WUsrRsvList", "a", "x", "123", "y", "z"), (0 until body.size).map(body::value))
    }

    @Test
    fun `対象外コードと未知onclickとフォーム不一致を拒否する`() {
        assertParseFails { ReservationHideFormParser.parse(page()).buildForm("999") }
        assertParseFails { ReservationHideFormParser.parse(page(onclick = "yoykHihyoji(code)")) }
        assertParseFails { ReservationHideFormParser.parse("<form name='LBForm'><input name='yoycod'></form>") }
        assertParseFails { ReservationHideFormParser.parse(page() + page()) }
    }

    @Test
    fun `外部controlとdisabled fieldsetとmultiple selectとvalueなしoptionを拒否する`() {
        assertParseFails { ReservationHideFormParser.parse(page().replace("<textarea", "<input form='other' name='outside'><textarea")) }
        assertParseFails { ReservationHideFormParser.parse(page().replace("<textarea", "<fieldset disabled><input name='disabled'></fieldset><textarea")) }
        assertParseFails { ReservationHideFormParser.parse(page().replace("<textarea", "<select multiple name='many'><option value='x'>x</option></select><textarea")) }
        assertParseFails { ReservationHideFormParser.parse(page().replace("<textarea", "<select name='missing'><option selected>x</option></select><textarea")) }
    }

    private fun assertParseFails(block: () -> Unit) {
        try {
            block()
            fail("ParseExceptionが必要です")
        } catch (_: ParseException) {
            Unit
        }
    }

    private fun page(onclick: String = "yoykHihyoji('123')") = """
        <form method='post' name='LBForm'>
          <input type='hidden' name='gamenid' value='tiles.WUsrRsvList'>
          <input name='first' value='a'><input name='same' value='x'>
          <input type='hidden' name='yoycod' value='old'><input name='same' value='y'>
          <textarea name='last'>z</textarea>
          <input type='button' onclick="$onclick">
        </form>
    """.trimIndent()
}
