package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import java.util.concurrent.TimeUnit
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReservationGatewayTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `認証から確認確定と予約一覧照合を同一セッションで送る`() = runBlocking {
        server.enqueue(page("<html>温め</html>", cookie = true))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(fixture("reservation_confirm_js_action.html")))
        server.enqueue(page("<html><div id='stat-login'></div></html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))

        val root = LicsXpSession(server.url("/"), waitForRequestSlot = {})
        val session = LicsXpReservationGateway(root).openAuthenticatedSession("1234", "secret")
        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))
        assertTrue(session.fetchReservations().isNotEmpty())
        session.close()

        val requests = List(9) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals("/WOpacEsSchCmpdDispAction.do", requests[0].path)
        assertEquals("/OpacInitLoginAction.do?subSystemFlag=0", requests[1].path)
        assertEquals("/j_security_check?subSystemFlag=0", requests[2].path)
        assertEquals(
            listOf(
                "hash" to "",
                "gamenid" to "tiles.WMnuTop",
                "username" to "1234",
                "j_username" to "00000000000000001234",
                "h_username" to "",
                "j_password" to "secret",
            ),
            decodeFormFields(requests[2].body.readUtf8()),
        )
        assertEquals("/WOpacTifTilListToTifTilDetailAction.do?urlNotFlag=1&tilcod=1000000000001", requests[4].path)
        assertEquals("GET", requests[4].method)
        assertEquals("/WOpacTifDirectYoyDispAction.do?tilcod=1000000000001", requests[5].path)
        assertEquals("POST", requests[5].method)
        assertEquals("tiles.WTifTilDetail", decodeForm(requests[5].body.readUtf8())["gamenid"]?.single())
        assertEquals(server.url("/WOpacTifTilListToTifTilDetailAction.do?urlNotFlag=1&tilcod=1000000000001").toString(), requests[5].getHeader("Referer"))
        assertEquals("${server.url("/").scheme}://${server.url("/").host}:${server.url("/").port}", requests[5].getHeader("Origin"))
        assertEquals("/WOpacTifDirectYoyExecAction.do?tilcod=1000000000001", requests[6].path)
        assertEquals(1, requests.count { it.path?.startsWith("/WOpacTifDirectYoyExecAction.do") == true })
        assertTrue(requests.none { it.path?.contains("EsTif") == true })
        assertEquals(server.url("/WOpacTifDirectYoyDispAction.do?tilcod=1000000000001").toString(), requests[6].getHeader("Referer"))
        assertEquals("${server.url("/").scheme}://${server.url("/").host}:${server.url("/").port}", requests[6].getHeader("Origin"))
        assertNull(requests[2].getHeader("Referer"))
        assertNull(requests[2].getHeader("Origin"))
        assertNull(requests[4].getHeader("Referer"))
        assertNull(requests[4].getHeader("Origin"))
        assertNull(requests[8].getHeader("Referer"))
        assertNull(requests[8].getHeader("Origin"))
        val body = requests[6].body.readUtf8()
        assertEquals(
            listOf(
                "gamenFlag" to "",
                "hash" to "confirm-hash",
                "returnid" to "",
                "gamenid" to "tiles.WYoyConfirm",
                "tilcod" to "1000000000001",
                "loginshuflag" to "",
                "contactdirectweb" to "4",
                "receivenameFocus" to "0",
                "watsptcodFocus" to "0",
                "contactFocus" to "0",
                "returnValue" to "",
                "bmtime_hide" to "",
                "receivename" to "106",
                "contact" to "4",
            ),
            decodeFormFields(body),
        )
        assertNotNull(requests[6].getHeader("User-Agent"))
    }

    @Test
    fun `指定館が確認画面に無ければ確定POSTを送らない`() = runBlocking {
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(fixture("reservation_confirm.html").replace("<option value=\"106\">高須分室</option>", "")))
        val root = LicsXpSession(server.url("/"), waitForRequestSlot = {})
        val session = LicsXpReservationGateway(root).openAuthenticatedSession("1234", "secret")
        val error = try {
            session.directReserve("1000000000001", "106")
            null
        } catch (exception: InvalidPickupLibraryException) {
            exception
        }
        assertNotNull(error)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `通常書誌詳細がログインフォームなら表示と確定POSTを送らない`() = runBlocking {
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("login_form.html")))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        assertEquals(DirectReservationAttempt.SessionExpiredBeforeSubmit, session.directReserve("1000000000001", "106"))

        val requests = List(5) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals("/WOpacTifTilListToTifTilDetailAction.do?urlNotFlag=1&tilcod=1000000000001", requests.last().path)
        assertTrue(requests.none { it.path?.contains("WOpacTifDirectYoy") == true })
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `確定POSTの接続断でもPOSTを再送しない`() = runBlocking {
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(fixture("reservation_confirm.html")))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))
        assertEquals(7, server.requestCount)
    }

    @Test
    fun `確定POSTがログインフォームを返しても再送しない`() = runBlocking {
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(fixture("reservation_confirm.html")))
        server.enqueue(page(fixture("login_form.html")))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))
        assertEquals(7, server.requestCount)
    }

    @Test
    fun `hash無し確認フォームでもサーバー発行hiddenを保持して確定できる`() = runBlocking {
        server.enqueue(page("<html>温め</html>")); server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>")); server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(fixture("reservation_confirm.html").replace("<input type=\"hidden\" name=\"hash\" value=\"confirm-hash\" />", "")))
        server.enqueue(page("<div id='stat-login'></div>"))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {})).openAuthenticatedSession("1", "p")
        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))
        repeat(6) { server.takeRequest() }
        val fields = decodeForm(requireNotNull(server.takeRequest()).body.readUtf8())
        assertEquals(listOf("keep-me"), fields["siteIssued"])
    }

    @Test
    fun `確認GET直後の競合窓でも別セッションの要求を確定POSTより前へ入れない`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/WOpacEsSchCmpdDispAction.do" -> page("<html>session</html>")
                "/OpacInitLoginAction.do" -> page(fixture("login_form.html"))
                "/j_security_check" -> page("<html>login relay</html>")
                "/WOpacMnuTopInitAction.do" -> page(fixture("menu.html"))
                "/WOpacTifTilListToTifTilDetailAction.do" -> page(reservationDetailFixture())
                "/WOpacTifDirectYoyDispAction.do" -> page(fixture("reservation_confirm_js_action.html"))
                "/WOpacTifDirectYoyExecAction.do" -> page("<html><div id='stat-login'></div></html>")
                "/background" -> page("background")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val now = AtomicLong(0)
        val confirmFetched = CompletableDeferred<Unit>()
        val allowConfirmProcessing = CompletableDeferred<Unit>()
        val waits = mutableListOf<Long>()
        val root = LicsXpSession(
            server.url("/"),
            waitForRequestSlot = { millis ->
                synchronized(waits) { waits += millis }
                now.addAndGet(millis)
            },
            nowMillis = now::get,
        )
        val reservationSession = LicsXpReservationGateway.forTesting(
            root,
            ReservationSequenceHooks(
                afterConfirmFetched = {
                    confirmFetched.complete(Unit)
                    allowConfirmProcessing.await()
                },
            ),
        ).openAuthenticatedSession("1234", "secret")
        now.addAndGet(500)

        val reservation = async {
            reservationSession.directReserve("1000000000001", "106")
        }
        confirmFetched.await()
        val backgroundStarted = CompletableDeferred<Unit>()
        val background = async {
            backgroundStarted.complete(Unit)
            root.get("background")
        }
        backgroundStarted.await()
        yield()

        assertEquals(6, server.requestCount)
        allowConfirmProcessing.complete(Unit)
        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, reservation.await())
        assertEquals("background", background.await())

        val requests = List(8) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals("/WOpacTifTilListToTifTilDetailAction.do?urlNotFlag=1&tilcod=1000000000001", requests[4].path)
        assertEquals("/WOpacTifDirectYoyDispAction.do?tilcod=1000000000001", requests[5].path)
        assertEquals("/WOpacTifDirectYoyExecAction.do?tilcod=1000000000001", requests[6].path)
        assertEquals("/background", requests[7].path)
        assertEquals(1, requests.count { it.path?.startsWith("/WOpacTifDirectYoyExecAction.do") == true })
        assertTrue(waits.all { it >= 500 })
    }

    @Test
    fun `確認画面の例外後にも他セッションの要求を実行できる`() = runBlocking {
        server.enqueue(page("<html>session</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>login relay</html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(fixture("reservation_confirm.html").replace("tiles.WYoyConfirm", "unexpected")))
        server.enqueue(page("after-error"))
        val root = LicsXpSession(server.url("/"), waitForRequestSlot = {})
        val session = LicsXpReservationGateway(root).openAuthenticatedSession("1234", "secret")

        val error = try {
            session.directReserve("1000000000001", "106")
            null
        } catch (exception: LibraryError.Parse) {
            exception
        }

        assertNotNull(error)
        assertEquals("after-error", root.get("after-error"))
        assertEquals(7, server.requestCount)
    }

    @Test
    fun `排他シーケンスのキャンセル後に別セッションの要求を実行できる`() = runBlocking {
        server.enqueue(page("first"))
        server.enqueue(page("after-cancel"))
        val root = LicsXpSession(server.url("/"), waitForRequestSlot = {})
        val otherSession = root.newIsolatedSession()
        val sequenceEntered = CompletableDeferred<Unit>()
        val waitForCancellation = CompletableDeferred<Unit>()
        val sequence = launch {
            root.withExclusiveRequestSequence {
                get("first")
                sequenceEntered.complete(Unit)
                waitForCancellation.await()
            }
        }
        sequenceEntered.await()
        val backgroundStarted = CompletableDeferred<Unit>()
        val background = async {
            backgroundStarted.complete(Unit)
            otherSession.get("after-cancel")
        }
        backgroundStarted.await()
        yield()

        assertEquals(1, server.requestCount)
        sequence.cancelAndJoin()
        assertEquals("after-cancel", background.await())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `contactDirectWebValueがnullなら再表示POSTを送らず確認画面解析だけを行う`() = runBlocking {
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(fixture("reservation_confirm.html")))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        val inspector = session as ReservationConfirmationInspector

        val inspection = inspector.inspectDirectReservationConfirmation("1000000000001", "106", contactDirectWebValue = null)

        assertTrue(inspection is ConfirmationInspection.Parsed)
        assertEquals(null, (inspection as ConfirmationInspection.Parsed).contactSelectionRetry)
        assertEquals(6, server.requestCount)
        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals("/WOpacTifDirectYoyDispAction.do?tilcod=1000000000001", requests[5].path)
        assertTrue(requests.none { it.path?.contains("webrak") == true })
        assertTrue(requests.none { it.path?.startsWith("/WOpacTifDirectYoyExecAction.do") == true })
    }

    @Test
    fun `contactDirectWebValue指定時は再表示POSTを1回だけ送りcontactdirectwebだけ上書きする`() = runBlocking {
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(fixture("reservation_confirm.html")))
        server.enqueue(page(fixture("reservation_confirm.html")))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        val inspector = session as ReservationConfirmationInspector

        val inspection = inspector.inspectDirectReservationConfirmation("1000000000001", "106", contactDirectWebValue = "4")

        assertTrue(inspection is ConfirmationInspection.Parsed)
        val retry = (inspection as ConfirmationInspection.Parsed).contactSelectionRetry
        assertNotNull(retry)
        assertEquals("4", retry!!.requestedValue)
        assertTrue(retry.parsed)
        assertEquals(null, retry.failureReason)

        assertEquals(7, server.requestCount)
        val requests = List(7) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(1, requests.count { it.path == "/WOpacTifDirectYoyDispAction.do?webrak=1" })
        assertTrue(requests.none { it.path?.startsWith("/WOpacTifDirectYoyExecAction.do") == true })
        val retryRequest = requests[6]
        assertEquals("/WOpacTifDirectYoyDispAction.do?webrak=1", retryRequest.path)
        assertEquals("POST", retryRequest.method)
        assertEquals(
            server.url("/WOpacTifDirectYoyDispAction.do?tilcod=1000000000001").toString(),
            retryRequest.getHeader("Referer"),
        )
        assertEquals(
            listOf(
                "gamenFlag" to "",
                "hash" to "confirm-hash",
                "returnid" to "",
                "gamenid" to "tiles.WYoyConfirm",
                "tilcod" to "1000000000001",
                "loginshuflag" to "",
                "contactdirectweb" to "4",
                "receivenameFocus" to "0",
                "watsptcodFocus" to "0",
                "contactFocus" to "0",
                "returnValue" to "",
                "bmtime_hide" to "",
                "siteIssued" to "keep-me",
                "receivename" to "001",
                "contact" to "4",
            ),
            decodeFormFields(retryRequest.body.readUtf8()),
        )
    }

    @Test
    fun `再表示POST応答が確認画面として解析できなければ例外を投げずparsed falseにする`() = runBlocking {
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(fixture("reservation_confirm.html")))
        server.enqueue(page("<html>想定外の画面</html>"))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        val inspector = session as ReservationConfirmationInspector

        val inspection = inspector.inspectDirectReservationConfirmation("1000000000001", "106", contactDirectWebValue = "4")

        assertTrue(inspection is ConfirmationInspection.Parsed)
        val retry = (inspection as ConfirmationInspection.Parsed).contactSelectionRetry
        assertNotNull(retry)
        assertFalse(retry!!.parsed)
        assertNotNull(retry.failureReason)
        assertEquals(7, server.requestCount)
        assertTrue(
            List(7) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
                .none { it.path?.startsWith("/WOpacTifDirectYoyExecAction.do") == true },
        )
    }

    @Test
    fun `確認画面のcontactdirectwebが空でも再表示POSTを送らず確定POSTは1回だけ送る`() = runBlocking {
        // 0ce645a で入れた「webrak=1への再表示POST」は誤りだったため撤去した回帰防止。
        // ブラウザ実測ではcontactdirectwebが空のまま確定POSTしている。
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(emptyContactDirectWebFixture()))
        server.enqueue(page("<html><div id='stat-login'></div></html>"))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))

        assertEquals(7, server.requestCount)
        val requests = List(7) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertTrue(requests.none { it.path?.contains("webrak") == true })
        assertEquals("/WOpacTifDirectYoyExecAction.do?tilcod=1000000000001", requests[6].path)
        assertEquals(1, requests.count { it.path?.startsWith("/WOpacTifDirectYoyExecAction.do") == true })
        assertEquals(
            server.url("/WOpacTifDirectYoyDispAction.do?tilcod=1000000000001").toString(),
            requests[6].getHeader("Referer"),
        )
        assertEquals(
            "${server.url("/").scheme}://${server.url("/").host}:${server.url("/").port}",
            requests[6].getHeader("Origin"),
        )
        assertEquals(
            listOf(
                "gamenFlag" to "",
                "hash" to "confirm-hash",
                "returnid" to "",
                "gamenid" to "tiles.WYoyConfirm",
                "tilcod" to "1000000000001",
                "loginshuflag" to "",
                "contactdirectweb" to "",
                "receivenameFocus" to "0",
                "watsptcodFocus" to "0",
                "contactFocus" to "0",
                "returnValue" to "",
                "bmtime_hide" to "",
                "siteIssued" to "keep-me",
                "receivename" to "106",
                "contact" to "4",
            ),
            decodeFormFields(requests[6].body.readUtf8()),
        )
    }

    @Test
    fun `hashが空な書誌詳細と確認画面の両方でセッショントークンのhashを元DOM位置へ補う`() = runBlocking {
        // book_detail.html のLBFormはhash空、確認画面もhash空版を使い、
        // ログイン直後メニュー(menu.html)のhash "1249c619e529de0b66c5fb9d64dfb98392615089" が
        // 両方のPOSTへ補われることを確認する。
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(emptyHashConfirmFixture()))
        server.enqueue(page("<html><div id='stat-login'></div></html>"))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))

        assertEquals(7, server.requestCount)
        val requests = List(7) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(
            "1249c619e529de0b66c5fb9d64dfb98392615089",
            decodeForm(requests[5].body.readUtf8())["hash"]?.single(),
        )
        assertEquals(
            "1249c619e529de0b66c5fb9d64dfb98392615089",
            decodeForm(requests[6].body.readUtf8())["hash"]?.single(),
        )
    }

    @Test
    fun `ページのhashに既存値があるときはセッショントークンで上書きしない`() = runBlocking {
        // reservation_confirm.html は元々hash="confirm-hash"を発行しているため、
        // menu.htmlのセッショントークンのhashへ置き換わらないことを確認する。
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(fixture("reservation_confirm.html")))
        server.enqueue(page("<html><div id='stat-login'></div></html>"))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))

        assertEquals(7, server.requestCount)
        val requests = List(7) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(
            "confirm-hash",
            decodeForm(requests[6].body.readUtf8())["hash"]?.single(),
        )
    }

    private fun emptyContactDirectWebFixture(): String =
        fixture("reservation_confirm.html").replace(
            "<input type=\"hidden\" name=\"contactdirectweb\" value=\"4\" />",
            "<input type=\"hidden\" name=\"contactdirectweb\" value=\"\" />",
        )

    private fun emptyHashConfirmFixture(): String =
        fixture("reservation_confirm.html").replace(
            "<input type=\"hidden\" name=\"hash\" value=\"confirm-hash\" />",
            "<input type=\"hidden\" name=\"hash\" value=\"\" />",
        )

    private fun page(body: String, cookie: Boolean = false): MockResponse = MockResponse().setBody(body).apply {
        if (cookie) addHeader("Set-Cookie", "JSESSIONID=fixture; Path=/")
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader).getResource("fixtures/$name")!!.readText()

    private fun reservationDetailFixture(): String = fixture("book_detail.html").replace("1000000961766", "1000000000001")

    private fun decodeForm(body: String): Map<String, List<String>> = decodeFormFields(body)
        .groupBy({ it.first }, { it.second })

    private fun decodeFormFields(body: String): List<Pair<String, String>> = body.split('&').filter(String::isNotBlank)
        .map { part -> part.substringBefore('=') to URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8.name()) }
}
