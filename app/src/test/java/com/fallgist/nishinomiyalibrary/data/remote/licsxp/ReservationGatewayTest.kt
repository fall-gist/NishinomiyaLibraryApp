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
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReservationGatewayTest {
    private companion object {
        const val CANCEL_TARGET_TILCOD = "1000001898886"
    }

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
        server.enqueue(page(""))
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

        val requests = List(10) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals("/WOpacEsSchCmpdDispAction.do", requests[0].path)
        assertEquals("/WOpacInitLoginActiontemp.do", requests[1].path)
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
        assertEquals("/WPwdLoginCheckAction.do", requests[3].path)
        assertEquals("GET", requests[3].method)
        assertEquals("/WOpacMsgNewListToTifTilDetailAction.do?urlNotFlag=1&tilcod=1000000000001", requests[5].path)
        assertEquals("GET", requests[5].method)
        assertEquals("/WOpacTifDirectYoyDispAction.do?tilcod=1000000000001", requests[6].path)
        assertEquals("POST", requests[6].method)
        assertEquals("tiles.WTifTilDetail2", decodeForm(requests[6].body.readUtf8())["gamenid"]?.single())
        assertEquals(server.url("/WOpacMsgNewListToTifTilDetailAction.do?urlNotFlag=1&tilcod=1000000000001").toString(), requests[6].getHeader("Referer"))
        assertEquals("${server.url("/").scheme}://${server.url("/").host}:${server.url("/").port}", requests[6].getHeader("Origin"))
        assertEquals("/WOpacTifDirectYoyExecAction.do?tilcod=1000000000001", requests[7].path)
        assertEquals(1, requests.count { it.path?.startsWith("/WOpacTifDirectYoyExecAction.do") == true })
        assertTrue(requests.none { it.path?.contains("EsTif") == true })
        assertEquals(server.url("/WOpacTifDirectYoyDispAction.do?tilcod=1000000000001").toString(), requests[7].getHeader("Referer"))
        assertEquals("${server.url("/").scheme}://${server.url("/").host}:${server.url("/").port}", requests[7].getHeader("Origin"))
        assertNull(requests[2].getHeader("Referer"))
        assertNull(requests[2].getHeader("Origin"))
        assertNull(requests[5].getHeader("Referer"))
        assertNull(requests[5].getHeader("Origin"))
        assertNull(requests[9].getHeader("Referer"))
        assertNull(requests[9].getHeader("Origin"))
        val body = requests[7].body.readUtf8()
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
        server.enqueue(page(""))
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
        assertEquals(7, server.requestCount)
    }

    @Test
    fun `通常書誌詳細がログインフォームなら表示と確定POSTを送らない`() = runBlocking {
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(""))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("login_form.html")))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        assertEquals(DirectReservationAttempt.SessionExpiredBeforeSubmit, session.directReserve("1000000000001", "106"))

        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals("/WOpacMsgNewListToTifTilDetailAction.do?urlNotFlag=1&tilcod=1000000000001", requests.last().path)
        assertTrue(requests.none { it.path?.contains("WOpacTifDirectYoy") == true })
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `確定POSTの接続断でもPOSTを再送しない`() = runBlocking {
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(""))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(fixture("reservation_confirm.html")))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))
        assertEquals(8, server.requestCount)
    }

    @Test
    fun `確定POSTがログインフォームを返しても再送しない`() = runBlocking {
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(""))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(fixture("reservation_confirm.html")))
        server.enqueue(page(fixture("login_form.html")))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))
        assertEquals(8, server.requestCount)
    }

    @Test
    fun `確定POSTの応答が確認画面のままならStayedOnConfirmationになる`() = runBlocking {
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(""))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(fixture("reservation_confirm.html")))
        server.enqueue(page(fixture("reservation_confirm.html")))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        assertEquals(DirectReservationAttempt.StayedOnConfirmation, session.directReserve("1000000000001", "106"))
        assertEquals(8, server.requestCount)
        assertEquals(1, List(8) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
            .count { it.path?.startsWith("/WOpacTifDirectYoyExecAction.do") == true })
    }

    @Test
    fun `hash無し確認フォームでもサーバー発行hiddenを保持して確定できる`() = runBlocking {
        server.enqueue(page("<html>温め</html>")); server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>")); server.enqueue(page(""))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(fixture("reservation_confirm.html").replace("<input type=\"hidden\" name=\"hash\" value=\"confirm-hash\" />", "")))
        server.enqueue(page("<div id='stat-login'></div>"))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {})).openAuthenticatedSession("1", "p")
        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))
        repeat(7) { server.takeRequest() }
        val fields = decodeForm(requireNotNull(server.takeRequest()).body.readUtf8())
        assertEquals(listOf("keep-me"), fields["siteIssued"])
    }

    @Test
    fun `確認GET直後の競合窓でも別セッションの要求を確定POSTより前へ入れない`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/WOpacEsSchCmpdDispAction.do" -> page("<html>session</html>")
                "/WOpacInitLoginActiontemp.do" -> page(fixture("login_form.html"))
                "/j_security_check" -> page("<html>login relay</html>")
                "/WPwdLoginCheckAction.do" -> page("")
                "/WOpacMnuTopInitAction.do" -> page(fixture("menu.html"))
                "/WOpacMsgNewListToTifTilDetailAction.do" -> page(reservationDetailFixture())
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

        assertEquals(7, server.requestCount)
        allowConfirmProcessing.complete(Unit)
        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, reservation.await())
        assertEquals("background", background.await())

        val requests = List(9) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals("/WOpacMsgNewListToTifTilDetailAction.do?urlNotFlag=1&tilcod=1000000000001", requests[5].path)
        assertEquals("/WOpacTifDirectYoyDispAction.do?tilcod=1000000000001", requests[6].path)
        assertEquals("/WOpacTifDirectYoyExecAction.do?tilcod=1000000000001", requests[7].path)
        assertEquals("/background", requests[8].path)
        assertEquals(1, requests.count { it.path?.startsWith("/WOpacTifDirectYoyExecAction.do") == true })
        assertTrue(waits.all { it >= 500 })
    }

    @Test
    fun `確認画面の例外後にも他セッションの要求を実行できる`() = runBlocking {
        server.enqueue(page("<html>session</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>login relay</html>"))
        server.enqueue(page(""))
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
        assertEquals(8, server.requestCount)
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
        server.enqueue(page(""))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(fixture("reservation_confirm.html")))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")
        val inspector = session as ReservationConfirmationInspector

        val inspection = inspector.inspectDirectReservationConfirmation("1000000000001", "106", contactDirectWebValue = null)

        assertTrue(inspection is ConfirmationInspection.Parsed)
        assertEquals(null, (inspection as ConfirmationInspection.Parsed).contactSelectionRetry)
        assertEquals(7, server.requestCount)
        val requests = List(7) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals("/WOpacTifDirectYoyDispAction.do?tilcod=1000000000001", requests[6].path)
        assertTrue(requests.none { it.path?.contains("webrak") == true })
        assertTrue(requests.none { it.path?.startsWith("/WOpacTifDirectYoyExecAction.do") == true })
    }

    @Test
    fun `contactDirectWebValue指定時は再表示POSTを1回だけ送りcontactdirectwebだけ上書きする`() = runBlocking {
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(""))
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

        assertEquals(8, server.requestCount)
        val requests = List(8) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(1, requests.count { it.path == "/WOpacTifDirectYoyDispAction.do?webrak=1" })
        assertTrue(requests.none { it.path?.startsWith("/WOpacTifDirectYoyExecAction.do") == true })
        val retryRequest = requests[7]
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
        server.enqueue(page(""))
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
        assertEquals(8, server.requestCount)
        assertTrue(
            List(8) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
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
        server.enqueue(page(""))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(emptyContactDirectWebFixture()))
        server.enqueue(page("<html><div id='stat-login'></div></html>"))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))

        assertEquals(8, server.requestCount)
        val requests = List(8) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertTrue(requests.none { it.path?.contains("webrak") == true })
        assertEquals("/WOpacTifDirectYoyExecAction.do?tilcod=1000000000001", requests[7].path)
        assertEquals(1, requests.count { it.path?.startsWith("/WOpacTifDirectYoyExecAction.do") == true })
        assertEquals(
            server.url("/WOpacTifDirectYoyDispAction.do?tilcod=1000000000001").toString(),
            requests[7].getHeader("Referer"),
        )
        assertEquals(
            "${server.url("/").scheme}://${server.url("/").host}:${server.url("/").port}",
            requests[7].getHeader("Origin"),
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
            decodeFormFields(requests[7].body.readUtf8()),
        )
    }

    @Test
    fun `詳細と確認のhashはページ発行値のまま送られセッショントークンで上書きしない`() = runBlocking {
        // reservationDetailFixture()はhash="detail-hash"、reservation_confirm.htmlはhash="confirm-hash"を
        // 発行している。menu.htmlのセッショントークンのhashへ置き換わらないことを確認する。
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(""))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(fixture("reservation_confirm.html")))
        server.enqueue(page("<html><div id='stat-login'></div></html>"))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))

        assertEquals(8, server.requestCount)
        val requests = List(8) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(
            "detail-hash",
            decodeForm(requests[6].body.readUtf8())["hash"]?.single(),
        )
        assertEquals(
            "confirm-hash",
            decodeForm(requests[7].body.readUtf8())["hash"]?.single(),
        )
    }

    @Test
    fun `hashが空な確認画面でもセッショントークンで補わず空のまま送る`() = runBlocking {
        // hash補完は撤去済み。emptyHashConfirmFixture()のhash空値がそのまま送られることを確認する。
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(""))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(reservationDetailFixture()))
        server.enqueue(page(emptyHashConfirmFixture()))
        server.enqueue(page("<html><div id='stat-login'></div></html>"))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))

        assertEquals(8, server.requestCount)
        val requests = List(8) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals("", decodeForm(requests[7].body.readUtf8())["hash"]?.single())
    }

    @Test
    fun `gamenidがtiles WTifTilDetailの詳細HTMLはParseExceptionになる`() = runBlocking {
        // WOpacTifTilListToTifTilDetailAction.do経由の実HTML(gamenid=tiles.WTifTilDetail)は
        // hashが空で描画され確定POSTが差し戻されるため、fail-closedで受け付けない。
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(""))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("book_detail.html").replace("1000000961766", "1000000000001")))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        val error = try {
            session.directReserve("1000000000001", "106")
            null
        } catch (exception: LibraryError.Parse) {
            exception
        }

        assertNotNull(error)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `予約一覧の解析に失敗すると診断ログへ注記を残すが例外とフローは変えない`() = runBlocking {
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(""))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page("<html>解析できない一覧画面</html>"))
        val notes = mutableListOf<Pair<String, String>>()
        val observer = object : LicsXpDiagnosticObserver {
            override fun onRequest(request: LicsXpDiagnosticRequest) = Unit

            override fun onWireRequest(
                method: String,
                path: String,
                protocol: String,
                headers: List<Pair<String, String>>,
                cookieNames: List<String>,
                setCookieNames: List<String>,
            ) = Unit

            override fun onResponse(method: String, path: String, statusCode: Int, redirectPath: String?) = Unit

            override fun onPage(path: String, classification: String, formFingerprint: String) = Unit

            override fun onScreenScript(path: String, actionTargets: List<String>, fieldAssignments: List<String>) = Unit

            override fun onSiteMessages(path: String, messages: List<String>) = Unit

            override fun onPageText(path: String, headings: List<String>, notices: List<String>) = Unit

            override fun onNote(stage: String, detail: String) {
                notes += stage to detail
            }
        }
        val root = LicsXpSession(server.url("/"), okhttp3.OkHttpClient(), observer, waitForRequestSlot = {})
        val session = LicsXpReservationGateway(root).openAuthenticatedSession("1234", "secret")

        val error = try {
            session.fetchReservations()
            null
        } catch (exception: LibraryError.Parse) {
            exception
        }

        assertNotNull(error)
        assertEquals(7, server.requestCount)
        val fetchNotes = notes.filter { it.first == "fetch-reservations" }
        assertEquals(1, fetchNotes.size)
        assertFalse(fetchNotes.single().second.contains("<html>"))
    }

    @Test
    fun `サマリ件数と解析行数が一致すれば一覧は完全と判定される`() = runBlocking {
        server.enqueue(page("<html>温め</html>", cookie = true))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(""))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        // menu.html のサマリ予約中件数(19件)と usrrsv.html の解析行数(19件)は一致するフィクスチャ。
        val snapshot = (session as ReservationSnapshotSource).fetchReservationSnapshot()

        assertTrue(snapshot.complete)
        assertEquals(19, snapshot.reservations.size)
    }

    @Test
    fun `サマリ件数と解析行数が不一致なら一覧は完全と判定されず件数だけ診断ログに残る`() = runBlocking {
        server.enqueue(page("<html>温め</html>", cookie = true))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(""))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(syntheticReservationListHtml(3)))
        val notes = mutableListOf<Pair<String, String>>()
        val root = LicsXpSession(server.url("/"), okhttp3.OkHttpClient(), noteCapturingObserver(notes), waitForRequestSlot = {})
        val session = LicsXpReservationGateway(root).openAuthenticatedSession("1234", "secret")

        // menu.html のサマリ予約中件数(19件)と、3行しかない一覧の解析行数は一致しない。
        val snapshot = (session as ReservationSnapshotSource).fetchReservationSnapshot()

        assertFalse(snapshot.complete)
        assertEquals(3, snapshot.reservations.size)
        val fetchNotes = notes.filter { it.first == "fetch-reservations" }
        assertEquals(1, fetchNotes.size)
        assertTrue(fetchNotes.single().second.contains("summary=19"))
        assertTrue(fetchNotes.single().second.contains("parsed=3"))
        assertFalse(fetchNotes.single().second.contains("タイトル"))
    }

    @Test
    fun `サマリが解析できない場合も完全と判定されない`() = runBlocking {
        server.enqueue(page("<html>温め</html>", cookie = true))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(""))
        server.enqueue(page(fixture("menu.html")))
        // LBForm(hash/gamenid)はそのまま残し、#stat-login の id だけを潰してサマリ解析だけを失敗させる。
        server.enqueue(page(fixture("menu.html").replace("id=\"stat-login\"", "id=\"stat-login-missing\"")))
        server.enqueue(page(fixture("usrrsv.html")))
        val notes = mutableListOf<Pair<String, String>>()
        val root = LicsXpSession(server.url("/"), okhttp3.OkHttpClient(), noteCapturingObserver(notes), waitForRequestSlot = {})
        val session = LicsXpReservationGateway(root).openAuthenticatedSession("1234", "secret")

        val snapshot = (session as ReservationSnapshotSource).fetchReservationSnapshot()

        assertFalse(snapshot.complete)
        assertEquals(19, snapshot.reservations.size)
        val fetchNotes = notes.filter { it.first == "fetch-reservations" }
        assertEquals(1, fetchNotes.size)
        assertTrue(fetchNotes.single().second.contains("summary=取得不可"))
        assertTrue(fetchNotes.single().second.contains("parsed=19"))
    }

    @Test
    fun `取消は要求列とRefererOriginを守り取消POSTを一回だけ送り対象消失でCancelledになる`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page("<html>取消受付</html>"))
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.Cancelled, result)
        assertEquals(5, server.requestCount)
        val requests = List(5) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals("/WOpacMnuTopInitAction.do?WebLinkFlag=1", requests[0].path)
        assertEquals("GET", requests[0].method)
        assertEquals("/WOpacMnuTopToPwdLibraryAction.do?gamen=usrrsv", requests[1].path)
        assertEquals("POST", requests[1].method)
        assertEquals("/WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1", requests[2].path)
        assertEquals("POST", requests[2].method)
        assertEquals(
            server.url("/WOpacMnuTopToPwdLibraryAction.do?gamen=usrrsv").toString(),
            requests[2].getHeader("Referer"),
        )
        assertEquals("${server.url("/").scheme}://${server.url("/").host}:${server.url("/").port}", requests[2].getHeader("Origin"))
        assertEquals("1013074729", decodeForm(requests[2].body.readUtf8())["yoycod"]?.single())
        assertEquals("/WOpacMnuTopInitAction.do?WebLinkFlag=1", requests[3].path)
        assertEquals("/WOpacMnuTopToPwdLibraryAction.do?gamen=usrrsv", requests[4].path)
        assertEquals(1, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })
    }

    @Test
    fun `取消後も対象が一覧に残っていればIndeterminateAfterPostになる`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page("<html>取消受付</html>"))
        server.enqueue(page(menuWithReservationCount(19)))
        server.enqueue(page(fixture("usrrsv.html")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.IndeterminateAfterPost, result)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `取消コードだけ消えて同じtilcod行が残っていてもCancelledにしない`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page("<html>取消受付</html>"))
        server.enqueue(page(menuWithReservationCount(19)))
        server.enqueue(page(fixture("usrrsv.html").replace("yoykCancel('1013074729')", "")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())

        assertEquals(ReservationCancelAttempt.IndeterminateAfterPost, session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD))
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `取消1段階目に確認ダイアログがあれば2段階目をクエリ無しで一回だけ送り本文とRefererOriginが1段階目由来になる`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page(cancelConfirmationStageHtml()))
        server.enqueue(page("<html>取消完了</html>"))
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.Cancelled, result)
        assertEquals(6, server.requestCount)
        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals("/WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1", requests[2].path)
        assertEquals("/WOpacUsrRsvCancelAction.do", requests[3].path)
        assertEquals("POST", requests[3].method)
        assertEquals(
            server.url("/WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1").toString(),
            requests[3].getHeader("Referer"),
        )
        assertEquals("${server.url("/").scheme}://${server.url("/").host}:${server.url("/").port}", requests[3].getHeader("Origin"))
        // 2段階目の本文は「mngFlg2_handan=1, kbnchgflag=1」→1段階目と同じ本文(同じ順序・同名重複のまま)→okCodesの順。
        val stage1Fields = decodeFormFields(requests[2].body.readUtf8())
        val stage2Fields = decodeFormFields(requests[3].body.readUtf8())
        assertEquals(
            listOf("mngFlg2_handan" to "1", "kbnchgflag" to "1") + stage1Fields + listOf("okCodes" to "OPACUSR001"),
            stage2Fields,
        )
        // 取消POSTはクエリ有無問わず1段階目・2段階目の2回だけ。
        assertEquals(2, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })
    }

    @Test
    fun `2段階目後に対象消失でCancelledになる場合と対象が残っている場合を区別する`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page(cancelConfirmationStageHtml()))
        server.enqueue(page("<html>取消完了</html>"))
        server.enqueue(page(menuWithReservationCount(19)))
        server.enqueue(page(fixture("usrrsv.html")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.IndeterminateAfterPost, result)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `取消2段階目の応答に確認文言が残っても一覧照合で成否不明にする`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page(cancelConfirmationStageHtml()))
        server.enqueue(page(cancelConfirmationStageHtml()))
        server.enqueue(page(menuWithReservationCount(19)))
        server.enqueue(page(fixture("usrrsv.html")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.IndeterminateAfterPost, result)
        assertEquals(6, server.requestCount)
        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(2, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })
    }

    @Test
    fun `全ページ共通JS定数の一般語だけではRejectedにもConfirmationRequiredにもならない`() = runBlocking {
        // 実測(2026-07-27)で判明した誤検出の回帰試験。
        // 「仮パスワードでは利用できません。パスワード変更を行なってください。」は全ページ共通のJS定数であり、
        // 「できません」のような一般語で判定すると取消応答でなくても誤ってRejected扱いになっていた。
        val commonJsConstantScript =
            "<script>var messageText = '仮パスワードでは利用できません。パスワード変更を行なってください。';</script>"
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page(fixture("usrrsv.html") + commonJsConstantScript))
        server.enqueue(page(menuWithReservationCount(19)))
        server.enqueue(page(fixture("usrrsv.html")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        // Rejected/ConfirmationRequiredにならず、取消後の一覧照合(対象が残っている)に委ねられる。
        assertEquals(ReservationCancelAttempt.IndeterminateAfterPost, result)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `静的な取消確認文言だけでは2段階目を送らない`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page("<html><script>var confirmationText = '予約の取消を行います。よろしいですか？';</script></html>"))
        server.enqueue(page(menuWithReservationCount(19)))
        server.enqueue(page(fixture("usrrsv.html")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.IndeterminateAfterPost, result)
        assertEquals(5, server.requestCount)
        val requests = List(5) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(1, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })
    }

    @Test
    fun `取消確認署名があってもstage1一覧から対象行が消えていれば2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent(cancelConfirmationStageHtml(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))
    }

    @Test
    fun `取消確認署名があってもstage1一覧の対象tilcodが変わっていれば2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent(
            cancelConfirmationStageHtml(fixture("usrrsv.html").replace("1000001898886", "1000000000000")),
        )
    }

    @Test
    fun `src付きscript内の取消確認署名では2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent(cancelConfirmationStageHtml(scriptAttributes = " src=\"cancel.js\""))
    }

    @Test
    fun `template script内の取消確認署名では2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent(cancelConfirmationStageHtml(scriptAttributes = " type=\"text/x-template\""))
    }

    @Test
    fun `json script内の取消確認署名では2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent(cancelConfirmationStageHtml(scriptAttributes = " type=\"application/json\""))
    }

    @Test
    fun `トップレベルreturnの後にある取消確認署名では2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent(cancelConfirmationStageHtml(scriptPrefix = "return;"))
    }

    @Test
    fun `未呼出し関数内の取消確認文言だけでは2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent(
            "<html><script>function showCancelConfirmation() { " +
                cancelConfirmationScript() + " }</script></html>",
        )
    }

    @Test
    fun `未呼出しarrow関数内の取消確認構造だけでは2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent(
            "<html><script>const showCancelConfirmation = () => { " +
                cancelConfirmationScript() + " };</script></html>",
        )
    }

    @Test
    fun `コメント内の取消確認構造だけでは2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent("<html><script>/* ${cancelConfirmationScript()} */</script></html>")
    }

    @Test
    fun `通常文字列内の取消確認構造だけでは2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent("<html><script>var candidate = \"${cancelConfirmationScript()}\";</script></html>")
    }

    @Test
    fun `template literal内の取消確認構造だけでは2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent("<html><script>const candidate = `${cancelConfirmationScript()}`;</script></html>")
    }

    @Test
    fun `取消確認構造を持つ到達不能な関数内では2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent(
            "<html><script>function unreachable() { " +
                cancelConfirmationScript() + " } if (false) { unreachable(); }</script></html>",
        )
    }

    @Test
    fun `直接のif false分岐内にある取消確認構造では2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent(
            "<html><script>if (false) { ${cancelConfirmationScript()} }</script></html>",
        )
    }

    @Test
    fun `正規表現リテラル内の取消確認構造では2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent(
            "<html><script>var candidate = /${cancelConfirmationScript().replace("/", "\\/")}/;</script></html>",
        )
    }

    @Test
    fun `分割代入を使う関数内の取消確認構造では2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent(
            "<html><script>function unreachable({value}) { ${cancelConfirmationScript()} }</script></html>",
        )
    }

    @Test
    fun `丸括弧が不整合な取消確認構造では2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent("<html><script>${cancelConfirmationScript()} (</script></html>")
    }

    @Test
    fun `角括弧が不整合な取消確認構造では2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent("<html><script>${cancelConfirmationScript()} [</script></html>")
    }

    @Test
    fun `取消後一覧のサマリ件数が不一致なら対象行が消えていてもCancelledにしない`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page("<html>取消受付</html>"))
        server.enqueue(page(menuWithReservationCount(19)))
        server.enqueue(page(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())

        assertEquals(ReservationCancelAttempt.IndeterminateAfterPost, session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD))
    }

    @Test
    fun `取消後メニューのサマリを解析できなければ対象行が消えていてもCancelledにしない`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page("<html>取消受付</html>"))
        server.enqueue(page(fixture("menu.html").replace("id=\"stat-login\"", "id=\"stat-login-missing\"")))
        server.enqueue(page(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())

        assertEquals(ReservationCancelAttempt.IndeterminateAfterPost, session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD))
    }

    @Test
    fun `一覧取得がログインフォームなら取消POSTを送らない`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("login_form.html")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.SessionExpiredBeforeSubmit, result)
        assertEquals(2, server.requestCount)
        val requests = List(2) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertTrue(requests.none { it.path?.contains("UsrRsvCancel") == true })
    }

    @Test
    fun `メニューがログインフォームなら一覧も取消POSTも送らない`() = runBlocking {
        server.enqueue(page(fixture("login_form.html")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.SessionExpiredBeforeSubmit, result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `一覧上に存在しない取消コードはLibraryErrorParseになる`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val error = try {
            session.cancelReservation("9999999999999", CANCEL_TARGET_TILCOD)
            null
        } catch (exception: LibraryError.Parse) {
            exception
        }

        assertNotNull(error)
        // 画面と対象の食い違いを検出した時点で中止するため、取消POSTは送らない。
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `依頼時のtilcodと送信前一覧の対象行が不一致なら取消POSTを送らない`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val error = try {
            session.cancelReservation("1013074729", "1000000000000")
            null
        } catch (exception: LibraryError.Parse) {
            exception
        }

        assertNotNull(error)
        assertEquals(2, server.requestCount)
        val requests = List(2) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertTrue(requests.none { it.path?.contains("UsrRsvCancel") == true })
    }

    @Test
    fun `対象tilcodが予約一覧内で重複していれば取消POST前にLibraryErrorParseになる`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(duplicateReservationRowWithSameTilcod(fixture("usrrsv.html"), "1013074729")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val error = try {
            session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)
            null
        } catch (exception: LibraryError.Parse) {
            exception
        }

        assertNotNull(error)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `取消POSTの接続断でも再送しない`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.IndeterminateAfterPost, result)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `取消2段階目の接続断でも再送せずIndeterminateAfterPostになる`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page(cancelConfirmationStageHtml()))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.IndeterminateAfterPost, result)
        assertEquals(4, server.requestCount)
        val requests = List(4) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(2, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })
    }

    private fun noteCapturingObserver(notes: MutableList<Pair<String, String>>): LicsXpDiagnosticObserver =
        object : LicsXpDiagnosticObserver {
            override fun onRequest(request: LicsXpDiagnosticRequest) = Unit
            override fun onWireRequest(
                method: String,
                path: String,
                protocol: String,
                headers: List<Pair<String, String>>,
                cookieNames: List<String>,
                setCookieNames: List<String>,
            ) = Unit
            override fun onResponse(method: String, path: String, statusCode: Int, redirectPath: String?) = Unit
            override fun onPage(path: String, classification: String, formFingerprint: String) = Unit
            override fun onScreenScript(path: String, actionTargets: List<String>, fieldAssignments: List<String>) = Unit
            override fun onSiteMessages(path: String, messages: List<String>) = Unit
            override fun onPageText(path: String, headings: List<String>, notices: List<String>) = Unit
            override fun onNote(stage: String, detail: String) {
                notes += stage to detail
            }
        }

    /** ParserSupportが要求する最小構造だけを満たす、行数を自由に変えられる予約状況一覧HTML。 */
    private fun syntheticReservationListHtml(rowCount: Int): String {
        val rows = (1..rowCount).joinToString("\n") { index ->
            """
            <tr>
                <td><a href="?hTilcod=100000000000$index">タイトル$index</a></td>
                <td>図書</td>
                <td>本館</td>
                <td>26/07/0$index</td>
                <td>$index</td>
                <td>予約中</td>
                <td></td>
            </tr>
            """.trimIndent()
        }
        return """
        <html>
        <h1>予約状況一覧</h1>
        <table summary="予約状況一覧表">
            <thead><tr><th>資料名</th><th>書誌種別</th><th>受取館</th><th>予約日</th><th>順位</th><th>予約状態</th><th>取置期限</th></tr></thead>
            <tbody>
            $rows
            </tbody>
        </table>
        </html>
        """.trimIndent()
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

    private fun menuWithReservationCount(count: Int): String {
        val document = Jsoup.parse(fixture("menu.html"))
        val countElements = document.select("#stat-login #stat-resv .value")
        require(countElements.size == 1) { "予約件数のサマリ要素を一意に特定できません" }
        val countElement = countElements.single()
        countElement.text(count.toString())
        return document.outerHtml()
    }

    private fun withoutReservationRow(html: String, cancelCode: String): String {
        val document = Jsoup.parse(html)
        val target = document.select("input[onclick*=yoykCancel]")
            .filter { it.attr("onclick").contains("yoykCancel('$cancelCode')") }
            .singleOrNull()
            ?: error("取消コード $cancelCode の取消ボタンを一意に特定できません")
        val row = requireNotNull(target.closest("tr")) { "取消コード $cancelCode の取消行を特定できません" }
        row.remove()
        return document.outerHtml()
    }

    private fun duplicateReservationRowWithSameTilcod(html: String, cancelCode: String): String {
        val document = Jsoup.parse(html)
        val target = document.select("input[onclick*=yoykCancel]")
            .filter { it.attr("onclick").contains("yoykCancel('$cancelCode')") }
            .singleOrNull()
            ?: error("取消コード $cancelCode の取消ボタンを一意に特定できません")
        val row = requireNotNull(target.closest("tr")) { "取消コード $cancelCode の取消行を特定できません" }
        val duplicate = row.clone()
        requireNotNull(duplicate.selectFirst("input[onclick*=yoykCancel]")) { "複製した取消行に取消ボタンがありません" }
            .attr("onclick", "yoykCancel('duplicate-cancel-code')")
        row.after(duplicate)
        return document.outerHtml()
    }

    private suspend fun assertOnlyStage1IsSent(stage1Html: String) {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page(stage1Html))
        server.enqueue(page(menuWithReservationCount(19)))
        server.enqueue(page(fixture("usrrsv.html")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        assertEquals(ReservationCancelAttempt.IndeterminateAfterPost, session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD))
        assertEquals(5, server.requestCount)
        val requests = List(5) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(1, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })
    }

    private fun cancelConfirmationStageHtml(
        listHtml: String = fixture("usrrsv.html"),
        scriptAttributes: String = "",
        scriptPrefix: String = "",
    ): String {
        val script = "<script$scriptAttributes>$scriptPrefix${cancelConfirmationScript()}</script>"
        require(listHtml.contains("</body>", ignoreCase = true)) { "予約一覧fixtureにbody終端がありません" }
        return listHtml.replace("</body>", "$script</body>", ignoreCase = true)
    }

    /**
     * DevToolsで採取した取消確認ページのlegacy scriptから、stage2判定に必要な実測構造を抜き出したもの。
     * 単にconfirm文言を置くだけではstage2へ進ませないことを、負例テストと対にして検証する。
     */
    private fun cancelConfirmationScript(): String = """
        if (0 != 1) {
            if (0 == 1) {
                rest = confirm('予約の取消を行います。よろしいですか？');
            } else {
                rest = lbConfirm('予約の取消を行います。よろしいですか？', '', '#F1F1FF');
            }
        } else {
            rest = window.confirm('予約の取消を行います。よろしいですか？');
        }
        if (rest) {
            okArray[okArray.length] = 'OPACUSR001';
            submitFlg = false;
        } else { return cancelDialog(); }
        newHidden.name = OK_CODES_NAME;
        document.prevRequestForm.appendChild(newHidden);
    """.trimIndent()

    // ブラウザ実測: 予約導線はWOpacMsgNewListToTifTilDetailAction.do経由でtiles.WTifTilDetail2・
    // hash非空で描画される。実HTMLフィクスチャ(gamenid=tiles.WTifTilDetail・hash空)をテスト内で
    // その形へ書き換えて使う。実HTMLファイル自体は書き換えない。
    private fun reservationDetailFixture(): String = fixture("book_detail.html")
        .replace("1000000961766", "1000000000001")
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

    private fun decodeForm(body: String): Map<String, List<String>> = decodeFormFields(body)
        .groupBy({ it.first }, { it.second })

    private fun decodeFormFields(body: String): List<Pair<String, String>> = body.split('&').filter(String::isNotBlank)
        .map { part -> part.substringBefore('=') to URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8.name()) }
}
