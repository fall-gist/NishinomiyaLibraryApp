package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/** 非表示診断では本文由来イベントを絶対に出力しない。 */
private fun hideDiagnosticObserver(logger: LiveReservationDiagnosticLogger): LicsXpDiagnosticObserver =
    object : LicsXpDiagnosticObserver {
        override fun onRequest(request: LicsXpDiagnosticRequest) =
            logger.stage("hide-request", "${request.method} ${request.path} queryNames=${request.query.keys} fieldNames=${request.form.keys}")
        override fun onWireRequest(method: String, path: String, protocol: String, headers: List<Pair<String, String>>, cookieNames: List<String>, setCookieNames: List<String>) =
            logger.stage("hide-wire", "$method $path proto=$protocol headerNames=${headers.map { it.first }} cookiePresent=${cookieNames.isNotEmpty()} setCookiePresent=${setCookieNames.isNotEmpty()}")
        override fun onResponse(method: String, path: String, statusCode: Int, redirectPath: String?) =
            logger.stage("hide-response", "$method $path status=$statusCode redirect=${redirectPath ?: "-"}")
        override fun onPage(path: String, classification: String, formFingerprint: String) =
            logger.stage("hide-page", "$path classification=$classification forms=$formFingerprint")
        override fun onScreenScript(path: String, actionTargets: List<String>, fieldAssignments: List<String>) = Unit
        override fun onSiteMessages(path: String, messages: List<String>) = Unit
        override fun onPageText(path: String, headings: List<String>, notices: List<String>) = Unit
        override fun onNote(stage: String, detail: String) = logger.stage("hide-note", "$stage: $detail")
    }

/** 通常testから隔離する、本番1件ライブ非表示診断の明示承認設定。 */
internal data class LiveReservationHideDiagnosticConfig(
    val cardNumber: String,
    val password: String,
    val tilcod: String,
) {
    companion object {
        const val ENABLE_FLAG = "LICSXP_LIVE_RESERVATION"
        const val HIDE_CONFIRM_FLAG = "LICSXP_LIVE_HIDE_CONFIRM"
        const val CARD_NUMBER = "LICSXP_CARD_NUMBER"
        const val PASSWORD = "LICSXP_PASSWORD"
        const val HIDE_TILCOD = "LICSXP_HIDE_TILCOD"

        fun from(environment: Map<String, String>): LiveReservationHideDiagnosticConfig? {
            if (environment[ENABLE_FLAG] != "YES_I_UNDERSTAND") return null
            if (environment[HIDE_CONFIRM_FLAG] != "HIDE_ON_PRODUCTION") return null
            val cardNumber = environment[CARD_NUMBER].orEmpty()
            val password = environment[PASSWORD].orEmpty()
            val tilcod = environment[HIDE_TILCOD].orEmpty()
            if (cardNumber.isBlank() || password.isBlank() || tilcod.isBlank()) return null
            return LiveReservationHideDiagnosticConfig(cardNumber, password, tilcod)
        }
    }
}

internal suspend fun runLiveReservationHideDiagnosticIfAuthorized(
    environment: Map<String, String>,
    gatewayFactory: () -> ReservationGateway,
    logger: LiveReservationDiagnosticLogger,
): ReservationHideDiagnosticResult? {
    val config = LiveReservationHideDiagnosticConfig.from(environment) ?: return null
    val session = gatewayFactory().openAuthenticatedSession(config.cardNumber, config.password)
    try {
        val capability = session as? ReservationHideDiagnosticCapability
            ?: return ReservationHideDiagnosticResult.FORM_CHANGED
        val result = capability.hideCancelledReservationForDiagnostic(config.tilcod)
        logger.stage("hide-result", "result=$result")
        return result
    } finally {
        session.close()
    }
}

/** `liveReservationHideDiagnostic` タスクだけが実行するライブテスト。 */
class LiveReservationHideDiagnosticTest {
    @Test
    fun executeOnlyWhenAllExplicitOptInsArePresent() = runBlocking {
        if (LiveReservationHideDiagnosticConfig.from(System.getenv()) == null) return@runBlocking
        val logger = LiveReservationDiagnosticLogger()
        val result = requireNotNull(
            runLiveReservationHideDiagnosticIfAuthorized(
                environment = System.getenv(),
                gatewayFactory = {
                    val rootSession = LicsXpSession(
                        baseUrl = LicsXpSession.DEFAULT_BASE_URL.toHttpUrl(),
                        client = OkHttpClient(),
                        diagnosticObserver = hideDiagnosticObserver(logger),
                    )
                    LicsXpReservationGateway(rootSession)
                },
                logger = logger,
            ),
        )
        assertEquals(ReservationHideDiagnosticResult.HIDDEN, result)
    }
}
