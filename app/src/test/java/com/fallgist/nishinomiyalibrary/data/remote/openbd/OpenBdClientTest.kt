package com.fallgist.nishinomiyalibrary.data.remote.openbd

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenBdClientTest {
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
    fun `cover URLだけを取得し空文字とJSON nullはnullにする`() = runBlocking {
        server.enqueue(json("[{\"summary\":{\"cover\":\"https://example.invalid/cover.jpg\"}}]"))
        server.enqueue(json("[{\"summary\":{\"cover\":\"\"}}]"))
        server.enqueue(json("[{\"summary\":{\"cover\":null}}]"))
        server.enqueue(json("[null]"))
        val client = OpenBdClient(server.url("/"), OkHttpClient())

        assertEquals("https://example.invalid/cover.jpg", client.coverUrl("9784000000000"))
        assertNull(client.coverUrl("9784000000001"))
        assertNull(client.coverUrl("9784000000002"))
        assertNull(client.coverUrl("9784000000003"))
        repeat(4) { index ->
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/get", request.requestUrl!!.encodedPath)
            assertEquals("978400000000$index", request.requestUrl!!.queryParameter("isbn"))
        }
    }

    @Test
    fun `不正JSONはParseに分類する`() = runBlocking {
        server.enqueue(json("not-json"))

        val error = try {
            OpenBdClient(server.url("/"), OkHttpClient()).coverUrl("9784000000000")
            throw AssertionError("LibraryError が送出されませんでした")
        } catch (exception: LibraryError) {
            exception
        }

        assertTrue(error is LibraryError.Parse)
        assertEquals("openbd", (error as LibraryError.Parse).screen)
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
