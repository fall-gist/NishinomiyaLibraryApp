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
    /** OkHttp自身の接続失敗再試行も、予約確定だけは明示的に無効化する。 */
    private val noRetryClient: OkHttpClient = client.newBuilder()
        .retryOnConnectionFailure(false)
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

    /**
     * 副作用を持つ予約確定専用。IOException を含め、呼出し一回につき HTTP POST は一度だけ。
     * 呼出し側は例外を成否不明として照合へ進み、絶対に再送してはならない。
     */
    internal suspend fun postExactlyOnce(
        path: String,
        query: Map<String, String> = emptyMap(),
        form: FormBody,
    ): String = try {
        executeOnceWithoutRetry(
            Request.Builder()
                .url(endpointUrl(path, query))
                .post(form)
                .build(),
        )
    } catch (exception: IOException) {
        throw LibraryError.Network(exception)
    } catch (exception: HttpFailure) {
        throw LibraryError.Network(exception)
    }

    /**
     * 確認画面の取得から確定送信までのように、途中へ別セッションの通信を入れてはいけない
     * 要求列を実行する。各要求の開始間隔は通常どおり 500ms 以上に保つ。
     */
    internal suspend fun <T> withExclusiveRequestSequence(
        block: suspend ExclusiveRequestSequence.() -> T,
    ): T = rateLimiter.withExclusiveSequence {
        ExclusiveRequestSequence().block()
    }

    internal inner class ExclusiveRequestSequence internal constructor() {
        suspend fun get(
            path: String,
            query: Map<String, String> = emptyMap(),
        ): String = executeInExclusiveSequence(
            Request.Builder()
                .url(endpointUrl(path, query))
                .get()
                .build(),
            client,
            retryOnIOException = true,
        )

        suspend fun postExactlyOnce(
            path: String,
            query: Map<String, String> = emptyMap(),
            form: FormBody,
        ): String = executeInExclusiveSequence(
            Request.Builder()
                .url(endpointUrl(path, query))
                .post(form)
                .build(),
            noRetryClient,
            retryOnIOException = false,
        )
    }

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

    private suspend fun executeOnceWithoutRetry(request: Request): String = withContext(Dispatchers.IO) {
        rateLimiter.executeWhenAllowed {
            noRetryClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw HttpFailure(response)
                response.body?.string().orEmpty()
            }
        }
    }

    private suspend fun executeInExclusiveSequence(
        request: Request,
        requestClient: OkHttpClient,
        retryOnIOException: Boolean,
    ): String {
        var latestIOException: IOException? = null
        val attempts = if (retryOnIOException) MAX_NETWORK_ATTEMPTS else 1
        repeat(attempts) {
            try {
                return withContext(Dispatchers.IO) {
                    rateLimiter.executeWhileExclusive {
                        requestClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw HttpFailure(response)
                            response.body?.string().orEmpty()
                        }
                    }
                }
            } catch (exception: IOException) {
                latestIOException = exception
            } catch (exception: HttpFailure) {
                throw LibraryError.Network(exception)
            }
        }
        throw LibraryError.Network(requireNotNull(latestIOException))
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
        executeWithRateLimit(block)
    }

    suspend fun <T> withExclusiveSequence(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> executeWhileExclusive(block: () -> T): T = executeWithRateLimit(block)

    private suspend fun <T> executeWithRateLimit(block: () -> T): T {
        lastRequestStartedAtMillis?.let { previousStart ->
            val remaining = MINIMUM_REQUEST_INTERVAL_MILLIS - (nowMillis() - previousStart)
            if (remaining > 0) waitForRequestSlot(remaining)
        }
        lastRequestStartedAtMillis = nowMillis()
        return block()
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
