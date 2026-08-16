package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import com.fallgist.nishinomiyalibrary.domain.model.Shelf
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import java.time.LocalDate
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
    fun `編集フォームは名称とbookcmnt_eachcmntの両方を新しいメモで置換する`() {
        // サイトのchangcmnt(cmtValue, valcod)は hidden bookcmnt と textarea eachcmnt の両方に
        // 同じ新値を入れて送信する。eachcmntだけを新値にするとbookcmntの旧値でサーバがメモを
        // 上書きしてしまうため、両方が新値になることをここで固定する（今回の修正の本体）。
        val html = form(listOf("hash", "returnid", "gamenid", "tilcod", "dispflg", "otherbook", "listname", "commnt", "bookcmnt", "eachcmnt", "sortno", "eachsortno", "bookcmnt", "eachcmnt", "sortno", "eachsortno"))
            .replace("name='otherbook' value='otherbook'", "name='otherbook' value='4'")
        val body = BookshelfEditFormParser.parse(html).edit("変更後の棚", listOf("一件目", "新しい\r\nメモ"))
        assertEquals("変更後の棚", body.value(6))
        assertEquals("bookcmnt", body.name(8))
        assertEquals("eachcmnt", body.name(9))
        assertEquals("bookcmnt", body.name(12))
        assertEquals("eachcmnt", body.name(13))
        assertEquals("一件目", body.value(8))
        assertEquals("一件目", body.value(9))
        assertEquals("新しい\r\nメモ", body.value(12))
        assertEquals("新しい\r\nメモ", body.value(13))
    }

    @Test
    fun `編集フォームはdisp_chkの有無どちらの項目列も受理する`() {
        val withoutDispChk = form(listOf("hash", "returnid", "gamenid", "tilcod", "dispflg", "otherbook", "listname", "commnt", "bookcmnt", "eachcmnt", "sortno", "eachsortno"))
            .replace("name='otherbook' value='otherbook'", "name='otherbook' value='4'")
        val withDispChk = form(listOf("hash", "returnid", "gamenid", "tilcod", "dispflg", "otherbook", "listname", "commnt"))
            .replace("</form>", "<input type='checkbox' name='disp_chk' value='on' checked><input type='hidden' name='bookcmnt' value='bookcmnt'><input type='hidden' name='eachcmnt' value='eachcmnt'><input type='hidden' name='sortno' value='sortno'><input type='hidden' name='eachsortno' value='eachsortno'></form>")
            .replace("name='otherbook' value='otherbook'", "name='otherbook' value='4'")

        val withoutForm = BookshelfEditFormParser.parse(withoutDispChk)
        val withForm = BookshelfEditFormParser.parse(withDispChk)

        assertEquals(1, withoutForm.itemCount)
        assertEquals(1, withForm.itemCount)
        val withBody = withForm.edit("変更後の棚", listOf("新メモ"))
        assertEquals("disp_chk", withBody.name(8))
        assertEquals("on", withBody.value(8))
        assertEquals("bookcmnt", withBody.name(9))
        assertEquals("新メモ", withBody.value(9))
        assertEquals("eachcmnt", withBody.name(10))
        assertEquals("新メモ", withBody.value(10))
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

    @Test
    fun `UPDATE完了後の第3フォームは固定項目と表示scriptを検証する`() {
        val fields = completionFields()
        val form = BookshelfCompletionFormParser.parse(completionHtml(fields), fields)

        assertEquals("okCodes", form.buildForm().name(12))
        assertEquals("OPACSDI011", form.buildForm().value(12))
        assertEquals("jp.co.necsoft.licsxp.base.util.validation.MessageUtil.CONFIRM_DIALOG_SEND_REDIRECT", form.buildForm().name(13))
        assertEquals("true", form.buildForm().value(13))
        val grouped = groupedCompletionFields()
        assertEquals(18, BookshelfCompletionFormParser.parse(completionHtml(grouped), grouped).buildForm().size)
        assertEquals(
            "bookcmnt",
            BookshelfCompletionFormParser.parse(completionHtml(grouped), grouped).buildForm().name(8),
        )
        assertEquals(
            "eachcmnt",
            BookshelfCompletionFormParser.parse(completionHtml(grouped), grouped).buildForm().name(10),
        )
        // 完了画面では、確認送信時と異なる項目名の間だけDOM順が変わる実測形を受理する。
        val crossNameReordered = grouped.take(8) + listOf(
            grouped[8], grouped[10], grouped[12], grouped[14],
            grouped[9], grouped[11], grouped[13], grouped[15],
        ) + grouped.last()
        assertEquals(
            18,
            BookshelfCompletionFormParser.parse(completionHtml(crossNameReordered), grouped).buildForm().size,
        )
        val emptyShelf = completionFields().take(8) + completionFields().last()
        assertEquals(10, BookshelfCompletionFormParser.parse(completionHtml(emptyShelf), emptyShelf).buildForm().size)
        assertEquals(
            "OPACSDI011",
            BookshelfCompletionFormParser.parse(
                completionHtml(fields).replace(
                    "document.prevRequestForm.action",
                    "lbAlert('マイ本棚の更新処理が完了しました。', ''); document.prevRequestForm.action",
                ),
                fields,
            ).buildForm().value(12),
        )
        // 実測HARを縮約した、redirect前のloopと文間の空白・コメントは受理する。
        assertEquals(
            "OPACSDI011",
            BookshelfCompletionFormParser.parse(
                completionHtml(fields)
                    .replace("redirect.type = 'hidden';", "/* input種別 */ redirect.type = 'hidden'; // 次は固定field\n")
                    .replace(
                        "redirect.name = CONFIRM_DIALOG_SEND_REDIRECT_NAME;",
                        "/* 固定field */ redirect.name = CONFIRM_DIALOG_SEND_REDIRECT_NAME;",
                    ),
                fields,
            ).buildForm().value(12),
        )
        assertEquals(
            "OPACSDI011",
            BookshelfCompletionFormParser.parse(
                completionHtml(fields) + "<script src='common.js'></script><script>var unrelated = 1;</script>",
                fields,
            ).buildForm().value(12),
        )
        listOf(
            completionHtml(fields).replace(
                "var OK_CODES_NAME",
                "var fake = '++CONFIRM_DIALOG_SEND_REDIRECT_NAME'; /* window.CONFIRM_DIALOG_SEND_REDIRECT_NAME = 'evil'; */ " +
                    "var pattern = /window[\\\"CONFIRM_DIALOG_SEND_REDIRECT_NAME\\\"] = 'evil'/; var OK_CODES_NAME",
            ),
        ).forEach { html ->
            assertEquals(
                "OPACSDI011",
                BookshelfCompletionFormParser.parse(html, fields).buildForm().value(12),
            )
        }
        listOf(
            completionHtml(fields).replace("'/licsxp-opac/WOpacSdiBookListDispAction.do'", "'WOpacSdiBookListDispAction.do'"),
            completionHtml(fields).replace("WOpacSdiBookListDispAction.do", "WOpacSdiBookListDispAction.do?next=1"),
            completionHtml(fields).replace("<script>", "<script type='text/javascript'>"),
            completionHtml(fields).replace("<form name='prevRequestForm'>", "<form name='prevRequestForm' action='WOpacSdiBookListDispAction.do'>"),
            completionHtml(fields.dropLast(1)),
            completionHtml(fields).replace("document.prevRequestForm.action", "evil.document.prevRequestForm.action"),
            completionHtml(fields).replace("document.prevRequestForm.submit();", "document.prevRequestForm.submit(); document.prevRequestForm.submit();"),
            completionHtml(fields).replace("document.prevRequestForm.action", "document.prevRequestForm.reset(); document.prevRequestForm.action"),
            completionHtml(fields).replace("document.prevRequestForm.action", "document.prevRequestForm.foo; document.prevRequestForm.action"),
            completionHtml(fields).replace("document.prevRequestForm.action", "if (ok) { document.prevRequestForm.action").replace("document.prevRequestForm.submit();", "document.prevRequestForm.submit(); }"),
            completionHtml(fields) + "<script>function createConfirmDialog() { document.prevRequestForm.action = '/licsxp-opac/WOpacSdiBookListDispAction.do'; document.prevRequestForm.submit(); } window.onload = createConfirmDialog;</script>",
            completionHtml(fields).replace("var CONFIRM_DIALOG_SEND_REDIRECT_NAME", "var OTHER_REDIRECT_NAME"),
            completionHtml(fields).replace("CONFIRM_DIALOG_SEND_REDIRECT\";", "CONFIRM_DIALOG_SEND_REDIRECT_OTHER\";"),
            completionHtml(fields).replace("var OK_CODES_NAME", "CONFIRM_DIALOG_SEND_REDIRECT_NAME = 'evil'; var OK_CODES_NAME"),
            completionHtml(fields).replace("var OK_CODES_NAME", "CONFIRM_DIALOG_SEND_REDIRECT_NAME++; var OK_CODES_NAME"),
            completionHtml(fields).replace("var OK_CODES_NAME", "++CONFIRM_DIALOG_SEND_REDIRECT_NAME; var OK_CODES_NAME"),
            completionHtml(fields).replace("var OK_CODES_NAME", "--CONFIRM_DIALOG_SEND_REDIRECT_NAME; var OK_CODES_NAME"),
            completionHtml(fields).replace("var OK_CODES_NAME", "((CONFIRM_DIALOG_SEND_REDIRECT_NAME)) = 'evil'; var OK_CODES_NAME"),
            completionHtml(fields).replace("var OK_CODES_NAME", "((CONFIRM_DIALOG_SEND_REDIRECT_NAME))++; var OK_CODES_NAME"),
            completionHtml(fields).replace("var OK_CODES_NAME", "++((CONFIRM_DIALOG_SEND_REDIRECT_NAME)); var OK_CODES_NAME"),
            completionHtml(fields).replace("var OK_CODES_NAME", "window.CONFIRM_DIALOG_SEND_REDIRECT_NAME = 'evil'; var OK_CODES_NAME"),
            completionHtml(fields).replace("var OK_CODES_NAME", "window[\"CONFIRM_DIALOG_SEND_REDIRECT_NAME\"] = 'evil'; var OK_CODES_NAME"),
            completionHtml(fields).replace("var OK_CODES_NAME", "globalThis.CONFIRM_DIALOG_SEND_REDIRECT_NAME = 'evil'; var OK_CODES_NAME"),
            completionHtml(fields).replace("var OK_CODES_NAME", "self['CONFIRM_DIALOG_SEND_REDIRECT_NAME'] = 'evil'; var OK_CODES_NAME"),
            completionHtml(fields).replace("var OK_CODES_NAME", "var observedRedirectName = CONFIRM_DIALOG_SEND_REDIRECT_NAME; var OK_CODES_NAME"),
            completionHtml(fields).replace("</script>", "</script><script>CONFIRM_DIALOG_SEND_REDIRECT_NAME = 'evil';</script>"),
            completionHtml(fields).replace("redirect.type = 'hidden'", "redirect.type = 'text'"),
            completionHtml(fields).replace("redirect.name = CONFIRM_DIALOG_SEND_REDIRECT_NAME", "redirect.name = OTHER_REDIRECT_NAME"),
            completionHtml(fields).replace("redirect.value = 'true'", "redirect.value = 'false'"),
            completionHtml(fields).replace("redirect.value = 'true';", "redirect.value = 'true'; redirect[\"value\"] = 'false';"),
            completionHtml(fields).replace("redirect.name = CONFIRM_DIALOG_SEND_REDIRECT_NAME;", "redirect.name = CONFIRM_DIALOG_SEND_REDIRECT_NAME; redirect['name'] = 'evil';"),
            completionHtml(fields).replace("document.prevRequestForm.appendChild(redirect);", "if (ok) { redirect.value = 'false'; } document.prevRequestForm.appendChild(redirect);"),
            completionHtml(fields).replace("appendChild(redirect)", "appendChild(other)"),
            completionHtml(fields).replace("var redirect =", "if (ok) { var redirect =").replace("document.prevRequestForm.action", "} document.prevRequestForm.action"),
            completionHtml(fields).replace("var redirect =", "if (blocked) ; else var redirect ="),
            completionHtml(fields).replace("var redirect =", "if (blocked) return; var redirect ="),
            completionHtml(fields).replace("var redirect =", "if (blocked) { return; } var redirect ="),
            completionHtml(fields).replace("redirect.type = 'hidden';", "redirect.type = 'hidden'; unexpected();"),
            completionHtml(fields).replace("redirect.name = CONFIRM_DIALOG_SEND_REDIRECT_NAME;", "if (ok) redirect.name = CONFIRM_DIALOG_SEND_REDIRECT_NAME;"),
            completionHtml(fields).replace("document.prevRequestForm.action", "document.prevRequestForm = otherForm; document.prevRequestForm.action"),
            completionHtml(fields).replace("document.prevRequestForm.action", "document['prevRequestForm'] = otherForm; document.prevRequestForm.action"),
            completionHtml(fields).replace("var OK_CODES_NAME", "document.forms['prevRequestForm'].submit(); var OK_CODES_NAME"),
            completionHtml(fields).replace("var OK_CODES_NAME", "Object.defineProperty(document, 'prevRequestForm', { value: otherForm }); var OK_CODES_NAME"),
            completionHtml(fields).replace("var redirect =", "var alias = document.prevRequestForm; var redirect ="),
            completionHtml(fields) + "<script>document.prevRequestForm = otherForm;</script>",
            completionHtml(fields) + "<script>document['prevRequestForm'] = otherForm;</script>",
            completionHtml(fields).replace("</form>", "<input name='jp.co.necsoft.licsxp.base.util.validation.MessageUtil.CONFIRM_DIALOG_SEND_REDIRECT' value='true'></form>"),
            completionHtml(fields).replace("var redirect = document.createElement('input');", "var fake = \"var redirect = document.createElement('input');\";"),
        ).forEach { html ->
            assertTrue(runCatching { BookshelfCompletionFormParser.parse(html, fields) }.exceptionOrNull() is ParseException)
        }
    }

    @Test
    fun `requireMatchesは改行を含むメモと空文字メモを表示メモの正規化と揃えて通す`() {
        // ShelfParserはJsoup#text()で表示メモを作るため改行が空白に潰れ連続空白がまとめられ前後がtrimされる。
        // フォームの生値（改行を保持）とその正規化を揃えて比較しないと、改行を含むメモの資料が
        // 1件でもあると編集が丸ごと停止してしまう。
        // 1件目: 改行入りメモ（表示側は空白1つに潰れる想定）、2件目: 空文字メモ
        val htmlWithRows = buildString {
            append("<form name='LBForm'>")
            append("<input type='hidden' name='hash' value='hash'>")
            append("<input type='hidden' name='returnid' value='returnid'>")
            append("<input type='hidden' name='gamenid' value='gamenid'>")
            append("<input type='hidden' name='tilcod' value='tilcod'>")
            append("<input type='hidden' name='dispflg' value='dispflg'>")
            append("<input type='hidden' name='otherbook' value='4'>")
            append("<input type='hidden' name='listname' value='棚'>")
            append("<input type='hidden' name='commnt' value='commnt'>")
            append("<input type='hidden' name='bookcmnt' value='改行&#10;混じり&#10;メモ'>")
            append("<textarea name='eachcmnt'>改行&#10;混じり&#10;メモ</textarea>")
            append("<input type='hidden' name='sortno' value='1'>")
            append("<input type='hidden' name='eachsortno' value='1'>")
            append("<input type='hidden' name='bookcmnt' value=''>")
            append("<textarea name='eachcmnt'></textarea>")
            append("<input type='hidden' name='sortno' value='2'>")
            append("<input type='hidden' name='eachsortno' value='2'>")
            append("</form>")
        }
        val form = BookshelfEditFormParser.parse(htmlWithRows)
        val shelf = Shelf(4, "棚")
        val items = listOf(
            ShelfItem(memberId = 0, tilcod = "t1", title = "本1", memo = "改行 混じり メモ", registeredDate = LocalDate.of(2026, 1, 1), shelfNo = 4, shelfName = "棚"),
            ShelfItem(memberId = 0, tilcod = "t2", title = "本2", memo = "", registeredDate = LocalDate.of(2026, 1, 1), shelfNo = 4, shelfName = "棚"),
        )

        // 例外が飛ばなければ成功
        form.requireMatches(shelf, items)
    }

    @Test
    fun `requireMatchesは正規化しても異なるメモなら拒否する`() {
        val htmlWithRows = buildString {
            append("<form name='LBForm'>")
            append("<input type='hidden' name='hash' value='hash'>")
            append("<input type='hidden' name='returnid' value='returnid'>")
            append("<input type='hidden' name='gamenid' value='gamenid'>")
            append("<input type='hidden' name='tilcod' value='tilcod'>")
            append("<input type='hidden' name='dispflg' value='dispflg'>")
            append("<input type='hidden' name='otherbook' value='4'>")
            append("<input type='hidden' name='listname' value='棚'>")
            append("<input type='hidden' name='commnt' value='commnt'>")
            append("<input type='hidden' name='bookcmnt' value='元のメモ'>")
            append("<textarea name='eachcmnt'>元のメモ</textarea>")
            append("<input type='hidden' name='sortno' value='1'>")
            append("<input type='hidden' name='eachsortno' value='1'>")
            append("</form>")
        }
        val form = BookshelfEditFormParser.parse(htmlWithRows)
        val shelf = Shelf(4, "棚")
        val items = listOf(
            ShelfItem(memberId = 0, tilcod = "t1", title = "本1", memo = "異なるメモ", registeredDate = LocalDate.of(2026, 1, 1), shelfNo = 4, shelfName = "棚"),
        )

        assertTrue(runCatching { form.requireMatches(shelf, items) }.exceptionOrNull() is ParseException)
    }

    private fun form(names: List<String>): String = buildString {
        append("<form name='LBForm'>")
        names.forEach { name -> append("<input type='hidden' name='$name' value='$name'>") }
        append("</form>")
    }

    /**
     * 実測HAR(2026-08-15採取、本棚1「a」を削除)由来のフィクスチャによる正常系試験
     * (handoff.md 進行指示13、docs/design/account-and-bookshelf-fixes.md §4)。
     *
     * - `mybooklist_delete_before.html` はHAR entry1の応答(`WOpacMnuTopToPwdLibraryAction.do?gamen=mybooklist`)。
     *   本棚切り替え(`WOpacSdiBookListToOtherBookDispAction.do`)の応答そのものはHARに含まれていないため、
     *   同じ「マイ本棚」画面を返すこのentryで代用する。両アクションが同一画面テンプレートを返すことは
     *   ヘッダ・本棚属性テーブル・LBFormの構造が一致することから確認済みだが、代用である事実は変えない。
     * - `bookshelf_delete_confirm.html` はHAR entry32の応答で、1段階目POST
     *   (`WOpacSdiBookListDelAction.do?delflg=1`)が返す削除確認ページそのもの(代用ではない)。
     * - `mybooklist_delete_after.html` はHAR entry92の応答(削除完了後の`WOpacSdiBookMyListDispAction.do`)。
     *   削除前は本棚1「a」・2「b」・3「c」、削除後は2「b」・3「c」に減っていることを実データで確認する。
     * - `hash`の実値は秘匿ポリシーによりマスク済み(`masked-hash-token-2026-08-15`)。他の個人情報
     *   (氏名・カード番号・メールアドレス等)はHAR採取時点でページ自体に含まれていなかった。
     */
    @Test
    fun `実測HARの削除確認ページを1段階目フォームの実測値でそのまま検証できる`() {
        val beforeHtml = fixture("mybooklist_delete_before.html")

        val shelves = ShelfListParser.parse(beforeHtml)
        assertEquals(listOf(Shelf(1, "a"), Shelf(2, "b"), Shelf(3, "c")), shelves)

        val current = ShelfParser.parse(beforeHtml)
        assertEquals(Shelf(1, "a"), current.shelf)
        assertTrue(current.items.isEmpty())

        val deleteForm = BookshelfDeleteFormParser.parse(beforeHtml)
        assertEquals(1, deleteForm.shelfNo)

        val stage1 = deleteForm.buildForm()
        val expected = listOf(BookshelfFormField("delflg", "1")) +
            (0 until stage1.size).map { BookshelfFormField(stage1.name(it), stage1.value(it)) }

        val confirmHtml = fixture("bookshelf_delete_confirm.html")
        val confirm = BookshelfConfirmationFormParser.parse(confirmHtml, expected, BookshelfConfirmationKind.DELETE_SHELF)

        assertEquals("WOpacSdiBookListDelAction.do", confirm.action)
        val confirmBody = confirm.buildForm()
        assertEquals("okCodes", confirmBody.name(confirmBody.size - 1))
        assertEquals("OPACSDI010", confirmBody.value(confirmBody.size - 1))

        // 削除後一覧(実測)で、本棚1が実際に消えていたことを裏付ける。
        val afterHtml = fixture("mybooklist_delete_after.html")
        assertEquals(listOf(Shelf(2, "b"), Shelf(3, "c")), ShelfListParser.parse(afterHtml))
        assertEquals(Shelf(2, "b"), ShelfParser.parse(afterHtml).shelf)
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader).getResource("fixtures/$name")!!.readText()

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

    private fun completionFields() = listOf(
        BookshelfFormField("hash", "masked"), BookshelfFormField("returnid", "tiles.WSdiBookList"),
        BookshelfFormField("gamenid", "tiles.WSdiBookList"), BookshelfFormField("tilcod", ""),
        BookshelfFormField("dispflg", ""), BookshelfFormField("otherbook", "1"),
        BookshelfFormField("listname", "棚"), BookshelfFormField("commnt", ""),
        BookshelfFormField("bookcmnt", "メモ"), BookshelfFormField("eachcmnt", "メモ"),
        BookshelfFormField("sortno", "0"), BookshelfFormField("eachsortno", "0"),
        BookshelfFormField("okCodes", "OPACSDI011"),
    )

    private fun groupedCompletionFields() = listOf(
        BookshelfFormField("hash", "masked"), BookshelfFormField("returnid", "tiles.WSdiBookList"),
        BookshelfFormField("gamenid", "tiles.WSdiBookList"), BookshelfFormField("tilcod", ""),
        BookshelfFormField("dispflg", ""), BookshelfFormField("otherbook", "1"),
        BookshelfFormField("listname", "棚"), BookshelfFormField("commnt", ""),
        BookshelfFormField("bookcmnt", "一件目"), BookshelfFormField("bookcmnt", "二件目"),
        BookshelfFormField("eachcmnt", "一件目"), BookshelfFormField("eachcmnt", "二件目"),
        BookshelfFormField("sortno", "0"), BookshelfFormField("sortno", "1"),
        BookshelfFormField("eachsortno", "0"), BookshelfFormField("eachsortno", "1"),
        BookshelfFormField("okCodes", "OPACSDI011"),
    )

    private fun completionHtml(fields: List<BookshelfFormField>) = """
        <form name='prevRequestForm'>${fields.joinToString("") { field -> if (field.name == "commnt" || field.name == "eachcmnt") "<textarea name='${field.name}'>${field.value}</textarea>" else "<input name='${field.name}' value='${field.value}'>" }}</form>
        <script>
        var CONFIRM_DIALOG_SEND_REDIRECT_NAME = "jp.co.necsoft.licsxp.base.util.validation.MessageUtil.CONFIRM_DIALOG_SEND_REDIRECT";
        var OK_CODES_NAME = 'okCodes'; var CANCEL_CODES_NAME = 'cancelCodes'; var browser = navigator.userAgent;
        function createConfirmDialog() {
          for (var i = 0; i < okArray.length; i++) { var ok = document.createElement('input'); ok.type = 'hidden'; ok.name = OK_CODES_NAME; ok.value = okArray[i]; document.prevRequestForm.appendChild(ok); }
          for (var c = 0; c < cancelArray.length; c++) { var cancel = document.createElement('input'); cancel.type = 'hidden'; cancel.name = CANCEL_CODES_NAME; cancel.value = cancelArray[c]; document.prevRequestForm.appendChild(cancel); }
          var redirect = document.createElement('input');
          redirect.type = 'hidden'; redirect.name = CONFIRM_DIALOG_SEND_REDIRECT_NAME; redirect.value = 'true';
          document.prevRequestForm.appendChild(redirect);
          document.prevRequestForm.action = '/licsxp-opac/WOpacSdiBookListDispAction.do';
          document.prevRequestForm.submit();
        }
        window.onload = createConfirmDialog;
        </script>
    """.trimIndent()
}
