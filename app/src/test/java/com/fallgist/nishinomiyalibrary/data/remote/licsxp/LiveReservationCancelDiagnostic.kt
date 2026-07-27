package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 本番サイトを対象にした予約取消診断の明示同意。通常の unit test ではこの設定を作れず、通信もしない。
 * 取消は不可逆な副作用のため、既存の予約診断とは別の専用同意フラグ(CANCEL_CONFIRM_FLAG)を要求する。
 * 認証情報は環境変数からのみ受け取り、ログや例外メッセージへ渡さない。
 */
internal data class LiveReservationCancelDiagnosticConfig(
    val cardNumber: String,
    val password: String,
    val tilcod: String,
) {
    companion object {
        const val ENABLE_FLAG = "LICSXP_LIVE_RESERVATION"
        const val CANCEL_CONFIRM_FLAG = "LICSXP_LIVE_CANCEL_CONFIRM"
        const val CARD_NUMBER = "LICSXP_CARD_NUMBER"
        const val PASSWORD = "LICSXP_PASSWORD"
        const val CANCEL_TILCOD = "LICSXP_CANCEL_TILCOD"

        private const val ENABLE_VALUE = "YES_I_UNDERSTAND"
        private const val CANCEL_CONFIRM_VALUE = "CANCEL_ON_PRODUCTION"

        fun from(environment: Map<String, String>): LiveReservationCancelDiagnosticConfig? {
            if (environment[ENABLE_FLAG] != ENABLE_VALUE) return null
            if (environment[CANCEL_CONFIRM_FLAG] != CANCEL_CONFIRM_VALUE) return null
            val cardNumber = environment[CARD_NUMBER].orEmpty()
            val password = environment[PASSWORD].orEmpty()
            val tilcod = environment[CANCEL_TILCOD].orEmpty()
            if (cardNumber.isBlank() || password.isBlank() || tilcod.isBlank()) return null
            return LiveReservationCancelDiagnosticConfig(cardNumber, password, tilcod)
        }
    }
}

internal data class LiveReservationCancelDiagnosticReport(
    val attempt: ReservationCancelAttempt,
    val stillPresentAfter: Boolean,
)

/**
 * 指定tilcodの予約を一覧から一意に特定できた場合だけ、その1件を取り消す。
 * 対象が見つからない・複数件一致する・取消コードが空の行のいずれかであれば、
 * 取消POSTを送らずcheckで停止する（対象を誤って取り消さないための安全策）。
 * 本番POSTはここから一度だけ呼ぶ。
 */
internal suspend fun runLiveReservationCancelDiagnostic(
    gateway: ReservationGateway,
    config: LiveReservationCancelDiagnosticConfig,
    logger: LiveReservationDiagnosticLogger,
): LiveReservationCancelDiagnosticReport {
    val session = gateway.openAuthenticatedSession(config.cardNumber, config.password)
    try {
        val reservations = session.fetchReservations()
        val matches = reservations.filter { it.tilcod == config.tilcod }
        // cancelCodeそのものはログへ出さない。tilcodは既存診断でも出しているため出してよい。
        logger.stage("cancel-target", "tilcod=${config.tilcod} matches=${matches.size}")
        check(matches.isNotEmpty()) { "対象の予約が一覧にありません" }
        check(matches.size == 1) { "対象が複数件一致したため、どれを取り消すか決められません" }
        val target = matches.single()
        check(target.cancelCode.isNotBlank()) { "対象行に取消コードがないため取消を行いません" }

        val attempt = session.cancelReservation(target.cancelCode)
        logger.stage("cancel-post", "attempt=$attempt")

        val stillPresent = session.fetchReservations().any { it.tilcod == config.tilcod }
        logger.stage("cancel-after", "targetPresent=$stillPresent")
        return LiveReservationCancelDiagnosticReport(attempt, stillPresent)
    } finally {
        session.close()
    }
}

/** 設定が完全一致しない限り gateway すら生成せず、通信不能にする入口。 */
internal suspend fun runLiveReservationCancelDiagnosticIfAuthorized(
    environment: Map<String, String>,
    gatewayFactory: () -> ReservationGateway,
    logger: LiveReservationDiagnosticLogger,
): LiveReservationCancelDiagnosticReport? {
    val config = LiveReservationCancelDiagnosticConfig.from(environment) ?: return null
    return runLiveReservationCancelDiagnostic(gatewayFactory(), config, logger)
}

/** 専用 Gradle タスクからだけ実行する本番取消診断。取消は1件だけで、ループや複数件取消は行わない。 */
class LiveReservationCancelDiagnosticTest {
    @org.junit.Test
    fun executeOnlyWhenAllExplicitOptInsArePresent() = runBlocking {
        val config = LiveReservationCancelDiagnosticConfig.from(System.getenv()) ?: return@runBlocking
        val logger = LiveReservationDiagnosticLogger()
        logger.stage("start", "本番予約取消診断を開始します")
        val report = requireNotNull(runLiveReservationCancelDiagnosticIfAuthorized(
            environment = System.getenv(),
            gatewayFactory = {
                val rootSession = LicsXpSession(
                    baseUrl = LicsXpSession.DEFAULT_BASE_URL.toHttpUrl(),
                    client = okhttp3.OkHttpClient(),
                    diagnosticObserver = logger.observer(),
                )
                LicsXpReservationGateway(rootSession)
            },
            logger = logger,
        ))
        org.junit.Assert.assertFalse("取消後照合で対象資料がまだ残っています", report.stillPresentAfter)
    }
}
