package com.fallgist.nishinomiyalibrary.ui.loans

import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionOutcome
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionTarget
import com.fallgist.nishinomiyalibrary.domain.repository.LoanExtensionRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 貸出中一覧の1行を一意に特定するキー。処理中表示・二重操作防止に使う。 */
data class LoanExtensionKey(val memberId: Long, val tilcod: String)

/**
 * 延長確認ダイアログ・確定操作に必要な、対象1件のUI表示用データ。
 * バックエンド([LoanExtensionRepository.extendLoan])へは[target]だけを渡す
 * (`docs/design/loan-extension.md` §9.1: UI層はrenewalCodeを一切持たない)。
 */
data class LoanExtensionCandidate(
    val target: LoanExtensionTarget,
    val title: String,
    val currentDueDate: LocalDate,
) {
    val key: LoanExtensionKey get() = LoanExtensionKey(target.memberId, target.tilcod)
}

/**
 * [LoanExtensionOutcome]の3値をUI表示の種別として保持する。
 * `succeeded: Boolean`のような2値へ潰すと、Unknown(成否不明)がFailure(失敗の断定)と
 * 区別できなくなる(`docs/handoff.md`進行指示15、`docs/design/loan-extension.md`§5.2・§6)。
 */
enum class LoanExtensionResultKind { EXTENDED, UNKNOWN, FAILED }

/** 確定操作1回分の結果表示。表示文言・タイトルはUI層(ここ)で組み立てる(§6・§9.1)。 */
data class LoanExtensionResultMessage(
    val title: String,
    val message: String,
    val kind: LoanExtensionResultKind,
) {
    /**
     * 成功扱いは[LoanExtensionResultKind.EXTENDED]だけ。Unknownを成功として集計しないための
     * 唯一の判定点(§6.1・進行指示15と同じ考え方)。
     * **注意**: これがfalseであることは「失敗」を意味しない(Unknownもfalseになる)。
     * 「延長できませんでした」等、失敗を断定する表示には使わないこと。[kind]で分岐すること。
     */
    val succeeded: Boolean get() = kind == LoanExtensionResultKind.EXTENDED
}

/** 経路1(1件延長)か経路2(一斉延長)かを保持する(`docs/design/bulk-selection.md` §5.2、予約取消と同じ流儀)。 */
sealed interface LoanExtensionConfirmationRequest {
    val candidates: List<LoanExtensionCandidate>

    data class Single(override val candidates: List<LoanExtensionCandidate>) : LoanExtensionConfirmationRequest
    data class Bulk(override val candidates: List<LoanExtensionCandidate>) : LoanExtensionConfirmationRequest
}

/** 一斉延長の処理中に出す進捗(`docs/design/bulk-selection.md` §5.2「3件目/5件」)。 */
data class LoanExtensionBulkProgress(val completed: Int, val total: Int)

/** 一斉延長の結果ダイアログの1行。 */
data class LoanExtensionResultRow(
    val key: LoanExtensionKey,
    val title: String,
    val message: LoanExtensionResultMessage,
)

data class LoanExtensionUiState(
    /** 一斉延長の選択キー(`docs/design/bulk-selection.md` §4.2、既存の型をそのまま使う)。 */
    val selectedKeys: Set<LoanExtensionKey> = emptySet(),
    val pendingConfirmation: LoanExtensionConfirmationRequest? = null,
    /** 処理中の対象行。行ごとのボタン無効化に使う。一斉延長中は現在処理中の対象を指す。 */
    val processingTarget: LoanExtensionKey? = null,
    /** 一斉延長の処理中だけ非null。1件延長では使わない。 */
    val bulkProgress: LoanExtensionBulkProgress? = null,
    /** 1件延長(経路1)の結果。一斉延長の結果は[results]に入る。 */
    val result: LoanExtensionResultMessage? = null,
    /** 一斉延長(経路2)の結果。件ごとの行を持つ(§5.2)。 */
    val results: List<LoanExtensionResultRow> = emptyList(),
    val errorMessage: String? = null,
) {
    val processing: Boolean get() = processingTarget != null || bulkProgress != null
    val canExtendSelection: Boolean get() = !processing && selectedKeys.isNotEmpty()
}

/**
 * 貸出延長専用のUI状態を集約するController([ReservationCancelUiController]と同じ流儀で
 * [LoansScreenController]から分離する)。[LoanExtensionRepository]だけを叩き、
 * Gatewayを直接呼ばない。UI層は`renewalCode`を一切保持しない(`docs/design/loan-extension.md` §9.1)。
 */
class LoanExtensionUiController(
    private val extensionRepository: LoanExtensionRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(LoanExtensionUiState())
    val state: StateFlow<LoanExtensionUiState> = _state

    // _stateは購読側(toggleSelection等、UIスレッド)と一斉延長のscope.launch(Defaultディスパッチャ)の
    // 両方から更新される。一斉延長は1件ごとに一覧再取得＋二段階POSTを行うため長時間動き続け、
    // その間も利用者はチェックボックスを操作できる。`_state.value = _state.value.copy(...)`は
    // 読みと書きの間に別コルーチンの書きが挟まるとそれを取りこぼす(lost update)。
    // CalendarScreenController・SettingsScreenControllerで実際に踏んだ不具合と同型(docs/handoff.md参照)。
    // **必ず`update {}`(CASループ)を使うこと。`_state.value = ...`を書いてはならない。**

    /** チェックボックスのタップ(`docs/design/bulk-selection.md` §5.2)。行タップ・行内延長ボタンとは別のタップ領域。 */
    fun toggleSelection(key: LoanExtensionKey) {
        if (state.value.processing) return
        _state.update { current ->
            current.copy(selectedKeys = if (key in current.selectedKeys) current.selectedKeys - key else current.selectedKeys + key)
        }
    }

    /** 経路1: 行の「延長」ボタン。確定するまで通信は開始しない。 */
    fun requestConfirmation(candidate: LoanExtensionCandidate) {
        if (state.value.processing) return
        _state.update { it.copy(pendingConfirmation = LoanExtensionConfirmationRequest.Single(listOf(candidate))) }
    }

    /** 経路2: 「一斉延長」ボタン。選択済みキーに対応する候補はScreen側で組み立てて渡す。 */
    fun requestBulkConfirmation(candidates: List<LoanExtensionCandidate>) {
        if (state.value.processing || candidates.isEmpty()) return
        _state.update { it.copy(pendingConfirmation = LoanExtensionConfirmationRequest.Bulk(candidates)) }
    }

    fun dismissConfirmation() {
        if (!state.value.processing) _state.update { it.copy(pendingConfirmation = null) }
    }

    /** 最終確認ダイアログの肯定操作だけが延長通信(extendLoan)を開始する。 */
    fun confirmPending() {
        val request = state.value.pendingConfirmation ?: return
        if (state.value.processing) return
        _state.update { it.copy(pendingConfirmation = null) }
        when (request) {
            is LoanExtensionConfirmationRequest.Single -> confirmSingle(request.candidates.single())
            is LoanExtensionConfirmationRequest.Bulk -> confirmBulk(request.candidates)
        }
    }

    private fun confirmSingle(candidate: LoanExtensionCandidate) {
        _state.update { it.copy(processingTarget = candidate.key) }
        scope.launch {
            try {
                val outcome = extensionRepository.extendLoan(candidate.target)
                _state.update {
                    it.copy(
                        processingTarget = null,
                        result = LoanExtensionContentBuilder.resultMessage(outcome),
                    )
                }
            } catch (exception: CancellationException) {
                _state.update { it.copy(processingTarget = null) }
                throw exception
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        processingTarget = null,
                        errorMessage = "延長処理を完了できませんでした。通信状態を確認して、もう一度お試しください。",
                    )
                }
            }
        }
    }

    /**
     * 一斉延長本体。`docs/design/bulk-selection.md` §9.1の構造要件どおり、複数件を順に回す
     * ループは[LoanExtensionRepository.extendLoans]側に置く(UI層で別のループを持たない)。
     * 進捗表示(§5.2「N件目/M件」)は`extendLoans`の`onProgress`コールバックで駆動する。
     * 結果行のtitleはScreen側から渡された[candidates]をtargetで引き当てて組み立てる
     * (`LoanExtensionItemResult`はtargetとoutcomeしか持たないため)。
     */
    private fun confirmBulk(candidates: List<LoanExtensionCandidate>) {
        val total = candidates.size
        _state.update { it.copy(bulkProgress = LoanExtensionBulkProgress(0, total), processingTarget = candidates.firstOrNull()?.key) }
        scope.launch {
            try {
                val titleByTarget = candidates.associate { it.target to it.title }
                val batchResult = extensionRepository.extendLoans(candidates.map { it.target }) { completed, progressTotal ->
                    _state.update {
                        it.copy(
                            bulkProgress = LoanExtensionBulkProgress(completed, progressTotal),
                            processingTarget = candidates.getOrNull(completed)?.key,
                        )
                    }
                }
                val rows = batchResult.items.map { item ->
                    LoanExtensionResultRow(
                        key = LoanExtensionKey(item.target.memberId, item.target.tilcod),
                        title = titleByTarget[item.target] ?: item.target.tilcod,
                        message = LoanExtensionContentBuilder.resultMessage(item.outcome),
                    )
                }
                _state.update {
                    it.copy(
                        // 一斉延長の対象は完了後に選択を空にする(予約中の一斉取消と同じ流儀)。
                        selectedKeys = emptySet(),
                        processingTarget = null,
                        bulkProgress = null,
                        results = rows,
                    )
                }
            } catch (exception: CancellationException) {
                _state.update { it.copy(processingTarget = null, bulkProgress = null) }
                throw exception
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        processingTarget = null,
                        bulkProgress = null,
                        errorMessage = "延長処理を完了できませんでした。通信状態を確認して、もう一度お試しください。",
                    )
                }
            }
        }
    }

    fun clearResult() {
        _state.update { it.copy(result = null) }
    }

    /** 一斉延長の結果ダイアログを閉じる。 */
    fun clearResults() {
        _state.update { it.copy(results = emptyList()) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun close() {
        scope.coroutineContext[Job]?.cancel()
    }
}

/** 貸出延長の表示文言組み立て。Android非依存でテストする(`ReservationCancelContentBuilder`と同じ流儀)。 */
object LoanExtensionContentBuilder {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    fun formatDueDate(date: LocalDate): String = dateFormatter.format(date)

    /**
     * [LoanExtensionOutcome]を画面表示文言(タイトル・本文・種別)へ変換する
     * (`docs/design/loan-extension.md` §6)。
     * Unknownを成功として扱わない([LoanExtensionResultMessage.succeeded]が唯一の判定点)一方、
     * Unknownのタイトルは「延長できませんでした」のような失敗の断定にしない(進行指示15)。
     * タイトルをここ(Android非依存)へ置くのは、Composable内でsucceededから2値へ潰す経路を
     * 構造的に無くすため。
     */
    fun resultMessage(outcome: LoanExtensionOutcome): LoanExtensionResultMessage = when (outcome) {
        is LoanExtensionOutcome.Extended ->
            LoanExtensionResultMessage(
                title = "延長しました",
                message = "返却期限を延長しました（新しい期限: ${formatDueDate(outcome.newDueDate)}）",
                kind = LoanExtensionResultKind.EXTENDED,
            )
        LoanExtensionOutcome.Unknown ->
            // 延長が成立している可能性があるため、「延長できませんでした」等の失敗断定文言にしない。
            // 「再度お試しください」も促さない(既に成立している延長を再送させないため)。
            LoanExtensionResultMessage(
                title = "延長の結果を確認できません",
                message = "延長できたか確認できません。しばらくしてから貸出状況をご確認ください",
                kind = LoanExtensionResultKind.UNKNOWN,
            )
        is LoanExtensionOutcome.Failure ->
            LoanExtensionResultMessage(
                title = "延長できませんでした",
                message = outcome.reason.extensionLabel(),
                kind = LoanExtensionResultKind.FAILED,
            )
    }
}

/** 予約側(ReservationCancelUiController)のFailureReason文言マッピングを、延長の文脈向けに書き直したもの。 */
private fun FailureReason.extensionLabel(): String = when (this) {
    FailureReason.AUTH -> "メンバーの認証に失敗しました"
    // 以下2件は予約確定側の理由であり延長では生成されないが、sealed enumの網羅性のため扱う。
    FailureReason.INVALID_PICKUP_LIBRARY -> "受取館の指定が無効です"
    FailureReason.RESERVATION_LIMIT_EXCEEDED -> "予約できる冊数の上限に達しています"
    FailureReason.REJECTED_BY_SITE -> "図書館サイトが延長を受け付けませんでした"
    FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT -> "ログイン状態が失効しました。再度お試しください"
    FailureReason.SITE_RESPONSE_CHANGED -> "図書館サイトの応答を確認できませんでした。時間をおいて再度お試しください"
    FailureReason.SITE_MAINTENANCE -> "図書館サイトがメンテナンス中です"
    FailureReason.NETWORK -> "通信に失敗しました。接続を確認して再度お試しください"
    FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE -> "サイトの状態が変わったため、処理を停止しました"
}
