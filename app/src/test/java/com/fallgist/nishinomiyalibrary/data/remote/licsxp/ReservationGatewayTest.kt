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
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ReservationCancelFormParser
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
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
        var writeBoundaryCalls = 0
        (session as ReservationWriteBoundaryAware).setBeforeWriteBoundary { writeBoundaryCalls++ }
        val error = try {
            session.directReserve("1000000000001", "106")
            null
        } catch (exception: InvalidPickupLibraryException) {
            exception
        }
        assertNotNull(error)
        assertEquals(0, writeBoundaryCalls)
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
        var writeBoundaryCalls = 0
        (session as ReservationWriteBoundaryAware).setBeforeWriteBoundary { writeBoundaryCalls++ }

        assertEquals(DirectReservationAttempt.SessionExpiredBeforeSubmit, session.directReserve("1000000000001", "106"))
        assertEquals(0, writeBoundaryCalls)

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
        var writeBoundaryCalls = 0
        (session as ReservationWriteBoundaryAware).setBeforeWriteBoundary { writeBoundaryCalls++ }

        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))
        assertEquals(1, writeBoundaryCalls)
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

    // 12回目のライブ取消診断で取消が成立した(2026-07-28)後、所有者から「取消後も一覧に対象が残り、
    // 予約状態列が『取消』・取消ボタンが『非表示』ボタンへ変わる」という仕様が提供された。
    // 消える前に実サイトの構造を読み取るための、書き込み副作用ゼロの観測診断のテスト。

    @Test
    fun `一覧観測は行ごとの状態テキストとボタン関数名とcancelCode有無を返す`() = runBlocking {
        server.enqueue(page("<html>温め</html>", cookie = true))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(""))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        val inspection = (session as ReservationListInspector).inspectReservationList()

        assertEquals(19, inspection.summaryReservationCount)
        assertEquals(19, inspection.parsedRowCount)
        assertEquals(19, inspection.rows.size)
        val firstRow = inspection.rows.first()
        assertEquals(CANCEL_TARGET_TILCOD, firstRow.tilcod)
        assertEquals("予約中", firstRow.stateText)
        assertEquals(ReservationState.WAITING, firstRow.state)
        assertTrue(firstRow.cancelCodePresent)
        assertEquals(listOf("yoykCancel"), firstRow.buttonFunctionNames)
        assertTrue(firstRow.cellClassNames.contains("a-center"))
        // 資料名はどの行にも含めない。
        inspection.rows.forEach { row ->
            assertFalse(row.toString().contains("一穂"))
            assertFalse(row.toString().contains("恋と食"))
        }
    }

    @Test
    fun `取消済み行を模したfixtureでReservationListParserがCANCELLEDへマップすることを固定する`() = runBlocking {
        server.enqueue(page("<html>温め</html>", cookie = true))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(""))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(cancelledFirstRowUsrrsvHtml()))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        val inspection = (session as ReservationListInspector).inspectReservationList()

        assertEquals(19, inspection.parsedRowCount)
        val firstRow = inspection.rows.first()
        assertEquals(CANCEL_TARGET_TILCOD, firstRow.tilcod)
        assertEquals("取消", firstRow.stateText)
        // 12回目のライブ取消＋一覧観測(2026-07-28)で確定した仕様どおり、「取消」はCANCELLEDへマップする
        // （本改修でReservationListParserに追加したマッピング）。
        assertEquals(ReservationState.CANCELLED, firstRow.state)
        // 取消ボタン(yoykCancel)が非表示ボタン(yoykHihyoji)へ差し替わるため、既存のcancelCode抽出
        // (input[onclick*=yoykCancel]限定)は一致せず空になる。
        assertFalse(firstRow.cancelCodePresent)
        assertEquals(listOf("yoykHihyoji"), firstRow.buttonFunctionNames)
        assertTrue(firstRow.cellClassNames.contains("red"))
    }

    /**
     * usrrsv.htmlの1行目(tilcod=CANCEL_TARGET_TILCOD)だけを、所有者提供の仕様どおり
     * 「予約状態が赤字で『取消』・取消ボタンの位置に『非表示』ボタン(yoykHihyoji)」へ書き換える。
     * 赤字表示の実クラス名は未確認のため、"red"は実測待ちの仮のクラス名である。
     */
    private fun cancelledFirstRowUsrrsvHtml(): String = withFirstRowCancelledAndHidden(fixture("usrrsv.html"))

    /** [html]の1行目(tilcod=CANCEL_TARGET_TILCOD)を「取消」状態＋非表示ボタン(yoykHihyoji)へ書き換える。 */
    private fun withFirstRowCancelledAndHidden(html: String): String {
        val withCancelledState = Regex("""id="ItemDeta0105i'1'" class="a-center">[\s\S]*?</td>""").replace(html) {
            "id=\"ItemDeta0105i'1'\" class=\"a-center red\">\n\t\t\t\t取消\n\t      </td>"
        }
        return withCancelledState.replace(
            "onclick=\"javascript:yoykCancel('1013074729')\" value=\"取消\"",
            "onclick=\"javascript:yoykHihyoji('1013074729')\" value=\"非表示\"",
        )
    }

    @Test
    fun `取消は要求列とRefererOriginを守り取消POSTを一回だけ送り対象消失でCancelledAndHiddenになる`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page("<html>取消受付</html>"))
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.CancelledAndHidden, result)
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

    // 12回目のライブ取消＋一覧観測(2026-07-28)で確定した仕様: 取消後も対象行は一覧から消えず、
    // 予約状態が「取消」になり取消ボタン(yoykCancel)が非表示ボタン(yoykHihyoji)へ置き換わる。
    // 状態文字列とボタンの両方が一致した場合だけCancelled、片方だけの一致はIndeterminateAfterPostへ倒す。

    @Test
    fun `取消後に対象行が取消状態かつ非表示ボタンありならCancelledになる`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page("<html>取消受付</html>"))
        // cancelledFirstRowUsrrsvHtmlは19行中1行を取消状態にする。サマリの予約中件数は取消済み行を
        // 数えないため18(=19-1)になる。
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(cancelledFirstRowUsrrsvHtml()))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.Cancelled, result)
        assertEquals(5, server.requestCount)
        val requests = List(5) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(1, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })
    }

    @Test
    fun `取消後に対象行の状態が取消でもボタンがyoykCancelのままならIndeterminateAfterPostになる`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page("<html>取消受付</html>"))
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(cancelledStateOnlyFirstRowUsrrsvHtml()))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        // 状態文字列だけの一致では成功と断定しない（確証がなければ成否不明へ倒す方針）。
        assertEquals(ReservationCancelAttempt.IndeterminateAfterPost, result)
        assertEquals(5, server.requestCount)
        val requests = List(5) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(1, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })
    }

    @Test
    fun `取消後に対象行のボタンがyoykHihyojiでも状態が予約中のままならIndeterminateAfterPostになる`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page("<html>取消受付</html>"))
        // 状態は「予約中」のまま(WAITING)なのでサマリの予約中件数は変わらず19のまま。
        server.enqueue(page(menuWithReservationCount(19)))
        server.enqueue(page(hideButtonOnlyFirstRowUsrrsvHtml()))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        // ボタンだけの一致では成功と断定しない（確証がなければ成否不明へ倒す方針）。
        assertEquals(ReservationCancelAttempt.IndeterminateAfterPost, result)
        assertEquals(5, server.requestCount)
        val requests = List(5) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(1, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })
    }

    /** 状態だけ「取消」に変え、取消ボタン(yoykCancel)はそのまま残す（ボタンだけ不一致の異常系検証用）。 */
    private fun cancelledStateOnlyFirstRowUsrrsvHtml(): String =
        Regex("""id="ItemDeta0105i'1'" class="a-center">[\s\S]*?</td>""").replace(fixture("usrrsv.html")) {
            "id=\"ItemDeta0105i'1'\" class=\"a-center red\">\n\t\t\t\t取消\n\t      </td>"
        }

    /** ボタンだけ非表示(yoykHihyoji)に変え、状態は「予約中」のまま残す（状態だけ不一致の異常系検証用）。 */
    private fun hideButtonOnlyFirstRowUsrrsvHtml(): String = fixture("usrrsv.html").replace(
        "onclick=\"javascript:yoykCancel('1013074729')\" value=\"取消\"",
        "onclick=\"javascript:yoykHihyoji('1013074729')\" value=\"非表示\"",
    )

    @Test
    fun `完全性ガードは移送中を除外せず取消済み行だけを除いて計算する`() = runBlocking {
        // 12回目のライブ観測実測(2026-07-28)どおりの内訳: 予約中15行+提供可能3行+移送中1行+取消1行=20行。
        // サマリの予約中件数(19)は取消済み行だけを除いた数であり、移送中・提供可能は数えられている。
        // 移送中を誤って除外しないことをこのテストで固定する。
        val states = List(15) { "予約中" } + List(3) { "提供可能" } + listOf("移送中", "取消")
        server.enqueue(page("<html>温め</html>", cookie = true))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(""))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(menuWithReservationCount(19)))
        server.enqueue(page(syntheticReservationListHtmlWithStates(states)))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        val snapshot = (session as ReservationSnapshotSource).fetchReservationSnapshot()

        assertEquals(20, snapshot.reservations.size)
        assertEquals(1, snapshot.reservations.count { it.state == ReservationState.CANCELLED })
        assertEquals(1, snapshot.reservations.count { it.state == ReservationState.IN_TRANSIT })
        assertTrue(snapshot.complete)
    }

    /** 行ごとに任意の予約状態文字列を指定できる、判定用の最小構造の予約状況一覧HTML。 */
    private fun syntheticReservationListHtmlWithStates(states: List<String>): String {
        val rows = states.mapIndexed { zeroBasedIndex, stateText ->
            val position = zeroBasedIndex + 1
            val button = when (stateText) {
                "予約中" -> "<input type=\"button\" onclick=\"javascript:yoykCancel('9000000000$position')\" value=\"取消\">"
                "取消" -> "<input type=\"button\" onclick=\"javascript:yoykHihyoji('9000000000$position')\" value=\"非表示\">"
                else -> ""
            }
            """
            <tr>
                <td><a href="?hTilcod=20000000000$position">タイトル$position</a></td>
                <td>図書</td>
                <td>本館</td>
                <td>26/07/01</td>
                <td>$position</td>
                <td>$stateText</td>
                <td></td>
                <td>$button</td>
            </tr>
            """.trimIndent()
        }.joinToString("\n")
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

    // 13回目のライブ観測(2026-07-28)実測: 同じtilcodの行が2つ(予約中1・取消1)ある状態が見つかった。
    // 取消済み行を非表示にせず同じ書誌を予約し直すと起きる、普通の運用操作であり実運用で必ず起きる。
    // tilcodだけでは行の同一性を追えないサイト仕様のため、送信前は「取消可能な行(cancelCodeが非空)」の
    // 一意性で対象を特定し、送信後は「取消可能な行の消失」＋「取消済み行の増分(基準値+1)」で判定する。

    @Test
    fun `同一tilcodに取消済み行が併存していても送信前に一意特定でき2段階目まで進みCancelledになる`() = runBlocking {
        val beforeHtml = usrrsvHtmlWithCancelledDuplicateOfTarget()
        // 基準値(送信前の取消済み行数)は1。送信後に取消済み行が2(基準値+1)・取消可能な行が0になる。
        val afterHtml = withFirstRowCancelledAndHidden(beforeHtml)
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(beforeHtml))
        server.enqueue(
            page(
                cancelConfirmationStageHtml(
                    listHtml = beforeHtml,
                    previousRequestSourceHtml = beforeHtml,
                ),
            ),
        )
        server.enqueue(page("<html>取消完了</html>"))
        // 元からの取消済み重複行1行を含めた合計20行のうち、取消済みは2行(重複行1+今回取消1)になるため
        // サマリの予約中件数は18。
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(afterHtml))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        // 送信前の一意特定(取消可能な行の一意性)を通過し、2段階目まで進んだことはPOST数で確認できる。
        assertEquals(ReservationCancelAttempt.Cancelled, result)
        assertEquals(6, server.requestCount)
        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(2, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })
    }

    @Test
    fun `重複併存の状態から対象tilcodの行が全て消えていればCancelledAndHiddenになる`() = runBlocking {
        val beforeHtml = usrrsvHtmlWithCancelledDuplicateOfTarget()
        // 対象tilcodの行(元の予約中行＋重複した取消済み行)を両方とも一覧から取り除く。
        val afterHtml = withoutReservationRow(withoutTargetTilcodDuplicateRow(beforeHtml), "1013074729")
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(beforeHtml))
        server.enqueue(
            page(
                cancelConfirmationStageHtml(
                    listHtml = beforeHtml,
                    previousRequestSourceHtml = beforeHtml,
                ),
            ),
        )
        server.enqueue(page("<html>取消完了</html>"))
        // 対象tilcodの行(元の予約中行1・重複した取消済み行1)がどちらも消えるため、20行から2行減って18行、
        // うち取消済みは0のためサマリの予約中件数も18。
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(afterHtml))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        // CancelledAndHiddenの判定は基準値で場合分けしない。対象tilcodの行が全て消えている状態は、
        // 並行操作が無い限り「取消＋非表示」以外では起きないため。
        assertEquals(ReservationCancelAttempt.CancelledAndHidden, result)
        assertEquals(6, server.requestCount)
        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(2, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })
    }

    @Test
    fun `重複併存の状態で取消済み行数が基準値のままならIndeterminateAfterPostになる`() = runBlocking {
        val beforeHtml = usrrsvHtmlWithCancelledDuplicateOfTarget()
        // 元の予約中行から取消ボタンだけを消し、状態は「予約中」のまま残す
        // （cancelCodeは消えるが取消済み(CANCELLED)にはならない、既存の「取消コードだけ消えて…」と同じ発想）。
        // 取消済み行数は重複行の1のまま(基準値のまま)で増えない。
        val afterHtml = beforeHtml.replace("onclick=\"javascript:yoykCancel('1013074729')\" value=\"取消\"", "")
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(beforeHtml))
        server.enqueue(
            page(
                cancelConfirmationStageHtml(
                    listHtml = beforeHtml,
                    previousRequestSourceHtml = beforeHtml,
                ),
            ),
        )
        server.enqueue(page("<html>取消完了</html>"))
        // 予約中のまま残る行1(cancelCodeだけ空)+取消済み行1で、取消済みでない行は19(=20-1)のまま。
        server.enqueue(page(menuWithReservationCount(19)))
        server.enqueue(page(afterHtml))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.IndeterminateAfterPost, result)
        assertEquals(6, server.requestCount)
        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(2, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })
    }

    @Test
    fun `重複併存の状態で取消可能な行が残っていればIndeterminateAfterPostになる`() = runBlocking {
        val beforeHtml = usrrsvHtmlWithCancelledDuplicateOfTarget()
        // 送信後も一覧が変化しない(取消が実際には反映されていない)場合を模す。
        val afterHtml = beforeHtml
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(beforeHtml))
        server.enqueue(
            page(
                cancelConfirmationStageHtml(
                    listHtml = beforeHtml,
                    previousRequestSourceHtml = beforeHtml,
                ),
            ),
        )
        server.enqueue(page("<html>取消完了</html>"))
        server.enqueue(page(menuWithReservationCount(19)))
        server.enqueue(page(afterHtml))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.IndeterminateAfterPost, result)
        assertEquals(6, server.requestCount)
        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(2, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })
    }

    /**
     * usrrsv.htmlの対象tilcod行(cancelCode=1013074729)を複製し、複製側だけ「取消」状態＋非表示ボタン
     * (yoykHihyoji)へ書き換えて元の行の直後に挿入する。取消済み行を非表示にせず同じ書誌を再予約すると
     * 起きる状態（普通の運用操作、13回目のライブ観測実測）を模す。元の行(予約中・取消可能)はそのまま
     * 残るため、送信前の一覧は「取消済み行1つ＋予約中行1つ」になる。
     * 実行の行は実サイト実測どおり列数が多く単純な合成行では列がずれるため、
     * duplicateReservationRowWithSameTilcodと同じくJsoupで実際の行を複製する方式を使う。
     */
    private fun usrrsvHtmlWithCancelledDuplicateOfTarget(): String {
        val document = Jsoup.parse(fixture("usrrsv.html"))
        val target = document.select("input[onclick*=yoykCancel]")
            .filter { it.attr("onclick").contains("yoykCancel('1013074729')") }
            .singleOrNull()
            ?: error("対象行の取消ボタンを一意に特定できません")
        val row = requireNotNull(target.closest("tr")) { "対象行を特定できません" }
        val duplicate = row.clone()
        // id="ItemDeta0105i'1'"のような、値そのものに引用符を含む実測id属性はCSSの[id=value]記法で
        // 安全に指定できないため、正規表現一致(~=)による部分一致で予約状態セルを特定する。
        val stateCell = duplicate.selectFirst("td[id~=ItemDeta0105i]")
            ?: error("複製行の予約状態セルを特定できません")
        stateCell.text("取消")
        val duplicateButton = duplicate.selectFirst("input[onclick~=yoykCancel]")
            ?: error("複製行に取消ボタンがありません")
        duplicateButton.attr("onclick", "javascript:yoykHihyoji('1013074728')")
        duplicateButton.attr("value", "非表示")
        row.after(duplicate)
        return document.outerHtml()
    }

    /** [usrrsvHtmlWithCancelledDuplicateOfTarget]が追加した複製行だけを取り除く。 */
    private fun withoutTargetTilcodDuplicateRow(html: String): String {
        val document = Jsoup.parse(html)
        val duplicateButton = document.select("input[onclick*=yoykHihyoji]")
            .singleOrNull { it.attr("onclick").contains("yoykHihyoji('1013074728')") }
            ?: error("複製行の非表示ボタンを一意に特定できません")
        requireNotNull(duplicateButton.closest("tr")) { "複製行を特定できません" }.remove()
        return document.outerHtml()
    }

    @Test
    fun `実測確認script抽出断片を含む取消1段階目なら2段階目をクエリ無しで一回だけ送り本文とRefererOriginが1段階目由来になる`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page(cancelConfirmationStageHtml()))
        server.enqueue(page("<html>取消完了</html>"))
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.CancelledAndHidden, result)
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
    fun `無関係なfunction正規表現arrowを同居させた巨大script内の実測抽出断片でも2段階目を送る`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page(cancelConfirmationStageHtml(scriptBody = cancelConfirmationScriptInLargeScript())))
        server.enqueue(page("<html>取消完了</html>"))
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())

        assertEquals(ReservationCancelAttempt.CancelledAndHidden, session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD))
        assertEquals(6, server.requestCount)
        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(2, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })
    }

    @Test
    fun `取消確認署名診断は通常の実測断片で集計のみを記録し2段階目を送る`() = runBlocking {
        assertCancelSignatureDiagnostic(
            stage1Html = cancelConfirmationStageHtml(),
            expectStage2 = true,
            expectedSummaryParts = listOf(
                "matched=true",
                "inlineScripts=1",
                "exactMessageScripts=1",
                "outerIfScripts=1",
                "scan=completed:1",
                "candidates=1",
                "sanitizeSucceeded=1",
                "fixedRegexMatches=1",
            ),
            expectedStageSummaryParts = listOf(
                "targetStillPresent=true",
                "signatureMatched=true",
                "matched=true",
            ),
        )
    }

    @Test
    fun `取消確認複合ガード診断は対象tilcod不一致を記録しPOST回数を変えない`() = runBlocking {
        assertCancelSignatureDiagnostic(
            stage1Html = cancelConfirmationStageHtml(
                fixture("usrrsv.html").replace("1000001898886", "1000000000000"),
            ),
            expectStage2 = false,
            expectedSummaryParts = listOf("matched=true"),
            expectedStageSummaryParts = listOf(
                "targetStillPresent=false",
                "signatureMatched=true",
                "matched=false",
            ),
        )
    }

    @Test
    fun `実ライブ相当の元一覧formとprevRequestFormが併存しても2段階目を送る`() = runBlocking {
        assertCancelSignatureDiagnostic(
            stage1Html = cancelConfirmationStageHtml(),
            expectStage2 = true,
            expectedSummaryParts = listOf("matched=true"),
            expectedStageSummaryParts = listOf(
                "targetStillPresent=true",
                "signatureMatched=true",
                "matched=true",
            ),
        )
    }

    @Test
    fun `外部originのprevRequestForm actionなら2段階目を送らない`() = runBlocking {
        assertCancelSignatureDiagnostic(
            stage1Html = cancelConfirmationStageHtml(previousRequestAction = "https://example.invalid/WOpacUsrRsvCancelAction.do"),
            expectStage2 = false,
            expectedSummaryParts = listOf("matched=true"),
            expectedStageSummaryParts = listOf(
                "targetStillPresent=true",
                "signatureMatched=true",
                "matched=true",
            ),
        )
    }

    // 10回目のライブ診断で判明した「実サイトのprevRequestFormにaction属性が無い」という事実の
    // 追加調査のためのprevform診断（読み取り専用）。既存の2段階目送信可否・送信内容は一切変えない。

    @Test
    fun `診断prevformはaction付きprevRequestFormと関連script文とokCodesNameを記録しPOST回数を変えない`() = runBlocking {
        val extraStatements =
            "document.prevRequestForm.action = \"WOpacUsrRsvCancelAction.do\"; document.prevRequestForm.submit();"
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page(cancelConfirmationStageHtml(scriptBody = "${cancelConfirmationScript()} $extraStatements")))
        server.enqueue(page("<html>取消完了</html>"))
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))
        val notes = mutableListOf<Pair<String, String>>()
        val root = LicsXpSession(server.url("/"), okhttp3.OkHttpClient(), noteCapturingObserver(notes), waitForRequestSlot = {})
        val session = LicsXpReservationSession(root, ReservationSequenceHooks())

        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.CancelledAndHidden, result)
        assertEquals(6, server.requestCount)
        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        // このprevform診断の追加によって取消POSTの回数・宛先が変わっていないことを確認する。
        assertEquals("/WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1", requests[2].path)
        assertEquals("/WOpacUsrRsvCancelAction.do", requests[3].path)
        assertEquals(2, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })

        val prevformNotes = notes.filter { it.first == "cancel-reservation-prevform" }
        assertEquals(1, prevformNotes.size)
        val summary = prevformNotes.single().second
        assertTrue(summary.contains("forms=1"))
        assertTrue(summary.contains("action=WOpacUsrRsvCancelAction.do"))
        assertTrue(summary.contains("method=(empty)"))
        assertTrue(summary.contains("document.prevRequestForm.appendChild(newHidden);"))
        assertTrue(summary.contains("document.prevRequestForm.action = \"WOpacUsrRsvCancelAction.do\";"))
        assertTrue(summary.contains("document.prevRequestForm.submit();"))
        assertTrue(summary.contains("okCodesName=okCodes"))
        assertFalse(summary.contains("1013074729"))
        assertFalse(summary.contains(CANCEL_TARGET_TILCOD))
    }

    @Test
    fun `診断prevformはaction空(明示的な空文字列)でも2段階目を1段階目と同じクエリ付きURLへ送る`() = runBlocking {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page(cancelConfirmationStageHtml(previousRequestAction = "")))
        server.enqueue(page("<html>取消完了</html>"))
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))
        val notes = mutableListOf<Pair<String, String>>()
        val root = LicsXpSession(server.url("/"), okhttp3.OkHttpClient(), noteCapturingObserver(notes), waitForRequestSlot = {})
        val session = LicsXpReservationSession(root, ReservationSequenceHooks())

        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        // 11回目のライブ診断(2026-07-28)の実測に基づく設計変更: action省略時はHTML標準どおり
        // 現在のドキュメントURL（＝1段階目に実際に送ったクエリ付きURL）へ2段階目を送る。
        // 以前はここでParseExceptionにして一覧照合へ倒していたが、今回の実測でaction省略は
        // 例外ではなく正常系（実サイトの実際の挙動）だと判明したための変更。
        assertEquals(ReservationCancelAttempt.CancelledAndHidden, result)
        assertEquals(6, server.requestCount)
        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals("/WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1", requests[2].path)
        assertEquals("/WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1", requests[3].path)
        assertEquals(2, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })

        val prevformNotes = notes.filter { it.first == "cancel-reservation-prevform" }
        assertEquals(1, prevformNotes.size)
        val summary = prevformNotes.single().second
        assertTrue(summary.contains("forms=1"))
        assertTrue(summary.contains("action=(empty)"))
        assertTrue(summary.contains("document.prevRequestForm.appendChild(newHidden);"))
        assertTrue(summary.contains("okCodesName=okCodes"))
    }

    @Test
    fun `実サイト相当(action属性なしmethod=post)のprevRequestFormは2段階目を1段階目と同じクエリ付きURLへ送る`() = runBlocking {
        // 11回目のライブ実測(2026-07-28)の再現: forms=1 attrs=method,name action=(empty) method=post
        // target=(empty) enctype=(empty) id=(empty)。action属性自体が存在しない点が前のテストとの違い。
        val listHtml = fixture("usrrsv.html")
        val stage1 = ReservationCancelFormParser.parse(listHtml).buildForm("1013074729")
        val controls = buildList {
            add("mngFlg2_handan" to "1")
            add("kbnchgflag" to "1")
            for (index in 0 until stage1.size) add(stage1.name(index) to stage1.value(index))
        }.joinToString("") { (name, value) -> "<input type=\"hidden\" name=\"${htmlAttribute(name)}\" value=\"${htmlAttribute(value)}\">" }
        val prevForm = "<form name=\"prevRequestForm\" method=\"post\">$controls</form>"
        val script = "<script>var OK_CODES_NAME = \"okCodes\"; ${cancelConfirmationScript()}</script>"
        val stage1Html = listHtml.replace("</body>", "$prevForm$script</body>", ignoreCase = true)

        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(listHtml))
        server.enqueue(page(stage1Html))
        server.enqueue(page("<html>取消完了</html>"))
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.CancelledAndHidden, result)
        assertEquals(6, server.requestCount)
        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals("/WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1", requests[2].path)
        // 実測どおり、2段階目は1段階目と同じクエリ付きURLへ送られる（クエリ無しへのハードコードはしない）。
        assertEquals("/WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1", requests[3].path)
        assertEquals("POST", requests[3].method)
        assertEquals(
            server.url("/WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1").toString(),
            requests[3].getHeader("Referer"),
        )
        assertEquals("${server.url("/").scheme}://${server.url("/").host}:${server.url("/").port}", requests[3].getHeader("Origin"))
        // 2段階目の本文はmngFlg2_handan/kbnchgflag→1段階目と同じ本文→okCodesの順（従来どおり不変）。
        val stage1Fields = decodeFormFields(requests[2].body.readUtf8())
        val stage2Fields = decodeFormFields(requests[3].body.readUtf8())
        assertEquals(
            listOf("mngFlg2_handan" to "1", "kbnchgflag" to "1") + stage1Fields + listOf("okCodes" to "OPACUSR001"),
            stage2Fields,
        )
        assertEquals(2, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })
    }

    @Test
    fun `診断が無効なときnoteDiagnosticのラムダは評価されない`() {
        // レビュー指摘対応: inspectPrevRequestFormDiagnostic相当の計算コストを、診断無効時（通常経路）
        // は一切払わないことを、呼び出し回数を数える形で直接検証する（LicsXpSession.noteDiagnosticの
        // ラムダ受け取りオーバーロード自体の振る舞いを、cancelReservation全体を動かさずに確認する）。
        var evaluationCount = 0
        val disabledSession = LicsXpSession(server.url("/"), waitForRequestSlot = {})
        disabledSession.noteDiagnostic("test-stage") {
            evaluationCount += 1
            "detail"
        }
        assertEquals(0, evaluationCount)

        val notes = mutableListOf<Pair<String, String>>()
        val enabledSession = LicsXpSession(server.url("/"), okhttp3.OkHttpClient(), noteCapturingObserver(notes), waitForRequestSlot = {})
        enabledSession.noteDiagnostic("test-stage") {
            evaluationCount += 1
            "detail"
        }
        assertEquals(1, evaluationCount)
        assertEquals(listOf("test-stage" to "detail"), notes)
    }

    @Test
    fun `診断prevformは文字列リテラルとコメント内のprevRequestFormを抽出しない`() = runBlocking {
        val decoyScript =
            "// prevRequestFormを参照するコメントは無視されるべき\n" +
                "var note = \"prevRequestFormという文字列も無視されるべき\";\n"
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page(cancelConfirmationStageHtml(scriptBody = "$decoyScript${cancelConfirmationScript()}")))
        server.enqueue(page("<html>取消完了</html>"))
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))
        val notes = mutableListOf<Pair<String, String>>()
        val root = LicsXpSession(server.url("/"), okhttp3.OkHttpClient(), noteCapturingObserver(notes), waitForRequestSlot = {})
        val session = LicsXpReservationSession(root, ReservationSequenceHooks())

        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.CancelledAndHidden, result)
        assertEquals(6, server.requestCount)
        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(2, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })

        val summary = notes.single { it.first == "cancel-reservation-prevform" }.second
        assertTrue(summary.contains("document.prevRequestForm.appendChild(newHidden);"))
        assertFalse(summary.contains("コメントは無視されるべき"))
        assertFalse(summary.contains("文字列も無視されるべき"))
    }

    @Test
    fun `診断prevformは6桁以上の連続数字をnumへマスクする`() = runBlocking {
        val listHtml = fixture("usrrsv.html")
        val stage1 = ReservationCancelFormParser.parse(listHtml).buildForm("1013074729")
        val controls = buildList {
            add("mngFlg2_handan" to "1")
            add("kbnchgflag" to "1")
            for (index in 0 until stage1.size) add(stage1.name(index) to stage1.value(index))
        }.joinToString("") { (name, value) -> "<input type=\"hidden\" name=\"$name\" value=\"$value\">" }
        val maskedStatement = "document.prevRequestForm.dataset.code = \"12345678\";"
        val script =
            "<script>var OK_CODES_NAME = \"okCodes\"; ${cancelConfirmationScript()} $maskedStatement</script>"
        // target属性は取消2段階目の送信先解決には使われないため、属性値マスクだけを安全に確認できる。
        val prevForm =
            "<form name=\"prevRequestForm\" action=\"WOpacUsrRsvCancelAction.do\" target=\"report1234567\">$controls</form>"
        val stage1Html = listHtml.replace("</body>", "$prevForm$script</body>", ignoreCase = true)

        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(listHtml))
        server.enqueue(page(stage1Html))
        server.enqueue(page("<html>取消完了</html>"))
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))
        val notes = mutableListOf<Pair<String, String>>()
        val root = LicsXpSession(server.url("/"), okhttp3.OkHttpClient(), noteCapturingObserver(notes), waitForRequestSlot = {})
        val session = LicsXpReservationSession(root, ReservationSequenceHooks())

        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.CancelledAndHidden, result)
        assertEquals(6, server.requestCount)
        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(2, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })

        val summary = notes.single { it.first == "cancel-reservation-prevform" }.second
        assertFalse(summary.contains("1234567"))
        assertFalse(summary.contains("12345678"))
        assertTrue(summary.contains("target=report<num>"))
        assertTrue(summary.contains("document.prevRequestForm.dataset.code = \"<num>\";"))
    }

    // レビュー指摘対応: 英数字混在の秘密値マスク（<tok>）とhash等を含む文の全体伏せ（[REDACTED]）。

    @Test
    fun `診断prevformは英数字混在の秘密値をtokへhashを含む文を丸ごとREDACTEDへマスクする`() = runBlocking {
        val tokenStatement = "document.prevRequestForm.token = \"8f3a91cd7e2b\";"
        val hashStatement = "document.prevRequestForm.hash.value = \"zzz\";"
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(
            page(cancelConfirmationStageHtml(scriptBody = "${cancelConfirmationScript()} $tokenStatement $hashStatement")),
        )
        server.enqueue(page("<html>取消完了</html>"))
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))
        val notes = mutableListOf<Pair<String, String>>()
        val root = LicsXpSession(server.url("/"), okhttp3.OkHttpClient(), noteCapturingObserver(notes), waitForRequestSlot = {})
        val session = LicsXpReservationSession(root, ReservationSequenceHooks())

        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.CancelledAndHidden, result)
        assertEquals(6, server.requestCount)
        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(2, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })

        val summary = notes.single { it.first == "cancel-reservation-prevform" }.second
        // 数字混じりの8文字以上の英数字トークンは値の一部だけ<tok>へマスクされる。
        assertTrue(summary.contains("document.prevRequestForm.token = \"<tok>\";"))
        assertFalse(summary.contains("8f3a91cd7e2b"))
        // hashを含む文は部分マスクではなく文全体を[REDACTED]にする。
        assertTrue(summary.contains("[REDACTED]"))
        assertFalse(summary.contains("hash.value"))
        assertFalse(summary.contains("zzz"))
        // 純粋な識別子（数字を含まない）はマスクしない。
        assertTrue(summary.contains("document.prevRequestForm.appendChild(newHidden);"))
    }

    // レビュー指摘対応: 識別子prevRequestFormに依存しない tail= 診断
    // （署名候補の直後に続く文を最大6文、字句走査で機械的に読む）。

    @Test
    fun `診断prevformのtailは署名候補直後の送信処理文を識別子どおりに拾う`() = runBlocking {
        val extraStatements =
            "document.prevRequestForm.action = \"WOpacUsrRsvCancelAction.do\"; document.prevRequestForm.submit();"
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page(cancelConfirmationStageHtml(scriptBody = "${cancelConfirmationScript()} $extraStatements")))
        server.enqueue(page("<html>取消完了</html>"))
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))
        val notes = mutableListOf<Pair<String, String>>()
        val root = LicsXpSession(server.url("/"), okhttp3.OkHttpClient(), noteCapturingObserver(notes), waitForRequestSlot = {})
        val session = LicsXpReservationSession(root, ReservationSequenceHooks())

        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.CancelledAndHidden, result)
        assertEquals(6, server.requestCount)
        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(2, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })

        val summary = notes.single { it.first == "cancel-reservation-prevform" }.second
        val tailSection = summary.substringAfter("tail=").substringBefore(" okCodesName=")
        assertTrue(tailSection.contains("document.prevRequestForm.action = \"WOpacUsrRsvCancelAction.do\";"))
        assertTrue(tailSection.contains("document.prevRequestForm.submit();"))
    }

    @Test
    fun `診断prevformのtailは別名束縛でも送信処理文を識別子非依存に拾う`() = runBlocking {
        // f はprevRequestFormのローカル別名。「prevRequestForm」という識別子はf.action/f.submit()には
        // 一切現れないため、識別子ベースのstmts=では取りこぼす。tail=は署名候補の直後を機械的に読むだけ
        // なので、別名束縛でも送信先を決める処理を拾えることを確認する。
        val aliasStatements =
            "var f = document.prevRequestForm; f.action = \"WOpacUsrRsvCancelAction.do\"; f.submit();"
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page(cancelConfirmationStageHtml(scriptBody = "${cancelConfirmationScript()} $aliasStatements")))
        server.enqueue(page("<html>取消完了</html>"))
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))
        val notes = mutableListOf<Pair<String, String>>()
        val root = LicsXpSession(server.url("/"), okhttp3.OkHttpClient(), noteCapturingObserver(notes), waitForRequestSlot = {})
        val session = LicsXpReservationSession(root, ReservationSequenceHooks())

        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(ReservationCancelAttempt.CancelledAndHidden, result)
        assertEquals(6, server.requestCount)
        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(2, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })

        val summary = notes.single { it.first == "cancel-reservation-prevform" }.second
        val stmtsSection = summary.substringAfter("stmts=").substringBefore(" tail=")
        val tailSection = summary.substringAfter("tail=").substringBefore(" okCodesName=")
        // 識別子ベースのstmts=は別名束縛の代入文までしか拾えない（既知の限界。tail=追加の動機そのもの）。
        assertTrue(stmtsSection.contains("var f = document.prevRequestForm;"))
        assertFalse(stmtsSection.contains("f.action"))
        assertFalse(stmtsSection.contains("f.submit"))
        // tail=は識別子に依存しないため、別名経由の送信処理文を両方とも拾う。
        assertTrue(tailSection.contains("f.action = \"WOpacUsrRsvCancelAction.do\";"))
        assertTrue(tailSection.contains("f.submit();"))
    }

    @Test
    fun `取消確認署名診断は巨大scriptでも集計のみを記録し2段階目を送る`() = runBlocking {
        assertCancelSignatureDiagnostic(
            stage1Html = cancelConfirmationStageHtml(scriptBody = cancelConfirmationScriptInLargeScript()),
            expectStage2 = true,
            expectedSummaryParts = listOf("matched=true", "scan=completed:1", "candidates=1", "fixedRegexMatches=1"),
        )
    }

    @Test
    fun `取消確認署名診断はtemplate拒否を集計して2段階目を送らない`() = runBlocking {
        assertCancelSignatureDiagnostic(
            stage1Html = cancelConfirmationStageHtml(
                scriptBody = "${cancelConfirmationScript()} const ignored = `template`;",
            ),
            expectStage2 = false,
            expectedSummaryParts = listOf("matched=false", "template:1", "candidates=0", "fixedRegexMatches=0"),
            expectedStageSummaryParts = listOf(
                "targetStillPresent=true",
                "signatureMatched=false",
                "matched=false",
            ),
        )
    }

    @Test
    fun `実ライブを模す外側block内の候補でも2段階目を送る`() = runBlocking {
        assertCancelSignatureDiagnostic(
            stage1Html = cancelConfirmationStageHtml(scriptBody = "if (false) { ${cancelConfirmationScript()} }"),
            expectStage2 = true,
            expectedSummaryParts = listOf("matched=true", "scan=completed:1", "candidates=1", "fixedRegexMatches=1"),
        )
    }

    @Test
    fun `取消確認署名診断は同一blockの文頭以外を拒否して2段階目を送らない`() = runBlocking {
        assertCancelSignatureDiagnostic(
            stage1Html = cancelConfirmationStageHtml(scriptBody = "const invalidAssignment = ${cancelConfirmationScript()}"),
            expectStage2 = false,
            expectedSummaryParts = listOf("matched=false", "scan=completed:1", "candidates=0", "fixedRegexMatches=0"),
        )
    }

    @Test
    fun `取消確認署名診断は後続rest if欠落を拒否して2段階目を送らない`() = runBlocking {
        assertCancelSignatureDiagnostic(
            stage1Html = cancelConfirmationStageHtml(
                scriptBody = cancelConfirmationScript().replace("if (rest)", "var restMissing = true;"),
            ),
            expectStage2 = false,
            expectedSummaryParts = listOf("matched=false", "scan=completed:1", "candidates=0", "fixedRegexMatches=0"),
        )
    }

    @Test
    fun `取消確認署名診断は後続for欠落を拒否して2段階目を送らない`() = runBlocking {
        assertCancelSignatureDiagnostic(
            stage1Html = cancelConfirmationStageHtml(
                scriptBody = cancelConfirmationScript().replace(
                    "for (var i = 0; i < okArray.length; i++)",
                    "while (true)",
                ),
            ),
            expectStage2 = false,
            expectedSummaryParts = listOf("matched=false", "scan=completed:1", "candidates=0", "fixedRegexMatches=0"),
        )
    }

    @Test
    fun `2段階目後に対象消失でCancelledAndHiddenになる場合と対象が残っている場合を区別する`() = runBlocking {
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
    fun `旧来の内側確認構造だけでは2段階目を送らない`() = runBlocking {
        // 実サイトで採取したブラウザ判別を含む外側構造がなければ、状態変更POSTを許可しない。
        assertOnlyStage1IsSent(cancelConfirmationStageHtml(scriptBody = legacyInnerCancelConfirmationScript()))
    }

    @Test
    fun `確認scriptのtailだけがif false内なら2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent(cancelConfirmationStageHtml(scriptBody = cancelConfirmationScriptWithTailInsideIfFalse()))
    }

    @Test
    fun `候補内部に禁止構文があれば2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent(
            cancelConfirmationStageHtml(
                scriptBody = cancelConfirmationScript().replace(
                    "rest = lbConfirm(",
                    "function unsupportedInsideCandidate() {}\nrest = lbConfirm(",
                ),
            ),
        )
    }

    @Test
    fun `確認文言に接頭文字があれば2段階目を送らない`() = runBlocking {
        val message = "予約の取消を行います。よろしいですか？"
        assertOnlyStage1IsSent(
            cancelConfirmationStageHtml(scriptBody = cancelConfirmationScript().replace(message, "通知: $message")),
        )
    }

    @Test
    fun `確認文言に接尾文字があれば2段階目を送らない`() = runBlocking {
        val message = "予約の取消を行います。よろしいですか？"
        assertOnlyStage1IsSent(
            cancelConfirmationStageHtml(scriptBody = cancelConfirmationScript().replace(message, "$message (確認)")),
        )
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
    fun `top level returnの後でも固定署名なら2段階目を送る`() = runBlocking {
        assertStage2IsSent(cancelConfirmationStageHtml(scriptPrefix = "return;"))
    }

    @Test
    fun `未呼出し関数内でも固定署名なら2段階目を送る`() = runBlocking {
        assertStage2IsSent(
            cancelConfirmationStageHtml(
                scriptBody = "function showCancelConfirmation() { ${cancelConfirmationScript()} }",
            ),
        )
    }

    @Test
    fun `未呼出しarrow関数内でも固定署名なら2段階目を送る`() = runBlocking {
        assertStage2IsSent(
            cancelConfirmationStageHtml(
                scriptBody = "const showCancelConfirmation = () => { ${cancelConfirmationScript()} };",
            ),
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
    fun `同一script内にtemplate literalがあれば実測構造があっても2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent("<html><script>${cancelConfirmationScript()} const ignored = `template`;</script></html>")
    }

    @Test
    fun `if条件直後の正規表現内にある取消確認構造では2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent("<html><script>${cancelConfirmationScriptInsideRegex("if (true)")}</script></html>")
    }

    @Test
    fun `else直後の正規表現内にある取消確認構造では2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent("<html><script>if (false) {} else ${cancelConfirmationScriptInsideRegex("")}</script></html>")
    }

    @Test
    fun `arrow直後の正規表現内にある取消確認構造では2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent("<html><script>const ignored = () => ${cancelConfirmationScriptInsideRegex("")}</script></html>")
    }

    @Test
    fun `閉じ波括弧直後の正規表現内にある取消確認構造では2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent("<html><script>if (false) {} ${cancelConfirmationScriptInsideRegex("")}</script></html>")
    }

    @Test
    fun `固定署名で拒否した候補直後の正規表現内にある有効署名では2段階目を送らない`() = runBlocking {
        val rejectedCandidate = cancelConfirmationScript().replace(
            "rest = lbConfirm(",
            "function unsupportedInsideCandidate() {}\nrest = lbConfirm(",
        )
        assertOnlyStage1IsSent("<html><script>$rejectedCandidate ${cancelConfirmationScriptInsideRegex("")}</script></html>")
    }

    @Test
    fun `到達不能かは判定せず関数内の固定署名で2段階目を送る`() = runBlocking {
        assertStage2IsSent(
            cancelConfirmationStageHtml(
                scriptBody = "function unreachable() { ${cancelConfirmationScript()} } if (false) { unreachable(); }",
            ),
        )
    }

    @Test
    fun `if false分岐内でも固定署名なら2段階目を送る`() = runBlocking {
        assertStage2IsSent(
            cancelConfirmationStageHtml(scriptBody = "if (false) { ${cancelConfirmationScript()} }"),
        )
    }

    @Test
    fun `正規表現リテラル内の取消確認構造では2段階目を送らない`() = runBlocking {
        assertOnlyStage1IsSent(
            "<html><script>var candidate = /${cancelConfirmationScript().replace("/", "\\/")}/;</script></html>",
        )
    }

    @Test
    fun `分割代入を使う関数内でも固定署名なら2段階目を送る`() = runBlocking {
        assertStage2IsSent(
            cancelConfirmationStageHtml(
                scriptBody = "function unreachable({value}) { ${cancelConfirmationScript()} }",
            ),
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
        // CANCEL_CODE_REGEXは数字だけを取消コードとして抽出するため、複製側も数字にしないと
        // 「取消可能な行(cancelCodeが非空)が2つ」という本来検証したい曖昧ケースを再現できない。
        requireNotNull(duplicate.selectFirst("input[onclick*=yoykCancel]")) { "複製した取消行に取消ボタンがありません" }
            .attr("onclick", "yoykCancel('9999999999')")
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

    private suspend fun assertStage2IsSent(stage1Html: String) {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page(stage1Html))
        server.enqueue(page("<html>取消完了</html>"))
        server.enqueue(page(menuWithReservationCount(18)))
        server.enqueue(page(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))

        val session = LicsXpReservationSession(LicsXpSession(server.url("/"), waitForRequestSlot = {}), ReservationSequenceHooks())
        assertEquals(ReservationCancelAttempt.CancelledAndHidden, session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD))
        assertEquals(6, server.requestCount)
        val requests = List(6) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(2, requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true })
    }

    private suspend fun assertCancelSignatureDiagnostic(
        stage1Html: String,
        expectStage2: Boolean,
        expectedSummaryParts: List<String>,
        expectedStageSummaryParts: List<String> = emptyList(),
    ) {
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))
        server.enqueue(page(stage1Html))
        if (expectStage2) {
            server.enqueue(page("<html>取消完了</html>"))
            server.enqueue(page(menuWithReservationCount(18)))
            server.enqueue(page(withoutReservationRow(fixture("usrrsv.html"), "1013074729")))
        } else {
            server.enqueue(page(menuWithReservationCount(19)))
            server.enqueue(page(fixture("usrrsv.html")))
        }
        val notes = mutableListOf<Pair<String, String>>()
        val root = LicsXpSession(server.url("/"), okhttp3.OkHttpClient(), noteCapturingObserver(notes), waitForRequestSlot = {})
        val session = LicsXpReservationSession(root, ReservationSequenceHooks())

        val result = session.cancelReservation("1013074729", CANCEL_TARGET_TILCOD)

        assertEquals(
            if (expectStage2) ReservationCancelAttempt.CancelledAndHidden else ReservationCancelAttempt.IndeterminateAfterPost,
            result,
        )
        assertEquals(if (expectStage2) 6 else 5, server.requestCount)
        val requests = List(server.requestCount) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(
            if (expectStage2) 2 else 1,
            requests.count { it.path?.startsWith("/WOpacUsrRsvCancelAction.do") == true },
        )
        val signatureNotes = notes.filter { it.first == "cancel-reservation-signature" }
        assertEquals(1, signatureNotes.size)
        expectedSummaryParts.forEach { expected -> assertTrue(signatureNotes.single().second.contains(expected)) }
        assertFalse(signatureNotes.single().second.contains("1013074729"))
        assertFalse(signatureNotes.single().second.contains(CANCEL_TARGET_TILCOD))
        val stageNotes = notes.filter { it.first == "cancel-reservation-stage" }
        assertEquals(1, stageNotes.size)
        expectedStageSummaryParts.forEach { expected -> assertTrue(stageNotes.single().second.contains(expected)) }
        assertFalse(stageNotes.single().second.contains("1013074729"))
        assertFalse(stageNotes.single().second.contains(CANCEL_TARGET_TILCOD))
    }

    private fun cancelConfirmationStageHtml(
        listHtml: String = fixture("usrrsv.html"),
        scriptAttributes: String = "",
        scriptPrefix: String = "",
        scriptBody: String = cancelConfirmationScript(),
        previousRequestAction: String = "WOpacUsrRsvCancelAction.do",
        okCodesFieldName: String = "okCodes",
        previousRequestSourceHtml: String = fixture("usrrsv.html"),
    ): String {
        val script = "<script$scriptAttributes>$scriptPrefix var OK_CODES_NAME = \"$okCodesFieldName\"; $scriptBody</script>"
        val previousRequestForm = previousRequestFormHtml(previousRequestSourceHtml, previousRequestAction)
        require(listHtml.contains("</body>", ignoreCase = true)) { "予約一覧fixtureにbody終端がありません" }
        return listHtml.replace("</body>", "$previousRequestForm$script</body>", ignoreCase = true)
    }

    /** 実ライブ同様、stage1送信内容を保持したprevRequestFormをDOM順で再現する。 */
    private fun previousRequestFormHtml(listHtml: String, action: String): String {
        val stage1 = ReservationCancelFormParser.parse(listHtml).buildForm("1013074729")
        val controls = buildList {
            add("mngFlg2_handan" to "1")
            add("kbnchgflag" to "1")
            for (index in 0 until stage1.size) add(stage1.name(index) to stage1.value(index))
        }.joinToString("\n") { (name, value) ->
            "<input type=\"hidden\" name=\"${htmlAttribute(name)}\" value=\"${htmlAttribute(value)}\">"
        }
        return "<form name=\"prevRequestForm\" action=\"${htmlAttribute(action)}\">$controls</form>"
    }

    private fun htmlAttribute(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * 初回ライブ取消診断の`screen-script`ログから採取したtop-level confirm抽出断片。
     * 実サイトHTML全文ではなく、認証情報・個人情報を含まない診断抽出結果をfixture化している。
     */
    private fun cancelConfirmationScript(): String = fixture("reservation_cancel_confirmation_live_fragment.js")

    private fun cancelConfirmationScriptWithTailInsideIfFalse(): String {
        val script = cancelConfirmationScript()
        val tailIndex = script.indexOf("for (var i = 0; i < okArray.length; i++)")
        require(tailIndex >= 0) { "実測確認script断片にOKコードhidden作成ループがありません" }
        return script.substring(0, tailIndex) + "if (false) {\n" + script.substring(tailIndex) + "}\n"
    }

    private fun cancelConfirmationScriptInLargeScript(): String = """
        function unrelatedBefore() {
            return /[{}]/.test("outside");
        }
        const unrelatedPattern = /outside\\/pattern/;
        const unrelatedArrow = () => ({ value: "outside" });
        ${cancelConfirmationScript()}
        class UnrelatedAfter {
            matches(value) { return /after/.test(value); }
        }
    """.trimIndent()

    private fun cancelConfirmationScriptInsideRegex(prefix: String): String {
        val escaped = cancelConfirmationScript().replace("/", "\\/")
        return "$prefix /noise; $escaped /;"
    }

    /** 外側のブラウザ判別分岐を含まない、旧来の最小合成構造。状態変更POSTの許可対象ではない。 */
    private fun legacyInnerCancelConfirmationScript(): String = """
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
        newHidden.value = okArray[i];
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
