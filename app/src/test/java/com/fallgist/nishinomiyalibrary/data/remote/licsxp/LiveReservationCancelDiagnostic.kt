package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
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
    /**
     * 取消後、対象tilcod行が1行以上一覧に残っているかどうかの観測値（診断ログにも`cancel-after`として
     * 出す）。13回目のライブ観測(2026-07-28)実測どおり、同一tilcodの行が複数（取消済み行の併存）に
     * なり得るため、単純な`singleOrNull`では判定しない（該当2件以上でnullを返してしまい、行が
     * 実際にはあるのに`false`と誤記録するバグがあった。修正済み）。`isNotEmpty()`相当の判定であり、
     * 行数そのものは診断ログの`cancel-after`に別途出す。
     * 12回目のライブ取消＋一覧観測(2026-07-28)で確定した仕様どおり、取消成立後も対象行は「取消」状態
     * (`ReservationState.CANCELLED`)のまま一覧に残り得る（非表示ボタン`yoykHihyoji`を押すまで消えない）。
     * そのためこの値**だけ**では成否を断定できない。観測値として残すが、成否判定には使わないこと
     * （判定は[attempt]で行う。[LiveReservationCancelDiagnosticTest]参照）。
     */
    val targetRowPresentAfter: Boolean,
)

/**
 * 指定tilcodの「取消可能な行（cancelCodeが非空）」を一意に特定できた場合だけ、その1件を取り消す。
 * 13回目のライブ観測(2026-07-28)実測どおり、取消済み行を非表示にせず同じ書誌を再予約すると
 * 同一tilcodの行が複数になり得る（普通の運用操作であり実運用で必ず起きる）。tilcodだけでは行の
 * 同一性を追えないサイト仕様のため、対象の特定は「取消可能な行」の中での一意性で行う（取消済み行が
 * 併存していても、取消可能な行が1つだけなら実行できる）。取消可能な行が0または2件以上であれば
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
        val cancellableMatches = matches.filter { it.cancelCode.isNotBlank() }
        val cancelledMatches = matches.count { it.state == ReservationState.CANCELLED }
        // cancelCodeの値・資料名はログへ出さない。tilcodは既存診断でも出しているため出してよい。
        // 内訳(取消可能な行数・取消済みの行数)は、対象特定の判断根拠として出す。
        logger.stage(
            "cancel-target",
            "tilcod=${config.tilcod} matches=${matches.size} cancellable=${cancellableMatches.size} cancelled=$cancelledMatches",
        )
        check(matches.isNotEmpty()) { "対象の予約が一覧にありません" }
        check(cancellableMatches.isNotEmpty()) { "対象行に取消コードがないため取消を行いません" }
        check(cancellableMatches.size == 1) { "取消可能な行が複数件一致したため、どれを取り消すか決められません" }
        val target = cancellableMatches.single()

        val attempt = session.cancelReservation(target.cancelCode, target.tilcod)
        // ConfirmationRequiredは互換型で、現行実装は生成しない。診断の将来互換のため文言は残して出す。
        // 他の分岐はattemptの既定のtoStringに委ねる(dataクラスのmessageも含めて出力される)。
        val attemptDetail = when (attempt) {
            is ReservationCancelAttempt.ConfirmationRequired -> "attempt=$attempt confirmationMessage=${attempt.message}"
            else -> "attempt=$attempt"
        }
        logger.stage("cancel-post", attemptDetail)

        // 12回目のライブ取消＋一覧観測(2026-07-28)で確定した仕様どおり、取消成立後も対象tilcod行は
        // 「取消」状態のまま一覧に残り得る。この観測（cancel-after）は成否の断定には使わず、
        // 「取消成立→取消状態で残存」を次のライブで直接確認するための読み取り専用の記録に限定する。
        // 送信内容は変えない。一覧観測に対応したセッション(ReservationListInspector)であれば、
        // 資料名・cancelCodeの値を出さずにstate・非表示ボタン(yoykHihyoji)の有無まで記録する。
        // 13回目のライブ観測(2026-07-28)実測どおり、同一tilcodの行は複数（取消済み行の併存）になり得る
        // ため、対象tilcodの行は`singleOrNull`ではなく`filter`で全件（0件以上）を記録する。
        // 対応していないセッション（テストのフェイク等）では、従来どおり件数だけをfetchReservationsで見る。
        val inspector = session as? ReservationListInspector
        val targetRowPresentAfter = if (inspector != null) {
            val inspection = inspector.inspectReservationList()
            val rows = inspection.rows.filter { it.tilcod == config.tilcod }
            if (rows.isEmpty()) {
                logger.stage("cancel-after", "targetPresent=false rows=0")
            } else {
                logger.stage("cancel-after", "targetPresent=true rows=${rows.size}")
                rows.forEachIndexed { index, row ->
                    logger.stage(
                        "cancel-after-row[$index]",
                        "state=${row.state} hideButtonPresent=${row.buttonFunctionNames.contains("yoykHihyoji")}",
                    )
                }
            }
            rows.isNotEmpty()
        } else {
            val matchCount = session.fetchReservations().count { it.tilcod == config.tilcod }
            logger.stage("cancel-after", "targetPresent=${matchCount > 0} rows=$matchCount")
            matchCount > 0
        }
        return LiveReservationCancelDiagnosticReport(attempt, targetRowPresentAfter)
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
        // 12回目のライブ取消＋一覧観測(2026-07-28)で確定した仕様どおり、取消成立後も対象行は
        // 「取消」状態のまま一覧に残り得るため、一覧からの消失(targetRowPresentAfter)では
        // 成否を断定しない。成否はreport.attemptで判定する。
        val succeeded = report.attempt == ReservationCancelAttempt.Cancelled ||
            report.attempt == ReservationCancelAttempt.CancelledAndHidden
        org.junit.Assert.assertTrue(
            "取消が成立しませんでした: attempt=${report.attempt} targetRowPresentAfter=${report.targetRowPresentAfter}",
            succeeded,
        )
    }
}
