package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 閲覧の通信を書き込みと別の列で並行に通す設計(docs/design/browse-lane.md)の固定テスト。
 * §3の5項目のうち、DI経路そのもの(項目5)を除く決定論的な部分をここで固定する。
 * DIでの分離自体はRepositoryProvisionModuleが根セッションと閲覧セッションを別の
 * `LicsXpSession`インスタンスとして提供していることで担保する(コード上の構造で保証。§5参照)。
 */
class LicsXpBrowseLaneTest {
    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        servers.forEach { it.shutdown() }
    }

    @Test
    fun `メンバーの列が占有中でも閲覧セッションのbookDetailは占有が解ける前に返る`() = runBlocking {
        val rootServer = newServer()
        val browseServer = newServer()
        browseServer.enqueue(html(fixture("book_detail.html")))

        val rootSession = session(rootServer)
        val browseSession = session(browseServer)
        val client = LicsXpClient(session = rootSession, browseSession = browseSession)

        val occupyStarted = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val occupyJob = launch {
            rootSession.withExclusiveRequestSequence {
                occupyStarted.complete(Unit)
                releaseGate.await()
            }
        }
        occupyStarted.await()

        // 占有中でも、閲覧セッションのbookDetailはタイムアウトせずすぐ返る。
        val detail = withTimeout(5_000) { client.bookDetail("1000000961766") }
        assertEquals("愛の哲学", detail.fields["書名"])

        // bookDetailが返った時点でも、メンバーの列の占有はまだ解けていない。
        assertFalse(occupyJob.isCompleted)

        releaseGate.complete(Unit)
        occupyJob.join()
    }

    @Test
    fun `メンバーの列の占有中は別の分離セッションの通信が待たされる(既存の直列化の維持)`() = runBlocking {
        val rootServer = newServer()
        rootServer.enqueue(html("isolated-response"))

        val rootSession = session(rootServer)
        val isolatedSession = rootSession.newIsolatedSession()

        val occupyStarted = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val occupyJob = launch {
            rootSession.withExclusiveRequestSequence {
                occupyStarted.complete(Unit)
                releaseGate.await()
            }
        }
        occupyStarted.await()

        val isolatedCallStarted = CompletableDeferred<Unit>()
        var isolatedCompleted = false
        val isolatedJob = launch {
            isolatedCallStarted.complete(Unit)
            isolatedSession.get("anything")
            isolatedCompleted = true
        }
        isolatedCallStarted.await()

        // 分離セッションはRequestRateLimiterを共有しているため、占有中は完了しない。
        assertFalse(isolatedCompleted)

        releaseGate.complete(Unit)
        occupyJob.join()
        withTimeout(5_000) { isolatedJob.join() }
        assertTrue(isolatedCompleted)
    }

    @Test
    fun `閲覧の列の中でも開始間隔は500ms以上に保たれる`() = runBlocking {
        val server = newServer()
        server.enqueue(html("first"))
        server.enqueue(html("second"))

        var currentTimeMillis = 0L
        val waitedMillis = mutableListOf<Long>()
        val browseSession = LicsXpSession(
            baseUrl = server.url("/"),
            client = OkHttpClient(),
            waitForRequestSlot = { millis ->
                waitedMillis += millis
                currentTimeMillis += millis
            },
            nowMillis = { currentTimeMillis },
        )

        browseSession.get("first")
        currentTimeMillis += 100 // 経過100msだけ進める(500ms未満)
        browseSession.get("second")

        assertEquals(listOf(400L), waitedMillis)
    }

    @Test
    fun `閲覧セッションと根セッションでCookieが共有されない`() = runBlocking {
        val server = newServer()
        server.enqueue(html("root-response", setCookie = true))
        server.enqueue(html("browse-response"))

        val rootSession = session(server)
        val browseSession = session(server)

        rootSession.get("root-path")
        browseSession.get("browse-path")

        takeRequest(server) // root-pathへの最初の要求(Cookieはまだ受け取っていない)
        val browseRequest = takeRequest(server)
        assertNull(browseRequest.getHeader("Cookie"))
    }

    @Test
    fun `LicsXpClientの閲覧操作とfetchCurrentCirculationは別セッションへ向かう`() = runBlocking {
        val browseServer = newServer()
        val rootServer = newServer()

        browseServer.enqueue(json("[\"愛\",\"愛の哲学\"]"))

        rootServer.enqueue(html("<html><body>温めページ</body></html>", setCookie = true))
        rootServer.enqueue(html(fixture("login_form.html")))
        rootServer.enqueue(html("<html><body>login</body></html>"))
        rootServer.enqueue(html(fixture("menu.html")))
        rootServer.enqueue(html(fixture("usrlend.html")))
        rootServer.enqueue(html(fixture("usrrsv.html")))

        val client = LicsXpClient(
            session = session(rootServer),
            browseSession = session(browseServer),
        )

        assertEquals(listOf("愛", "愛の哲学"), client.autocomplete("愛"))
        assertEquals(1, browseServer.requestCount)
        assertEquals(0, rootServer.requestCount)

        client.fetchCurrentCirculation(generatedCardNumber(), generatedPassword())
        assertEquals(6, rootServer.requestCount)
        assertEquals(1, browseServer.requestCount)
    }

    private fun newServer(): MockWebServer = MockWebServer().also {
        it.start()
        servers += it
    }

    private fun session(server: MockWebServer): LicsXpSession = LicsXpSession(
        baseUrl = server.url("/"),
        client = OkHttpClient(),
        waitForRequestSlot = {},
    )

    private fun takeRequest(server: MockWebServer) = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))

    private fun html(body: String, setCookie: Boolean = false): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .apply { if (setCookie) setHeader("Set-Cookie", "JSESSIONID=fixture; Path=/") }
        .setBody(body)

    private fun json(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader).getResource("fixtures/$name")!!.readText()

    private fun generatedCardNumber(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    private fun generatedPassword(): String = UUID.randomUUID().toString()
}
