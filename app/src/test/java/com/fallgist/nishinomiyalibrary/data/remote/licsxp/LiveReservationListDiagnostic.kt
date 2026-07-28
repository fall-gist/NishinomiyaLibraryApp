package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 本番サイトを対象にした、予約状況一覧の構造観測専用の明示同意。
 *
 * 12回目のライブ取消診断(2026-07-28)で取消が実サイトで成立し、所有者から
 * 「取消後も対象書誌は一覧に残る。予約状態列が赤字で『取消』になり、取消ボタンの位置に
 * 『非表示』ボタンが置かれる。『非表示』ボタンを押すと初めて一覧から消える」という、
 * これまでドキュメントに無かった仕様が提供された。この診断は、その状態が実際にどう出ているかを
 * **書き込み副作用ゼロ**で確定させるためだけのものである。
 *
 * 取消POST・非表示POST・予約POSTのいずれも送らないため、[LiveReservationCancelDiagnosticConfig]の
 * `CANCEL_CONFIRM_FLAG`のような追加の同意フラグは要求しない。専用の`INSPECT_FLAG`だけを要求する。
 */
internal data class LiveReservationListInspectDiagnosticConfig(
    val cardNumber: String,
    val password: String,
    /** 指定されれば、その行をログの先頭で強調する。未指定でも全行の要約は出す。 */
    val highlightTilcod: String?,
) {
    companion object {
        const val ENABLE_FLAG = "LICSXP_LIVE_RESERVATION"
        const val INSPECT_FLAG = "LICSXP_LIST_INSPECT"
        const val CARD_NUMBER = "LICSXP_CARD_NUMBER"
        const val PASSWORD = "LICSXP_PASSWORD"
        const val INSPECT_TILCOD = "LICSXP_INSPECT_TILCOD"

        private const val ENABLE_VALUE = "YES_I_UNDERSTAND"
        private const val INSPECT_VALUE = "INSPECT_ONLY"

        fun from(environment: Map<String, String>): LiveReservationListInspectDiagnosticConfig? {
            if (environment[ENABLE_FLAG] != ENABLE_VALUE) return null
            if (environment[INSPECT_FLAG] != INSPECT_VALUE) return null
            val cardNumber = environment[CARD_NUMBER].orEmpty()
            val password = environment[PASSWORD].orEmpty()
            if (cardNumber.isBlank() || password.isBlank()) return null
            val highlightTilcod = environment[INSPECT_TILCOD]?.takeIf { it.isNotBlank() }
            return LiveReservationListInspectDiagnosticConfig(cardNumber, password, highlightTilcod)
        }
    }
}

/**
 * 予約状況一覧を1回だけ取得し、行ごとの構造を[logger]へ残す。
 * 書き込み副作用はゼロ（取消・非表示・予約のいずれのPOSTも送らない）。
 * 通信経路は既存の`fetchReservationSnapshot`と同じ（メニューGET→一覧表示POST）だけである。
 */
internal suspend fun runLiveReservationListInspection(
    gateway: ReservationGateway,
    config: LiveReservationListInspectDiagnosticConfig,
    logger: LiveReservationDiagnosticLogger,
) {
    logger.stage("list-inspect-mode", "読み取り専用です。取消・非表示・予約のPOSTは一切送りません")
    val session = gateway.openAuthenticatedSession(config.cardNumber, config.password)
    try {
        val inspector = session as? ReservationListInspector
            ?: error("一覧観測に対応していないセッション実装です")
        val inspection = inspector.inspectReservationList()
        logger.stage(
            "list-summary",
            "summaryReservationCount=${inspection.summaryReservationCount?.toString() ?: "取得不可"} " +
                "parsedRowCount=${inspection.parsedRowCount}",
        )
        val highlightTilcod = config.highlightTilcod
        if (highlightTilcod != null) {
            val highlighted = inspection.rows.filter { it.tilcod == highlightTilcod }
            if (highlighted.isEmpty()) {
                logger.stage("list-row-highlight", "指定tilcodの行が見つかりません")
            } else {
                highlighted.forEachIndexed { index, row -> logger.stage("list-row-highlight[$index]", row.toLogLine()) }
            }
        }
        // 行数が多い(20行程度)ため、1行1レコードとして読みやすく出す。
        inspection.rows.forEachIndexed { index, row -> logger.stage("list-row[$index]", row.toLogLine()) }
    } finally {
        session.close()
    }
}

/** 資料名は含めない。cancelCodeは有無の真偽値だけ、他も値ではなく構造だけを1行に並べる。 */
private fun ReservationListRowInspection.toLogLine(): String =
    "tilcod=$tilcod stateText=$stateText state=$state cancelCodePresent=$cancelCodePresent " +
        "buttons=$buttonFunctionNames cellClasses=$cellClassNames"

/** 設定が完全一致しない限り gateway すら生成せず、通信不能にする入口。 */
internal suspend fun runLiveReservationListInspectionIfAuthorized(
    environment: Map<String, String>,
    gatewayFactory: () -> ReservationGateway,
    logger: LiveReservationDiagnosticLogger,
): Boolean {
    val config = LiveReservationListInspectDiagnosticConfig.from(environment) ?: return false
    runLiveReservationListInspection(gatewayFactory(), config, logger)
    return true
}

/**
 * 専用 Gradle タスク(`liveReservationListInspect`)からだけ実行する、予約状況一覧の読み取り専用ライブ観測。
 * 書き込み副作用はゼロ。取消・非表示・予約のいずれのPOSTも送らない。
 */
class LiveReservationListInspectionTest {
    @org.junit.Test
    fun executeOnlyWhenListInspectOptInIsPresent() = runBlocking {
        val config = LiveReservationListInspectDiagnosticConfig.from(System.getenv()) ?: return@runBlocking
        val logger = LiveReservationDiagnosticLogger()
        logger.stage("start", "本番予約状況一覧の読み取り専用観測を開始します")
        runLiveReservationListInspectionIfAuthorized(
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
