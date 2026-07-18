package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.HashExtractor
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.PageTokens
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ParseException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val MINIMUM_REQUEST_INTERVAL_MILLIS = 500L

/** LICS-XP の画面遷移に必要な、1利用者分の状態を保持する。 */
class LicsXpSession private constructor(
    val baseUrl: HttpUrl,
    client: OkHttpClient,
    private val waitForRequestSlot: suspend (Long) -> Unit,
    private val nowMillis: () -> Long,
    private val rateLimiter: RequestRateLimiter,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://tosho.nishi.or.jp/licsxp-opac/"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; NishinomiyaLibraryApp) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
        private const val MAX_NETWORK_ATTEMPTS = 2
    }

    private val cookieJar = InMemoryCookieJar()
    private val sourceClient = client
    private val client: OkHttpClient = sourceClient.newBuilder()
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .build()
            chain.proceed(request)
        }
        .build()

    var lastPageTokens: PageTokens? = null
        private set

    constructor(
        baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
        client: OkHttpClient = OkHttpClient(),
        waitForRequestSlot: suspend (Long) -> Unit = { delay(it) },
        nowMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        baseUrl = baseUrl,
        client = client,
        waitForRequestSlot = waitForRequestSlot,
        nowMillis = nowMillis,
        rateLimiter = RequestRateLimiter(waitForRequestSlot, nowMillis),
    )

    internal suspend fun get(
        path: String,
        query: Map<String, String> = emptyMap(),
    ): String = execute(
        Request.Builder()
            .url(endpointUrl(path, query))
            .get()
            .build(),
    )

    internal suspend fun post(
        path: String,
        query: Map<String, String> = emptyMap(),
        form: FormBody,
    ): String = execute(
        Request.Builder()
            .url(endpointUrl(path, query))
            .post(form)
            .build(),
    )

    internal fun updateTokens(html: String): PageTokens = HashExtractor.extract(html).also { tokens ->
        lastPageTokens = tokens
    }

    internal fun requireTokens(): PageTokens = lastPageTokens
        ?: throw ParseException("session", "hash と gamenid がまだ取得されていません")

    internal fun newIsolatedSession(): LicsXpSession = LicsXpSession(
        baseUrl = baseUrl,
        client = sourceClient,
        waitForRequestSlot = waitForRequestSlot,
        nowMillis = nowMillis,
        rateLimiter = rateLimiter,
    )

    private fun endpointUrl(path: String, query: Map<String, String>): HttpUrl {
        require(path.isNotBlank() && !path.startsWith('/')) { "相対パスを指定してください" }
        return baseUrl.newBuilder()
            .addPathSegment(path)
            .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
    }

    private suspend fun execute(request: Request): String {
        var latestIOException: IOException? = null
        repeat(MAX_NETWORK_ATTEMPTS) {
            try {
                return executeOnce(request)
            } catch (exception: IOException) {
                latestIOException = exception
            } catch (exception: HttpFailure) {
                throw LibraryError.Network(exception)
            }
        }
        throw LibraryError.Network(requireNotNull(latestIOException))
    }

    private suspend fun executeOnce(request: Request): String = withContext(Dispatchers.IO) {
        rateLimiter.executeWhenAllowed {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw HttpFailure(response)
                response.body?.string().orEmpty()
            }
        }
    }

    private class HttpFailure(response: Response) : Exception() {
        val statusCode: Int = response.code
    }
}

/** Cookieや画面トークンとは独立して、同一クライアント内の開始間隔を直列化する。 */
private class RequestRateLimiter(
    private val waitForRequestSlot: suspend (Long) -> Unit,
    private val nowMillis: () -> Long,
) {
    private val mutex = Mutex()
    private var lastRequestStartedAtMillis: Long? = null

    suspend fun <T> executeWhenAllowed(block: () -> T): T = mutex.withLock {
        lastRequestStartedAtMillis?.let { previousStart ->
            val remaining = MINIMUM_REQUEST_INTERVAL_MILLIS - (nowMillis() - previousStart)
            if (remaining > 0) waitForRequestSlot(remaining)
        }
        lastRequestStartedAtMillis = nowMillis()
        block()
    }
}

/** プロセス内だけにCookieを保持し、OkHttpのHost/path判定に従って送信する。 */
private class InMemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        removeExpiredCookies()
        cookies.forEach { received ->
            this.cookies.removeAll {
                it.name == received.name && it.domain == received.domain && it.path == received.path
            }
            if (received.expiresAt > System.currentTimeMillis()) this.cookies += received
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        removeExpiredCookies()
        return cookies.filter { it.matches(url) }
    }

    private fun removeExpiredCookies() {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
    }
}
