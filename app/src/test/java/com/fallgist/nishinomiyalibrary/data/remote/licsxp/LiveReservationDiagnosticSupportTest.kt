package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveReservationDiagnosticSupportTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `明示フラグが不足すれば gateway を生成せず通信できない`() = runBlocking {
        var gatewayFactoryCalls = 0
        val result = runLiveReservationDiagnosticIfAuthorized(
            environment = mapOf(
                LiveReservationDiagnosticConfig.CARD_NUMBER to "1234",
                LiveReservationDiagnosticConfig.PASSWORD to "secret",
                LiveReservationDiagnosticConfig.TILCOD to "1000000000001",
                LiveReservationDiagnosticConfig.PICKUP_LIBRARY to "106",
            ),
            gatewayFactory = {
                gatewayFactoryCalls += 1
                error("認可されていない診断で gateway を生成してはいけない")
            },
            logger = LiveReservationDiagnosticLogger(),
        )

        assertNull(result)
        assertEquals(0, gatewayFactoryCalls)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `監査記録は認証値と hash を伏せリダイレクト連鎖を記録する`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/next;jsessionid=redirect-secret?private=value"))
        server.enqueue(MockResponse().setBody("<form action='/final;jsessionid=html-secret?private=value'><input name=\"j_password\" /></form>"))
        val requests = mutableListOf<LicsXpDiagnosticRequest>()
        val responses = mutableListOf<String>()
        val pages = mutableListOf<String>()
        val observer = object : LicsXpDiagnosticObserver {
            override fun onRequest(request: LicsXpDiagnosticRequest) {
                requests += request
            }

            override fun onResponse(method: String, path: String, statusCode: Int, redirectPath: String?) {
                responses += "$method $path $statusCode ${redirectPath ?: "-"}"
            }

            override fun onPage(path: String, classification: String, formFingerprint: String) {
                pages += "$path $classification $formFingerprint"
            }
        }
        val session = LicsXpSession(server.url("/"), OkHttpClient(), observer, waitForRequestSlot = {})
        session.post(
            path = "j_security_check",
            form = FormBody.Builder()
                .add("j_username", "00000000000000001234")
                .add("j_password", "secret-password")
                .add("hash", "site-secret")
                .add("gamenid", "tiles.WYoyConfirm")
                .add("tilcod", "1000000000001")
                .add("receivename", "106")
                .add("contact", "4")
                .build(),
        )

        val record = requests.single()
        assertEquals("[REDACTED]", record.form["j_username"])
        assertEquals("[REDACTED]", record.form["j_password"])
        assertEquals("[REDACTED]", record.form["hash"])
        assertEquals("[REDACTED]", record.form["tilcod"])
        assertEquals("[REDACTED]", record.form["receivename"])
        assertEquals("[REDACTED]", record.form["contact"])
        assertEquals("tiles.WYoyConfirm", record.form["gamenid"])
        assertFalse(record.toString().contains("secret-password"))
        assertFalse(record.toString().contains("00000000000000001234"))
        assertFalse(record.toString().contains("1000000000001"))
        assertTrue(responses.contains("POST /j_security_check 302 /next"))
        assertTrue(responses.contains("GET /next 200 -"))
        assertTrue(pages.single().startsWith("/next login-form"))
        assertFalse((responses + pages).joinToString().contains("redirect-secret"))
        assertFalse((responses + pages).joinToString().contains("html-secret"))
    }

    @Test
    fun `無効な監査先では診断用の観測処理を呼ばない`() = runBlocking {
        server.enqueue(MockResponse().setBody("<form><input name='j_password'></form>"))
        val disabledObserver = object : LicsXpDiagnosticObserver {
            override val enabled: Boolean = false

            override fun onRequest(request: LicsXpDiagnosticRequest): Nothing = error("通常経路でrequest監査を呼んではいけない")

            override fun onResponse(method: String, path: String, statusCode: Int, redirectPath: String?): Nothing =
                error("通常経路でresponse監査を呼んではいけない")

            override fun onPage(path: String, classification: String, formFingerprint: String): Nothing =
                error("通常経路でHTML監査を呼んではいけない")
        }

        val session = LicsXpSession(server.url("/"), OkHttpClient(), disabledObserver, waitForRequestSlot = {})
        assertTrue(session.get("normal").contains("j_password"))
    }

    @Test
    fun `確認画面分類は実フォーム構造を使い詳細検索フォームを区別する`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                <script>const common = 'tiles.WYoyConfirm WOpacTifDirectYoyExecAction';</script>
                <form><input name="gamenid" value="tiles.WEsSchCmpd" /><input name="condition1Text" /></form>
                """.trimIndent(),
            ),
        )
        val classifications = mutableListOf<String>()
        val observer = object : LicsXpDiagnosticObserver {
            override fun onRequest(request: LicsXpDiagnosticRequest) = Unit

            override fun onResponse(method: String, path: String, statusCode: Int, redirectPath: String?) = Unit

            override fun onPage(path: String, classification: String, formFingerprint: String) {
                classifications += classification
            }
        }
        val session = LicsXpSession(server.url("/"), OkHttpClient(), observer, waitForRequestSlot = {})

        session.get("page")

        assertEquals(listOf("search-form"), classifications)
    }

    @Test
    fun `確認画面分類は必要な同一フォーム項目が揃う場合だけ成立する`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                <form>
                  <input name="gamenid" value="tiles.WYoyConfirm" />
                  <input name="tilcod" value="1000000000001" />
                  <select name="receivename"><option value="106">館</option></select>
                </form>
                """.trimIndent(),
            ),
        )
        val classifications = mutableListOf<String>()
        val observer = object : LicsXpDiagnosticObserver {
            override fun onRequest(request: LicsXpDiagnosticRequest) = Unit

            override fun onResponse(method: String, path: String, statusCode: Int, redirectPath: String?) = Unit

            override fun onPage(path: String, classification: String, formFingerprint: String) {
                classifications += classification
            }
        }
        val session = LicsXpSession(server.url("/"), OkHttpClient(), observer, waitForRequestSlot = {})

        session.get("page")

        assertEquals(listOf("reservation-confirmation"), classifications)
    }

    @Test
    fun `予約前後照合は確認取得と確定送信を一度ずつ行う`() = runBlocking {
        val gateway = FakeGateway()
        val config = authorizedConfig()
        val report = runLiveReservationDiagnostic(gateway, config, LiveReservationDiagnosticLogger())

        assertFalse(report.wasReservedBefore)
        assertTrue(report.isReservedAfter)
        assertEquals(1, gateway.session.directReserveCalls)
        // 事前照合は使い捨ての別セッションで行い、確定は開き直したセッションで連続実行する。
        assertEquals(2, gateway.openCalls)
        assertEquals(listOf("open", "fetch-before", "close", "open", "direct", "fetch-after", "close"), gateway.session.calls)
    }

    @Test
    fun `dry-runフラグ単独でも確定フラグなしでconfigが成立しdryRunがtrueになる`() {
        val config = requireNotNull(
            LiveReservationDiagnosticConfig.from(
                mapOf(
                    LiveReservationDiagnosticConfig.ENABLE_FLAG to "YES_I_UNDERSTAND",
                    LiveReservationDiagnosticConfig.DRY_RUN_FLAG to "INSPECT_ONLY",
                    LiveReservationDiagnosticConfig.CARD_NUMBER to "1234",
                    LiveReservationDiagnosticConfig.PASSWORD to "secret",
                    LiveReservationDiagnosticConfig.TILCOD to "1000000000001",
                    LiveReservationDiagnosticConfig.PICKUP_LIBRARY to "106",
                ),
            ),
        )
        assertTrue(config.dryRun)
    }

    @Test
    fun `dry-run指定は確定フラグが同時にあってもdryRunを優先する`() {
        val config = requireNotNull(
            LiveReservationDiagnosticConfig.from(
                mapOf(
                    LiveReservationDiagnosticConfig.ENABLE_FLAG to "YES_I_UNDERSTAND",
                    LiveReservationDiagnosticConfig.DRY_RUN_FLAG to "INSPECT_ONLY",
                    LiveReservationDiagnosticConfig.CONFIRM_FLAG to "RESERVE_ON_PRODUCTION",
                    LiveReservationDiagnosticConfig.CARD_NUMBER to "1234",
                    LiveReservationDiagnosticConfig.PASSWORD to "secret",
                    LiveReservationDiagnosticConfig.TILCOD to "1000000000001",
                    LiveReservationDiagnosticConfig.PICKUP_LIBRARY to "106",
                ),
            ),
        )
        assertTrue(config.dryRun)
    }

    @Test
    fun `dry-run診断はdirectReserveを一度も呼ばずopen inspect closeの順で呼ぶ`() = runBlocking {
        val gateway = FakeGateway()
        val config = requireNotNull(
            LiveReservationDiagnosticConfig.from(
                mapOf(
                    LiveReservationDiagnosticConfig.ENABLE_FLAG to "YES_I_UNDERSTAND",
                    LiveReservationDiagnosticConfig.DRY_RUN_FLAG to "INSPECT_ONLY",
                    LiveReservationDiagnosticConfig.CARD_NUMBER to "1234",
                    LiveReservationDiagnosticConfig.PASSWORD to "secret",
                    LiveReservationDiagnosticConfig.TILCOD to "1000000000001",
                    LiveReservationDiagnosticConfig.PICKUP_LIBRARY to "106",
                ),
            ),
        )

        val inspection = runLiveReservationInspection(gateway, config, LiveReservationDiagnosticLogger())

        assertEquals(0, gateway.session.directReserveCalls)
        assertEquals(1, gateway.session.inspectCalls)
        assertEquals(listOf("open", "inspect", "close"), gateway.session.calls)
        assertTrue(inspection is ConfirmationInspection.Parsed)
    }

    private fun authorizedConfig(): LiveReservationDiagnosticConfig = requireNotNull(
        LiveReservationDiagnosticConfig.from(
            mapOf(
                LiveReservationDiagnosticConfig.ENABLE_FLAG to "YES_I_UNDERSTAND",
                LiveReservationDiagnosticConfig.CONFIRM_FLAG to "RESERVE_ON_PRODUCTION",
                LiveReservationDiagnosticConfig.CARD_NUMBER to "1234",
                LiveReservationDiagnosticConfig.PASSWORD to "secret",
                LiveReservationDiagnosticConfig.TILCOD to "1000000000001",
                LiveReservationDiagnosticConfig.PICKUP_LIBRARY to "106",
            ),
        ),
    )

    private class FakeGateway : ReservationGateway {
        val session = FakeSession()
        var openCalls = 0

        override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession {
            openCalls += 1
            session.calls += "open"
            return session
        }
    }

    private class FakeSession : ReservationSession, ReservationConfirmationInspector {
        val calls = mutableListOf<String>()
        var directReserveCalls = 0
        var inspectCalls = 0
        private var reserved = false

        override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt {
            calls += "direct"
            directReserveCalls += 1
            reserved = true
            return DirectReservationAttempt.IndeterminateAfterPost
        }

        override suspend fun inspectDirectReservationConfirmation(
            tilcod: String,
            pickupLibraryCode: String,
        ): ConfirmationInspection {
            calls += "inspect"
            inspectCalls += 1
            return ConfirmationInspection.Parsed(
                fieldNames = listOf("gamenid", "tilcod", "receivename", "contact", "contactdirectweb"),
                pickupLibraryCodes = setOf(pickupLibraryCode),
                requestedPickupAvailable = true,
            )
        }

        override suspend fun fetchReservations(): List<Reservation> {
            calls += if (reserved) "fetch-after" else "fetch-before"
            return if (reserved) listOf(reservation()) else emptyList()
        }

        override fun close() {
            calls += "close"
        }

        private fun reservation() = Reservation(
            memberId = 0,
            title = "",
            materialType = "",
            pickupLibrary = "",
            reservedDate = LocalDate.of(2030, 1, 1),
            queuePosition = null,
            state = ReservationState.WAITING,
            holdExpiryDate = null,
            tilcod = "1000000000001",
        )
    }
}
