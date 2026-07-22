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
import org.junit.Assert.assertNotNull
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
        server.enqueue(page(fixture("reservation_confirm_js_action.html")))
        server.enqueue(page("<html><div id='stat-login'></div></html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("usrrsv.html")))

        val root = LicsXpSession(server.url("/"), waitForRequestSlot = {})
        val session = LicsXpReservationGateway(root).openAuthenticatedSession("1234", "secret")
        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))
        assertTrue(session.fetchReservations().isNotEmpty())
        session.close()

        val requests = List(8) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals("/WOpacEsSchCmpdDispAction.do", requests[0].path)
        assertEquals("/OpacInitLoginAction.do?subSystemFlag=0", requests[1].path)
        assertEquals("/j_security_check?subSystemFlag=0", requests[2].path)
        assertTrue(requests[2].body.readUtf8().contains("j_username=00000000000000001234"))
        assertEquals("/WOpacEsTifDirectYoyDispAction.do?tilcod=1000000000001", requests[4].path)
        assertEquals("/WOpacEsTifDirectYoyExecAction.do?tilcod=1000000000001", requests[5].path)
        assertEquals(1, requests.count { it.path?.startsWith("/WOpacEsTifDirectYoyExecAction.do") == true })
        val body = requests[5].body.readUtf8()
        val fields = decodeForm(body)
        assertEquals(listOf("106"), fields["receivename"])
        assertEquals(listOf("4"), fields["contact"])
        assertEquals(listOf("4"), fields["contactweb"])
        assertEquals(listOf("keep-me"), fields["siteIssued"])
        assertNotNull(requests[5].getHeader("User-Agent"))
    }

    @Test
    fun `指定館が確認画面に無ければ確定POSTを送らない`() = runBlocking {
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(fixture("menu.html")))
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
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `確定POSTの接続断でもPOSTを再送しない`() = runBlocking {
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("reservation_confirm.html")))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `確定POSTがログインフォームを返しても再送しない`() = runBlocking {
        server.enqueue(page("<html>温め</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("reservation_confirm.html")))
        server.enqueue(page(fixture("login_form.html")))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
            .openAuthenticatedSession("1234", "secret")

        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `hash無し確認フォームでもサーバー発行hiddenを保持して確定できる`() = runBlocking {
        server.enqueue(page("<html>温め</html>")); server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>中継</html>")); server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("reservation_confirm.html").replace("<input type=\"hidden\" name=\"hash\" value=\"confirm-hash\" />", "")))
        server.enqueue(page("<div id='stat-login'></div>"))
        val session = LicsXpReservationGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {})).openAuthenticatedSession("1", "p")
        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, session.directReserve("1000000000001", "106"))
        repeat(5) { server.takeRequest() }
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
                "/WOpacEsTifDirectYoyDispAction.do" -> page(fixture("reservation_confirm_js_action.html"))
                "/WOpacEsTifDirectYoyExecAction.do" -> page("<html><div id='stat-login'></div></html>")
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

        assertEquals(5, server.requestCount)
        allowConfirmProcessing.complete(Unit)
        assertEquals(DirectReservationAttempt.IndeterminateAfterPost, reservation.await())
        assertEquals("background", background.await())

        val requests = List(7) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals("/WOpacEsTifDirectYoyDispAction.do?tilcod=1000000000001", requests[4].path)
        assertEquals("/WOpacEsTifDirectYoyExecAction.do?tilcod=1000000000001", requests[5].path)
        assertEquals("/background", requests[6].path)
        assertEquals(1, requests.count { it.path?.startsWith("/WOpacEsTifDirectYoyExecAction.do") == true })
        assertTrue(waits.all { it >= 500 })
    }

    @Test
    fun `確認画面の例外後にも他セッションの要求を実行できる`() = runBlocking {
        server.enqueue(page("<html>session</html>"))
        server.enqueue(page(fixture("login_form.html")))
        server.enqueue(page("<html>login relay</html>"))
        server.enqueue(page(fixture("menu.html")))
        server.enqueue(page(fixture("reservation_confirm.html").replace("tiles.WEsYoyConfirm", "unexpected")))
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
        assertEquals(6, server.requestCount)
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

    private fun page(body: String, cookie: Boolean = false): MockResponse = MockResponse().setBody(body).apply {
        if (cookie) addHeader("Set-Cookie", "JSESSIONID=fixture; Path=/")
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader).getResource("fixtures/$name")!!.readText()

    private fun decodeForm(body: String): Map<String, List<String>> = body.split('&').filter(String::isNotBlank)
        .map { part -> part.substringBefore('=') to URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8.name()) }
        .groupBy({ it.first }, { it.second })
}
