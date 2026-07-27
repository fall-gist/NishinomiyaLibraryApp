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
    // プロパティ client と同名にするとプロパティ初期化子でこの引数が優先され、
    // CookieJarもインターセプタも持たない素のクライアントが使われてしまう。
    httpClient: OkHttpClient,
    private val waitForRequestSlot: suspend (Long) -> Unit,
    private val nowMillis: () -> Long,
    private val rateLimiter: RequestRateLimiter,
    private val diagnosticObserver: LicsXpDiagnosticObserver,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://tosho.nishi.or.jp/licsxp-opac/"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; NishinomiyaLibraryApp) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
        private const val MAX_NETWORK_ATTEMPTS = 2

        // Accept-Language はブラウザでも遷移種別（ナビゲーション / XHR）やリダイレクトの有無で
        // 値が変わらないため付与する。一方 Accept、Upgrade-Insecure-Requests、Sec-Fetch-*、
        // sec-ch-ua* はブラウザ実測に合わせて一度付与を試みたが、これらはブラウザ側でも
        // 遷移種別やリダイレクトの有無で値が変わるものであり、JSON APIの呼び出しにまで一律
        // 付与するのは誤りであり、効果も未確認だったため比較のベースラインから外した。
        private const val BROWSER_ACCEPT_LANGUAGE = "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7"
    }

    private val cookieJar = InMemoryCookieJar()
    private val sourceClient = httpClient
    private val client: OkHttpClient = sourceClient.newBuilder()
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
                .header("User-Agent", USER_AGENT)
            if (original.header("Accept-Language") == null) {
                builder.header("Accept-Language", BROWSER_ACCEPT_LANGUAGE)
            }
            chain.proceed(builder.build())
        }
        .addNetworkInterceptor { chain ->
            if (!diagnosticObserver.enabled) return@addNetworkInterceptor chain.proceed(chain.request())
            val request = chain.request()
            val response = chain.proceed(request)
            safelyObserve {
                diagnosticObserver.onWireRequest(
                    method = request.method,
                    path = sanitizeDiagnosticPath(request.url.encodedPath),
                    protocol = response.protocol.toString(),
                    headers = wireRequestHeaders(request),
                    cookieNames = wireCookieNames(request),
                    setCookieNames = wireSetCookieNames(response),
                )
            }
            response
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
        httpClient = client,
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
        httpClient = client,
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

        /**
         * 確認画面のLBFormを、確認画面自身のRefererで再表示側へ送るPOST。
         * メール選択の再表示（WOpacTifDirectYoyDispAction.do?webrak=1）診断専用に、
         * 直前の確認画面をReferer元として使えるようにするための最小限のオーバーロード。
         */
        suspend fun postReservationConfirmation(
            path: String,
            query: Map<String, String> = emptyMap(),
            form: FormBody,
            refererPage: LicsXpReservationConfirmationPage,
        ): LicsXpReservationConfirmationPage {
            require(refererPage.url.hasSameOriginAs(baseUrl)) { "確認ページのoriginが不正です" }
            val url = endpointUrl(path, query)
            val html = executeInExclusiveSequence(
                Request.Builder()
                    .url(url)
                    .header("Referer", refererPage.url.toString())
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

        /**
         * 予約状況一覧(usrrsv)POSTのURLを、直後の取消POSTのRefererにだけ使う。
         * 一覧取得自体は既存のfetchReservationSnapshotと同じ読み取り専用の位置づけであり、
         * Refererは付与しない（ブラウザ実測でも一覧遷移自体はメニューからの通常POSTである）。
         */
        suspend fun postReservationList(
            path: String,
            query: Map<String, String> = emptyMap(),
            form: FormBody,
        ): LicsXpReservationListPage {
            val url = endpointUrl(path, query)
            val html = executeInExclusiveSequence(
                Request.Builder().url(url).post(form).build(),
                client,
                retryOnIOException = true,
            )
            return LicsXpReservationListPage(html, url)
        }

        /** 予約状況一覧ページ由来のReferer/Originを持つ、予約取消専用の一回限りPOST。 */
        suspend fun postReservationExactlyOnce(
            path: String,
            query: Map<String, String> = emptyMap(),
            form: FormBody,
            listPage: LicsXpReservationListPage,
        ): String {
            require(listPage.url.hasSameOriginAs(baseUrl)) { "一覧ページのoriginが不正です" }
            return executeInExclusiveSequence(
                Request.Builder()
                    .url(endpointUrl(path, query))
                    .header("Referer", listPage.url.toString())
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
        httpClient = sourceClient,
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
            val path = sanitizeDiagnosticPath(request.url.encodedPath)
            diagnosticObserver.onPage(
                path = path,
                classification = classifyDiagnosticPage(document),
                formFingerprint = formFingerprint(document),
            )
            diagnosticObserver.onScreenScript(
                path = path,
                actionTargets = extractScreenScriptActionTargets(body),
                fieldAssignments = extractScreenScriptFieldAssignments(body),
            )
            diagnosticObserver.onSiteMessages(
                path = path,
                messages = extractSiteMessages(document),
            )
            diagnosticObserver.onPageText(
                path = path,
                headings = extractPageHeadings(document),
                notices = extractPageNotices(document),
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

    /**
     * 通信フロー自体は一切変えず、診断ログへ短い注記を残すためだけの内部口。
     * HTML本文や個人情報は渡さないこと。無効時は何もしない。
     */
    internal fun noteDiagnostic(stage: String, detail: String) {
        if (!diagnosticObserver.enabled) return
        safelyObserve { diagnosticObserver.onNote(stage, detail) }
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

/** 予約状況一覧(usrrsv)POSTから内部生成したURLとHTMLの組。取消POSTのRefererにだけ使う。 */
internal class LicsXpReservationListPage internal constructor(
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

    /**
     * 実際にネットワークへ出た時点のリクエスト。値を持つのはヘッダのみで、Cookieは名前だけ。
     * [observeRequest] によるアプリ組立て時点の記録とは異なり、インターセプタが足したヘッダや
     * CookieJarが足すCookieも含めて記録できる。
     */
    fun onWireRequest(
        method: String,
        path: String,
        protocol: String,
        headers: List<Pair<String, String>>,
        cookieNames: List<String>,
        setCookieNames: List<String>,
    )

    fun onResponse(method: String, path: String, statusCode: Int, redirectPath: String?)

    fun onPage(path: String, classification: String, formFingerprint: String)

    /** 画面のインラインJSが送信直前に行うaction設定とフィールド代入。値はページ由来の定数だけを残す。 */
    fun onScreenScript(path: String, actionTargets: List<String>, fieldAssignments: List<String>)

    /** サイトが表示するメッセージ（alert用のdiv#messages）。数字列はマスクする。 */
    fun onSiteMessages(path: String, messages: List<String>)

    /** 画面の見出しと、エラー表示に使われていそうな要素のテキスト。値は数字をマスクして抜粋する。 */
    fun onPageText(path: String, headings: List<String>, notices: List<String>)

    /** 通信フロー内の失敗理由などの短い注記。HTML本文や個人情報を含めないこと。 */
    fun onNote(stage: String, detail: String)

    data object None : LicsXpDiagnosticObserver {
        override val enabled: Boolean = false

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

        override fun onNote(stage: String, detail: String) = Unit
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
    "bmtime_hide", "returnValue", "contactdirectweb", "contact", "receivename", "tilcod",
    "islogin", "execflg", "gamentilcod", "preNextTilcod", "prevORnext", "kensaku", "kensakuFlg",
    "refCode", "diccod", "syurui", "syuruivalue", "syuruiName", "amazonIsbn", "amazonDispFlag",
    "storeId", "booklist", "otherbook",
)

/**
 * ネットワークへ実際に出たリクエストのヘッダを、値を持つ形で列挙する。
 * `Cookie` はここに含めない（名前だけを [wireCookieNames] へ渡す）。
 * `Referer` は経路だけに、`Authorization` は保険として `[REDACTED]` にする。
 */
private fun wireRequestHeaders(request: Request): List<Pair<String, String>> =
    request.headers.names().filterNot { it.equals("Cookie", ignoreCase = true) }.map { name ->
        val value = request.header(name).orEmpty()
        val sanitizedValue = when {
            name.equals("Referer", ignoreCase = true) -> sanitizeDiagnosticPath(value)
            name.equals("Authorization", ignoreCase = true) -> "[REDACTED]"
            else -> value
        }
        name to sanitizedValue
    }

/** `Cookie` ヘッダを `;` で分割し、`=` の左側（Cookie名）だけを返す。値は返さない。 */
private fun wireCookieNames(request: Request): List<String> =
    request.header("Cookie").orEmpty()
        .split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.substringBefore('=').trim() }

/**
 * レスポンスの `Set-Cookie` を、Cookie名と `;` 以降の属性名（`Path` / `HttpOnly` 等）だけに
 * 分解して `name;attr1;attr2` の形にする。Cookie値・属性値は出さない。
 */
private fun wireSetCookieNames(response: Response): List<String> =
    response.headers("Set-Cookie").map { setCookie ->
        val parts = setCookie.split(';').map { it.trim() }
        val name = parts.firstOrNull().orEmpty().substringBefore('=').trim()
        val attributeNames = parts.drop(1).map { it.substringBefore('=').trim() }
        (listOf(name) + attributeNames).joinToString(";")
    }

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
        "$method:$action:[$fields]:${formHashState(form)}"
    }.ifBlank { "no-form" }.take(1_000)
}

/** hidden hash の有無・値の空欄有無だけを返す。値そのものも長さも出さない。 */
private fun formHashState(form: org.jsoup.nodes.Element): String {
    val hashInput = form.selectFirst("input[type=hidden][name=hash]") ?: return "hash=none"
    return if (hashInput.attr("value").isEmpty()) "hash=empty" else "hash=set"
}

private val SCREEN_SCRIPT_ACTION_REGEX = Regex("""document\.\w+\.action\s*=\s*([^;]+);""")
/** window.open(...) / showModalDialog(...) / window.showModalDialog(...) の第1引数を捉える。 */
private val POPUP_CALL_REGEX = Regex("""(?:window\.)?(?:open|showModalDialog)\s*\(\s*([^,)]+)""")
private val SCREEN_SCRIPT_FIELD_ASSIGNMENT_REGEX =
    Regex("""document\.\w+\.(\w+)\.(value|disabled)\s*=\s*([^;]+);""")
private val QUOTED_LITERAL_REGEX = Regex("""^(['"])(.*)\1$""")
private val NUMERIC_LITERAL_REGEX = Regex("""^-?\d+(\.\d+)?$""")
private val LONG_DIGIT_RUN_REGEX = Regex("""\d{6,}""")
private val FUNCTION_DEF_REGEX = Regex("""function\s+(\w+)\s*\([^)]*\)\s*\{""")
private const val TOP_LEVEL_LABEL = "(top-level)"
private const val SCREEN_SCRIPT_ENTRY_LIMIT = 200
private const val SCREEN_SCRIPT_ENTRY_MAX_CHARS = 160
private const val FUNCTION_BODY_LIMIT = 8
private const val FUNCTION_BODY_MAX_CHARS = 1200
private const val TOP_LEVEL_CONFIRM_ENTRY_LIMIT = 3
private const val TOP_LEVEL_CONFIRM_ENTRY_MAX_CHARS = 300
/** 予約取消の確認ダイアログ（`confirm(` / `lbConfirm(`）が、関数の外(トップレベル)で呼ばれている場合に捉える。 */
private val TOP_LEVEL_CONFIRM_CALL_REGEX = Regex("""\b(?:confirm|lbConfirm)\s*\(""")

/**
 * 画面インラインJSの `document.<フォーム名>.action = ...` 代入から、
 * リテラル部分と連結識別子（`+<identifier>`）だけを抜き出す。式全体は残さない。
 * 先頭には、代入がどの関数本体で行われたかを示す `関数名:`（トップレベルなら `(top-level):`）を付ける。
 * `window.open(...)` / `showModalDialog(...)` / `window.showModalDialog(...)` によるポップアップ呼び出しの
 * 第1引数も同様にリテラル部分・連結識別子へ分解し、ポップアップ由来と分かるよう `関数名:popup=<リテラル>` の形式で加える。
 */
internal fun extractScreenScriptActionTargets(html: String): List<String> {
    val targets = mutableListOf<String>()
    forEachScriptFunctionRegion(html) { label, body ->
        for (match in SCREEN_SCRIPT_ACTION_REGEX.findAll(body)) {
            val expression = match.groupValues[1]
            for (part in splitConcatenationExpression(expression)) {
                targets += "$label:$part"
            }
        }
        for (match in POPUP_CALL_REGEX.findAll(body)) {
            val expression = match.groupValues[1]
            for (part in splitConcatenationExpression(expression)) {
                targets += "$label:popup=$part"
            }
        }
    }
    return targets.distinct().take(SCREEN_SCRIPT_ENTRY_LIMIT).map { it.take(SCREEN_SCRIPT_ENTRY_MAX_CHARS) }
}

/**
 * 画面インラインJSの `document.<フォーム名>.<フィールド名>.value/.disabled = ...` 代入を抜き出す。
 * 右辺はリテラル（文字列・数値）のときだけ値を残し、それ以外は `(expr)` に置き換える。
 * 先頭には代入元の関数名（トップレベルなら `(top-level)`）を付ける。
 * さらに、次のいずれかを満たす関数については、本体ソースを `関数名:body=<本体>` の形で追加する
 * （数値マスク・1200文字切り詰め済み、最大8関数まで）。
 * 1. action設定の文字列リテラルに `Exec` を含む
 * 2. フォームフィールドへの代入（`.value=` または `.disabled=`）を1つ以上持つ
 * 3. 本体に `confirm(` または `lbConfirm(` を含む（予約取消の確認ダイアログを組み立てる関数を
 *    診断で見えるようにするための条件。取消の確認画面が実測(2026-07-27)で判明したため追加した）
 * 出力順は、条件1に該当する関数を先に、次に条件2のみの関数を、最後に条件3のみの関数を、
 * いずれもソース中の出現順とする（条件1・2に該当する関数は条件3を満たしていてもそちらに含める）。
 * 加えて、関数の外(トップレベル)にある `confirm(` / `lbConfirm(` を含む文を、
 * `(top-level):confirm=<文>` の形で最大3件・各300文字まで追加する
 * （ページ読み込み時に確認ダイアログを出す仕組みがトップレベルにある可能性があるため）。
 */
internal fun extractScreenScriptFieldAssignments(html: String): List<String> {
    val assignments = mutableListOf<String>()
    val execFunctionBodies = mutableListOf<Pair<String, String>>()
    val fieldAssignmentFunctionBodies = mutableListOf<Pair<String, String>>()
    val confirmFunctionBodies = mutableListOf<Pair<String, String>>()
    val topLevelConfirmStatements = mutableListOf<String>()
    forEachScriptFunctionRegion(html) { label, body ->
        var hasFieldAssignment = false
        for (match in SCREEN_SCRIPT_FIELD_ASSIGNMENT_REGEX.findAll(body)) {
            hasFieldAssignment = true
            val fieldName = match.groupValues[1]
            val propertyName = match.groupValues[2]
            val rhs = match.groupValues[3].trim()
            val value = literalValueOrExpr(rhs)
            assignments += "$label:$fieldName.$propertyName=$value"
        }
        if (label != TOP_LEVEL_LABEL) {
            when {
                containsExecActionLiteral(body) -> execFunctionBodies += label to body
                hasFieldAssignment -> fieldAssignmentFunctionBodies += label to body
                containsConfirmCall(body) -> confirmFunctionBodies += label to body
            }
        } else {
            topLevelConfirmStatements += extractTopLevelConfirmStatements(body)
        }
    }
    val normalizedAssignments = assignments.distinct().map { it.take(SCREEN_SCRIPT_ENTRY_MAX_CHARS) }
    val bodyEntries = (
        execFunctionBodies.distinctBy { it.first } +
            fieldAssignmentFunctionBodies.distinctBy { it.first } +
            confirmFunctionBodies.distinctBy { it.first }
        )
        .distinctBy { it.first }
        .take(FUNCTION_BODY_LIMIT)
        .map { (name, body) -> "$name:body=${maskAndTruncateFunctionBody(body)}" }
    val topLevelConfirmEntries = topLevelConfirmStatements.distinct()
        .take(TOP_LEVEL_CONFIRM_ENTRY_LIMIT)
        .map { statement -> "$TOP_LEVEL_LABEL:confirm=${collapseAndMaskDigits(statement).take(TOP_LEVEL_CONFIRM_ENTRY_MAX_CHARS)}" }
    return (normalizedAssignments + bodyEntries + topLevelConfirmEntries).distinct().take(SCREEN_SCRIPT_ENTRY_LIMIT)
}

/** action設定の連結式のうち、文字列リテラル部分（識別子ではない部分）に `Exec` を含むかどうか。 */
private fun containsExecActionLiteral(body: String): Boolean =
    SCREEN_SCRIPT_ACTION_REGEX.findAll(body).any { match ->
        splitConcatenationExpression(match.groupValues[1]).any { part -> !part.startsWith("+") && part.contains("Exec") }
    }

/** 関数本体に確認ダイアログ呼び出し(`confirm(` / `lbConfirm(`)を含むかどうか。大小文字は区別しない実測基準。 */
private fun containsConfirmCall(body: String): Boolean =
    body.contains("confirm(") || body.contains("lbConfirm(")

/**
 * トップレベル(関数の外)にある `confirm(` / `lbConfirm(` 呼び出しを含む文を、直前・直後のセミコロンで
 * 区切って取り出す。`if (confirm(...)) { ... }` のような複合文は最初のセミコロンまでしか捉えないが、
 * 診断用の抜粋としては十分であり、後段でも300文字までに切り詰める。
 */
private fun extractTopLevelConfirmStatements(topLevelBody: String): List<String> {
    val statements = mutableListOf<String>()
    for (match in TOP_LEVEL_CONFIRM_CALL_REGEX.findAll(topLevelBody)) {
        val start = topLevelBody.lastIndexOf(';', match.range.first).let { if (it < 0) 0 else it + 1 }
        val semicolonIndex = topLevelBody.indexOf(';', match.range.first)
        val end = if (semicolonIndex < 0) topLevelBody.length else semicolonIndex + 1
        val statement = topLevelBody.substring(start, end).trim()
        if (statement.isNotEmpty()) statements += statement
    }
    return statements
}

/** 連続空白を1個へ潰し、6桁以上の数字を `[NUM]` に伏せる。本体・トップレベル抜粋の両方で使う。 */
private fun collapseAndMaskDigits(text: String): String {
    val collapsed = text.replace(Regex("""\s+"""), " ").trim()
    return LONG_DIGIT_RUN_REGEX.replace(collapsed, "[NUM]")
}

/** 関数本体ソースを、連続空白を1個へ潰し・6桁以上の数字を伏せ・1200文字までに切って返す。 */
private fun maskAndTruncateFunctionBody(body: String): String = collapseAndMaskDigits(body).take(FUNCTION_BODY_MAX_CHARS)

/**
 * HTML（の中の `<script>` ブロック相当部分）を、関数本体（名前付き）とトップレベル部分に分けて順に処理する。
 * 抽出対象の正規表現自体が `document.xxx.action=...` 等のJS構文にしか反応しないため、
 * HTMLタグを含む文字列全体に対して適用しても実害はない。
 */
private fun forEachScriptFunctionRegion(html: String, action: (label: String, body: String) -> Unit) {
    for ((name, body) in splitScriptByFunction(html)) {
        action(name ?: TOP_LEVEL_LABEL, body)
    }
}

/**
 * スクリプト本文から `function 名(...) { ... }` を波括弧の対応で切り出す。
 * ネストした関数は外側の本体の一部として扱う（個別の名前付きエントリにはしない）。
 * 関数の外側に残ったソースは、名前 `null`（トップレベル扱い）としてまとめて返す。
 */
private fun splitScriptByFunction(script: String): List<Pair<String?, String>> {
    val regions = mutableListOf<Pair<String?, String>>()
    val topLevel = StringBuilder()
    var cursor = 0
    for (match in FUNCTION_DEF_REGEX.findAll(script)) {
        if (match.range.first < cursor) continue // 既に外側関数の本体として消費済み
        topLevel.append(script, cursor, match.range.first)
        val name = match.groupValues[1]
        val openBraceIndex = match.range.last
        val closeBraceIndex = findMatchingBrace(script, openBraceIndex)
        if (closeBraceIndex == -1) {
            cursor = match.range.first
            break
        }
        regions += name to script.substring(openBraceIndex + 1, closeBraceIndex)
        cursor = closeBraceIndex + 1
    }
    topLevel.append(script, cursor, script.length)
    regions += null to topLevel.toString()
    return regions
}

/** 開き波括弧の位置を受け取り、対応する閉じ波括弧の位置を返す。文字列リテラル内の波括弧は簡易的に無視する。 */
private fun findMatchingBrace(script: String, openBraceIndex: Int): Int {
    var depth = 0
    var index = openBraceIndex
    var inString: Char? = null
    while (index < script.length) {
        val current = script[index]
        if (inString != null) {
            if (current == '\\') {
                index += 2
                continue
            }
            if (current == inString) inString = null
        } else {
            when (current) {
                '\'', '"' -> inString = current
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        index++
    }
    return -1
}

private const val SITE_MESSAGE_ENTRY_LIMIT = 20
private const val SITE_MESSAGE_ENTRY_MAX_CHARS = 300

/**
 * `alert(...)` / `lbAlert(...)` / `lbConfirm(...)` / `confirm(...)` / `lbWarning(...)`
 * （関数名の大小文字は区別しない）の第1引数のうち、文字列リテラルのものだけを捉える。
 * 引数が変数・関数呼び出し・連結式などリテラルでない場合は、開き括弧の直後が引用符にならないため
 * そもそもマッチしない（＝抽出しない）。
 */
private val DIALOG_CALL_LITERAL_REGEX = Regex(
    """(?i)\b(?:lbAlert|lbConfirm|lbWarning|alert|confirm)\s*\(\s*(['"])((?:\\.|(?!\1).)*)\1""",
)

/**
 * `messageText` / `message` / `msg` という名前の変数への文字列リテラル代入を捉える。
 * `var messageText = "...";` の形（`var` は省略されている場合も許容する）。
 * `message.replace("{0}", usrcod)` のように後から値が埋め込まれる式であっても、
 * ここで捉えるのは代入時点のリテラル（置換前のテンプレート文字列）そのものであり、
 * 埋め込まれる利用者側の値（usrcod 等）はソースコード上に存在しないため抽出されない。
 */
private val DIALOG_MESSAGE_VARIABLE_LITERAL_REGEX = Regex(
    """(?i)(?:\bvar\s+)?\b(?:messageText|message|msg)\s*=\s*(['"])((?:\\.|(?!\1).)*)\1""",
)

/**
 * サイトが利用者へ見せるメッセージ文言を、次の経路から抜き出す:
 * 1. `div#messages` 配下の `li` テキスト（従来どおり）
 * 2. `li` が無い場合に備え、`#messages` 直下のテキストそのもの
 * 3. `<script>` 内のダイアログ関数呼び出し（[DIALOG_CALL_LITERAL_REGEX]）の第1引数リテラル
 * 4. `<script>` 内の `messageText`/`message`/`msg` 変数へのリテラル代入（[DIALOG_MESSAGE_VARIABLE_LITERAL_REGEX]）
 *
 * レスポンス本文そのものは記録せず、ここで抽出した文言だけを返す。
 * 6桁以上連続する数字は `[NUM]` に伏せ、空文字・空白のみを除外し重複除去のうえ
 * 最大20件・各300文字までに切る。
 */
internal fun extractSiteMessages(document: org.jsoup.nodes.Document): List<String> {
    val raw = mutableListOf<String>()

    val messagesContainer = document.selectFirst("#messages")
    if (messagesContainer != null) {
        val items = messagesContainer.select("li")
        if (items.isNotEmpty()) {
            items.forEach { raw += it.text().trim() }
        } else {
            raw += messagesContainer.text().trim()
        }
    }

    document.select("script").forEach { script ->
        val scriptBody = script.data()
        DIALOG_CALL_LITERAL_REGEX.findAll(scriptBody).forEach { match -> raw += match.groupValues[2] }
        DIALOG_MESSAGE_VARIABLE_LITERAL_REGEX.findAll(scriptBody).forEach { match -> raw += match.groupValues[2] }
    }

    return raw
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { LONG_DIGIT_RUN_REGEX.replace(it, "[NUM]") }
        .distinct()
        .take(SITE_MESSAGE_ENTRY_LIMIT)
        .map { it.take(SITE_MESSAGE_ENTRY_MAX_CHARS) }
}

private const val PAGE_TEXT_ENTRY_LIMIT_HEADINGS = 5
private const val PAGE_TEXT_ENTRY_MAX_CHARS_HEADINGS = 100
private const val PAGE_TEXT_ENTRY_LIMIT_NOTICES = 10
private const val PAGE_TEXT_ENTRY_MAX_CHARS_NOTICES = 200
private val NOTICE_KEYWORD_REGEX = Regex("error|msg|message|alert|caution|attention", RegexOption.IGNORE_CASE)

/** h1/h2のテキストを、前後の空白を詰め・空文字を除外して抜き出す。数字は6桁以上を伏せる。 */
internal fun extractPageHeadings(document: org.jsoup.nodes.Document): List<String> =
    document.select("h1, h2")
        .map { it.text().trim() }
        .filter { it.isNotEmpty() }
        .take(PAGE_TEXT_ENTRY_LIMIT_HEADINGS)
        .map { LONG_DIGIT_RUN_REGEX.replace(it, "[NUM]").take(PAGE_TEXT_ENTRY_MAX_CHARS_HEADINGS) }

/**
 * エラー表示に使われていそうな要素のテキストを抜き出す。対象は次のいずれか:
 * - idまたはclassに error/msg/message/alert/caution/attention のいずれかを含む要素(大小文字を区別しない)
 * - font[color=red]、.red、strong のうち、テキストが1〜200文字のもの
 * いずれも、テキストが空・200文字超のものは除外し、重複除去のうえ最大10件・数字は6桁以上を伏せて200文字までに切る。
 */
internal fun extractPageNotices(document: org.jsoup.nodes.Document): List<String> {
    val keywordMatches = document.select("*").filter { element ->
        NOTICE_KEYWORD_REGEX.containsMatchIn(element.id()) || NOTICE_KEYWORD_REGEX.containsMatchIn(element.className())
    }
    val markupMatches = document.select("font[color=red], .red, strong")
    return (keywordMatches + markupMatches)
        .map { it.text().trim() }
        .filter { it.isNotEmpty() && it.length <= PAGE_TEXT_ENTRY_MAX_CHARS_NOTICES }
        .distinct()
        .take(PAGE_TEXT_ENTRY_LIMIT_NOTICES)
        .map { LONG_DIGIT_RUN_REGEX.replace(it, "[NUM]").take(PAGE_TEXT_ENTRY_MAX_CHARS_NOTICES) }
}

/** `"literal"+identifier+"literal"` のような連結式を、リテラルは値そのまま・識別子は `+<identifier>` にして分解する。 */
private fun splitConcatenationExpression(expression: String): List<String> =
    expression.split('+').map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
        val quoted = QUOTED_LITERAL_REGEX.find(part)
        if (quoted != null) quoted.groupValues[2] else "+$part"
    }

private fun literalValueOrExpr(rhs: String): String {
    val quoted = QUOTED_LITERAL_REGEX.find(rhs)
    if (quoted != null) return quoted.groupValues[2]
    if (NUMERIC_LITERAL_REGEX.matches(rhs)) return rhs
    return "(expr)"
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
