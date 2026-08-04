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

/** 確定操作1回分の結果表示。表示文言はUI層(ここ)で組み立てる(§6・§9.1)。 */
data class LoanExtensionResultMessage(
    val message: String,
    /** Unknownを成功として扱わないための唯一の判定点(§6.1・進行指示15と同じ考え方)。 */
    val succeeded: Boolean,
)

data class LoanExtensionUiState(
    val pendingConfirmation: LoanExtensionCandidate? = null,
    /** 処理中の対象行。行ごとのボタン無効化に使う。 */
    val processingTarget: LoanExtensionKey? = null,
    val result: LoanExtensionResultMessage? = null,
    val errorMessage: String? = null,
) {
    val processing: Boolean get() = processingTarget != null
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

    /** 行の「延長」ボタン。確定するまで通信は開始しない。 */
    fun requestConfirmation(candidate: LoanExtensionCandidate) {
        if (state.value.processing) return
        _state.value = state.value.copy(pendingConfirmation = candidate)
    }

    fun dismissConfirmation() {
        if (!state.value.processing) _state.value = state.value.copy(pendingConfirmation = null)
    }

    /** 最終確認ダイアログの肯定操作だけが延長通信(extendLoan)を開始する。 */
    fun confirmPending() {
        val candidate = state.value.pendingConfirmation ?: return
        if (state.value.processing) return
        _state.value = state.value.copy(pendingConfirmation = null, processingTarget = candidate.key)
        scope.launch {
            try {
                val outcome = extensionRepository.extendLoan(candidate.target)
                _state.value = _state.value.copy(
                    processingTarget = null,
                    result = LoanExtensionContentBuilder.resultMessage(outcome),
                )
            } catch (exception: CancellationException) {
                _state.value = _state.value.copy(processingTarget = null)
                throw exception
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    processingTarget = null,
                    errorMessage = "延長処理を完了できませんでした。通信状態を確認して、もう一度お試しください。",
                )
            }
        }
    }

    fun clearResult() {
        _state.value = state.value.copy(result = null)
    }

    fun clearError() {
        _state.value = state.value.copy(errorMessage = null)
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
     * [LoanExtensionOutcome]を画面表示文言へ変換する(`docs/design/loan-extension.md` §6)。
     * Unknownを成功として扱わない([LoanExtensionResultMessage.succeeded]が唯一の判定点)。
     */
    fun resultMessage(outcome: LoanExtensionOutcome): LoanExtensionResultMessage = when (outcome) {
        is LoanExtensionOutcome.Extended ->
            LoanExtensionResultMessage("返却期限を延長しました（新しい期限: ${formatDueDate(outcome.newDueDate)}）", succeeded = true)
        LoanExtensionOutcome.Unknown ->
            LoanExtensionResultMessage("延長できたか確認できません。しばらくしてから貸出状況をご確認ください", succeeded = false)
        is LoanExtensionOutcome.Failure ->
            LoanExtensionResultMessage(outcome.reason.extensionLabel(), succeeded = false)
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
