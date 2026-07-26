package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    @Test
    fun `通常書誌詳細LBFormのsuccessful controlsをDOM順で予約表示へ送る`() {
        val form = BookDetailReservationFormParser.parse(reservationDetailFixture(), "1000000961766").buildForm()
        val fields = (0 until form.size).map { index -> form.name(index) to form.value(index) }

        assertEquals(
            listOf("islogin", "gamentilcod", "prevORnext", "preNextTilcod", "hash"),
            fields.take(5).map { it.first },
        )
        assertTrue(fields.contains("gamenid" to "tiles.WTifTilDetail2"))
        assertTrue(fields.contains("tilcod" to "1000000961766"))
        assertFalse(fields.any { it.first == "yoyb" || it.first == "addlistbnt" })
    }

    @Test
    fun `通常書誌詳細LBFormの期待hiddenは型重複disabledでfail-closedにする`() {
        val valid = reservationDetailFixture()
        assertParseError("reservation-detail") {
            BookDetailReservationFormParser.parse(
                valid.replace("type=\"hidden\" name=\"gamenid\"", "type=\"text\" name=\"gamenid\""),
                "1000000961766",
            )
        }
        assertParseError("reservation-detail") {
            BookDetailReservationFormParser.parse(
                valid.replace("</form>", "<input type=\"hidden\" name=\"tilcod\" value=\"1000000961766\" /></form>"),
                "1000000961766",
            )
        }
        assertParseError("reservation-detail") {
            BookDetailReservationFormParser.parse(
                valid.replace("name=\"kensakuFlg\"", "name=\"kensakuFlg\" disabled"),
                "1000000961766",
            )
        }
    }

    @Test
    fun `gamenidがtiles WTifTilDetailの通常書誌詳細LBFormはParseExceptionになる`() {
        // WOpacTifTilListToTifTilDetailAction.do経由の実HTML(gamenid=tiles.WTifTilDetail)は
        // ブラウザ実測で確定POSTが差し戻されるため、fail-closedで受け付けない。
        assertParseError("reservation-detail") {
            BookDetailReservationFormParser.parse(fixture("book_detail.html"), "1000000961766")
        }
    }

    @Test
    fun `ログインフォームのsuccessful controlsをDOM順で構築する`() {
        val form = LoginFormParser.parse(fixture("login_form.html")).buildForm("1234", "password")

        assertEquals(
            listOf(
                "hash" to "",
                "gamenid" to "tiles.WMnuTop",
                "username" to "1234",
                "j_username" to "00000000000000001234",
                "h_username" to "",
                "j_password" to "password",
            ),
            (0 until form.size).map { index -> form.name(index) to form.value(index) },
        )
    }

    @Test
    fun `ログインフォームの重複制御項目は一度だけ送る`() {
        val duplicated = fixture("login_form.html").replace(
            "<input type=\"hidden\" name=\"j_username\">",
            "<input type=\"hidden\" name=\"j_username\"><input type=\"hidden\" name=\"j_username\" value=\"old\">",
        )
        val form = LoginFormParser.parse(duplicated).buildForm("1234", "password")

        assertEquals(1, (0 until form.size).count { form.name(it) == "j_username" })
        assertEquals("00000000000000001234", form.value((0 until form.size).first { form.name(it) == "j_username" }))
    }

    @Test
    fun `ログインフォームはunknown hiddenだけを送り無名disabledbuttonを除外する`() {
        val html = """
            <form>
              <input type="hidden" name="hash" value="" />
              <input name="username" value="old" />
              <input type="hidden" name="j_username" value="old" />
              <input type="password" name="j_password" value="old" />
              <input type="hidden" name="siteHidden" value="keep" />
              <input type="text" name="unknownText" value="drop" />
              <input type="hidden" name="disabledHidden" value="drop" disabled />
              <input type="button" name="button" value="drop" />
              <input type="hidden" value="drop" />
            </form>
        """.trimIndent()
        val form = LoginFormParser.parse(html).buildForm("1234", "password")

        assertEquals(
            listOf(
                "hash" to "",
                "username" to "1234",
                "j_username" to "00000000000000001234",
                "j_password" to "password",
                "siteHidden" to "keep",
            ),
            (0 until form.size).map { index -> form.name(index) to form.value(index) },
        )
    }

    @Test
    fun `ログインフォームの欠落と複数候補はParseExceptionになる`() {
        assertParseError("login") {
            LoginFormParser.parse("<form><input name='username'><input name='j_username'></form>")
        }
        assertParseError("login") {
            val valid = "<form><input name='username'><input name='j_username'><input name='j_password'></form>"
            LoginFormParser.parse(valid + valid)
        }
    }

    @Test
    fun `ログイン制御項目は実フォームの型以外を採用しない`() {
        listOf("button", "submit", "reset", "image", "file", "checkbox", "radio").forEach { type ->
            assertParseError("login") {
                LoginFormParser.parse(loginFormWith(usernameType = type))
            }
        }
        assertParseError("login") {
            LoginFormParser.parse(loginFormWith(jUsernameType = "text"))
        }
        assertParseError("login") {
            LoginFormParser.parse(loginFormWith(passwordType = "hidden"))
        }
    }

    @Test
    fun `ログイン例外は認証値を含まない`() {
        val password = "password-secret"
        val error = try {
            LoginFormParser.parse("<form><input name='username' value='card-secret'><input name='j_username'></form>")
            throw AssertionError("ParseException が送出されませんでした")
        } catch (exception: ParseException) {
            exception
        }
        assertFalse(error.message.orEmpty().contains("card-secret"))

        val form = LoginFormParser.parse(fixture("login_form.html"))
        val buildError = try {
            form.buildForm("", password)
            throw AssertionError("IllegalArgumentException が送出されませんでした")
        } catch (exception: IllegalArgumentException) {
            exception
        }
        assertFalse(buildError.message.orEmpty().contains(password))
    }

    @Test
    fun `予約確認画面のhiddenと12館を保持する`() {
        val parsed = DirectReservationConfirmParser.parse(fixture("reservation_confirm.html"), "1000000000001")
        assertEquals("confirm-hash", parsed.hiddenFields.toMap()["hash"])
        assertEquals("keep-me", parsed.hiddenFields.toMap()["siteIssued"])
        assertEquals(12, parsed.pickupLibraryCodes.size)
        assertTrue("106" in parsed.pickupLibraryCodes)
    }

    @Test
    fun `確認フォームのhasNonEmptyHashはhash値の有無で判定する`() {
        val withHash = DirectReservationConfirmParser.parse(fixture("reservation_confirm.html"), "1000000000001")
        assertTrue(withHash.hasNonEmptyHash)

        val emptyHash = fixture("reservation_confirm.html")
            .replace("<input type=\"hidden\" name=\"hash\" value=\"confirm-hash\" />", "<input type=\"hidden\" name=\"hash\" value=\"\" />")
        val withoutHashValue = DirectReservationConfirmParser.parse(emptyHash, "1000000000001")
        assertFalse(withoutHashValue.hasNonEmptyHash)
    }

    @Test
    fun `確認画面の明示選択はselected属性の有無で判定する`() {
        val parsed = DirectReservationConfirmParser.parse(fixture("reservation_confirm.html"), "1000000000001")

        // receivenameにはselectedが無いためnull、contactには<option value="4" selected>があるため"4"になる。
        assertEquals(null, parsed.explicitPickupLibraryCode)
        assertEquals("4", parsed.explicitContactCode)
    }

    @Test
    fun `JavaScript action無しLBFormを解析できる`() {
        val parsed = DirectReservationConfirmParser.parse(fixture("reservation_confirm_js_action.html"), "1000000000001")

        assertEquals("confirm-hash", parsed.hiddenFields.toMap()["hash"])
        assertEquals("0", parsed.hiddenFields.toMap()["contactFocus"])
        assertEquals(setOf("001", "106"), parsed.pickupLibraryCodes)
    }

    @Test
    fun `予約確認画面のtilcod不一致は拒否する`() = assertParseError("reservation-confirm") {
        DirectReservationConfirmParser.parse(fixture("reservation_confirm.html"), "999")
    }

    @Test
    fun `予約確認フォームは先行する別formを選ばず重複候補を拒否する`() = assertParseError("reservation-confirm") {
        val valid = fixture("reservation_confirm.html")
        val other = "<form action='x'><input name='gamenid' value='tiles.WYoyConfirm'><input name='tilcod' value='1000000000001'></form>"
        DirectReservationConfirmParser.parse(other + valid + valid, "1000000000001")
    }

    @Test
    fun `予約確認フォームの危険なhidden重複を拒否する`() = assertParseError("reservation-confirm") {
        val duplicated = fixture("reservation_confirm.html").replace(
            "<input type=\"hidden\" name=\"hash\" value=\"confirm-hash\" />",
            "<input type=\"hidden\" name=\"hash\" value=\"confirm-hash\" /><input type=\"hidden\" name=\"hash\" value=\"other\" />",
        )
        DirectReservationConfirmParser.parse(duplicated, "1000000000001")
    }

    @Test
    fun `予約応答はログインと既知重複を安全に区別する`() {
        assertEquals(
            DirectReservationResponseParser.Result.LoginAfterPost,
            DirectReservationResponseParser.parse("<form action='j_security_check'><input name='j_password'></form>"),
        )
        assertEquals(
            DirectReservationResponseParser.Result.DuplicateDetected,
            DirectReservationResponseParser.parse("<script>alert('予約済の書誌があります。予約できません。')</script>"),
        )
        assertEquals(
            DirectReservationResponseParser.Result.IndeterminateAfterPost,
            DirectReservationResponseParser.parse("<html><body>想定外</body></html>"),
        )
        assertEquals(
            DirectReservationResponseParser.Result.IndeterminateAfterPost,
            DirectReservationResponseParser.parse("<html><div id='stat-login'></div><p>メニュー</p></html>"),
        )
        assertEquals(
            DirectReservationResponseParser.Result.IndeterminateAfterPost,
            DirectReservationResponseParser.parse("<tr data-tilcod='999'><button disabled>予約済み</button></tr>"),
        )
    }

    @Test
    fun `予約応答が確認画面のままならStayedOnConfirmationになる`() {
        assertEquals(
            DirectReservationResponseParser.Result.StayedOnConfirmation,
            DirectReservationResponseParser.parse(fixture("reservation_confirm.html")),
        )
        assertEquals(
            DirectReservationResponseParser.Result.StayedOnConfirmation,
            DirectReservationResponseParser.parse("<form action='WOpacTifDirectYoyExecAction.do'></form>"),
        )
    }

    @Test
    fun `予約応答の既知重複は確認画面のままより優先される`() {
        val html = fixture("reservation_confirm.html") + "<script>alert('予約済の書誌があります。予約できません。')</script>"
        assertEquals(DirectReservationResponseParser.Result.DuplicateDetected, DirectReservationResponseParser.parse(html))
    }

    @Test
    fun `予約応答のログイン画面は確認画面のままより優先される`() {
        val html = fixture("reservation_confirm.html") + "<form action='j_security_check'><input name='j_password'></form>"
        assertEquals(DirectReservationResponseParser.Result.LoginAfterPost, DirectReservationResponseParser.parse(html))
    }

    @Test
    fun `予約確認フォームの制御項目は正しい型で一意に必要になる`() {
        val valid = fixture("reservation_confirm.html")
        assertParseError("reservation-confirm") {
            DirectReservationConfirmParser.parse(
                valid.replace("<select name=\"contact\">", "<input type=\"hidden\" name=\"contact\" value=\"4\" />"),
                "1000000000001",
            )
        }
        assertParseError("reservation-confirm") {
            DirectReservationConfirmParser.parse(
                valid.replace("</form>", "<select name=\"contact\"><option value=\"4\">Email</option></select></form>"),
                "1000000000001",
            )
        }
    }

    @Test
    fun `hidden contactdirectwebが無いフォームは特定できない`() = assertParseError("reservation-confirm") {
        val html = fixture("reservation_confirm.html")
            .replace("<input type=\"hidden\" name=\"contactdirectweb\" value=\"4\" />", "")
        DirectReservationConfirmParser.parse(html, "1000000000001")
    }

    @Test
    fun `contactdirectwebはサイト発行値のまま上書きされない`() {
        val html = fixture("reservation_confirm.html")
            .replace(
                "<input type=\"hidden\" name=\"contactdirectweb\" value=\"4\" />",
                "<input type=\"hidden\" name=\"contactdirectweb\" value=\"site-issued-value\" />",
            )
        val form = DirectReservationConfirmParser.parse(html, "1000000000001").buildForm("106")
        val fields = (0 until form.size).associate { index -> form.name(index) to form.value(index) }

        assertEquals("site-issued-value", fields["contactdirectweb"])
        assertEquals("106", fields["receivename"])
        assertEquals("4", fields["contact"])
    }

    @Test
    fun `receivenameとcontactだけが元のDOM位置で上書きされる`() {
        val form = DirectReservationConfirmParser.parse(fixture("reservation_confirm.html"), "1000000000001").buildForm("106")
        val names = (0 until form.size).map { index -> form.name(index) }
        val values = (0 until form.size).map { index -> form.value(index) }

        assertEquals(names.indexOf("receivename"), names.lastIndexOf("receivename"))
        assertEquals(names.indexOf("contact"), names.lastIndexOf("contact"))
        assertEquals("106", values[names.indexOf("receivename")])
        assertEquals("4", values[names.indexOf("contact")])
    }

    @Test
    fun `予約確認フォームのhidden以外のコントロールもDOM順で全て送る`() {
        val html = """
            <html><body>
            <form action="WOpacTifDirectYoyExecAction.do">
              <input type="hidden" name="hash" value="confirm-hash" />
              <input type="hidden" name="gamenid" value="tiles.WYoyConfirm" />
              <input type="hidden" name="tilcod" value="1000000000001" />
              <input type="hidden" name="contactdirectweb" value="4" />
              <input type="text" name="memo" value="めも" />
              <input type="radio" name="mailflg" value="1" checked />
              <select name="receivename">
                <option value="001">中央図書館</option><option value="106">高須分室</option>
              </select>
              <select name="contact"><option value="4" selected>Email</option></select>
              <select name="other"><option value="a" selected>A</option></select>
              <textarea name="note">備考</textarea>
            </form>
            </body></html>
        """.trimIndent()

        val form = DirectReservationConfirmParser.parse(html, "1000000000001").buildForm("106")
        val fields = (0 until form.size).map { index -> form.name(index) to form.value(index) }

        assertEquals(
            listOf(
                "hash" to "confirm-hash",
                "gamenid" to "tiles.WYoyConfirm",
                "tilcod" to "1000000000001",
                "contactdirectweb" to "4",
                "memo" to "めも",
                "mailflg" to "1",
                "receivename" to "106",
                "contact" to "4",
                "other" to "a",
                "note" to "備考",
            ),
            fields,
        )
    }

    @Test
    fun `予約確認フォームの未チェックradioとbutton系inputは送らない`() {
        val html = """
            <html><body>
            <form action="WOpacTifDirectYoyExecAction.do">
              <input type="hidden" name="hash" value="confirm-hash" />
              <input type="hidden" name="gamenid" value="tiles.WYoyConfirm" />
              <input type="hidden" name="tilcod" value="1000000000001" />
              <input type="hidden" name="contactdirectweb" value="4" />
              <input type="radio" name="mailflg" value="1" />
              <input type="checkbox" name="agree" value="on" />
              <input type="button" name="cancelBtn" value="キャンセル" />
              <input type="submit" name="submitBtn" value="送信" />
              <select name="receivename">
                <option value="001">中央図書館</option><option value="106">高須分室</option>
              </select>
              <select name="contact"><option value="4" selected>Email</option></select>
            </form>
            </body></html>
        """.trimIndent()

        val form = DirectReservationConfirmParser.parse(html, "1000000000001").buildForm("106")
        val names = (0 until form.size).map { index -> form.name(index) }

        assertFalse(names.contains("mailflg"))
        assertFalse(names.contains("agree"))
        assertFalse(names.contains("cancelBtn"))
        assertFalse(names.contains("submitBtn"))
        assertEquals(listOf("hash", "gamenid", "tilcod", "contactdirectweb", "receivename", "contact"), names)
    }

    @Test
    fun `検索結果フィクスチャをパースできる`() {
        val result = SearchResultParser.parse(fixture("search_result.html"))
        assertEquals(20, result.hits.size)
        assertEquals(5286, result.totalCount)
        assertTrue(result.hasNext)
        assertEquals("1000000961766", result.hits.first().tilcod)
        assertEquals("愛の哲学", result.hits.first().title)
        assertEquals("柴門 ふみ／著　KADOKAWA　2015.3", result.hits.first().writerLine)
        assertEquals("一般図書", result.hits.first().materialType)
    }

    @Test
    fun `検索結果の0件画面をパースできる`() {
        val result = SearchResultParser.parse(
            "<h1>検索結果 書誌一覧</h1><ul><li>該当件数は <span>0</span> です。</li></ul><div class='doclist'></div>",
        )
        assertEquals(0, result.totalCount)
        assertTrue(result.hits.isEmpty())
    }

    @Test
    fun `検索結果の不正HTMLはParseExceptionになる`() = assertParseError("search_result") {
        SearchResultParser.parse("<h1>検索結果 書誌一覧</h1><div class='doclist'></div>")
    }

    @Test
    fun `書誌詳細フィクスチャをパースできる`() {
        val result = BookDetailParser.parse(fixture("book_detail.html"))
        assertEquals("1000000961766", result.tilcod)
        assertEquals("愛の哲学", result.fields["書名"])
        assertEquals("柴門 ふみ／著", result.fields["著者名"])
        assertEquals("KADOKAWA", result.fields["出版者"])
        assertEquals("2015.3", result.fields["出版年月"])
        assertEquals("4-04-067384-4", result.fields["ISBN"])
        assertEquals("4040673844", result.isbn)
        assertEquals(1, result.holdingCount)
        assertEquals(1, result.availableCount)
        assertEquals(0, result.reservationCount)
        assertEquals(1, result.holdings.size)
        assertEquals("北口図書館", result.holdings.first().library)
        assertEquals("一般和書", result.holdings.first().materialType)
        assertEquals("Sｻｲ/ｻｱE/", result.holdings.first().callNumber)
        assertEquals("一般開架", result.holdings.first().location)
        assertEquals("帯出可", result.holdings.first().lendable)
        assertEquals("在庫", result.holdings.first().status)
    }

    @Test
    fun `書誌詳細の0件所蔵画面をパースできる`() {
        val result = BookDetailParser.parse(emptyBookDetail())
        assertTrue(result.holdings.isEmpty())
        assertEquals(0, result.holdingCount)
    }

    @Test
    fun `書誌詳細の不正HTMLはParseExceptionになる`() = assertParseError("book_detail") {
        BookDetailParser.parse("<h1>検索結果書誌詳細</h1>")
    }

    @Test
    fun `書誌詳細の所蔵ヘッダーが欠落した空表はParseExceptionになる`() = assertParseError("book_detail") {
        BookDetailParser.parse(emptyBookDetail(holdingsTable = "<table summary='資料情報２'></table>"))
    }

    @Test
    fun `貸出一覧フィクスチャをパースできる`() {
        val result = LoanListParser.parse(fixture("usrlend.html"))
        assertEquals(12, result.size)
        assertEquals(UNASSIGNED_MEMBER_ID, result.first().memberId)
        assertEquals("かいてんずしのきょうふ　ゆうれいたんていドロヒュー 5　やまもと しょうぞう／作・絵　フレーベル館", result.first().title)
        assertEquals("児童図書", result.first().materialType)
        assertEquals("高須分室", result.first().lendingLibrary)
        assertEquals(LocalDate.of(2026, 7, 4), result.first().loanDate)
        assertEquals(LocalDate.of(2026, 7, 18), result.first().dueDate)
        assertEquals("貸出中", result.first().status)
        assertEquals("1000000817183", result.first().tilcod)
    }

    @Test
    fun `読書履歴フィクスチャは閉じtrの欠落とページングを許容する`() {
        val result = UsrReadListParser.parse(fixture("usrread.html"))

        assertEquals(2, result.records.size)
        assertEquals("1000000000001", result.records.first().tilcod)
        assertEquals("全角ＡＢＣ 著者", result.records.first().title)
        assertEquals(LocalDate.of(2026, 7, 18), result.records.first().loanDate)
        assertEquals("中央図書館", result.records.first().library)
        assertEquals(20, result.nextStartIndex)
    }

    @Test
    fun `読書履歴の実サイトHTMLをパースできる`() {
        val result = UsrReadListParser.parse(fixture("usrread_live.html"))

        assertEquals(10, result.records.size)
        assertTrue(result.records.all { it.tilcod.matches(Regex("\\d{13}")) })
        assertTrue(result.records.first().title.contains("ナゾロリ"))
        assertEquals(LocalDate.of(2026, 7, 18), result.records.first().loanDate)
        assertEquals("高須分室", result.records.first().library)
        assertEquals(10, result.nextStartIndex)
    }

    @Test
    fun `読書履歴の0件画面をパースできる`() {
        val result = UsrReadListParser.parse(
            "<h1>読書履歴</h1><table summary='読書履歴一覧表'><thead><tr>" +
                "<th>No</th><th>書誌情報</th><th>貸出日</th><th>貸出館</th></tr></thead><tbody></tbody></table>",
        )
        assertTrue(result.records.isEmpty())
        assertEquals(null, result.nextStartIndex)
    }

    @Test
    fun `読書履歴の不正HTMLはParseExceptionになる`() = assertParseError("usr_read_list") {
        UsrReadListParser.parse("<h1>読書履歴</h1>")
    }

    @Test
    fun `貸出一覧の0件画面をパースできる`() {
        assertTrue(LoanListParser.parse(emptyLoanList()).isEmpty())
    }

    @Test
    fun `貸出一覧の不正HTMLはParseExceptionになる`() = assertParseError("loan_list") {
        LoanListParser.parse("<h1>貸出状況一覧</h1>")
    }

    @Test
    fun `予約一覧フィクスチャをパースできる`() {
        val result = ReservationListParser.parse(fixture("usrrsv.html"))
        assertEquals(19, result.size)
        assertEquals(UNASSIGNED_MEMBER_ID, result.first().memberId)
        assertEquals("1000001898886", result.first().tilcod)
        assertTrue(result.all { it.tilcod.matches(Regex("\\d{13}")) })
        assertEquals("", result.first().pickupLibrary)
        assertEquals(13, result.first().queuePosition)
        assertEquals(ReservationState.WAITING, result.first().state)
        assertEquals("高須分室", result[12].pickupLibrary)
        assertEquals(ReservationState.READY, result[12].state)
        assertEquals(LocalDate.of(2026, 7, 10), result[12].reservedDate)
        assertEquals(LocalDate.of(2026, 7, 23), result[12].holdExpiryDate)
        assertEquals(ReservationState.UNKNOWN, result[13].state)
        assertEquals(ReservationState.READY, result.last().state)
        assertEquals(LocalDate.of(2026, 7, 29), result.last().holdExpiryDate)
    }

    @Test
    fun `予約一覧の0件画面をパースできる`() {
        assertTrue(ReservationListParser.parse(emptyReservationList()).isEmpty())
    }

    @Test
    fun `予約一覧の不正HTMLはParseExceptionになる`() = assertParseError("reservation_list") {
        ReservationListParser.parse("<h1>予約状況一覧</h1>")
    }

    @Test
    fun `取置期限の月日表記は基準日より前なら翌年に補完する`() {
        val result = ReservationListParser.parse(
            """
            <h1>予約状況一覧</h1>
            <table summary='予約状況一覧表'><thead><tr><th>資料名</th><th>書誌種別</th><th>受取館</th><th>予約日</th><th>順位</th><th>予約状態</th><th>取置期限</th></tr></thead>
            <tbody><tr><td>年越し予約</td><td>一般</td><td>高須分室　Ｅｍａｉｌ</td><td>26/12/30 26/12/30</td><td></td><td>提供可能</td><td>01/05</td></tr></tbody></table>
            """.trimIndent(),
        )
        assertEquals("高須分室", result.single().pickupLibrary)
        assertEquals(LocalDate.of(2027, 1, 5), result.single().holdExpiryDate)
    }

    @Test
    fun `本棚フィクスチャをパースできる`() {
        val result = ShelfParser.parse(fixture("mybooklist.html"))
        assertEquals(1, result.shelf.no)
        assertEquals("借りるか悩み中", result.shelf.name)
        assertEquals(6, result.items.size)
        assertEquals(UNASSIGNED_MEMBER_ID, result.items.first().memberId)
        assertEquals(1, result.items.first().shelfNo)
        assertEquals("借りるか悩み中", result.items.first().shelfName)
        assertEquals("1001000581719", result.items.first().tilcod)
        assertEquals("見て考えたい", result.items.first().memo)
        assertEquals(LocalDate.of(2021, 9, 2), result.items.first().registeredDate)
    }

    @Test
    fun `本棚の0件画面をパースできる`() {
        val result = ShelfParser.parse(
            "<h1>マイ本棚</h1><form name='LBForm'><input name='otherbook' value='1'></form>" +
                "<table summary='本棚属性'><tbody><tr><td><em class='huge'>空の本棚</em></td></tr></tbody></table>" +
                "<table summary='リスト詳細'><tbody></tbody></table>",
        )
        assertEquals("空の本棚", result.shelf.name)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `本棚の不正HTMLはParseExceptionになる`() = assertParseError("shelf") {
        ShelfParser.parse("<h1>マイ本棚</h1>")
    }

    @Test
    fun `本棚一覧はコメント内のselectから順番どおりに取得する`() {
        val html = fixture("mybooklist.html")
        assertTrue(org.jsoup.Jsoup.parse(html).select("select[name=otherbook]").isEmpty())

        val shelves = ShelfListParser.parse(html)

        assertEquals(listOf(1, 2, 3, 4, 5), shelves.map { it.no })
        assertEquals(
            listOf("借りるか悩み中", "高須にあるやつ", "借りたことあるやつ", "今度借りる", "シリーズ本の借りた続き"),
            shelves.map { it.name },
        )
    }

    @Test
    fun `利用状況サマリフィクスチャをパースできる`() {
        val result = SummaryParser.parse(fixture("usrlend.html"))
        assertEquals(UNASSIGNED_MEMBER_ID, result.memberId)
        assertEquals(5, result.shelfCount)
        assertEquals(12, result.loanCount)
        assertEquals(19, result.reservationCount)
        assertEquals(4, result.cartCount)
    }

    @Test
    fun `利用状況サマリの0件画面をパースできる`() {
        val result = SummaryParser.parse(emptySummary())
        assertEquals(0, result.shelfCount)
        assertEquals(0, result.loanCount)
        assertEquals(0, result.reservationCount)
        assertEquals(0, result.cartCount)
    }

    @Test
    fun `利用状況サマリの不正HTMLはParseExceptionになる`() = assertParseError("summary") {
        SummaryParser.parse("<div id='stat-login'></div>")
    }

    @Test
    fun `カレンダーフィクスチャをパースできる`() {
        val result = CalendarParser.parse(fixture("calendar.html"))
        assertEquals(16, result.size)
        assertEquals(LocalDate.of(2026, 7, 2), result.first())
        assertEquals(LocalDate.of(2026, 9, 28), result.last())
    }

    @Test
    fun `カレンダーの休日なし画面をパースできる`() {
        assertTrue(CalendarParser.parse("<h1>図書館カレンダー</h1><script>var holidayzonzai = 0;</script>").isEmpty())
    }

    @Test
    fun `カレンダーの不正HTMLはParseExceptionになる`() = assertParseError("calendar") {
        CalendarParser.parse("<h1>図書館カレンダー</h1>")
    }

    @Test
    fun `hashとgamenidをフィクスチャから抽出できる`() {
        val result = HashExtractor.extract(fixture("usrlend.html"))
        assertEquals("1249c619e529de0b66c5fb9d64dfb98392615089", result.hash)
        assertEquals("tiles.WUsrLendList", result.gamenId)
    }

    @Test
    fun `空のhashは有効な値として抽出できる`() {
        val search = HashExtractor.extract(fixture("search_form.html"))
        val login = HashExtractor.extract(fixture("login_form.html"))
        assertEquals("", search.hash)
        assertEquals("", login.hash)
    }

    @Test
    fun `hash要素またはgamenidが欠落した画面はParseExceptionになる`() {
        assertParseError("hash") {
            HashExtractor.extract("<form name='LBForm'><input name='hash' value=''></form>")
        }
        assertParseError("hash") {
            HashExtractor.extract("<form name='LBForm'><input name='gamenid' value='x'></form>")
        }
    }

    @Test
    fun `新着資料ジャンル一覧からジャンルコードを抽出できる`() {
        val codes = NewArrivalMenuParser.parseGenreCodes(fixture("new_arrival_menu.html"))
        assertEquals(listOf("01", "22", "27"), codes)
    }

    @Test
    fun `新着資料ジャンル一覧にリンクがなければParseExceptionになる`() = assertParseError("new_arrival_menu") {
        NewArrivalMenuParser.parseGenreCodes("<h1>新着資料ジャンル一覧</h1><table class='list'></table>")
    }

    @Test
    fun `新着資料一覧フィクスチャをパースできる`() {
        val arrivals = NewArrivalListParser.parse(fixture("new_arrival_list.html"))
        assertEquals(3, arrivals.size)

        val first = arrivals[0]
        assertEquals("1000002034242", first.tilcod)
        assertEquals("御成敗式目の殺人", first.title)
        assertEquals("", first.volume)
        assertEquals("羽生 飛鳥／著", first.author)
        assertEquals("中央公論新社", first.publisher)
        assertEquals("2026/06", first.publishedYearMonth)
        assertEquals("Fﾊﾆ", first.classification)
        assertEquals(false, first.lendable)

        // 巻次あり・貸出可
        assertEquals("2", arrivals[1].volume)
        assertEquals(true, arrivals[1].lendable)

        // 貸出セルが空なら判定不能(null)
        assertEquals(null, arrivals[2].lendable)
        assertEquals("1000002099999", arrivals[2].tilcod)
    }

    @Test
    fun `新着資料一覧の結果テーブルが無ければParseExceptionになる`() = assertParseError("new_arrival_list") {
        NewArrivalListParser.parse("<h1>新着資料一覧</h1>")
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader).getResource("fixtures/$name")!!.readText()

    // ブラウザ実測: 予約導線はWOpacMsgNewListToTifTilDetailAction.do経由でtiles.WTifTilDetail2・
    // hash非空で描画される。実HTMLフィクスチャ(gamenid=tiles.WTifTilDetail・hash空)をテスト内で
    // その形へ書き換えて使う。実HTMLファイル自体は書き換えない。
    private fun reservationDetailFixture(): String = fixture("book_detail.html")
        .replace("name=\"gamenid\" value=\"tiles.WTifTilDetail\"", "name=\"gamenid\" value=\"tiles.WTifTilDetail2\"")
        .let(::withNonEmptyDetailHash)

    // book_detail.html にはhidden hashが2つ存在する（LBFormMFの1つ目、予約対象のLBFormの2つ目）。
    // 実測ではLBFormのhashが非空で発行されるため、2つ目だけを書き換える。改行コードには依存しない。
    private fun withNonEmptyDetailHash(html: String): String {
        var occurrence = 0
        return Regex("name=\"hash\" value=\"\"").replace(html) { match ->
            occurrence += 1
            if (occurrence == 2) "name=\"hash\" value=\"detail-hash\"" else match.value
        }
    }

    private fun loginFormWith(
        usernameType: String = "text",
        jUsernameType: String = "hidden",
        passwordType: String = "password",
    ): String = """
        <form>
          <input type="$usernameType" name="username" />
          <input type="$jUsernameType" name="j_username" />
          <input type="$passwordType" name="j_password" />
        </form>
    """.trimIndent()

    private fun assertParseError(screen: String, block: () -> Unit) {
        val error = try {
            block()
            throw AssertionError("ParseException が送出されませんでした")
        } catch (exception: ParseException) {
            exception
        }
        assertEquals(screen, error.screen)
    }

    private fun emptyBookDetail(
        holdingsTable: String = """
            <table summary='資料情報２'><thead><tr><th>No.</th><th>所蔵館</th><th>資料番号</th><th>資料種別</th><th>請求記号</th><th>配架場所</th><th>帯出区分</th><th>状態</th></tr></thead><tbody></tbody></table>
        """.trimIndent(),
    ): String = """
        <h1>検索結果書誌詳細</h1>
        <table summary='蔵書情報'><tr><th>所蔵数</th><td>0</td><th>在庫数</th><td>0</td><th>予約数</th><td>0</td></tr></table>
        <table summary='詳細情報'><tr><th>タイトルコード</th><td>0</td></tr></table>
        $holdingsTable
    """.trimIndent()

    private fun emptyLoanList(): String = """
        <h1>貸出状況一覧</h1>
        <table summary='貸出状況一覧表'><thead><tr><th>資料名</th><th>書誌種別</th><th>貸出館</th><th>貸出日</th><th>返却期日</th><th>状態</th></tr></thead><tbody></tbody></table>
    """.trimIndent()

    private fun emptyReservationList(): String = """
        <h1>予約状況一覧</h1>
        <table summary='予約状況一覧表'><thead><tr><th>資料名</th><th>書誌種別</th><th>受取館</th><th>予約日</th><th>順位</th><th>予約状態</th><th>取置期限</th></tr></thead><tbody></tbody></table>
    """.trimIndent()

    private fun emptySummary(): String = """
        <div id='stat-login'>
          <span id='stat-shlf'><span class='value'>0</span></span>
          <span id='stat-lent'><span class='value'>0</span></span>
          <span id='stat-resv'><span class='value'>0</span></span>
          <span id='stat-cart'><span class='value'>0</span></span>
        </div>
    """.trimIndent()
}
