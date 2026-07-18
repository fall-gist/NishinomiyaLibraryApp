package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.PageTokens
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
        server.enqueue(html(fixture("login_form.html"), setCookie = true))
        server.enqueue(html("""
            <html><body><form action="continue"></form>
            <script>document.forms[0].submit()</script></body></html>
        """.trimIndent()))
        server.enqueue(html(fixture("menu.html")))
        server.enqueue(html(fixture("usrlend.html")))
        server.enqueue(html(fixture("usrrsv.html")))
        server.enqueue(html(fixture("mybooklist.html")))
        val cardNumber = generatedCardNumber()
        val password = generatedPassword()

        val result = client().fetchUserData(cardNumber, password)

        assertEquals(5, result.summary.shelfCount)
        assertEquals(12, result.summary.loanCount)
        assertEquals(19, result.summary.reservationCount)
        assertEquals(4, result.summary.cartCount)
        assertEquals(12, result.loans.size)
        assertEquals(19, result.reservations.size)
        assertEquals(6, result.shelf.size)

        val login = takeRequest()
        assertEquals("GET", login.method)
        assertEquals("/OpacInitLoginAction.do", login.requestUrl!!.encodedPath)
        assertEquals("0", login.requestUrl!!.queryParameter("subSystemFlag"))

        val security = takeRequest()
        assertEquals("POST", security.method)
        assertEquals("/j_security_check", security.requestUrl!!.encodedPath)
        assertEquals("0", security.requestUrl!!.queryParameter("subSystemFlag"))
        assertTrue(security.getHeader("Cookie")!!.contains("JSESSIONID=fixture"))
        assertEquals("0".repeat(16) + cardNumber, formValue(security, "j_username"))
        assertEquals(password, formValue(security, "j_password"))

        val menu = takeRequest()
        assertEquals("GET", menu.method)
        assertEquals("/WOpacMnuTopInitAction.do", menu.requestUrl!!.encodedPath)
        assertEquals("1", menu.requestUrl!!.queryParameter("WebLinkFlag"))
        assertTrue(menu.getHeader("Cookie")!!.contains("JSESSIONID=fixture"))

        assertUserPageRequest(takeRequest(), "usrlend", PageTokens("1249c619e529de0b66c5fb9d64dfb98392615089", "tiles.WUsrRsvList"))
        assertUserPageRequest(takeRequest(), "usrrsv", PageTokens("1249c619e529de0b66c5fb9d64dfb98392615089", "tiles.WUsrLendList"))
        assertUserPageRequest(takeRequest(), "mybooklist", PageTokens("1249c619e529de0b66c5fb9d64dfb98392615089", "tiles.WUsrRsvList"))
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `ログインフォームの再表示はAuthに分類し認証情報を例外メッセージに含めない`() = runBlocking {
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

        server.enqueue(html(fixture("login_form.html")))
        server.enqueue(html("<input name=\"j_password\" value=\"失敗風の中継本文\">"))
        server.enqueue(html(fixture("menu.html").replace("id=\"stat-login\"", "id=\"legacy-login\"")))
        server.enqueue(html(fixture("usrlend.html")))
        server.enqueue(html(fixture("usrrsv.html")))
        server.enqueue(html(fixture("mybooklist.html")))
        assertEquals(12, client().fetchUserData(cardNumber, password).loans.size)

        server.enqueue(html(fixture("login_form.html")))
        server.enqueue(html("<html><body>システムメンテナンス中の中継本文</body></html>"))
        server.enqueue(html("<input name=\"j_password\">"))
        assertTrue(libraryError { client().fetchUserData(cardNumber, password) } is LibraryError.Auth)

        server.enqueue(html(fixture("login_form.html")))
        server.enqueue(html("<div id=\"stat-login\">成功風の中継本文</div>"))
        server.enqueue(html("<html><body>未知のメニュー</body></html>"))
        val parse = libraryError { client().fetchUserData(cardNumber, password) }
        assertTrue(parse is LibraryError.Parse)
        assertEquals("login", (parse as LibraryError.Parse).screen)

        server.enqueue(html(fixture("login_form.html")))
        server.enqueue(html("<html><body>中継本文</body></html>"))
        server.enqueue(html("<html><body>システムメンテナンス中です</body></html>"))
        assertTrue(libraryError { client().fetchUserData(cardNumber, password) } is LibraryError.Maintenance)
        assertEquals(15, server.requestCount)
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
        server.enqueue(html(fixture("login_form.html")))
        server.enqueue(html(fixture("after_login.html")))
        server.enqueue(html(fixture("menu.html")))
        server.enqueue(html(fixture("usrlend.html")))
        server.enqueue(html(fixture("usrrsv.html")))
        server.enqueue(html(fixture("mybooklist.html")))
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

        assertEquals(List(6) { 500L }, waits)
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
        request.body.clone().readUtf8()
            .split('&')
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator < 0) return@mapNotNull null
                val key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8)
                val value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8)
                key to value
            }
            .firstOrNull { (key, _) -> key == name }
            ?.second

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
