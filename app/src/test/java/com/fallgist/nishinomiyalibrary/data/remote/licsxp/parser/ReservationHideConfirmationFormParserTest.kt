package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ReservationHideConfirmationFormParserTest {
    private val expected = listOf(
        HideConfirmationField("mngFlg2_handan", "1"),
        HideConfirmationField("gamenid", "tiles.WUsrRsvList"),
    )

    @Test
    fun `HAR順序の単一inline scriptならokCodesを末尾追加する`() {
        val body = ReservationHideConfirmationFormParser.parse(page(), expected).buildForm()
        assertEquals(listOf("mngFlg2_handan", "gamenid", "okCodes"), (0 until body.size).map(body::name))
        assertEquals("OPACUSR423", body.value(body.size - 1))
    }

    @Test
    fun `コメントと文字列内の偽署名はコードとして数えない`() {
        ReservationHideConfirmationFormParser.parse(page("${signatureScript()} // okArray[0] = 'OTHER';"), expected)
        ReservationHideConfirmationFormParser.parse(page("${signatureScript()} var note = \"document.prevRequestForm.submit();\";"), expected)
    }

    @Test
    fun `フォームと署名の逸脱を拒否する`() {
        val script = signatureScript()
        assertFails { ReservationHideConfirmationFormParser.parse(page().replace("prevRequestForm", "other"), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page().replace("tiles.WUsrRsvList", "changed"), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace("OPACUSR423", "OTHER")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace("okArray[okArray.length] = 'OPACUSR423';", "okArray[okArray.length] = 'OPACUSR423'; okArray[0] = 'OTHER';")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace("cancelArray = new Array();", "cancelArray = new Array(); cancelArray.push('x');")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace("var OK_CODES_NAME = 'okCodes';", "/* var OK_CODES_NAME = 'okCodes'; */")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace("var OK_CODES_NAME = 'okCodes';", "var note = \"var OK_CODES_NAME = 'okCodes';\";")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace("var CANCEL_CODES_NAME = 'cancelCodes';", "</script><script>var CANCEL_CODES_NAME = 'cancelCodes';")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace("function createConfirmDialog()", "function otherFunction()")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace("function createConfirmDialog() {", "")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace("if (rest) {", "if (other) {")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace("if (rest) {", "return false; if (rest) {")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace("if (rest) {", "throw error; if (rest) {")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace("if (rest) {", "if (true) { return cancelDialog(); } if (rest) {")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace("if (rest) {", "while (true) {} if (rest) {")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace(okLoop(), "return false; ${okLoop()}")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace(okLoop(), "throw error; ${okLoop()}")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace("newHidden.name = OK_CODES_NAME; newHidden.value = okArray[i];", "newHidden.value = okArray[i]; newHidden.name = OK_CODES_NAME;")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace(okLoop(), "")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace(cancelLoop(), "")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace(actionAndSubmit(), "document.prevRequestForm.submit(); document.prevRequestForm.action = '/licsxp-opac/WOpacUsrRsvHiddenAction.do';")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace(actionAndSubmit(), "") + actionAndSubmit()), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(moveIntoOtherFunction(script, okLoop())), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(moveIntoOtherFunction(script, actionAndSubmit())), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace("window.onload = createConfirmDialog;", "")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page(script.replace("window.onload = createConfirmDialog;", "window.onload = otherFunction;")), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page().replace("</form>", "<select multiple name='x'><option value='x'>x</option></select></form>"), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page().replace("</form>", "<select name='x'><optgroup disabled><option value='x'>x</option></optgroup></select></form>"), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page().replace("</form>", "<select name='x'><option selected disabled value='x'>x</option></select></form>"), expected) }
        assertFails { ReservationHideConfirmationFormParser.parse(page().replace("</form>", "</form><input form='prevRequestForm' name='outside' value='x'>"), expected) }
    }

    private fun assertFails(block: () -> Unit) {
        try { block(); fail("ParseExceptionが必要です") } catch (_: ParseException) { Unit }
    }

    private fun page(script: String = signatureScript()) = """
        <form name='prevRequestForm' method='post'>
          <input name='mngFlg2_handan' value='1'><input name='gamenid' value='tiles.WUsrRsvList'>
        </form><script>$script</script>
    """.trimIndent()

    private fun signatureScript() = """
        var OK_CODES_NAME = 'okCodes'; var CANCEL_CODES_NAME = 'cancelCodes';
        function createConfirmDialog() {
        if (window.onload) { rest = true; }
        var okArray = new Array(); var cancelArray = new Array();
        var shouldAbort = false; if (shouldAbort) { return cancelDialog(); }
        if (rest) { okArray[okArray.length] = 'OPACUSR423'; submitFlg = false; } else { return cancelDialog(); }
        ${okLoop()}
        ${cancelLoop()}
        ${actionAndSubmit()}
        }
        function cancelDialog() { return false; }
        window.onload = createConfirmDialog;
    """.trimIndent()

    private fun okLoop() = """
        for (var i = 0; i < okArray.length; i++) {
          var newHidden = document.createElement('input');
          newHidden.type = 'hidden';
          newHidden.name = OK_CODES_NAME; newHidden.value = okArray[i];
          document.prevRequestForm.appendChild(newHidden);
        }
    """.trimIndent()

    private fun cancelLoop() = """
        for (var i = 0; i < cancelArray.length; i++) {
          var newHidden = document.createElement('input');
          newHidden.type = 'hidden';
          newHidden.name = CANCEL_CODES_NAME; newHidden.value = cancelArray[i];
          document.prevRequestForm.appendChild(newHidden);
        }
    """.trimIndent()

    private fun actionAndSubmit() = "document.prevRequestForm.action = '/licsxp-opac/WOpacUsrRsvHiddenAction.do'; document.prevRequestForm.submit();"

    private fun moveIntoOtherFunction(script: String, fragment: String): String = script
        .replace(fragment, "")
        .replace("function cancelDialog() { return false; }", "function otherFunction() { $fragment } function cancelDialog() { return false; }")
}
