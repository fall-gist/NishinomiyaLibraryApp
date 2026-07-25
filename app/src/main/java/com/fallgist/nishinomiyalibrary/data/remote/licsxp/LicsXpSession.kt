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
    private val diagnosticObserver: LicsXpDiagnosticObserver,
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
        diagnosticObserver = LicsXpDiagnosticObserver.None,
    )

    /**
     * 明示実行のライブ診断だけで使う、通信内容を秘匿化して記録するための生成口。
     * 通常経路の公開コンストラクタは常に no-op の監査先を使う。
     */
    internal constructor(
        baseUrl: HttpUrl,
        client: OkHttpClient,
        diagnosticObserver: LicsXpDiagnosticObserver,
        waitForRequestSlot: suspend (Long) -> Unit = { delay(it) },
        nowMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        baseUrl = baseUrl,
        client = client,
        waitForRequestSlot = waitForRequestSlot,
        nowMillis = nowMillis,
        rateLimiter = RequestRateLimiter(waitForRequestSlot, nowMillis),
        diagnosticObserver = diagnosticObserver,
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

        /** 通常書誌詳細GETのURLを、その直後の予約確認表示POSTのRefererにだけ使う。 */
        suspend fun getReservationDetail(
            path: String,
            query: Map<String, String> = emptyMap(),
        ): LicsXpReservationDetailPage {
            val url = endpointUrl(path, query)
            val html = executeInExclusiveSequence(
                Request.Builder().url(url).get().build(),
                client,
                retryOnIOException = true,
            )
            return LicsXpReservationDetailPage(html, url)
        }

        /** 通常書誌詳細のLBFormを送信して予約確認表示へ遷移する、読み取り専用のPOST。 */
        suspend fun postReservationConfirmation(
            path: String,
            query: Map<String, String> = emptyMap(),
            form: FormBody,
            detailPage: LicsXpReservationDetailPage,
        ): LicsXpReservationConfirmationPage {
            require(detailPage.url.hasSameOriginAs(baseUrl)) { "書誌詳細ページのoriginが不正です" }
            val url = endpointUrl(path, query)
            val html = executeInExclusiveSequence(
                Request.Builder()
                    .url(url)
                    .header("Referer", detailPage.url.toString())
                    .header("Origin", baseUrl.origin())
                    .post(form)
                    .build(),
                client,
                retryOnIOException = true,
            )
            return LicsXpReservationConfirmationPage(html, url)
        }

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

        /** 予約確認ページ由来のReferer/Originを持つ、予約確定専用の一回限りPOST。 */
        suspend fun postReservationExactlyOnce(
            path: String,
            query: Map<String, String> = emptyMap(),
            form: FormBody,
            confirmationPage: LicsXpReservationConfirmationPage,
        ): String {
            require(confirmationPage.url.hasSameOriginAs(baseUrl)) { "確認ページのoriginが不正です" }
            return executeInExclusiveSequence(
                Request.Builder()
                    .url(endpointUrl(path, query))
                    .header("Referer", confirmationPage.url.toString())
                    .header("Origin", baseUrl.origin())
                    .post(form)
                    .build(),
                noRetryClient,
                retryOnIOException = false,
            )
        }
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
        diagnosticObserver = diagnosticObserver,
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
            observeRequest(request)
            client.newCall(request).execute().use { response ->
                response.readObservedBody()
            }
        }
    }

    private suspend fun executeOnceWithoutRetry(request: Request): String = withContext(Dispatchers.IO) {
        rateLimiter.executeWhenAllowed {
            observeRequest(request)
            noRetryClient.newCall(request).execute().use { response ->
                response.readObservedBody()
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
                        observeRequest(request)
                        requestClient.newCall(request).execute().use { response ->
                            response.readObservedBody()
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

    private fun Response.readObservedBody(): String {
        if (!diagnosticObserver.enabled) {
            if (!isSuccessful) throw HttpFailure(this)
            return body?.string().orEmpty()
        }
        val responseChain = generateSequence(this) { it.priorResponse }.toList().asReversed()
        responseChain.forEach { response ->
            safelyObserve {
                diagnosticObserver.onResponse(
                    method = response.request.method,
                    path = sanitizeDiagnosticPath(response.request.url.encodedPath),
                    statusCode = response.code,
                    redirectPath = response.header("Location")?.let(::sanitizeDiagnosticPath),
                )
            }
        }
        if (!isSuccessful) throw HttpFailure(this)
        val body = body?.string().orEmpty()
        safelyObserve {
            val document = org.jsoup.Jsoup.parse(body)
            diagnosticObserver.onPage(
                path = sanitizeDiagnosticPath(request.url.encodedPath),
                classification = classifyDiagnosticPage(document),
                formFingerprint = formFingerprint(document),
            )
        }
        return body
    }

    private inline fun safelyObserve(block: () -> Unit) {
        runCatching(block)
    }

    private fun observeRequest(request: Request) {
        if (diagnosticObserver.enabled) {
            safelyObserve { diagnosticObserver.onRequest(request.diagnosticFormFields()) }
        }
    }
}

/** 確認GETから内部生成したURLとHTMLの組。外部URLを予約POSTへ渡せないよう内部公開に留める。 */
internal class LicsXpReservationConfirmationPage internal constructor(
    val html: String,
    internal val url: HttpUrl,
)

/** 通常書誌詳細GETから内部生成したURLとHTMLの組。 */
internal class LicsXpReservationDetailPage internal constructor(
    val html: String,
    internal val url: HttpUrl,
)

private fun HttpUrl.hasSameOriginAs(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

private fun HttpUrl.origin(): String {
    val authority = if ((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) host else "$host:$port"
    return "$scheme://$authority"
}

/**
 * ライブ診断用の監査口。値を持つ HTTP 本文・Cookie・認証情報は引き渡さない。
 * 実装は例外を送出してはならず、通信結果へ影響を与えない。
 */
internal interface LicsXpDiagnosticObserver {
    /** false の通常経路では、診断用の文字列生成・HTML解析を完全に省略する。 */
    val enabled: Boolean get() = true

    fun onRequest(request: LicsXpDiagnosticRequest)

    fun onResponse(method: String, path: String, statusCode: Int, redirectPath: String?)

    fun onPage(path: String, classification: String, formFingerprint: String)

    data object None : LicsXpDiagnosticObserver {
        override val enabled: Boolean = false

        override fun onRequest(request: LicsXpDiagnosticRequest) = Unit

        override fun onResponse(method: String, path: String, statusCode: Int, redirectPath: String?) = Unit

        override fun onPage(path: String, classification: String, formFingerprint: String) = Unit
    }
}

/** 機密値を削除済みのリクエスト記録。 */
internal data class LicsXpDiagnosticRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val form: Map<String, String>,
)

private fun Request.diagnosticFormFields(): LicsXpDiagnosticRequest = LicsXpDiagnosticRequest(
    method = method,
    path = sanitizeDiagnosticPath(url.encodedPath),
    query = url.queryParameterNames.associateWith { name -> diagnosticValue(name, url.queryParameter(name).orEmpty()) },
    form = (body as? FormBody)?.let { formBody ->
        (0 until formBody.size).associate { index ->
            formBody.name(index) to diagnosticValue(formBody.name(index), formBody.value(index))
        }
    }.orEmpty(),
)

private fun diagnosticValue(name: String, value: String): String = when (name) {
    "j_username", "j_password", "hash" -> "[REDACTED]"
    in DIAGNOSTIC_SAFE_CONTROL_FIELDS -> value.take(120)
    else -> "[REDACTED]"
}

private val DIAGNOSTIC_SAFE_CONTROL_FIELDS = setOf(
    "gamenFlag", "gamenid", "returnid", "loginshuflag", "receivenameFocus", "watsptcodFocus",
    "contactFocus", "btnflg", "sortKey", "isAsc", "startIndex", "subSystemFlag", "WebLinkFlag",
)

private val SESSION_PATH_PARAMETER = Regex("(?i);(?:jsessionid|sessionid)(?:=[^/;?]*)?")

/** URL中のqueryとセッションパスパラメータを除去し、監査ログには経路だけを残す。 */
private fun sanitizeDiagnosticPath(value: String): String {
    val withoutQuery = value.substringBefore('?')
    val schemeEnd = withoutQuery.indexOf("://")
    val pathOnly = if (schemeEnd >= 0) {
        withoutQuery.substring(withoutQuery.indexOf('/', schemeEnd + 3).takeIf { it >= 0 } ?: withoutQuery.length)
    } else {
        withoutQuery
    }
    return pathOnly.replace(SESSION_PATH_PARAMETER, "").take(200)
}

private fun classifyDiagnosticPage(document: org.jsoup.nodes.Document): String = when {
    document.selectFirst("input[name=j_password], input[name=j_username], form[action*=j_security_check]") != null -> "login-form"
    document.select("form").any(::isReservationConfirmationForm) -> "reservation-confirmation"
    document.select("form").any(::isSearchForm) -> "search-form"
    document.selectFirst("#stat-login, a[href*=logout], [id*=logout], [class*=logout]") != null -> "authenticated-menu"
    document.body().text().isBlank() -> "empty"
    else -> "other"
}

private fun isReservationConfirmationForm(form: org.jsoup.nodes.Element): Boolean =
    form.selectFirst("input[name=gamenid]")?.attr("value") == "tiles.WYoyConfirm" &&
        form.selectFirst("input[name=tilcod]") != null &&
        form.selectFirst("select[name=receivename]") != null

private fun isSearchForm(form: org.jsoup.nodes.Element): Boolean =
    form.selectFirst("input[name=gamenid][value=tiles.WEsSchCmpd], input[name=condition1Text]") != null

private fun formFingerprint(document: org.jsoup.nodes.Document): String {
    return document.select("form").joinToString(separator = ";") { form ->
        val method = form.attr("method").ifBlank { "GET" }.uppercase()
        val action = sanitizeDiagnosticPath(form.attr("action")).ifBlank { "(script)" }
        // 値は出さず、inputはtypeも含めてfingerprintにする（確認画面のhidden以外の有無をログだけで判別するため）。
        val fields = form.select("input[name], select[name], textarea[name]")
            .map { element ->
                if (element.tagName() == "input") {
                    val type = element.attr("type").ifBlank { "text" }.lowercase()
                    "input[$type]:${element.attr("name")}"
                } else {
                    "${element.tagName()}:${element.attr("name")}"
                }
            }
            .distinct()
            .sorted()
            .joinToString(",")
        "$method:$action:[$fields]"
    }.ifBlank { "no-form" }.take(1_000)
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
