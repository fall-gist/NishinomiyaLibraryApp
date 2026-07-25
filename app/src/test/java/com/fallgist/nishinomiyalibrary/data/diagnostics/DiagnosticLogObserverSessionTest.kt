package com.fallgist.nishinomiyalibrary.data.diagnostics

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LicsXpSession
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DiagnosticLogObserverを実際のLicsXpSessionへ注入し、
 * レスポンス本文・Cookie・パスワードがログ文字列に一切現れないことを確認する。
 */
class DiagnosticLogObserverSessionTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `記録ONではレスポンス本文とCookieとパスワードが一切ログへ現れない`() = runBlocking {
        val secretBody = "SUPER_SECRET_BODY_CONTENT_1234567890"
        val secretCookieValue = "SUPER_SECRET_COOKIE_VALUE"
        val secretPassword = "SuperSecretPassword123"
        server.enqueue(
            MockResponse()
                .setBody("<html><body>$secretBody<form><input name=\"j_password\"/></form></body></html>")
                .addHeader("Set-Cookie", "JSESSIONID=$secretCookieValue; Path=/"),
        )

        val log = DiagnosticLog(fixedClock())
        log.recording = true
        val observer = DiagnosticLogObserver(log)
        val session = LicsXpSession(server.url("/"), OkHttpClient(), observer, waitForRequestSlot = {})

        session.post(
            path = "j_security_check",
            form = FormBody.Builder()
                .add("j_username", "00000000000000001234")
                .add("j_password", secretPassword)
                .add("hash", "site-secret")
                .build(),
        )

        val formatted = log.formatted()
        assertFalse(formatted.contains(secretBody))
        assertFalse(formatted.contains(secretCookieValue))
        assertFalse(formatted.contains(secretPassword))
        assertFalse(formatted.contains("00000000000000001234"))
        assertFalse(formatted.contains("site-secret"))
        assertTrue(formatted.contains("[REDACTED]"))
        assertTrue(log.entries.value.isNotEmpty())
    }

    @Test
    fun `記録OFFのときは1件も記録されない`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html><body>hello</body></html>"))

        val log = DiagnosticLog(fixedClock())
        // recording は既定でfalseのまま
        val observer = DiagnosticLogObserver(log)
        val session = LicsXpSession(server.url("/"), OkHttpClient(), observer, waitForRequestSlot = {})

        session.get("some-path")

        assertTrue(log.entries.value.isEmpty())
        assertEquals("", log.formatted())
    }
}
