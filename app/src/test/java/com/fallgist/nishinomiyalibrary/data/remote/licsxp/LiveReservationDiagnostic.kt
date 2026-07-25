package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 本番サイトを対象にした予約診断の明示同意。通常の unit test ではこの設定を作れず、通信もしない。
 * 認証情報は環境変数からのみ受け取り、ログや例外メッセージへ渡さない。
 */
internal data class LiveReservationDiagnosticConfig(
    val cardNumber: String,
    val password: String,
    val tilcod: String,
    val pickupLibraryCode: String,
    val dryRun: Boolean,
) {
    companion object {
        const val ENABLE_FLAG = "LICSXP_LIVE_RESERVATION"
        const val CONFIRM_FLAG = "LICSXP_LIVE_RESERVATION_CONFIRM"
        const val DRY_RUN_FLAG = "LICSXP_LIVE_RESERVATION_DRY_RUN"
        const val CARD_NUMBER = "LICSXP_CARD_NUMBER"
        const val PASSWORD = "LICSXP_PASSWORD"
        const val TILCOD = "LICSXP_TILCOD"
        const val PICKUP_LIBRARY = "LICSXP_PICKUP_LIBRARY"

        private const val ENABLE_VALUE = "YES_I_UNDERSTAND"
        private const val CONFIRM_VALUE = "RESERVE_ON_PRODUCTION"
        private const val DRY_RUN_VALUE = "INSPECT_ONLY"

        fun from(environment: Map<String, String>): LiveReservationDiagnosticConfig? {
            if (environment[ENABLE_FLAG] != ENABLE_VALUE) return null
            // dry-runは書き込みを行わないため、CONFIRM_FLAGを要求しない。fail-safeとしてdry-run指定を優先する。
            val dryRun = environment[DRY_RUN_FLAG] == DRY_RUN_VALUE
            if (!dryRun && environment[CONFIRM_FLAG] != CONFIRM_VALUE) return null
            val cardNumber = environment[CARD_NUMBER].orEmpty()
            val password = environment[PASSWORD].orEmpty()
            val tilcod = environment[TILCOD].orEmpty()
            val pickup = environment[PICKUP_LIBRARY].orEmpty()
            if (cardNumber.isBlank() || password.isBlank() || tilcod.isBlank() || pickup.isBlank()) return null
            return LiveReservationDiagnosticConfig(cardNumber, password, tilcod, pickup, dryRun)
        }
    }
}

internal data class LiveReservationDiagnosticReport(
    val attempt: DirectReservationAttempt,
    val wasReservedBefore: Boolean,
    val isReservedAfter: Boolean,
)

/** 出力は経路・状態・秘匿済みフォーム構造だけに限定する。 */
internal class LiveReservationDiagnosticLogger {
    fun stage(name: String, detail: String) {
        println("[live-reservation] $name: $detail")
    }

    fun observer(): LicsXpDiagnosticObserver = object : LicsXpDiagnosticObserver {
        override fun onRequest(request: LicsXpDiagnosticRequest) {
            val query = request.query.entries.joinToString("&") { (name, value) -> "$name=$value" }
            val form = request.form.entries.joinToString("&") { (name, value) -> "$name=$value" }
            stage("http-request", "${request.method} ${request.path} query=[$query] form=[$form]")
        }

        override fun onResponse(method: String, path: String, statusCode: Int, redirectPath: String?) {
            stage("http-response", "$method $path status=$statusCode redirect=${redirectPath ?: "-"}")
        }

        override fun onPage(path: String, classification: String, formFingerprint: String) {
            stage("page", "$path classification=$classification forms=$formFingerprint")
        }

        override fun onScreenScript(path: String, actionTargets: List<String>, fieldAssignments: List<String>) {
            if (actionTargets.isEmpty() && fieldAssignments.isEmpty()) return
            stage("screen-script", "$path actions=$actionTargets assignments=$fieldAssignments")
        }

        override fun onSiteMessages(path: String, messages: List<String>) {
            if (messages.isEmpty()) return
            stage("site-messages", "$path $messages")
        }
    }
}

/**
 * 予約前後を必ず照合する。本番 POST はここから一度だけ呼ぶ。
 * 既に予約済みなら POST せず、安全に終了する。
 */
internal suspend fun runLiveReservationDiagnostic(
    gateway: ReservationGateway,
    config: LiveReservationDiagnosticConfig,
    logger: LiveReservationDiagnosticLogger,
): LiveReservationDiagnosticReport {
    // 事前照合はサーバ側の画面状態を変えないよう使い捨ての別セッションで行い、必ずcloseする。
    val precheckSession = gateway.openAuthenticatedSession(config.cardNumber, config.password)
    val reservedBefore = try {
        precheckSession.fetchReservations().any { it.tilcod == config.tilcod }
    } finally {
        precheckSession.close()
    }
    logger.stage("reservation-before", "targetPresent=$reservedBefore")
    check(!reservedBefore) { "対象資料は予約前一覧に既に存在するため、本番POSTを行いません" }

    // ブラウザで成功が確認された列（ログイン直後に 詳細GET→確認POST→確定POST）を再現するため、新しいセッションを開き直す。
    val session = gateway.openAuthenticatedSession(config.cardNumber, config.password)
    try {
        val attempt = session.directReserve(config.tilcod, config.pickupLibraryCode)
        logger.stage("reservation-post", "attempt=$attempt")

        val reservedAfter = session.fetchReservations().any { it.tilcod == config.tilcod }
        logger.stage("reservation-after", "targetPresent=$reservedAfter")
        return LiveReservationDiagnosticReport(attempt, reservedBefore, reservedAfter)
    } finally {
        session.close()
    }
}

/** 設定が完全一致しない限り gateway すら生成せず、通信不能にする入口。 */
internal suspend fun runLiveReservationDiagnosticIfAuthorized(
    environment: Map<String, String>,
    gatewayFactory: () -> ReservationGateway,
    logger: LiveReservationDiagnosticLogger,
): LiveReservationDiagnosticReport? {
    val config = LiveReservationDiagnosticConfig.from(environment) ?: return null
    // dry-run指定が残っている環境では、書き込み経路を絶対に開始しない。
    if (config.dryRun) return null
    return runLiveReservationDiagnostic(gatewayFactory(), config, logger)
}

/**
 * 確定POSTを一切行わず、確認画面までの遷移と解析結果だけを調べる。
 * directReserveは呼ばない。書き込み副作用はゼロ。
 */
internal suspend fun runLiveReservationInspection(
    gateway: ReservationGateway,
    config: LiveReservationDiagnosticConfig,
    logger: LiveReservationDiagnosticLogger,
): ConfirmationInspection {
    logger.stage("inspect-mode", "確定POSTは行いません")
    val session = gateway.openAuthenticatedSession(config.cardNumber, config.password)
    try {
        val inspector = session as? ReservationConfirmationInspector
            ?: error("検査に対応していないセッション実装です")
        val inspection = inspector.inspectDirectReservationConfirmation(config.tilcod, config.pickupLibraryCode)
        val detail = when (inspection) {
            is ConfirmationInspection.Parsed ->
                "fieldNames=[${inspection.fieldNames.joinToString(",")}] " +
                    "pickupLibraryCodes=[${inspection.pickupLibraryCodes.joinToString(",")}] " +
                    "requestedPickupAvailable=${inspection.requestedPickupAvailable} " +
                    "explicitPickup=${inspection.explicitPickupLibraryCode ?: "(none)"} " +
                    "explicitContact=${inspection.explicitContactCode ?: "(none)"}"
            is ConfirmationInspection.ParseFailed -> "screen=${inspection.screen} reason=${inspection.reason}"
            ConfirmationInspection.SessionExpiredBeforeConfirm -> "確認画面到達前にセッションが失効しました"
        }
        logger.stage("confirm-inspection", detail)
        return inspection
    } finally {
        session.close()
    }
}

/** 設定が完全一致しない限り gateway すら生成せず、通信不能にする入口（dry-run版）。 */
internal suspend fun runLiveReservationInspectionIfAuthorized(
    environment: Map<String, String>,
    gatewayFactory: () -> ReservationGateway,
    logger: LiveReservationDiagnosticLogger,
): ConfirmationInspection? {
    val config = LiveReservationDiagnosticConfig.from(environment) ?: return null
    if (!config.dryRun) return null
    return runLiveReservationInspection(gatewayFactory(), config, logger)
}

/** 専用 Gradle タスクからだけ実行する本番診断。 */
class LiveReservationDiagnosticTest {
    @org.junit.Test
    fun executeOnlyWhenAllExplicitOptInsArePresent() = runBlocking {
        val config = LiveReservationDiagnosticConfig.from(System.getenv()) ?: return@runBlocking
        // dry-run指定が残っている間は、書き込みを伴うこの診断を実行しない。
        if (config.dryRun) return@runBlocking
        val logger = LiveReservationDiagnosticLogger()
        logger.stage("start", "本番予約診断を開始します")
        val report = requireNotNull(runLiveReservationDiagnosticIfAuthorized(
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
        org.junit.Assert.assertTrue("予約後照合で対象資料を確認できませんでした", report.isReservedAfter)
    }
}

/** 専用 Gradle タスクからだけ実行する dry-run 診断。確定POSTは行わず、構造調査の結果をログに残すだけで良い。 */
class LiveReservationInspectionTest {
    @org.junit.Test
    fun executeOnlyWhenDryRunOptInIsPresent() = runBlocking {
        val config = LiveReservationDiagnosticConfig.from(System.getenv()) ?: return@runBlocking
        if (!config.dryRun) return@runBlocking
        val logger = LiveReservationDiagnosticLogger()
        logger.stage("start", "本番予約確認dry-run診断を開始します")
        // 構造調査が目的のため、ParseFailedやSessionExpiredBeforeConfirmでもテストは失敗させない。
        runLiveReservationInspectionIfAuthorized(
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
        )
    }
}
