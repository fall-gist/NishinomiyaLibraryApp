package com.fallgist.nishinomiyalibrary.ui.reservationcart

import com.fallgist.nishinomiyalibrary.data.local.AppSettings
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartItem
import com.fallgist.nishinomiyalibrary.domain.model.ReservationConfirmation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationItemResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
import com.fallgist.nishinomiyalibrary.domain.model.UnknownReason
import com.fallgist.nishinomiyalibrary.domain.repository.CalendarRepository
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCartRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ReservationCartMemberGroup(
    val member: Member?,
    val items: List<ReservationCartItem>,
)

sealed interface ReservationConfirmationRequest {
    val targets: List<ReservationTarget>

    data class Cart(override val targets: List<ReservationTarget>) : ReservationConfirmationRequest

    data class Immediate(override val targets: List<ReservationTarget>) : ReservationConfirmationRequest
}

data class ReservationResultRow(
    val memberId: Long,
    val title: String,
    val outcomeLabel: String,
    val detail: String?,
    val completed: Boolean,
)

enum class ReservationFeedbackOrigin { CART, IMMEDIATE, CART_ADD }

/** 結果・案内・エラーを、起点画面と必要なら書誌単位で限定して保持する。 */
data class ReservationFeedback(
    val origin: ReservationFeedbackOrigin,
    val tilcod: String? = null,
    val results: List<ReservationResultRow> = emptyList(),
    val notice: String? = null,
    val errorMessage: String? = null,
)

data class ReservationUiState(
    val initialized: Boolean = false,
    val members: List<Member> = emptyList(),
    val cartGroups: List<ReservationCartMemberGroup> = emptyList(),
    val libraries: List<Library> = emptyList(),
    val pickupLibraryCode: String = "",
    val selectedMemberId: Long? = null,
    val pendingConfirmation: ReservationConfirmationRequest? = null,
    val processing: Boolean = false,
    val feedback: ReservationFeedback? = null,
) {
    val cartItemCount: Int get() = cartGroups.sumOf { it.items.size }
    val hasValidPickupLibrary: Boolean get() = libraries.any { it.code == pickupLibraryCode }
    val canConfirmCart: Boolean get() = !processing && cartItemCount > 0 && hasValidPickupLibrary
}

/** 予約カートと書誌詳細が共有する、予約操作専用のUI状態。 */
class ReservationUiController(
    private val cartRepository: ReservationCartRepository,
    private val familyRepository: FamilyRepository,
    private val calendarRepository: CalendarRepository,
    private val settings: Flow<AppSettings>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(ReservationUiState(libraries = calendarRepository.libraries))
    val state: StateFlow<ReservationUiState> = _state

    init {
        scope.launch {
            combine(
                cartRepository.cartItems(),
                familyRepository.members(),
                settings,
            ) { cartItems, members, settings ->
                Triple(cartItems, members, settings.defaultCalendarLibrary)
            }.collect { (cartItems, members, defaultCode) ->
                val pickupCode = validLibraryCode(_state.value.pickupLibraryCode.ifBlank { defaultCode })
                val selectedMember = _state.value.selectedMemberId
                    ?.takeIf { id -> members.any { it.id == id } }
                    ?: members.firstOrNull()?.id
                _state.value = _state.value.copy(
                    initialized = true,
                    members = members,
                    cartGroups = ReservationCartContentBuilder.groups(cartItems, members),
                    libraries = calendarRepository.libraries,
                    pickupLibraryCode = pickupCode,
                    selectedMemberId = selectedMember,
                )
            }
        }
    }

    fun selectPickupLibrary(code: String) {
        if (state.value.libraries.any { it.code == code }) {
            _state.value = _state.value.copy(pickupLibraryCode = code)
        }
    }

    fun selectMember(memberId: Long) {
        if (state.value.members.any { it.id == memberId }) {
            _state.value = _state.value.copy(selectedMemberId = memberId)
        }
    }

    fun addToCart(target: ReservationTarget) {
        if (state.value.processing || target.memberId !in state.value.members.map { it.id }) return
        scope.launch {
            try {
                cartRepository.addToCart(target)
                _state.value = _state.value.copy(
                    feedback = ReservationFeedback(ReservationFeedbackOrigin.CART_ADD, target.tilcod, notice = "カートに追加しました"),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    feedback = ReservationFeedback(ReservationFeedbackOrigin.CART_ADD, target.tilcod, errorMessage = "カートへ追加できませんでした"),
                )
            }
        }
    }

    fun removeFromCart(cartItemId: Long) {
        if (state.value.processing) return
        scope.launch {
            try {
                cartRepository.removeFromCart(cartItemId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    feedback = ReservationFeedback(ReservationFeedbackOrigin.CART, errorMessage = "カートから削除できませんでした"),
                )
            }
        }
    }

    fun requestCartConfirmation() {
        val targets = state.value.cartGroups.flatMap { group -> group.items.map { it.toTarget() } }
        if (state.value.processing || targets.isEmpty()) return
        if (!state.value.hasValidPickupLibrary) {
            _state.value = _state.value.copy(
                feedback = ReservationFeedback(ReservationFeedbackOrigin.CART, errorMessage = "有効な受取館を選択してください"),
            )
            return
        }
        _state.value = _state.value.copy(
            pendingConfirmation = ReservationConfirmationRequest.Cart(targets),
        )
    }

    fun requestImmediateConfirmation(target: ReservationTarget) {
        if (state.value.processing || target.memberId !in state.value.members.map { it.id }) return
        if (!state.value.hasValidPickupLibrary) {
            _state.value = _state.value.copy(
                feedback = ReservationFeedback(ReservationFeedbackOrigin.IMMEDIATE, target.tilcod, errorMessage = "有効な受取館を選択してください"),
            )
            return
        }
        _state.value = _state.value.copy(
            pendingConfirmation = ReservationConfirmationRequest.Immediate(listOf(target)),
        )
    }

    fun dismissConfirmation() {
        if (!state.value.processing) _state.value = _state.value.copy(pendingConfirmation = null)
    }

    /** 最終確認ダイアログの肯定操作だけが予約通信を開始する。 */
    fun confirmPending() {
        val request = state.value.pendingConfirmation ?: return
        if (state.value.processing) return
        if (!state.value.hasValidPickupLibrary) {
            _state.value = _state.value.copy(
                pendingConfirmation = null,
                feedback = request.feedback(errorMessage = "有効な受取館を選択してください"),
            )
            return
        }
        val confirmation = ReservationConfirmation(state.value.pickupLibraryCode, now())
        _state.value = _state.value.copy(pendingConfirmation = null, processing = true)
        scope.launch {
            try {
                val result = executeReservation(request, confirmation)
                _state.value = _state.value.copy(
                    processing = false,
                    feedback = request.feedback(results = ReservationCartContentBuilder.resultRows(result)),
                )
            } catch (exception: CancellationException) {
                _state.value = _state.value.copy(processing = false)
                throw exception
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    processing = false,
                    feedback = request.feedback(errorMessage = "予約処理を完了できませんでした。通信状態を確認して、残っている項目を再度お試しください。"),
                )
            }
        }
    }

    fun clearCartFeedback() {
        if (state.value.feedback?.origin == ReservationFeedbackOrigin.CART) {
            _state.value = _state.value.copy(feedback = null)
        }
    }

    internal suspend fun executeReservation(
        request: ReservationConfirmationRequest,
        confirmation: ReservationConfirmation,
    ): ReservationBatchResult = when (request) {
        is ReservationConfirmationRequest.Cart -> cartRepository.confirmCart(confirmation)
        is ReservationConfirmationRequest.Immediate -> cartRepository.reserveNow(request.targets.single(), confirmation)
    }

    fun close() {
        scope.coroutineContext[Job]?.cancel()
    }

    private fun validLibraryCode(code: String): String =
        calendarRepository.libraries.firstOrNull { it.code == code }?.code
            ?: calendarRepository.libraries.firstOrNull()?.code.orEmpty()
}

object ReservationCartContentBuilder {
    fun groups(items: List<ReservationCartItem>, members: List<Member>): List<ReservationCartMemberGroup> =
        items.groupBy { it.memberId }.map { (memberId, memberItems) ->
            ReservationCartMemberGroup(members.find { it.id == memberId }, memberItems)
        }

    fun resultRows(result: ReservationBatchResult): List<ReservationResultRow> =
        result.members.flatMap { member ->
            member.itemResults.map { item -> item.toResultRow(member.memberId) }
        }

    fun feedbackForCart(feedback: ReservationFeedback?): ReservationFeedback? =
        feedback?.takeIf { it.origin == ReservationFeedbackOrigin.CART }

    fun feedbackForDetail(feedback: ReservationFeedback?, tilcod: String): ReservationFeedback? =
        feedback?.takeIf { it.origin != ReservationFeedbackOrigin.CART && it.tilcod == tilcod }

}

private fun ReservationConfirmationRequest.feedback(
    results: List<ReservationResultRow> = emptyList(),
    errorMessage: String? = null,
): ReservationFeedback = when (this) {
    is ReservationConfirmationRequest.Cart -> ReservationFeedback(ReservationFeedbackOrigin.CART, results = results, errorMessage = errorMessage)
    is ReservationConfirmationRequest.Immediate -> ReservationFeedback(ReservationFeedbackOrigin.IMMEDIATE, targets.single().tilcod, results, errorMessage = errorMessage)
}

private fun ReservationCartItem.toTarget() = ReservationTarget(id, memberId, tilcod, title, writerLine)

private fun ReservationItemResult.toResultRow(memberId: Long): ReservationResultRow = when (val value = outcome) {
    ReservationOutcome.Success -> ReservationResultRow(memberId, target.title, "予約成立", null, completed = true)
    ReservationOutcome.AlreadyReserved -> ReservationResultRow(memberId, target.title, "予約済み", "すでに予約済みの資料です", completed = true)
    is ReservationOutcome.Failure -> ReservationResultRow(memberId, target.title, "予約できませんでした", value.detail(), completed = false)
    is ReservationOutcome.Unknown -> ReservationResultRow(memberId, target.title, "予約状態を確認できません", value.reason.label(), completed = false)
}

/**
 * 予約制限超過（[FailureReason.RESERVATION_LIMIT_EXCEEDED]）は、サイトが返した文言
 * （[ReservationOutcome.Failure.siteMessage]）をそのまま見せることを基本とする。
 * 文言が無い場合だけ既定文言を使う。
 */
private fun ReservationOutcome.Failure.detail(): String =
    if (reason == FailureReason.RESERVATION_LIMIT_EXCEEDED) siteMessage ?: reason.label() else reason.label()

private fun FailureReason.label(): String = when (this) {
    FailureReason.AUTH -> "メンバーの認証に失敗しました"
    FailureReason.INVALID_PICKUP_LIBRARY -> "受取館の指定が無効です"
    // サイトは業務的拒否（上限超過など）の理由を一切返さないため、断定的な理由を表示してはならない。
    FailureReason.REJECTED_BY_SITE -> "図書館サイトが予約を受け付けませんでした（予約上限に達しているなどの理由が考えられます）"
    FailureReason.RESERVATION_LIMIT_EXCEEDED -> "予約できる冊数の上限に達しています"
    FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT -> "ログイン状態が失効しました。再度お試しください"
    FailureReason.SITE_RESPONSE_CHANGED -> "図書館サイトの応答を確認できませんでした。時間をおいて再度お試しください"
    FailureReason.SITE_MAINTENANCE -> "図書館サイトがメンテナンス中です"
    FailureReason.NETWORK -> "通信に失敗しました。接続を確認して再度お試しください"
    FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE -> "サイトの状態が変わったため、このメンバーの処理を停止しました"
}

private fun UnknownReason.label(): String = when (this) {
    UnknownReason.POST_CONNECTION_LOST -> "送信後に通信が切断されました。予約状況をご確認ください"
    UnknownReason.POST_RESPONSE_UNEXPECTED -> "送信結果を確認できませんでした。予約状況をご確認ください"
    UnknownReason.VERIFICATION_UNAVAILABLE -> "予約状況を照合できませんでした。予約状況をご確認ください"
}
