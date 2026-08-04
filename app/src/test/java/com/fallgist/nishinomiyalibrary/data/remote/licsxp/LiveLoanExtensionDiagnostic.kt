package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionOutcome
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 貸出延長のライブ診断(`docs/design/loan-extension.md` §8・§11-6)。
 * 既存の`liveReservationHideDiagnostic`と同じ流儀で、通常の`:app:testDebugUnitTest`とCIから
 * このクラスを除外する(`app/build.gradle.kts`の`liveLoanExtensionDiagnosticClass`)。
 *
 * 記録してよいのはPOST回数・段階名・対象一意性の成否・返却期限の変化有無(値そのものではなく
 * 「変化した/しない」)・一覧完全性だけ。カード番号・パスワード・Cookie・hash実値・
 * para/mngcod実値・資料名・HTML本文は一切出力しない。
 */
private fun extensionDiagnosticObserver(logger: LiveReservationDiagnosticLogger): LicsXpDiagnosticObserver =
    object : LicsXpDiagnosticObserver {
        override fun onRequest(request: LicsXpDiagnosticRequest) =
            logger.stage("extend-request", "${request.method} ${request.path} queryNames=${request.query.keys} fieldNames=${request.form.keys}")
        override fun onWireRequest(method: String, path: String, protocol: String, headers: List<Pair<String, String>>, cookieNames: List<String>, setCookieNames: List<String>) =
            logger.stage("extend-wire", "$method $path proto=$protocol headerNames=${headers.map { it.first }} cookiePresent=${cookieNames.isNotEmpty()} setCookiePresent=${setCookieNames.isNotEmpty()}")
        override fun onResponse(method: String, path: String, statusCode: Int, redirectPath: String?) =
            logger.stage("extend-response", "$method $path status=$statusCode redirect=${redirectPath ?: "-"}")
        override fun onPage(path: String, classification: String, formFingerprint: String) =
            logger.stage("extend-page", "$path classification=$classification forms=$formFingerprint")
        override fun onScreenScript(path: String, actionTargets: List<String>, fieldAssignments: List<String>) = Unit
        override fun onSiteMessages(path: String, messages: List<String>) = Unit
        override fun onPageText(path: String, headings: List<String>, notices: List<String>) = Unit
        override fun onNote(stage: String, detail: String) = logger.stage("extend-note", "$stage: $detail")
    }

/** 通常testから隔離する、本番1件ライブ延長診断の明示承認設定。 */
internal data class LiveLoanExtensionDiagnosticConfig(
    val cardNumber: String,
    val password: String,
    val tilcod: String,
) {
    companion object {
        const val ENABLE_FLAG = "LICSXP_LIVE_RESERVATION"
        const val EXTEND_CONFIRM_FLAG = "LICSXP_LIVE_EXTEND_CONFIRM"
        const val CARD_NUMBER = "LICSXP_CARD_NUMBER"
        const val PASSWORD = "LICSXP_PASSWORD"
        const val TILCOD = "LICSXP_TILCOD"

        fun from(environment: Map<String, String>): LiveLoanExtensionDiagnosticConfig? {
            if (environment[ENABLE_FLAG] != "YES_I_UNDERSTAND") return null
            if (environment[EXTEND_CONFIRM_FLAG] != "EXTEND_ON_PRODUCTION") return null
            val cardNumber = environment[CARD_NUMBER].orEmpty()
            val password = environment[PASSWORD].orEmpty()
            val tilcod = environment[TILCOD].orEmpty()
            if (cardNumber.isBlank() || password.isBlank() || tilcod.isBlank()) return null
            return LiveLoanExtensionDiagnosticConfig(cardNumber, password, tilcod)
        }
    }
}

/** [LoanExtensionOutcome]を安全な文言だけへ変換する。返却期限の実値(newDueDate)は出さない。 */
internal fun LoanExtensionOutcome.diagnosticLabel(): String = when (this) {
    // 「変化した」ことだけを記録し、実際の日付は出さない(§8)。
    is LoanExtensionOutcome.Extended -> "Extended(dueDateChanged=true)"
    LoanExtensionOutcome.Unknown -> "Unknown"
    is LoanExtensionOutcome.Failure -> "Failure(${reason})"
}

internal suspend fun runLiveLoanExtensionDiagnosticIfAuthorized(
    environment: Map<String, String>,
    gatewayFactory: () -> LoanExtensionGateway,
    logger: LiveReservationDiagnosticLogger,
): LoanExtensionOutcome? {
    val config = LiveLoanExtensionDiagnosticConfig.from(environment) ?: return null
    val session = gatewayFactory().openAuthenticatedSession(config.cardNumber, config.password)
    try {
        // 既存の本番実装(LicsXpLoanExtensionSession.extendLoan)をそのまま1回だけ呼ぶ。
        // 対象一意性判定・二段階POST・返却期限照合は既存のexactly-once実装に委ねる(再送しない)。
        val outcome = session.extendLoan(config.tilcod)
        logger.stage("extend-result", "outcome=${outcome.diagnosticLabel()}")
        return outcome
    } finally {
        session.close()
    }
}

/** `liveLoanExtensionDiagnostic` タスクだけが実行するライブテスト。 */
class LiveLoanExtensionDiagnosticTest {
    @Test
    fun executeOnlyWhenAllExplicitOptInsArePresent() = runBlocking {
        if (LiveLoanExtensionDiagnosticConfig.from(System.getenv()) == null) return@runBlocking
        val logger = LiveReservationDiagnosticLogger()
        val outcome = runLiveLoanExtensionDiagnosticIfAuthorized(
            environment = System.getenv(),
            gatewayFactory = {
                val rootSession = LicsXpSession(
                    baseUrl = LicsXpSession.DEFAULT_BASE_URL.toHttpUrl(),
                    client = OkHttpClient(),
                    diagnosticObserver = extensionDiagnosticObserver(logger),
                )
                LicsXpLoanExtensionGateway(rootSession)
            },
            logger = logger,
        )
        // 成否不明でも診断としては正常終了とする(§8: 対象1件について1回だけ実行し、再送しない)。
        assertTrue(outcome != null)
    }
}
