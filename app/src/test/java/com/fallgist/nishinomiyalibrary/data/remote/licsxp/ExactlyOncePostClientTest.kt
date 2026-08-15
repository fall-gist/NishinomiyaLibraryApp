package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 予約確定専用の一回限りPOSTが、通常のGET/POSTと同じCookieJarとヘッダを使うことの回帰試験。
 * ここが崩れると確定POSTだけがセッションCookieを持たずに送信され、サイトは未知のセッションからの
 * 要求として入口画面へ差し戻す。
 */
class ExactlyOncePostClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `確定専用POSTも通常要求と同じセッションCookieとUser-Agentを送る`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("<html>初回</html>")
                .addHeader("Set-Cookie", "JSESSIONID=session-value; Path=/; HttpOnly"),
        )
        server.enqueue(MockResponse().setBody("<html>確定</html>"))
        val session = LicsXpSession(server.url("/"), waitForRequestSlot = {})

        session.get("first.do")
        session.postExactlyOnce("exec.do", form = FormBody.Builder().add("a", "b").build())

        // 初回はSet-Cookieを受け取る側なのでCookieを持たない。2回目の確定専用POSTが本題。
        val first = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val exactlyOnce = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertNull(first.getHeader("Cookie"))
        assertEquals("JSESSIONID=session-value", exactlyOnce.getHeader("Cookie"))
        assertEquals(LicsXpSession.USER_AGENT, exactlyOnce.getHeader("User-Agent"))
        assertNotNull(exactlyOnce.getHeader("Accept-Language"))
    }
}
