package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** site-research.md §13の確定契約だけから作った最小合成fixtureによるパーサ試験。 */
class BookshelfFormsTest {
    @Test
    fun `詳細追加は無効な項目も含む29項目をDOM順で送りメモ空文字を保持する`() {
        val html = form(
            listOf(
                "islogin", "gamentilcod", "prevORnext", "preNextTilcod", "hash", "syurui", "syuruivalue", "returnid",
                "diccod", "syuruiName", "btnflg", "execflg", "amazonUrl", "aWSAccessKeyId", "secretAccessKey", "associateTag",
                "version", "responseGroup", "amazonIsbn", "storeId", "amazonDispFlag", "kensakuFlg", "kensaku", "yoy_directtilcod",
                "tilcod", "refCode", "gamenid", "booklist", "commnt",
            ),
        ).replace("name='tilcod' value='tilcod'", "name='tilcod' value='1000000000001'")
        val body = BookshelfDetailAddFormParser.parse(html).buildForm(3, "")
        assertEquals(29, body.size)
        assertEquals("booklist", body.name(27))
        assertEquals("3", body.value(27))
        assertEquals("commnt", body.name(28))
        assertEquals("", body.value(28))
    }

    @Test
    fun `編集フォームは繰返し4項目の対象メモだけを置換する`() {
        val html = form(listOf("hash", "returnid", "gamenid", "tilcod", "dispflg", "otherbook", "listname", "commnt", "bookcmnt", "eachcmnt", "sortno", "eachsortno", "bookcmnt", "eachcmnt", "sortno", "eachsortno"))
            .replace("name='otherbook' value='otherbook'", "name='otherbook' value='4'")
        val body = BookshelfEditFormParser.parse(html).updateMemo(1, "新しい\r\nメモ")
        assertEquals("eachcmnt", body.name(9))
        assertEquals("eachcmnt", body.name(13))
        assertEquals("eachcmnt", body.value(9))
        assertEquals("新しい\r\nメモ", body.value(13))
    }

    @Test
    fun `確認コードとcreateConfirmDialogが操作と一致しなければ拒否する`() {
        val fields = listOf(BookshelfFormField("hash", "masked"))
        val html = """
            <form name='prevRequestForm'><input name='hash' value='masked'></form>
            <script>var OK_CODES_NAME = 'okCodes'; var okArray=[]; okArray[okArray.length] = 'OPACSDI011'; createConfirmDialog('x');</script>
        """.trimIndent()
        val failure = runCatching { BookshelfConfirmationFormParser.parse(html, fields, BookshelfConfirmationKind.CREATE) }.exceptionOrNull()
        assertTrue(failure is ParseException)
    }

    @Test
    fun `確認フォームは同じ項目のDOM順変更と重複差異を拒否する`() {
        val expected = listOf(
            BookshelfFormField("hash", "masked"),
            BookshelfFormField("eachcmnt", "一件目"),
            BookshelfFormField("eachcmnt", "二件目"),
        )
        val reordered = confirmationHtml(listOf(expected[0], expected[2], expected[1]))

        val failure = runCatching { BookshelfConfirmationFormParser.parse(reordered, expected, BookshelfConfirmationKind.UPDATE) }.exceptionOrNull()

        assertTrue(failure is ParseException)
    }

    @Test
    fun `確認フォームは実行時JSのaction hidden追加 submit連鎖を検証し文字列とコメント内の偽署名を無視する`() {
        val expected = listOf(BookshelfFormField("hash", "masked"))
        val valid = confirmationHtml(
            expected,
            "/* createConfirmDialog('偽'); document.prevRequestForm.submit(); */ var ignored = \"createConfirmDialog window.onload OK_CODES_NAME\";",
        )
        val form = BookshelfConfirmationFormParser.parse(valid, expected, BookshelfConfirmationKind.UPDATE)
        assertEquals("WOpacSdiBookListUpdateAction.do", form.action)
        val applicationAbsolute = valid.replace(
            "action = 'WOpacSdiBookListUpdateAction.do'",
            "action = '/licsxp-opac/WOpacSdiBookListUpdateAction.do'",
        )
        assertEquals(
            "WOpacSdiBookListUpdateAction.do",
            BookshelfConfirmationFormParser.parse(applicationAbsolute, expected, BookshelfConfirmationKind.UPDATE).action,
        )

        listOf(
            valid.replace("WOpacSdiBookListUpdateAction.do", "WOpacSdiBookListUpdateAction.do?x=1"),
            valid.replace("action = 'WOpacSdiBookListUpdateAction.do'", "action = 'https://example.invalid/licsxp-opac/WOpacSdiBookListUpdateAction.do'"),
            valid.replace("document.prevRequestForm.appendChild(newHidden);", "document.prevRequestForm.appendChild(other);"),
            valid.replace("newHidden.value = okArray[i];", "newHidden.value = okArray[0];"),
            valid.replace("<form name='prevRequestForm'>", "<form name='prevRequestForm' action='WOpacSdiBookListUpdateAction.do'>"),
        ).forEach { changed ->
            assertTrue(runCatching { BookshelfConfirmationFormParser.parse(changed, expected, BookshelfConfirmationKind.UPDATE) }.exceptionOrNull() is ParseException)
        }
    }

    @Test
    fun `確認フォームは制御条件直後の正規表現内にある完全な偽署名を拒否する`() {
        val expected = listOf(BookshelfFormField("hash", "masked"))
        val fakeSignature = """
            var OK_CODES_NAME = 'okCodes'; function createConfirmDialog() { var okArray = new Array(); if (rest) { okArray[okArray.length] = 'OPACSDI011'; } for (var i = 0; i < okArray.length; i++) { var newHidden = document.createElement('input'); newHidden.type = 'hidden'; newHidden.name = OK_CODES_NAME; newHidden.value = okArray[i]; document.prevRequestForm.appendChild(newHidden); } document.prevRequestForm.action = 'WOpacSdiBookListUpdateAction.do'; document.prevRequestForm.submit(); } window.onload = createConfirmDialog;
        """.trimIndent()
        val html = """
            <form name='prevRequestForm'><input name='hash' value='masked'></form>
            <script>if /* 制御条件と括弧の間のコメント */
                (x)
                /$fakeSignature/;</script>
        """.trimIndent()

        assertTrue(
            runCatching {
                BookshelfConfirmationFormParser.parse(html, expected, BookshelfConfirmationKind.UPDATE)
            }.exceptionOrNull() is ParseException,
        )
    }

    @Test
    fun `削除フォームの項目順変更は送信前に拒否する`() {
        val html = form(listOf("hash", "returnid", "gamenid", "tilcod", "otherbook", "btnflg"))
        assertTrue(runCatching { BookshelfDeleteFormParser.parse(html) }.exceptionOrNull() is ParseException)
    }

    @Test
    fun `UPDATEのトップレベル確認scriptはroot relativeとapplication absoluteを受理する`() {
        val expected = listOf(BookshelfFormField("hash", "masked"))
        val rootRelative = inlineUpdateConfirmationHtml(expected)

        assertEquals(
            "WOpacSdiBookListUpdateAction.do",
            BookshelfConfirmationFormParser.parse(rootRelative, expected, BookshelfConfirmationKind.UPDATE).action,
        )
        assertEquals(
            "WOpacSdiBookListUpdateAction.do",
            BookshelfConfirmationFormParser.parse(
                rootRelative.replace(
                    "'/licsxp-opac/WOpacSdiBookListUpdateAction.do'",
                    "'WOpacSdiBookListUpdateAction.do'",
                ),
                expected,
                BookshelfConfirmationKind.UPDATE,
            ).action,
        )
        assertEquals(
            "WOpacSdiBookListUpdateAction.do",
            BookshelfConfirmationFormParser.parse(
                rootRelative.replace("OPACSDI011'; submitFlg", "OPACSDI011'; /* 実測上の補足 */\n submitFlg"),
                expected,
                BookshelfConfirmationKind.UPDATE,
            ).action,
        )
    }

    @Test
    fun `UPDATEのトップレベル確認scriptは不正な構造とnamed混在を拒否する`() {
        val expected = listOf(BookshelfFormField("hash", "masked"))
        val valid = inlineUpdateConfirmationHtml(expected)
        listOf(
            valid.replace("OPACSDI011", "OPACSDI017"),
            valid.replace("WOpacSdiBookListUpdateAction.do'", "WOpacSdiBookListUpdateAction.do?x=1'"),
            valid.replace("'/licsxp-opac/WOpacSdiBookListUpdateAction.do'", "'https://example.invalid/licsxp-opac/WOpacSdiBookListUpdateAction.do'"),
            valid.replace("var OK_CODES_NAME = 'okCodes';", "evil.OK_CODES_NAME = 'okCodes';"),
            valid.replace("var OK_CODES_NAME = 'okCodes';", "evil . OK_CODES_NAME = 'okCodes';"),
            valid.replace("document.createElement('input')", "evil.document.createElement('input')"),
            valid.replace("document.prevRequestForm.appendChild(newHidden);", "evil.document.prevRequestForm.appendChild(newHidden);"),
            valid.replace("document.prevRequestForm.action", "evil.document.prevRequestForm.action"),
            valid.replace("document.prevRequestForm.action", "evil . document.prevRequestForm.action"),
            valid.replace("document.prevRequestForm.action", "evil./*x*/document.prevRequestForm.action"),
            valid.replace("document.prevRequestForm.action", "evil.//x\ndocument.prevRequestForm.action"),
            valid.replace("document.prevRequestForm.submit();", "evil.document.prevRequestForm.submit();"),
            valid.replace("document.prevRequestForm.submit();", "document.prevRequestForm.action = '/licsxp-opac/WOpacSdiBookListUpdateAction.do'; document.prevRequestForm.submit();"),
            valid.replace("okArray[i]", "okArray[0]"),
            valid.replace("} document.prevRequestForm.action", "} for (var j = 0; j < okArray.length; j++) { var extraHidden = document.createElement('input'); extraHidden.type = 'hidden'; extraHidden.name = OK_CODES_NAME; extraHidden.value = okArray[j]; document.prevRequestForm.appendChild(extraHidden); } document.prevRequestForm.action"),
            valid.replace("appendChild(newHidden);", "appendChild(other);"),
            valid.replace("appendChild(newHidden);", "appendChild(newHidden); document.prevRequestForm.appendChild(newHidden);"),
            valid.replace("submit();", "submit(); document.prevRequestForm.submit();"),
            valid.replace("var okArray", "if (rest) { var okArray").replace("document.prevRequestForm.submit();", "document.prevRequestForm.submit(); }"),
            valid.replace("document.prevRequestForm.submit();", "document.prevRequestForm.submit(); function createConfirmDialog() {} window.onload = createConfirmDialog;"),
            valid.replace(" submitFlg = false;", ""),
            valid.replace("submitFlg = false;", "submitFlg = true;"),
            valid.replace("submitFlg = false;", "return cancelDialog();"),
            valid.replace("submitFlg = false;", "evil(); submitFlg = false;"),
            valid.replace("submitFlg = false;", "submitFlg = false; evil();"),
            valid.replace("submitFlg = false;", "if (nested) { submitFlg = false; }"),
            valid.replace("submitFlg = false;", "evil.submitFlg = false;"),
        ).forEach { changed ->
            assertTrue(
                runCatching {
                    BookshelfConfirmationFormParser.parse(changed, expected, BookshelfConfirmationKind.UPDATE)
                }.exceptionOrNull() is ParseException,
            )
        }
        assertTrue(
            runCatching {
                BookshelfConfirmationFormParser.parse(valid, expected, BookshelfConfirmationKind.CREATE)
            }.exceptionOrNull() is ParseException,
        )
    }

    @Test
    fun `先頭空白後のnamed確認scriptを受理する`() {
        val expected = listOf(BookshelfFormField("hash", "masked"))

        assertEquals(
            "WOpacSdiBookListUpdateAction.do",
            BookshelfConfirmationFormParser.parse(
                minimalNamedConfirmationHtml(expected),
                expected,
                BookshelfConfirmationKind.UPDATE,
            ).action,
        )
    }

    private fun form(names: List<String>): String = buildString {
        append("<form name='LBForm'>")
        names.forEach { name -> append("<input type='hidden' name='$name' value='$name'>") }
        append("</form>")
    }

    @Test
    fun `確認フォームは異なる項目名間だけのDOM順変更を受理する`() {
        val expected = stage1FieldsForOrderingTest()
        val reordered = listOf(expected.first()) + listOf("bookcmnt", "eachcmnt", "sortno", "eachsortno").flatMap { name ->
            expected.filter { it.name == name }
        }

        val form = BookshelfConfirmationFormParser.parse(
            confirmationHtml(reordered),
            expected,
            BookshelfConfirmationKind.UPDATE,
        )

        assertEquals("WOpacSdiBookListUpdateAction.do", form.action)
    }

    @Test
    fun `確認フォームは同名内の値順変更と項目の改変を拒否する`() {
        val expected = stage1FieldsForOrderingTest()
        val grouped = listOf(expected.first()) + listOf("bookcmnt", "eachcmnt", "sortno", "eachsortno").flatMap { name ->
            expected.filter { it.name == name }
        }
        val sameNameOrderChanged = grouped.map {
            when (it) {
                BookshelfFormField("bookcmnt", "book-1") -> BookshelfFormField("bookcmnt", "book-2")
                BookshelfFormField("bookcmnt", "book-2") -> BookshelfFormField("bookcmnt", "book-1")
                else -> it
            }
        }
        val invalidFields = listOf(
            grouped.map { if (it == BookshelfFormField("eachcmnt", "item-1")) BookshelfFormField("eachcmnt", "changed") else it },
            grouped.dropLast(1),
            grouped + BookshelfFormField("bookcmnt", "extra"),
            grouped + BookshelfFormField("unknown", "value"),
            sameNameOrderChanged,
        )

        invalidFields.forEach { fields ->
            assertTrue(
                runCatching {
                    BookshelfConfirmationFormParser.parse(confirmationHtml(fields), expected, BookshelfConfirmationKind.UPDATE)
                }.exceptionOrNull() is ParseException,
            )
        }
    }

    @Test
    fun `確認フォームは空および一項目の境界値を受理する`() {
        listOf(emptyList(), listOf(BookshelfFormField("hash", "masked"))).forEach { expected ->
            assertEquals(
                "WOpacSdiBookListUpdateAction.do",
                BookshelfConfirmationFormParser.parse(
                    confirmationHtml(expected),
                    expected,
                    BookshelfConfirmationKind.UPDATE,
                ).action,
            )
        }
    }

    private fun stage1FieldsForOrderingTest() = listOf(
        BookshelfFormField("hash", "masked"),
        BookshelfFormField("bookcmnt", "book-1"),
        BookshelfFormField("eachcmnt", "item-1"),
        BookshelfFormField("sortno", "0"),
        BookshelfFormField("eachsortno", "0"),
        BookshelfFormField("bookcmnt", "book-2"),
        BookshelfFormField("eachcmnt", "item-2"),
        BookshelfFormField("sortno", "1"),
        BookshelfFormField("eachsortno", "1"),
    )

    /** 実測済み共通構造を縮約した合成fixture。 */
    private fun confirmationHtml(fields: List<BookshelfFormField>, prefix: String = "") = """
        <form name='prevRequestForm'>${fields.joinToString("") { "<input name='${it.name}' value='${it.value}'>" }}</form>
        <script>$prefix var OK_CODES_NAME = 'okCodes'; function createConfirmDialog() { var okArray = new Array(); if (rest) { okArray[okArray.length] = 'OPACSDI011'; } for (var i = 0; i < okArray.length; i++) { var newHidden = document.createElement('input'); newHidden.type = 'hidden'; newHidden.name = OK_CODES_NAME; newHidden.value = okArray[i]; document.prevRequestForm.appendChild(newHidden); } document.prevRequestForm.action = 'WOpacSdiBookListUpdateAction.do'; document.prevRequestForm.submit(); } window.onload = createConfirmDialog;</script>
    """.trimIndent()

    private fun inlineUpdateConfirmationHtml(fields: List<BookshelfFormField>) = """
        <form name='prevRequestForm'>${fields.joinToString("") { "<input name='${it.name}' value='${it.value}'>" }}</form>
        <script>/* document.prevRequestForm.submit(); */ var ignored = "OK_CODES_NAME createConfirmDialog"; var fake = /document\.prevRequestForm\.submit\(\)/; var OK_CODES_NAME = 'okCodes'; var CANCEL_CODES_NAME = 'cancelCodes'; var okArray = new Array(); var cancelArray = new Array(); if (rest) { okArray[okArray.length] = 'OPACSDI011'; submitFlg = false; } else { return cancelDialog(); } for (var i = 0; i < okArray.length; i++) { var newHidden = document.createElement('input'); newHidden.type = 'hidden'; newHidden.name = OK_CODES_NAME; newHidden.value = okArray[i]; document.prevRequestForm.appendChild(newHidden); } for (var c = 0; c < cancelArray.length; c++) { var cancelHidden = document.createElement('input'); cancelHidden.type = 'hidden'; cancelHidden.name = CANCEL_CODES_NAME; cancelHidden.value = cancelArray[c]; document.prevRequestForm.appendChild(cancelHidden); } document.prevRequestForm.action = '/licsxp-opac/WOpacSdiBookListUpdateAction.do'; document.prevRequestForm.submit();</script>
    """.trimIndent()

    private fun minimalNamedConfirmationHtml(fields: List<BookshelfFormField>) = """
        <form name='prevRequestForm'>${fields.joinToString("") { "<input name='${it.name}' value='${it.value}'>" }}</form>
        <script>  function createConfirmDialog() { var okArray = new Array(); if (rest) { okArray[okArray.length] = 'OPACSDI011'; } for (var i = 0; i < okArray.length; i++) { var newHidden = document.createElement('input'); newHidden.type = 'hidden'; newHidden.name = OK_CODES_NAME; newHidden.value = okArray[i]; document.prevRequestForm.appendChild(newHidden); } document.prevRequestForm.action = 'WOpacSdiBookListUpdateAction.do'; document.prevRequestForm.submit(); } var OK_CODES_NAME = 'okCodes'; window.onload = createConfirmDialog;</script>
    """.trimIndent()
}
