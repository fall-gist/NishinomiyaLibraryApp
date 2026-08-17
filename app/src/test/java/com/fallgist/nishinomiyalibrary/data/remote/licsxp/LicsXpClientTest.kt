package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.PageTokens
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecordKey
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LicsXpClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `検索はフォームGETとPOSTを順に行いCookieとUser-Agentを送る`() = runBlocking {
        server.enqueue(html(fixture("search_form.html"), setCookie = true))
        server.enqueue(html(fixture("search_result.html")))

        val result = client().search("愛の哲学")

        assertEquals(5286, result.totalCount)
        assertEquals("1000000961766", result.hits.first().tilcod)
        assertTrue(result.hasNext)

        val formRequest = takeRequest()
        assertEquals("GET", formRequest.method)
        assertEquals("/WOpacEsSchCmpdDispAction.do", formRequest.requestUrl!!.encodedPath)
        assertEquals(LicsXpSession.USER_AGENT, formRequest.getHeader("User-Agent"))

        val searchRequest = takeRequest()
        assertEquals("POST", searchRequest.method)
        assertEquals("/WOpacEsSchCmpdExecAction.do", searchRequest.requestUrl!!.encodedPath)
        assertTrue(searchRequest.getHeader("Cookie")!!.contains("JSESSIONID=fixture"))
        assertEquals("愛の哲学", formValue(searchRequest, "condition1Text"))
        assertEquals("tiles.WEsSchCmpd", formValue(searchRequest, "gamenid"))
        assertEquals("1", formValue(searchRequest, "tifKanrabtn"))
        assertEquals("nocheck", formValue(searchRequest, "loccodschkflg"))
        assertEquals("", formValue(searchRequest, "returnid"))
        assertEquals("", formValue(searchRequest, "hash"))
        assertEquals("", formValue(searchRequest, "chkflg"))
    }

    @Test
    fun `検索2ページ目は直近トークンをフォームとクエリに送る`() = runBlocking {
        server.enqueue(html(fixture("search_form.html")))
        server.enqueue(html(fixture("search_result.html")))
        server.enqueue(html(fixture("search_result.html")))

        client().search("愛", page = 2)

        takeRequest()
        takeRequest()
        val pageRequest = takeRequest()
        assertEquals("POST", pageRequest.method)
        assertEquals("/WOpacWebEsTilSubListAction.do", pageRequest.requestUrl!!.encodedPath)
        assertEquals("", pageRequest.requestUrl!!.queryParameter("sortKey"))
        assertEquals("20", pageRequest.requestUrl!!.queryParameter("startIndex"))
        assertEquals("", pageRequest.requestUrl!!.queryParameter("hash"))
        assertEquals("", formValue(pageRequest, "hash"))
        assertEquals("tiles.WEsTilList", formValue(pageRequest, "gamenid"))
    }

    @Test
    fun `公開APIはJSONと既存パーサを使って具体値を返す`() = runBlocking {
        server.enqueue(json("[\"愛\",\"愛の哲学\"]"))
        server.enqueue(json("{\"isLend\":\"1\"}"))
        server.enqueue(json("{\"isLend\":\"0\"}"))
        server.enqueue(json("{\"isLend\":\"unknown\"}"))
        server.enqueue(html(fixture("book_detail.html")))
        server.enqueue(html(fixture("calendar.html")))

        val gateway = client()
        assertEquals(listOf("愛", "愛の哲学"), gateway.autocomplete("愛"))
        assertEquals(true, gateway.isLendable("1000000961766"))
        assertEquals(false, gateway.isLendable("1000000961766"))
        assertNull(gateway.isLendable("1000000961766"))
        assertEquals("愛の哲学", gateway.bookDetail("1000000961766").fields["書名"])
        val closedDays = gateway.closedDays("106")
        assertEquals(16, closedDays.size)
        assertEquals(LocalDate.of(2026, 7, 2), closedDays.first())

        val autocomplete = takeRequest()
        assertEquals("GET", autocomplete.method)
        assertEquals("/WOpacEsApiAutoCompleteAction.do", autocomplete.requestUrl!!.encodedPath)
        assertEquals("愛", autocomplete.requestUrl!!.queryParameter("keyword"))
        repeat(3) {
            val lend = takeRequest()
            assertEquals("POST", lend.method)
            assertEquals("/getIsLend.do", lend.requestUrl!!.encodedPath)
            assertEquals("1000000961766", formValue(lend, "tilcod"))
        }
        val detail = takeRequest()
        assertEquals("/WOpacTifTilListToTifTilDetailAction.do", detail.requestUrl!!.encodedPath)
        assertEquals("1", detail.requestUrl!!.queryParameter("urlNotFlag"))
        assertEquals("1000000961766", detail.requestUrl!!.queryParameter("tilcod"))
        val calendar = takeRequest()
        assertEquals("/WOpacMnuTopInitAction.do", calendar.requestUrl!!.encodedPath)
        assertEquals("msgcld", calendar.requestUrl!!.queryParameter("moveToGamenId"))
        assertEquals("106", calendar.requestUrl!!.queryParameter("loccod"))
    }

    @Test
    fun `利用者データ取得は認証後に3ページをトークン付きで順に読む`() = runBlocking {
        server.enqueue(html("<html><body>温めページ</body></html>", setCookie = true))
        server.enqueue(html(fixture("login_form.html")))
        server.enqueue(html("""
            <html><body><form action="continue"></form>
            <script>document.forms[0].submit()</script></body></html>
        """.trimIndent()))
        server.enqueue(html(fixture("menu.html")))
        server.enqueue(html(fixture("usrlend.html")))
        server.enqueue(html(fixture("usrrsv.html")))
        server.enqueue(html(fixture("mybooklist.html")))
        server.enqueue(html(shelfPage(2, "高須にあるやつ", "shelf-hash-2")))
        server.enqueue(html(shelfPage(3, "借りたことあるやつ", "shelf-hash-3")))
        server.enqueue(html(shelfPage(4, "今度借りる", "shelf-hash-4")))
        server.enqueue(html(shelfPage(5, "シリーズ本の借りた続き", "shelf-hash-5")))
        server.enqueue(html(usrReadPage(hash = "history-open", records = emptyList())))
        server.enqueue(html(emptyUsrReadPage()))
        val cardNumber = generatedCardNumber()
        val password = generatedPassword()

        val result = client().fetchUserData(cardNumber, password)

        assertEquals(5, result.summary.shelfCount)
        assertEquals(12, result.summary.loanCount)
        assertEquals(19, result.summary.reservationCount)
        assertEquals(4, result.summary.cartCount)
        assertEquals(12, result.loans.size)
        assertEquals(19, result.reservations.size)
        assertEquals(listOf(1, 2, 3, 4, 5), result.shelves.map { it.no })
        assertEquals(
            listOf("借りるか悩み中", "高須にあるやつ", "借りたことあるやつ", "今度借りる", "シリーズ本の借りた続き"),
            result.shelves.map { it.name },
        )
        assertEquals(30, result.shelfItems.size)
        assertEquals(
            result.shelves.associate { it.name to 6 },
            result.shelfItems.groupBy { it.shelfName }.mapValues { it.value.size },
        )

        val warmUp = takeRequest()
        assertEquals("GET", warmUp.method)
        assertEquals("/WOpacEsSchCmpdDispAction.do", warmUp.requestUrl!!.encodedPath)
        assertNull(warmUp.requestUrl!!.query)

        val login = takeRequest()
        assertEquals("GET", login.method)
        assertEquals("/OpacInitLoginAction.do", login.requestUrl!!.encodedPath)
        assertEquals("0", login.requestUrl!!.queryParameter("subSystemFlag"))
        assertTrue(login.getHeader("Cookie")!!.contains("JSESSIONID=fixture"))

        val security = takeRequest()
        assertEquals("POST", security.method)
        assertEquals("/j_security_check", security.requestUrl!!.encodedPath)
        assertEquals("0", security.requestUrl!!.queryParameter("subSystemFlag"))
        assertTrue(security.getHeader("Cookie")!!.contains("JSESSIONID=fixture"))
        assertEquals(
            listOf(
                "hash" to "",
                "gamenid" to "tiles.WMnuTop",
                "username" to cardNumber,
                "j_username" to "0".repeat(16) + cardNumber,
                "h_username" to "",
                "j_password" to password,
            ),
            formFields(security),
        )

        val menu = takeRequest()
        assertEquals("GET", menu.method)
        assertEquals("/WOpacMnuTopInitAction.do", menu.requestUrl!!.encodedPath)
        assertEquals("1", menu.requestUrl!!.queryParameter("WebLinkFlag"))
        assertTrue(menu.getHeader("Cookie")!!.contains("JSESSIONID=fixture"))

        assertUserPageRequest(takeRequest(), "usrlend", PageTokens("1249c619e529de0b66c5fb9d64dfb98392615089", "tiles.WUsrRsvList"))
        assertUserPageRequest(takeRequest(), "usrrsv", PageTokens("1249c619e529de0b66c5fb9d64dfb98392615089", "tiles.WUsrLendList"))
        assertUserPageRequest(takeRequest(), "mybooklist", PageTokens("1249c619e529de0b66c5fb9d64dfb98392615089", "tiles.WUsrRsvList"))
        assertShelfSwitchRequest(takeRequest(), "1249c619e529de0b66c5fb9d64dfb98392615089", 2)
        assertShelfSwitchRequest(takeRequest(), "shelf-hash-2", 3)
        assertShelfSwitchRequest(takeRequest(), "shelf-hash-3", 4)
        assertShelfSwitchRequest(takeRequest(), "shelf-hash-4", 5)
        val readOpen = takeRequest()
        assertEquals("POST", readOpen.method)
        assertEquals("/WOpacMnuTopToPwdLibraryAction.do", readOpen.requestUrl!!.encodedPath)
        assertEquals("usrread", readOpen.requestUrl!!.queryParameter("gamen"))
        assertEquals("0", readOpen.requestUrl!!.queryParameter("initFlag"))
        assertNull(readOpen.requestUrl!!.queryParameter("pagingMax"))
        assertEquals("shelf-hash-5", formValue(readOpen, "hash"))
        val readList = takeRequest()
        assertEquals("POST", readList.method)
        assertEquals("/WOpacUsrReadListAction.do", readList.requestUrl!!.encodedPath)
        assertEquals("history-open", formValue(readList, "hash"))
        assertEquals("tiles.WUsrReadList", formValue(readList, "gamenid"))
        assertEquals("100", formValue(readList, "rowsPerPage"))
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `本棚0件の実測フィクスチャ応答では本棚取得だけをスキップし他データは取得できる`() = runBlocking {
        // 新規アカウント等、本棚0件のときの画面は実物では新規作成フォーム(見出し「マイ本棚の新規作成」)を返す。
        // select[name=otherbook]自体はプレースホルダoption(value=0)を持って存在するためShelfListParserは
        // 成功してしまい、input[name=otherbook]が無いことでShelfParserがParseExceptionを投げて初めて
        // 本棚取得ブロック全体がスキップされる (docs/design/account-and-bookshelf-fixes.md §2.1, §2.3.A)。
        server.enqueue(html("<html><body>温めページ</body></html>", setCookie = true))
        server.enqueue(html(fixture("login_form.html")))
        server.enqueue(html(fixture("after_login.html")))
        server.enqueue(html(fixture("menu.html")))
        server.enqueue(html(fixture("usrlend.html")))
        server.enqueue(html(fixture("usrrsv.html")))
        server.enqueue(html(fixture("mybooklist_empty.html")))
        server.enqueue(html(usrReadPage(hash = "history-open", records = emptyList())))
        server.enqueue(html(emptyUsrReadPage()))

        val result = client().fetchUserData(generatedCardNumber(), generatedPassword())

        assertFalse(result.shelvesAvailable)
        assertTrue(result.shelves.isEmpty())
        assertTrue(result.shelfItems.isEmpty())
        assertEquals(12, result.loans.size)
        assertEquals(19, result.reservations.size)

        val requests = List(9) { takeRequest() }
        assertUserPageRequest(requests[6], "mybooklist", PageTokens("1249c619e529de0b66c5fb9d64dfb98392615089", "tiles.WUsrRsvList"))
        // 本棚取得をスキップするため、切り替えPOSTは一切送らない。
        assertTrue(requests.none { it.requestUrl!!.encodedPath == "/WOpacSdiBookListToOtherBookDispAction.do" })
        assertEquals("/WOpacMnuTopToPwdLibraryAction.do", requests[7].requestUrl!!.encodedPath)
        assertEquals("usrread", requests[7].requestUrl!!.queryParameter("gamen"))
        assertEquals("masked-hash-token-2026-08-17", formValue(requests[7], "hash"))
        assertEquals("history-open", formValue(requests[8], "hash"))
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `本棚切替中のメンテナンス検知は本棚スキップに倒れずMaintenanceとして伝播する`() = runBlocking {
        // docs/design/account-and-bookshelf-fixes.md §2.3.A: 本棚解析ブロックで捕捉するのは
        // ParseExceptionだけであり、メンテナンス・通信・認証の失敗は従来どおり伝播させる。
        // ここを`catch (exception: Exception)`へ広げる劣化が起きても、他の本棚関連テストは
        // 1件も落ちないことがレビューで判明したため、切替ループ2件目でのメンテナンス検知を
        // 専用に固定する。
        server.enqueue(html("<html><body>温めページ</body></html>", setCookie = true))
        server.enqueue(html(fixture("login_form.html")))
        server.enqueue(html(fixture("after_login.html")))
        server.enqueue(html(fixture("menu.html")))
        server.enqueue(html(fixture("usrlend.html")))
        server.enqueue(html(fixture("usrrsv.html")))
        server.enqueue(html(fixture("mybooklist.html")))
        server.enqueue(html(shelfPage(2, "高須にあるやつ", "shelf-hash-2")))
        // 2件目の切替応答(本棚3への切替)をメンテナンス画面にする。
        server.enqueue(html("<html><body>システムメンテナンス中です</body></html>"))
        // 本棚スキップへ倒れて後続処理が続行してしまう劣化が起きても、実時間のタイムアウト待ちに
        // 頼らず即座に検出できるよう、後続の読書履歴ページも用意しておく(正常系では未消費のまま残る)。
        server.enqueue(html(usrReadPage(hash = "history-open", records = emptyList())))
        server.enqueue(html(emptyUsrReadPage()))

        val error = libraryError { client().fetchUserData(generatedCardNumber(), generatedPassword()) }

        assertTrue(error is LibraryError.Maintenance)
    }

    @Test
    fun `利用状況取得は通常同期のusrrsvまでの厳密な接頭辞だけを実行する`() = runBlocking {
        server.enqueue(html("<html><body>温めページ</body></html>", setCookie = true))
        server.enqueue(html(fixture("login_form.html")))
        server.enqueue(html("<html><body>login</body></html>"))
        server.enqueue(html(fixture("menu.html")))
        server.enqueue(html(fixture("usrlend.html")))
        server.enqueue(html(fixture("usrrsv.html")))

        val snapshot = client().fetchCurrentCirculation(generatedCardNumber(), generatedPassword())

        assertEquals(12, snapshot.loans.size)
        assertEquals(19, snapshot.reservations.size)
        assertTrue(snapshot.reservationListComplete)
        assertEquals("/WOpacEsSchCmpdDispAction.do", takeRequest().requestUrl!!.encodedPath)
        assertEquals("/OpacInitLoginAction.do", takeRequest().requestUrl!!.encodedPath)
        assertEquals("/j_security_check", takeRequest().requestUrl!!.encodedPath)
        assertEquals("/WOpacMnuTopInitAction.do", takeRequest().requestUrl!!.encodedPath)
        assertUserPageRequest(takeRequest(), "usrlend", PageTokens("1249c619e529de0b66c5fb9d64dfb98392615089", "tiles.WUsrRsvList"))
        assertUserPageRequest(takeRequest(), "usrrsv", PageTokens("1249c619e529de0b66c5fb9d64dfb98392615089", "tiles.WUsrLendList"))
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `利用状況取得の完全性は貸出ページの予約数を根拠にする`() = runBlocking {
        server.enqueue(html("<html><body>温めページ</body></html>", setCookie = true))
        server.enqueue(html(fixture("login_form.html")))
        server.enqueue(html("<html><body>login</body></html>"))
        // メニューの件数は意図的に壊し、usrlendのサマリだけを根拠にできることを確認する。
        server.enqueue(html(fixture("menu.html").replace(
            Regex("""(<a id="stat-resv"[\s\S]*?<span class="value"[^>]*>)19"""),
            "$1" + "999",
        )))
        server.enqueue(html(fixture("usrlend.html")))
        server.enqueue(html(fixture("usrrsv.html")))

        assertTrue(client().fetchCurrentCirculation(generatedCardNumber(), generatedPassword()).reservationListComplete)
    }

    @Test
    fun `読書履歴の初回同期は全ページをGETで取得し削除操作を作らない`() = runBlocking {
        enqueueAuthenticatedUserData(
            usrReadPages = listOf(
                usrReadPage(
                    hash = "history-hash-0",
                    nextStartIndexes = listOf(20, 100),
                    records = listOf(
                        UsrReadFixture("1000000000011", "新しい記録", "2026/07/18", "中央図書館"),
                        UsrReadFixture("1000000000012", "次に新しい記録", "2026/07/17", "北口図書館"),
                    ),
                ),
                usrReadPage(
                    hash = "history-hash-20",
                    records = listOf(UsrReadFixture("1000000000013", "過去の記録", "2026/07/16", "鳴尾図書館")),
                ),
            ),
        )

        val records = client().fetchUserData(generatedCardNumber(), generatedPassword()).readingRecords

        assertEquals(listOf("1000000000011", "1000000000012", "1000000000013"), records.map { it.tilcod })
        repeat(11) { takeRequest() }
        val openRequest = takeRequest()
        assertEquals("POST", openRequest.method)
        assertEquals("usrread", openRequest.requestUrl!!.queryParameter("gamen"))
        assertEquals("0", openRequest.requestUrl!!.queryParameter("initFlag"))
        assertNull(openRequest.requestUrl!!.queryParameter("pagingMax"))
        val listRequest = takeRequest()
        assertEquals("POST", listRequest.method)
        assertEquals("/WOpacUsrReadListAction.do", listRequest.requestUrl!!.encodedPath)
        assertEquals("history-open", formValue(listRequest, "hash"))
        assertEquals("tiles.WUsrReadList", formValue(listRequest, "gamenid"))
        assertEquals("100", formValue(listRequest, "rowsPerPage"))
        val nextRequest = takeRequest()
        assertEquals("GET", nextRequest.method)
        assertEquals("/WOpacUsrReadListAction.do", nextRequest.requestUrl!!.encodedPath)
        assertEquals("KASYMD", nextRequest.requestUrl!!.queryParameter("sortKey"))
        assertEquals("false", nextRequest.requestUrl!!.queryParameter("isAsc"))
        assertEquals("20", nextRequest.requestUrl!!.queryParameter("startIndex"))
        assertEquals("history-hash-0", nextRequest.requestUrl!!.queryParameter("hash"))
        assertNull(nextRequest.requestUrl!!.queryParameter("pagingMax"))
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `読書履歴の差分同期はページ内の最初の既知行直前で停止する`() = runBlocking {
        enqueueAuthenticatedUserData(
            usrReadPages = listOf(
                usrReadPage(
                    hash = "history-hash-0",
                    nextStartIndexes = listOf(20),
                    records = listOf(
                        UsrReadFixture("1000000000021", "新規一冊目", "2026/07/18", "中央図書館"),
                        UsrReadFixture("1000000000022", "既知の記録", "2026/07/17", "北口図書館"),
                        UsrReadFixture("1000000000023", "既知行より古い未知記録", "2026/07/16", "鳴尾図書館"),
                    ),
                ),
            ),
        )

        val records = client().fetchUserData(
            cardNumber = generatedCardNumber(),
            password = generatedPassword(),
            knownReadingRecordKeys = setOf(ReadingRecordKey("1000000000022", LocalDate.of(2026, 7, 17))),
        ).readingRecords

        assertEquals(listOf("1000000000021"), records.map { it.tilcod })
        val requests = List(13) { takeRequest() }
        assertTrue(requests.none { it.requestUrl!!.encodedPath.contains("Delete", ignoreCase = true) })
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `ログインフォームの再表示はAuthに分類し認証情報を例外メッセージに含めない`() = runBlocking {
        server.enqueue(html("<html><body>温めページ</body></html>"))
        server.enqueue(html(fixture("login_form.html")))
        server.enqueue(html("<div id=\"stat-login\">認証成功風の中継本文</div>"))
        server.enqueue(html(fixture("login_form.html")))
        val cardNumber = generatedCardNumber()
        val password = generatedPassword()

        val error = libraryError { client().fetchUserData(cardNumber, password) }

        assertTrue(error is LibraryError.Auth)
        assertFalse(error.message.orEmpty().contains(cardNumber))
        assertFalse(error.message.orEmpty().contains(password))
    }

    @Test
    fun `認証POST本文は判定せずメニュー本文だけで成功失敗とメンテナンスを分類する`() = runBlocking {
        val cardNumber = generatedCardNumber()
        val password = generatedPassword()

        server.enqueue(html("<html><body>温めページ</body></html>"))
        server.enqueue(html(fixture("login_form.html")))
        server.enqueue(html("<input name=\"j_password\" value=\"失敗風の中継本文\">"))
        server.enqueue(html(fixture("menu.html").replace("id=\"stat-login\"", "id=\"legacy-login\"")))
        server.enqueue(html(fixture("usrlend.html")))
        server.enqueue(html(fixture("usrrsv.html")))
        server.enqueue(html(fixture("mybooklist.html")))
        enqueueShelfSwitchPages()
        server.enqueue(html(usrReadPage(hash = "history-open", records = emptyList())))
        server.enqueue(html(emptyUsrReadPage()))
        assertEquals(12, client().fetchUserData(cardNumber, password).loans.size)

        server.enqueue(html("<html><body>温めページ</body></html>"))
        server.enqueue(html(fixture("login_form.html")))
        server.enqueue(html("<html><body>システムメンテナンス中の中継本文</body></html>"))
        server.enqueue(html("<input name=\"j_password\">"))
        assertTrue(libraryError { client().fetchUserData(cardNumber, password) } is LibraryError.Auth)

        server.enqueue(html("<html><body>温めページ</body></html>"))
        server.enqueue(html(fixture("login_form.html")))
        server.enqueue(html("<div id=\"stat-login\">成功風の中継本文</div>"))
        server.enqueue(html("<html><body>未知のメニュー</body></html>"))
        val parse = libraryError { client().fetchUserData(cardNumber, password) }
        assertTrue(parse is LibraryError.Parse)
        assertEquals("login", (parse as LibraryError.Parse).screen)

        server.enqueue(html("<html><body>温めページ</body></html>"))
        server.enqueue(html(fixture("login_form.html")))
        server.enqueue(html("<html><body>中継本文</body></html>"))
        server.enqueue(html("<html><body>システムメンテナンス中です</body></html>"))
        assertTrue(libraryError { client().fetchUserData(cardNumber, password) } is LibraryError.Maintenance)
        // 読書履歴の表示件数指定がPOST1回分増えたため、成功パス(1回目)のリクエスト数も+1。
        assertEquals(25, server.requestCount)
    }

    @Test
    fun `参照画面の不正HTMLはParseにメンテナンス画面はMaintenanceに分類する`() = runBlocking {
        server.enqueue(html("<h1>想定外</h1>"))
        val parse = libraryError { client().bookDetail("1000000961766") }
        assertTrue(parse is LibraryError.Parse)
        assertEquals("book_detail", (parse as LibraryError.Parse).screen)

        server.enqueue(html("<html><body>システムメンテナンス中です</body></html>"))
        assertTrue(libraryError { client().closedDays("106") } is LibraryError.Maintenance)
    }

    @Test
    fun `IO失敗は一度だけ再試行し注入した待機関数を使う`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(json("[\"愛\"]"))
        val waits = mutableListOf<Long>()
        val client = LicsXpClient(
            LicsXpSession(
                baseUrl = server.url("/"),
                client = OkHttpClient(),
                waitForRequestSlot = { waits += it },
                nowMillis = { 0L },
            ),
        )

        assertEquals(listOf("愛"), client.autocomplete("愛"))
        assertEquals(listOf(500L), waits)
    }

    @Test
    fun `認証用の新規sessionも公開操作と待機状態を共有する`() = runBlocking {
        server.enqueue(json("[\"愛\"]"))
        server.enqueue(html("<html><body>温めページ</body></html>"))
        server.enqueue(html(fixture("login_form.html")))
        server.enqueue(html(fixture("after_login.html")))
        server.enqueue(html(fixture("menu.html")))
        server.enqueue(html(fixture("usrlend.html")))
        server.enqueue(html(fixture("usrrsv.html")))
        server.enqueue(html(fixture("mybooklist.html")))
        enqueueShelfSwitchPages()
        server.enqueue(html(usrReadPage(hash = "history-open", records = emptyList())))
        server.enqueue(html(emptyUsrReadPage()))
        val waits = mutableListOf<Long>()
        val gateway = LicsXpClient(
            LicsXpSession(
                baseUrl = server.url("/"),
                client = OkHttpClient(),
                waitForRequestSlot = { waits += it },
                nowMillis = { 0L },
            ),
        )

        gateway.autocomplete("愛")
        gateway.fetchUserData(
            cardNumber = generatedCardNumber(),
            password = generatedPassword(),
        )

        assertEquals(List(13) { 500L }, waits)
    }

    private fun client(): LicsXpClient = LicsXpClient(
        LicsXpSession(
            baseUrl = server.url("/"),
            client = OkHttpClient(),
            waitForRequestSlot = {},
        ),
    )

    private fun html(body: String, setCookie: Boolean = false): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .apply { if (setCookie) setHeader("Set-Cookie", "JSESSIONID=fixture; Path=/") }
        .setBody(body)

    private fun json(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun takeRequest() = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))

    private fun formValue(request: okhttp3.mockwebserver.RecordedRequest, name: String): String? =
        formFields(request)
            .firstOrNull { (key, _) -> key == name }
            ?.second

    private fun formFields(request: okhttp3.mockwebserver.RecordedRequest): List<Pair<String, String>> =
        request.body.clone().readUtf8()
            .split('&')
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator < 0) return@mapNotNull null
                val key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8)
                val value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8)
                key to value
            }

    private fun assertUserPageRequest(
        request: okhttp3.mockwebserver.RecordedRequest,
        gamen: String,
        tokens: PageTokens,
    ) {
        assertEquals("POST", request.method)
        assertEquals("/WOpacMnuTopToPwdLibraryAction.do", request.requestUrl!!.encodedPath)
        assertEquals(gamen, request.requestUrl!!.queryParameter("gamen"))
        assertEquals(tokens.hash, formValue(request, "hash"))
        assertEquals(tokens.gamenId, formValue(request, "gamenid"))
        assertTrue(request.getHeader("Cookie")!!.contains("JSESSIONID=fixture"))
    }

    private fun assertShelfSwitchRequest(
        request: okhttp3.mockwebserver.RecordedRequest,
        expectedHash: String,
        shelfNo: Int,
    ) {
        assertEquals("POST", request.method)
        assertEquals("/WOpacSdiBookListToOtherBookDispAction.do", request.requestUrl!!.encodedPath)
        assertEquals("1", request.requestUrl!!.queryParameter("flg"))
        assertEquals(expectedHash, formValue(request, "hash"))
        assertEquals("tiles.WSdiBookList", formValue(request, "gamenid"))
        assertEquals(shelfNo.toString(), formValue(request, "otherbook"))
        assertEquals("", formValue(request, "tilcod"))
        assertEquals("", formValue(request, "btnflg"))
        assertTrue(request.getHeader("Cookie")!!.contains("JSESSIONID=fixture"))
    }

    private fun shelfPage(no: Int, name: String, hash: String): String = fixture("mybooklist.html")
        .replace(
            Regex("""(<form name="LBForm" method="post">[\s\S]*?<input type="hidden" name="hash" value=")[^"]+"""),
        ) { match -> "${match.groupValues[1]}$hash" }
        .replace("name=\"otherbook\" value='1'", "name=\"otherbook\" value='$no'")
        .replace(Regex("""(<em class="huge"\s*>).*?(</em>)""")) { match ->
            "${match.groupValues[1]}$name${match.groupValues[2]}"
        }

    private fun enqueueShelfSwitchPages() {
        server.enqueue(html(shelfPage(2, "高須にあるやつ", "shelf-hash-2")))
        server.enqueue(html(shelfPage(3, "借りたことあるやつ", "shelf-hash-3")))
        server.enqueue(html(shelfPage(4, "今度借りる", "shelf-hash-4")))
        server.enqueue(html(shelfPage(5, "シリーズ本の借りた続き", "shelf-hash-5")))
    }

    private fun enqueueAuthenticatedUserData(usrReadPages: List<String>) {
        server.enqueue(html("<html><body>温めページ</body></html>", setCookie = true))
        server.enqueue(html(fixture("login_form.html")))
        server.enqueue(html(fixture("after_login.html")))
        server.enqueue(html(fixture("menu.html")))
        server.enqueue(html(fixture("usrlend.html")))
        server.enqueue(html(fixture("usrrsv.html")))
        server.enqueue(html(fixture("mybooklist.html")))
        enqueueShelfSwitchPages()
        // 読書履歴を開く応答(rowsPerPage送信用のトークン取得のみに使う)
        server.enqueue(html(usrReadPage(hash = "history-open", records = emptyList())))
        usrReadPages.forEach { page -> server.enqueue(html(page)) }
    }

    private fun emptyUsrReadPage(): String = usrReadPage(hash = "history-empty", records = emptyList())

    private fun usrReadPage(
        hash: String,
        nextStartIndexes: List<Int> = emptyList(),
        records: List<UsrReadFixture>,
    ): String = """
        <html><body><h1>読書履歴</h1>
        <form name="LBForm"><input name="hash" value="$hash"><input name="gamenid" value="tiles.WUsrReadList"></form>
        <table summary="読書履歴一覧表"><thead><tr><th>No</th><th>書誌情報</th><th>貸出日</th><th>貸出館</th><th>貸出区分</th><th>削除</th></tr></thead><tbody>
        ${records.mapIndexed { index, record -> "<tr class='ItemNo'><td>${index + 1}</td><td><a href='detail?tilcod=${record.tilcod}'>${record.title}</a></td><td>${record.loanDate}</td><td>${record.library}</td><td>図書</td><td>削除</td></tr>" }.joinToString("\n")}
        </tbody></table>
        ${nextStartIndexes.joinToString("\n") { index -> "<a href='WOpacUsrReadListAction.do?sortKey=KASYMD&amp;isAsc=false&amp;startIndex=$index&amp;hash=$hash'>次へ</a>" }}
        </body></html>
    """.trimIndent()

    private data class UsrReadFixture(
        val tilcod: String,
        val title: String,
        val loanDate: String,
        val library: String,
    )

    private suspend fun libraryError(block: suspend () -> Unit): LibraryError = try {
        block()
        throw AssertionError("LibraryError が送出されませんでした")
    } catch (error: LibraryError) {
        error
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader).getResource("fixtures/$name")!!.readText()

    private fun generatedCardNumber(): String = UUID.randomUUID()
        .toString()
        .replace("-", "")
        .take(8)

    private fun generatedPassword(): String = UUID.randomUUID().toString()
}
