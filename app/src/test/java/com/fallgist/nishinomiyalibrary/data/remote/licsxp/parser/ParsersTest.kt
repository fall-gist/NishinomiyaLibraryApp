package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
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
