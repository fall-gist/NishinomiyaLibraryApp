package com.fallgist.nishinomiyalibrary.ui.shelf

import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutation
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.repository.BookshelfRepository
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
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

/** 本棚ヘッダーで選んだ操作対象。画面表示に必要な値だけを持つ。 */
data class BookshelfShelfTarget(
    val memberId: Long,
    val shelfNo: Int,
    val memberName: String,
    val shelfName: String,
    val itemCount: Int,
)

/** 資料カードで選んだ操作対象。 */
data class BookshelfItemTarget(
    val memberId: Long,
    val shelfNo: Int,
    val shelfName: String,
    val tilcod: String,
    val title: String,
    val memo: String,
)

/** 入力中のダイアログ。送信対象は最終確認時に固定する。 */
sealed interface BookshelfEditingDialog {
    data class CreateShelf(val memberId: Long? = null, val name: String = "") : BookshelfEditingDialog
    data class RenameShelf(val target: BookshelfShelfTarget, val name: String = target.shelfName) : BookshelfEditingDialog
    data class EditItemMemo(val target: BookshelfItemTarget, val memo: String = target.memo) : BookshelfEditingDialog
}

/** 最終確認に表示する、操作対象を固定済みの要求。 */
sealed interface BookshelfEditingConfirmation {
    val mutation: BookshelfMutation

    data class CreateShelf(
        val memberName: String,
        val shelfName: String,
        override val mutation: BookshelfMutation.CreateShelf,
    ) : BookshelfEditingConfirmation

    data class RenameShelf(
        val target: BookshelfShelfTarget,
        val newName: String,
        override val mutation: BookshelfMutation.RenameShelf,
    ) : BookshelfEditingConfirmation

    data class EditItemMemo(
        val target: BookshelfItemTarget,
        val newMemo: String,
        override val mutation: BookshelfMutation.UpdateItemMemo,
    ) : BookshelfEditingConfirmation

    data class DeleteItem(
        val target: BookshelfItemTarget,
        override val mutation: BookshelfMutation.DeleteItem,
    ) : BookshelfEditingConfirmation

    data class DeleteShelf(
        val target: BookshelfShelfTarget,
        override val mutation: BookshelfMutation.DeleteShelf,
    ) : BookshelfEditingConfirmation
}

enum class BookshelfEditingResultKind { APPLIED, ALREADY_REGISTERED, UNKNOWN, FAILURE }

data class BookshelfEditingResultMessage(
    val title: String,
    val message: String,
    val kind: BookshelfEditingResultKind,
)

data class BookshelfEditingUiState(
    val initialized: Boolean = false,
    val members: List<Member> = emptyList(),
    val dialog: BookshelfEditingDialog? = null,
    val inputError: String? = null,
    val pendingConfirmation: BookshelfEditingConfirmation? = null,
    val processingMutation: BookshelfMutation? = null,
    val result: BookshelfEditingResultMessage? = null,
    val errorMessage: String? = null,
) {
    val processing: Boolean get() = processingMutation != null
}

/**
 * 本棚編集の入力・確認・送信・結果を共有するController。
 * UIはこのControllerを経由して[BookshelfRepository.mutate]だけを呼び、独自の永続化や排他は持たない。
 */
class BookshelfEditingUiController(
    private val bookshelfRepository: BookshelfRepository,
    private val familyRepository: FamilyRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(BookshelfEditingUiState())
    val state: StateFlow<BookshelfEditingUiState> = _state
    private val membersJob: Job

    init {
        membersJob = scope.launch {
            familyRepository.members().collect { members ->
                _state.update { it.copy(initialized = true, members = members) }
            }
        }
    }

    fun requestCreateShelf() {
        if (state.value.processing) return
        _state.value = state.value.copy(dialog = BookshelfEditingDialog.CreateShelf(), inputError = null)
    }

    fun selectCreateMember(memberId: Long) {
        val dialog = state.value.dialog as? BookshelfEditingDialog.CreateShelf ?: return
        if (state.value.processing || state.value.members.none { it.id == memberId }) return
        _state.value = state.value.copy(dialog = dialog.copy(memberId = memberId), inputError = null)
    }

    fun requestRenameShelf(target: BookshelfShelfTarget) {
        if (state.value.processing) return
        _state.value = state.value.copy(dialog = BookshelfEditingDialog.RenameShelf(target), inputError = null)
    }

    fun requestEditItemMemo(target: BookshelfItemTarget) {
        if (state.value.processing) return
        _state.value = state.value.copy(dialog = BookshelfEditingDialog.EditItemMemo(target), inputError = null)
    }

    fun requestDeleteItem(target: BookshelfItemTarget) {
        if (state.value.processing) return
        _state.value = state.value.copy(
            pendingConfirmation = BookshelfEditingConfirmation.DeleteItem(
                target,
                BookshelfMutation.DeleteItem(target.memberId, target.shelfNo, target.tilcod),
            ),
            inputError = null,
        )
    }

    fun requestDeleteShelf(target: BookshelfShelfTarget) {
        if (state.value.processing) return
        _state.value = state.value.copy(
            pendingConfirmation = BookshelfEditingConfirmation.DeleteShelf(
                target,
                BookshelfMutation.DeleteShelf(target.memberId, target.shelfNo),
            ),
            inputError = null,
        )
    }

    fun updateInput(value: String) {
        if (state.value.processing) return
        val dialog = state.value.dialog ?: return
        val updated = when (dialog) {
            is BookshelfEditingDialog.CreateShelf -> dialog.copy(name = value)
            is BookshelfEditingDialog.RenameShelf -> dialog.copy(name = value)
            is BookshelfEditingDialog.EditItemMemo -> dialog.copy(memo = value)
        }
        _state.value = state.value.copy(dialog = updated, inputError = null)
    }

    /** 入力画面の「次へ」。検証を通った場合だけ、通信しない最終確認へ進む。 */
    fun requestInputConfirmation() {
        if (state.value.processing) return
        when (val dialog = state.value.dialog) {
            is BookshelfEditingDialog.CreateShelf -> {
                val member = state.value.members.find { it.id == dialog.memberId }
                when {
                    member == null -> setInputError("対象メンバーを選択してください")
                    !isValidShelfName(dialog.name) -> setInputError(shelfNameError(dialog.name))
                    else -> _state.value = state.value.copy(
                        dialog = null,
                        inputError = null,
                        pendingConfirmation = BookshelfEditingConfirmation.CreateShelf(
                            memberName = member.name,
                            shelfName = dialog.name,
                            mutation = BookshelfMutation.CreateShelf(member.id, dialog.name),
                        ),
                    )
                }
            }
            is BookshelfEditingDialog.RenameShelf -> {
                if (!isValidShelfName(dialog.name)) {
                    setInputError(shelfNameError(dialog.name))
                } else {
                    _state.value = state.value.copy(
                        dialog = null,
                        inputError = null,
                        pendingConfirmation = BookshelfEditingConfirmation.RenameShelf(
                            target = dialog.target,
                            newName = dialog.name,
                            mutation = BookshelfMutation.RenameShelf(dialog.target.memberId, dialog.target.shelfNo, dialog.name),
                        ),
                    )
                }
            }
            is BookshelfEditingDialog.EditItemMemo -> {
                if (dialog.memo.length > MAX_MEMO_LENGTH) {
                    setInputError("資料メモは${MAX_MEMO_LENGTH}文字以内で入力してください")
                } else {
                    _state.value = state.value.copy(
                        dialog = null,
                        inputError = null,
                        pendingConfirmation = BookshelfEditingConfirmation.EditItemMemo(
                            target = dialog.target,
                            newMemo = dialog.memo,
                            mutation = BookshelfMutation.UpdateItemMemo(
                                dialog.target.memberId,
                                dialog.target.shelfNo,
                                dialog.target.tilcod,
                                dialog.memo,
                            ),
                        ),
                    )
                }
            }
            null -> Unit
        }
    }

    fun dismissDialog() {
        if (!state.value.processing) _state.value = state.value.copy(dialog = null, inputError = null)
    }

    fun dismissConfirmation() {
        if (!state.value.processing) _state.value = state.value.copy(pendingConfirmation = null)
    }

    /** 最終確認の肯定操作だけがRepositoryへのmutationを開始する。 */
    fun confirmPending() {
        val confirmation = state.value.pendingConfirmation ?: return
        if (state.value.processing) return
        // 作成対象のメンバーが確認表示中に削除されていた場合は、送信せず選択し直しを求める。
        if (confirmation.mutation.memberId !in state.value.members.map { it.id }) {
            _state.value = state.value.copy(
                pendingConfirmation = null,
                errorMessage = "対象メンバーが見つかりません。内容を確認してからやり直してください",
            )
            return
        }
        _state.value = state.value.copy(pendingConfirmation = null, processingMutation = confirmation.mutation)
        scope.launch {
            try {
                val outcome = bookshelfRepository.mutate(confirmation.mutation)
                _state.value = _state.value.copy(
                    processingMutation = null,
                    result = BookshelfEditingContentBuilder.resultMessage(outcome),
                )
            } catch (exception: CancellationException) {
                _state.value = _state.value.copy(processingMutation = null)
                throw exception
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    processingMutation = null,
                    errorMessage = "本棚の操作を完了できませんでした。通信状態を確認してください。",
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
        membersJob.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun setInputError(message: String) {
        _state.value = state.value.copy(inputError = message)
    }

    companion object {
        const val MAX_SHELF_NAME_LENGTH = 50
        const val MAX_MEMO_LENGTH = 1000

        fun isValidShelfName(value: String): Boolean = !value.isBlank() && value.length <= MAX_SHELF_NAME_LENGTH

        fun shelfNameError(value: String): String = when {
            value.isBlank() -> "本棚名を入力してください"
            value.length > MAX_SHELF_NAME_LENGTH -> "本棚名は${MAX_SHELF_NAME_LENGTH}文字以内で入力してください"
            else -> "本棚名を確認してください"
        }
    }
}

/** Android非依存の確認・結果文言。対象の取り違えを単体テストで検証する。 */
object BookshelfEditingContentBuilder {
    fun confirmationTitle(confirmation: BookshelfEditingConfirmation): String = when (confirmation) {
        is BookshelfEditingConfirmation.CreateShelf -> "本棚を作成しますか？"
        is BookshelfEditingConfirmation.RenameShelf -> "本棚名を変更しますか？"
        is BookshelfEditingConfirmation.EditItemMemo -> "資料メモを変更しますか？"
        is BookshelfEditingConfirmation.DeleteItem -> "本棚から資料を削除しますか？"
        is BookshelfEditingConfirmation.DeleteShelf -> "本棚を削除しますか？"
    }

    fun confirmationMessage(confirmation: BookshelfEditingConfirmation): String = when (confirmation) {
        is BookshelfEditingConfirmation.CreateShelf ->
            "対象メンバー：${confirmation.memberName}\n本棚名：${confirmation.shelfName}"
        is BookshelfEditingConfirmation.RenameShelf ->
            "対象本棚：${confirmation.target.shelfName}\n新しい名前：${confirmation.newName}"
        is BookshelfEditingConfirmation.EditItemMemo ->
            "資料名：${confirmation.target.title}\n本棚：${confirmation.target.shelfName}\n\nメモを変更しますか？"
        is BookshelfEditingConfirmation.DeleteItem ->
            "資料名：${confirmation.target.title}\n本棚：${confirmation.target.shelfName}\n\nこの資料を本棚から削除しますか？"
        is BookshelfEditingConfirmation.DeleteShelf ->
            "本棚「${confirmation.target.shelfName}」と登録資料${confirmation.target.itemCount}件を削除します。\nこの操作は元に戻せません。"
    }

    fun confirmLabel(confirmation: BookshelfEditingConfirmation): String = when (confirmation) {
        is BookshelfEditingConfirmation.CreateShelf -> "本棚を作成"
        is BookshelfEditingConfirmation.RenameShelf -> "名前を変更"
        is BookshelfEditingConfirmation.EditItemMemo -> "メモを変更"
        is BookshelfEditingConfirmation.DeleteItem -> "本棚から削除"
        is BookshelfEditingConfirmation.DeleteShelf -> "本棚と${confirmation.target.itemCount}件を削除"
    }

    fun isDestructive(confirmation: BookshelfEditingConfirmation): Boolean =
        confirmation is BookshelfEditingConfirmation.DeleteItem || confirmation is BookshelfEditingConfirmation.DeleteShelf

    fun resultMessage(outcome: BookshelfMutationOutcome): BookshelfEditingResultMessage = when (outcome) {
        is BookshelfMutationOutcome.Applied -> successMessage("本棚へ反映しました", outcome.localRefreshRequired)
        is BookshelfMutationOutcome.AlreadyRegistered -> BookshelfEditingResultMessage(
            title = "すでに登録済みです",
            message = appendRefreshWarning("この資料はすでに選択した本棚に登録されています", outcome.localRefreshRequired),
            kind = BookshelfEditingResultKind.ALREADY_REGISTERED,
        )
        BookshelfMutationOutcome.Unknown -> BookshelfEditingResultMessage(
            title = "処理結果を確認できません",
            message = "処理結果を確認できません。自動では再送しません。本棚を更新して確認してください",
            kind = BookshelfEditingResultKind.UNKNOWN,
        )
        is BookshelfMutationOutcome.Failure -> BookshelfEditingResultMessage(
            title = "本棚の操作を完了できませんでした",
            message = outcome.reason.bookshelfLabel(),
            kind = BookshelfEditingResultKind.FAILURE,
        )
    }

    private fun successMessage(message: String, refreshRequired: Boolean) = BookshelfEditingResultMessage(
        title = "本棚へ反映しました",
        message = appendRefreshWarning(message, refreshRequired),
        kind = BookshelfEditingResultKind.APPLIED,
    )

    private fun appendRefreshWarning(message: String, refreshRequired: Boolean): String =
        if (refreshRequired) "$message\n表示更新に失敗しました。画面を更新してください" else message
}

private fun FailureReason.bookshelfLabel(): String = when (this) {
    FailureReason.AUTH -> "メンバーの認証に失敗しました"
    FailureReason.INVALID_PICKUP_LIBRARY -> "操作内容が無効です"
    FailureReason.REJECTED_BY_SITE -> "図書館サイトが本棚の操作を受け付けませんでした"
    FailureReason.RESERVATION_LIMIT_EXCEEDED -> "本棚の操作を受け付けられませんでした"
    FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT -> "ログイン状態が失効しました。内容を確認してからやり直してください"
    FailureReason.SITE_RESPONSE_CHANGED -> "図書館サイトの表示が変更された可能性があるため、安全に停止しました。自動では再試行しません"
    FailureReason.SITE_MAINTENANCE -> "図書館サイトがメンテナンス中です"
    FailureReason.NETWORK -> "通信に失敗しました。接続を確認してください"
    FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE -> "サイト上の本棚の状態が変わったため、操作を停止しました。更新して確認してください"
}
