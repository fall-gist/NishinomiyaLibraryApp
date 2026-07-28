package com.fallgist.nishinomiyalibrary.ui.reservations

import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelItemResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelOutcome
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelTarget
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCancelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 予約中一覧の1行を一意に特定するキー。チェック選択・取消確認対象の指定に使う。 */
data class ReservationCancelKey(
    val memberId: Long,
    val tilcod: String,
    val cancelCode: String,
)

/**
 * 取消確認・結果表示に必要な、対象1件のUI表示用データ。
 * バックエンド([ReservationCancelRepository.cancelReservations])へは[target]だけを渡す。
 */
data class ReservationCancelCandidate(
    val target: ReservationCancelTarget,
    val title: String,
) {
    val key: ReservationCancelKey get() = ReservationCancelKey(target.memberId, target.tilcod, target.cancelCode)
}

/** 経路1(1件取消)か経路2(一斉取消)かを保持する。確認ダイアログ・結果ダイアログの文言分岐に使う。 */
sealed interface ReservationCancelConfirmationRequest {
    val candidates: List<ReservationCancelCandidate>

    data class Single(override val candidates: List<ReservationCancelCandidate>) : ReservationCancelConfirmationRequest
    data class Bulk(override val candidates: List<ReservationCancelCandidate>) : ReservationCancelConfirmationRequest
}

enum class ReservationCancelResultOrigin { SINGLE, BULK }

enum class ReservationCancelResultCategory { CANCELLED, UNKNOWN, FAILED }

data class ReservationCancelResultRow(
    val memberId: Long,
    val title: String,
    val outcomeLabel: String,
    val detail: String?,
    val category: ReservationCancelResultCategory,
) {
    /** 成功扱いは[ReservationCancelResultCategory.CANCELLED]だけ。Unknownを成功と混ぜないための唯一の判定点。 */
    val completed: Boolean get() = category == ReservationCancelResultCategory.CANCELLED
}

data class ReservationCancelSummary(
    val cancelledCount: Int,
    val unknownCount: Int,
    val failedCount: Int,
)

data class ReservationCancelUiState(
    val initialized: Boolean = false,
    val members: List<Member> = emptyList(),
    val selectedKeys: Set<ReservationCancelKey> = emptySet(),
    val pendingConfirmation: ReservationCancelConfirmationRequest? = null,
    val processing: Boolean = false,
    val resultOrigin: ReservationCancelResultOrigin? = null,
    val results: List<ReservationCancelResultRow> = emptyList(),
    val errorMessage: String? = null,
) {
    val canCancelSelection: Boolean get() = !processing && selectedKeys.isNotEmpty()
    val summary: ReservationCancelSummary get() = ReservationCancelContentBuilder.summarize(results)
}

/**
 * 予約取消(経路1・2)専用のUI状態を集約するController。[ReservationCancelRepository]だけを叩く。
 * 予約一覧そのもの([com.fallgist.nishinomiyalibrary.domain.model.Reservation])は
 * [ReservationsScreenController]が既に保持しているため、行データはこのControllerでは持たない
 * (選択キーだけを保持し、候補の組み立てはScreen側で[ReservationsContentBuilder.cancelCandidates]を使う)。
 */
class ReservationCancelUiController(
    private val cancelRepository: ReservationCancelRepository,
    private val familyRepository: FamilyRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(ReservationCancelUiState())
    val state: StateFlow<ReservationCancelUiState> = _state
    private val membersJob: Job

    init {
        membersJob = scope.launch {
            familyRepository.members().collect { members ->
                _state.value = _state.value.copy(initialized = true, members = members)
            }
        }
    }

    /** チェックボックスのタップ。行タップ(書誌詳細を開く。経路3は第2段階)とはタップ領域を分けている。 */
    fun toggleSelection(key: ReservationCancelKey) {
        if (state.value.processing) return
        val current = state.value.selectedKeys
        _state.value = state.value.copy(
            selectedKeys = if (key in current) current - key else current + key,
        )
    }

    /** 経路1: 行の「取消」ボタン。 */
    fun requestSingleCancelConfirmation(candidate: ReservationCancelCandidate) {
        if (state.value.processing) return
        _state.value = state.value.copy(
            pendingConfirmation = ReservationCancelConfirmationRequest.Single(listOf(candidate)),
        )
    }

    /** 経路2: 「一斉取消」ボタン。選択済みキーに対応する候補はScreen側で組み立てて渡す。 */
    fun requestBulkCancelConfirmation(candidates: List<ReservationCancelCandidate>) {
        if (state.value.processing || candidates.isEmpty()) return
        _state.value = state.value.copy(
            pendingConfirmation = ReservationCancelConfirmationRequest.Bulk(candidates),
        )
    }

    fun dismissConfirmation() {
        if (!state.value.processing) _state.value = state.value.copy(pendingConfirmation = null)
    }

    /** 最終確認ダイアログの肯定操作だけが取消通信(cancelReservations)を開始する。 */
    fun confirmPending() {
        val request = state.value.pendingConfirmation ?: return
        if (state.value.processing) return
        val origin = when (request) {
            is ReservationCancelConfirmationRequest.Single -> ReservationCancelResultOrigin.SINGLE
            is ReservationCancelConfirmationRequest.Bulk -> ReservationCancelResultOrigin.BULK
        }
        _state.value = state.value.copy(pendingConfirmation = null, processing = true)
        scope.launch {
            try {
                val titleByTarget = request.candidates.associate { it.target to it.title }
                val batchResult = cancelRepository.cancelReservations(request.candidates.map { it.target })
                _state.value = _state.value.copy(
                    processing = false,
                    // 取消成立行はローカルDBから即時削除され一覧から消えるため、選択状態を引きずらない。
                    selectedKeys = emptySet(),
                    resultOrigin = origin,
                    results = ReservationCancelContentBuilder.resultRows(batchResult, titleByTarget),
                )
            } catch (exception: CancellationException) {
                _state.value = _state.value.copy(processing = false)
                throw exception
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    processing = false,
                    errorMessage = "取消処理を完了できませんでした。通信状態を確認して、残っている項目を再度お試しください。",
                )
            }
        }
    }

    fun clearResults() {
        _state.value = state.value.copy(results = emptyList(), resultOrigin = null)
    }

    fun clearError() {
        _state.value = state.value.copy(errorMessage = null)
    }

    fun close() {
        membersJob.cancel()
        scope.coroutineContext[Job]?.cancel()
    }
}

object ReservationCancelContentBuilder {
    fun resultRows(
        result: ReservationCancelBatchResult,
        titleByTarget: Map<ReservationCancelTarget, String>,
    ): List<ReservationCancelResultRow> = result.members.flatMap { member ->
        member.itemResults.map { item -> item.toResultRow(member.memberId, titleByTarget[item.target] ?: item.target.tilcod) }
    }

    fun summarize(rows: List<ReservationCancelResultRow>): ReservationCancelSummary = ReservationCancelSummary(
        cancelledCount = rows.count { it.category == ReservationCancelResultCategory.CANCELLED },
        unknownCount = rows.count { it.category == ReservationCancelResultCategory.UNKNOWN },
        failedCount = rows.count { it.category == ReservationCancelResultCategory.FAILED },
    )
}

private fun ReservationCancelItemResult.toResultRow(memberId: Long, title: String): ReservationCancelResultRow =
    when (val value = outcome) {
        ReservationCancelOutcome.Cancelled, ReservationCancelOutcome.CancelledAndHidden ->
            // Cancelled/CancelledAndHiddenは利用者に区別して見せない(docs/ui-design.md「方針: 予約取消の導線」)。
            ReservationCancelResultRow(memberId, title, "取り消しました", null, ReservationCancelResultCategory.CANCELLED)
        is ReservationCancelOutcome.Unknown ->
            // 成否不明を成功と混ぜないことが最優先の要件。理由の内訳は出さず固定文言にする(設計書の表どおり)。
            ReservationCancelResultRow(
                memberId,
                title,
                "取り消せたか確認できません",
                "予約状況を再同期して確認してください",
                ReservationCancelResultCategory.UNKNOWN,
            )
        is ReservationCancelOutcome.Failure ->
            ReservationCancelResultRow(memberId, title, "取り消せませんでした", value.reason.cancelLabel(), ReservationCancelResultCategory.FAILED)
        is ReservationCancelOutcome.ConfirmationRequired ->
            ReservationCancelResultRow(memberId, title, "取り消せませんでした（確認画面が返りました）", null, ReservationCancelResultCategory.FAILED)
        is ReservationCancelOutcome.Rejected ->
            // 現状のバックエンド実装はRejectedを生成しないが(Models.kt参照)、sealed interfaceの網羅性のため扱う。
            ReservationCancelResultRow(memberId, title, "取り消せませんでした", value.siteMessage, ReservationCancelResultCategory.FAILED)
    }

/** 予約側(ReservationUiController)のFailureReason文言マッピングを、取消の文脈向けに書き直したもの。 */
private fun FailureReason.cancelLabel(): String = when (this) {
    FailureReason.AUTH -> "メンバーの認証に失敗しました"
    // 以下2件は予約確定側の理由であり取消では生成されないが、sealed enumの網羅性のため扱う。
    FailureReason.INVALID_PICKUP_LIBRARY -> "受取館の指定が無効です"
    FailureReason.RESERVATION_LIMIT_EXCEEDED -> "予約できる冊数の上限に達しています"
    FailureReason.REJECTED_BY_SITE -> "図書館サイトが取消を受け付けませんでした"
    FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT -> "ログイン状態が失効しました。再度お試しください"
    FailureReason.SITE_RESPONSE_CHANGED -> "図書館サイトの応答を確認できませんでした。時間をおいて再度お試しください"
    FailureReason.SITE_MAINTENANCE -> "図書館サイトがメンテナンス中です"
    FailureReason.NETWORK -> "通信に失敗しました。接続を確認して再度お試しください"
    FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE -> "サイトの状態が変わったため、このメンバーの処理を停止しました"
}
