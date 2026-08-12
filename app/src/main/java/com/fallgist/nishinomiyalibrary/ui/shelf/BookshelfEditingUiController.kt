package com.fallgist.nishinomiyalibrary.ui.shelf

import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutation
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutationExpectation
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfExpectedItem
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfExpectedShelf
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfEditItem
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfContent
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
    val shelfCount: Int = 0,
    val items: List<BookshelfEditItem> = emptyList(),
)

/** 資料カードで選んだ操作対象。 */
data class BookshelfItemTarget(
    val memberId: Long,
    val shelfNo: Int,
    val shelfName: String,
    val tilcod: String,
    val title: String,
    val memo: String,
    val itemCount: Int = 0,
    val shelfCount: Int = 0,
)

/** 入力中のダイアログ。送信対象は最終確認時に固定する。 */
sealed interface BookshelfEditingDialog {
    data class CreateShelf(val memberId: Long? = null, val name: String = "") : BookshelfEditingDialog
    data class AddItem(
        val tilcod: String,
        val title: String,
        val memberId: Long? = null,
        val shelfNo: Int? = null,
        val memo: String = "",
    ) : BookshelfEditingDialog
    data class EditShelf(
        val target: BookshelfShelfTarget,
        val name: String = target.shelfName,
        val items: List<BookshelfEditItem> = target.items,
    ) : BookshelfEditingDialog
}

/** 最終確認に表示する、操作対象を固定済みの要求。 */
sealed interface BookshelfEditingConfirmation {
    val mutation: BookshelfMutation

    data class CreateShelf(
        val memberName: String,
        val shelfName: String,
        override val mutation: BookshelfMutation.CreateShelf,
    ) : BookshelfEditingConfirmation

    data class AddItem(
        val memberName: String,
        val shelfName: String,
        val title: String,
        val memo: String,
        override val mutation: BookshelfMutation.AddItem,
    ) : BookshelfEditingConfirmation

    data class EditShelf(
        val target: BookshelfShelfTarget,
        val newName: String,
        val items: List<BookshelfEditItem>,
        override val mutation: BookshelfMutation.EditShelf,
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
    /** 資料追加ダイアログで選択中のメンバーに属する棚。空棚も含む。 */
    val addItemShelves: List<BookshelfContent> = emptyList(),
    /** [addItemShelves] が選択メンバーの最初のFlow値を受け取ったか。 */
    val addItemShelvesLoadedForMemberId: Long? = null,
    val createShelfCount: Int? = null,
    val createShelvesLoadedForMemberId: Long? = null,
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
    private var addItemShelvesJob: Job? = null
    private var createShelvesJob: Job? = null

    init {
        membersJob = scope.launch {
            familyRepository.members().collect { members ->
                _state.update { it.copy(initialized = true, members = members) }
            }
        }
    }

    fun requestCreateShelf() {
        _state.update { current ->
            if (current.processing) current else current.copy(
                dialog = BookshelfEditingDialog.CreateShelf(),
                createShelfCount = null,
                createShelvesLoadedForMemberId = null,
                inputError = null,
            )
        }
    }

    /** 書誌詳細からの資料追加を開始する。前回の選択・メモは引き継がない。 */
    fun requestAddItem(tilcod: String, title: String) {
        if (tilcod.isBlank()) return
        var opened = false
        _state.update { current ->
            opened = false
            if (current.processing || current.members.isEmpty()) current else {
                opened = true
                current.copy(
                    dialog = BookshelfEditingDialog.AddItem(tilcod = tilcod, title = title),
                    addItemShelves = emptyList(),
                    addItemShelvesLoadedForMemberId = null,
                    inputError = null,
                )
            }
        }
        if (opened) stopAddItemShelfObservation()
    }

    fun selectAddItemMember(memberId: Long) {
        var selected = false
        _state.update { current ->
            selected = false
            val dialog = current.dialog as? BookshelfEditingDialog.AddItem
            if (current.processing || dialog == null || current.members.none { it.id == memberId }) current else {
                selected = true
                current.copy(
                    dialog = dialog.copy(memberId = memberId, shelfNo = null),
                    addItemShelves = emptyList(),
                    addItemShelvesLoadedForMemberId = null,
                    inputError = null,
                )
            }
        }
        if (!selected) return
        stopAddItemShelfObservation()
        addItemShelvesJob = scope.launch {
            bookshelfRepository.observeShelves(memberId).collect { shelves ->
                _state.update { current ->
                    val dialog = current.dialog as? BookshelfEditingDialog.AddItem
                    val pending = current.pendingConfirmation as? BookshelfEditingConfirmation.AddItem
                    if (dialog?.memberId == memberId || pending?.mutation?.memberId == memberId) {
                        current.copy(
                            addItemShelves = shelves.sortedBy { it.shelfNo },
                            addItemShelvesLoadedForMemberId = memberId,
                        )
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun selectAddItemShelf(shelfNo: Int) {
        _state.update { current ->
            val dialog = current.dialog as? BookshelfEditingDialog.AddItem
            if (current.processing || dialog?.memberId == null || current.addItemShelves.none { it.shelfNo == shelfNo }) current
            else current.copy(dialog = dialog.copy(shelfNo = shelfNo), inputError = null)
        }
    }

    fun selectCreateMember(memberId: Long) {
        var selected = false
        _state.update { current ->
            val dialog = current.dialog as? BookshelfEditingDialog.CreateShelf
            selected = !current.processing && dialog != null && current.members.any { it.id == memberId }
            if (!selected) current else current.copy(
                dialog = dialog!!.copy(memberId = memberId),
                createShelfCount = null,
                createShelvesLoadedForMemberId = null,
                inputError = null,
            )
        }
        if (!selected) return
        createShelvesJob?.cancel()
        createShelvesJob = scope.launch {
            bookshelfRepository.observeShelves(memberId).collect { shelves ->
                _state.update { current ->
                    val dialog = current.dialog as? BookshelfEditingDialog.CreateShelf
                    val pending = current.pendingConfirmation as? BookshelfEditingConfirmation.CreateShelf
                    if (dialog?.memberId == memberId || pending?.mutation?.memberId == memberId) {
                        current.copy(createShelfCount = shelves.size, createShelvesLoadedForMemberId = memberId)
                    } else current
                }
            }
        }
    }

    fun requestEditShelf(target: BookshelfShelfTarget) {
        _state.update { current -> if (current.processing) current else current.copy(dialog = BookshelfEditingDialog.EditShelf(target), inputError = null) }
    }

    fun requestDeleteItem(target: BookshelfItemTarget) {
        _state.update { current ->
            if (current.processing) current else current.copy(
                pendingConfirmation = BookshelfEditingConfirmation.DeleteItem(
                    target,
                    BookshelfMutation.DeleteItem(
                        target.memberId,
                        target.shelfNo,
                        target.tilcod,
                        target.expectation(current.members.find { it.id == target.memberId }?.name ?: ""),
                    ),
                ),
                inputError = null,
            )
        }
    }

    fun requestDeleteShelf(target: BookshelfShelfTarget) {
        _state.update { current ->
            if (current.processing) current else current.copy(
                pendingConfirmation = BookshelfEditingConfirmation.DeleteShelf(
                    target,
                    BookshelfMutation.DeleteShelf(target.memberId, target.shelfNo, target.expectation()),
                ),
                inputError = null,
            )
        }
    }

    fun updateInput(value: String) {
        _state.update { current ->
            val dialog = current.dialog
            if (current.processing || dialog == null) return@update current
            val updated = when (dialog) {
                is BookshelfEditingDialog.CreateShelf -> dialog.copy(name = value)
                is BookshelfEditingDialog.AddItem -> dialog.copy(memo = value)
                is BookshelfEditingDialog.EditShelf -> dialog.copy(name = value)
            }
            current.copy(dialog = updated, inputError = null)
        }
    }

    /** 本棚編集画面の資料メモを資料IDで更新する。重複IDは確認前に安全に拒否する。 */
    fun updateEditShelfMemo(tilcod: String, value: String) {
        _state.update { current ->
            val dialog = current.dialog as? BookshelfEditingDialog.EditShelf ?: return@update current
            if (current.processing || dialog.items.count { it.tilcod == tilcod } != 1) current
            else current.copy(dialog = dialog.copy(items = dialog.items.map { if (it.tilcod == tilcod) it.copy(newMemo = value) else it }), inputError = null)
        }
    }

    /** 入力画面の「次へ」。検証を通った場合だけ、通信しない最終確認へ進む。 */
    fun requestInputConfirmation() {
        _state.update { current ->
            if (current.processing) return@update current
            when (val dialog = current.dialog) {
            is BookshelfEditingDialog.CreateShelf -> {
                val member = current.members.find { it.id == dialog.memberId }
                when {
                    member == null -> current.copy(inputError = "対象メンバーを選択してください")
                    current.createShelvesLoadedForMemberId != member.id -> current.copy(inputError = "本棚を読み込んでいます")
                    !isValidShelfName(dialog.name) -> current.copy(inputError = shelfNameError(dialog.name))
                    else -> current.copy(
                        dialog = null,
                        inputError = null,
                        pendingConfirmation = BookshelfEditingConfirmation.CreateShelf(
                            memberName = member.name,
                            shelfName = dialog.name,
                            mutation = BookshelfMutation.CreateShelf(
                                member.id,
                                dialog.name,
                                BookshelfMutationExpectation(member.name, requireNotNull(current.createShelfCount)),
                            ),
                        ),
                    )
                }
            }
            is BookshelfEditingDialog.AddItem -> {
                val member = current.members.find { it.id == dialog.memberId }
                val shelf = dialog.shelfNo?.let { shelfNo -> current.addItemShelves.find { it.shelfNo == shelfNo } }
                when {
                    member == null -> current.copy(inputError = "対象メンバーを選択してください")
                    current.addItemShelvesLoadedForMemberId != member.id -> current.copy(inputError = "本棚を読み込んでいます")
                    current.addItemShelves.isEmpty() -> current.copy(inputError = "先に本棚を作成してください")
                    shelf == null -> current.copy(inputError = "追加先の本棚を選択してください")
                    dialog.memo.length > MAX_MEMO_LENGTH -> current.copy(inputError = "資料メモは${MAX_MEMO_LENGTH}文字以内で入力してください")
                    else -> current.copy(
                        dialog = null,
                        inputError = null,
                        pendingConfirmation = BookshelfEditingConfirmation.AddItem(
                            memberName = member.name,
                            shelfName = shelf.name,
                            title = dialog.title,
                            memo = dialog.memo,
                            mutation = BookshelfMutation.AddItem(
                                member.id,
                                shelf.shelfNo,
                                dialog.tilcod,
                                dialog.memo,
                                BookshelfMutationExpectation(
                                    member.name,
                                    current.addItemShelves.size,
                                    BookshelfExpectedShelf(shelf.shelfNo, shelf.name, shelf.items.size),
                                ),
                            ),
                        ),
                    )
                }
            }
            is BookshelfEditingDialog.EditShelf -> {
                if (!isValidShelfName(dialog.name)) {
                    current.copy(inputError = shelfNameError(dialog.name))
                } else if (dialog.items.any { it.newMemo.length > MAX_MEMO_LENGTH }) {
                    current.copy(inputError = "資料メモは${MAX_MEMO_LENGTH}文字以内で入力してください")
                } else if (dialog.items.map { it.tilcod }.distinct().size != dialog.items.size || dialog.items.size != dialog.target.itemCount) {
                    current.copy(inputError = "本棚の資料構成が一致しません。画面を更新してやり直してください")
                } else if (dialog.name == dialog.target.shelfName && dialog.items.all { it.newMemo == it.originalMemo }) {
                    current.copy(inputError = "変更内容がありません。名前または資料メモを変更してください")
                } else {
                    current.copy(
                        dialog = null,
                        inputError = null,
                        pendingConfirmation = BookshelfEditingConfirmation.EditShelf(
                            target = dialog.target,
                            newName = dialog.name,
                            items = dialog.items,
                            mutation = BookshelfMutation.EditShelf(
                                dialog.target.memberId,
                                dialog.target.shelfNo,
                                dialog.name,
                                dialog.items,
                                dialog.target.expectation(),
                            ),
                        ),
                    )
                }
            }
            null -> current
            }
        }
    }

    fun dismissDialog() {
        var stopObservation = false
        _state.update { current ->
            stopObservation = false
            if (current.processing) current else {
                stopObservation = current.dialog is BookshelfEditingDialog.AddItem
                current.copy(
                    dialog = null,
                    inputError = null,
                    addItemShelves = if (stopObservation) emptyList() else current.addItemShelves,
                    addItemShelvesLoadedForMemberId = if (stopObservation) null else current.addItemShelvesLoadedForMemberId,
                )
            }
        }
        if (stopObservation) stopAddItemShelfObservation()
    }

    fun dismissConfirmation() {
        var stopObservation = false
        _state.update { current ->
            stopObservation = false
            if (current.processing) current else {
                stopObservation = current.pendingConfirmation is BookshelfEditingConfirmation.AddItem
                current.copy(
                    pendingConfirmation = null,
                    addItemShelves = if (stopObservation) emptyList() else current.addItemShelves,
                    addItemShelvesLoadedForMemberId = if (stopObservation) null else current.addItemShelvesLoadedForMemberId,
                )
            }
        }
        if (stopObservation) stopAddItemShelfObservation()
    }

    /** 最終確認の肯定操作だけがRepositoryへのmutationを開始する。 */
    fun confirmPending() {
        var mutationToSend: BookshelfMutation? = null
        var stopObservation = false
        _state.update { current ->
            mutationToSend = null
            stopObservation = false
            val confirmation = current.pendingConfirmation ?: return@update current
            if (current.processing) return@update current
            val member = current.members.find { it.id == confirmation.mutation.memberId }
            fun reject(message: String): BookshelfEditingUiState {
                stopObservation = confirmation is BookshelfEditingConfirmation.AddItem
                return current.copy(
                    pendingConfirmation = null,
                    errorMessage = message,
                    addItemShelves = if (stopObservation) emptyList() else current.addItemShelves,
                    addItemShelvesLoadedForMemberId = if (stopObservation) null else current.addItemShelvesLoadedForMemberId,
                )
            }
            when {
                member == null -> reject("対象メンバーが見つかりません。内容を確認してからやり直してください")
                confirmation is BookshelfEditingConfirmation.AddItem && member.name != confirmation.memberName ->
                    reject("対象メンバーの名前が変更されました。もう一度選択してください")
                confirmation is BookshelfEditingConfirmation.AddItem &&
                    current.addItemShelvesLoadedForMemberId != confirmation.mutation.memberId ->
                    reject("本棚を読み込めませんでした。もう一度選択してください")
                confirmation is BookshelfEditingConfirmation.AddItem -> {
                    val shelf = current.addItemShelves.find { it.shelfNo == confirmation.mutation.shelfNo }
                    when {
                        shelf == null -> reject("追加先の本棚が見つかりません。もう一度選択してください")
                        shelf.name != confirmation.shelfName -> reject("追加先の本棚名が変更されました。もう一度選択してください")
                        else -> {
                            mutationToSend = confirmation.mutation
                            current.copy(pendingConfirmation = null, processingMutation = confirmation.mutation)
                        }
                    }
                }
                else -> {
                    mutationToSend = confirmation.mutation
                    current.copy(pendingConfirmation = null, processingMutation = confirmation.mutation)
                }
            }
        }
        if (stopObservation) stopAddItemShelfObservation()
        val mutation = mutationToSend ?: return
        scope.launch {
            try {
                val outcome = bookshelfRepository.mutate(mutation)
                _state.update { current ->
                    if (current.processingMutation != mutation) current else current.copy(
                        processingMutation = null,
                        result = BookshelfEditingContentBuilder.resultMessage(outcome),
                    )
                }
                if (mutation is BookshelfMutation.AddItem) clearAddItemShelfObservation()
            } catch (exception: CancellationException) {
                _state.update { current ->
                    if (current.processingMutation == mutation) current.copy(processingMutation = null) else current
                }
                if (mutation is BookshelfMutation.AddItem) clearAddItemShelfObservation()
                throw exception
            } catch (_: Exception) {
                _state.update { current ->
                    if (current.processingMutation != mutation) current else current.copy(
                        processingMutation = null,
                        errorMessage = "本棚の操作を完了できませんでした。通信状態を確認してください。",
                    )
                }
                if (mutation is BookshelfMutation.AddItem) clearAddItemShelfObservation()
            }
        }
    }

    fun clearResult() {
        _state.update { it.copy(result = null) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun close() {
        membersJob.cancel()
        addItemShelvesJob?.cancel()
        createShelvesJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun clearAddItemShelfObservation() {
        stopAddItemShelfObservation()
        _state.update { current ->
            current.copy(
                addItemShelves = emptyList(),
                addItemShelvesLoadedForMemberId = null,
            )
        }
    }

    private fun stopAddItemShelfObservation() {
        addItemShelvesJob?.cancel()
        addItemShelvesJob = null
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

private fun BookshelfShelfTarget.expectation() = BookshelfMutationExpectation(
    memberName = memberName,
    shelfCount = shelfCount,
    shelf = BookshelfExpectedShelf(shelfNo, shelfName, itemCount),
)

private fun BookshelfItemTarget.expectation(memberName: String) = BookshelfMutationExpectation(
    memberName = memberName,
    shelfCount = shelfCount,
    shelf = BookshelfExpectedShelf(shelfNo, shelfName, itemCount),
    item = BookshelfExpectedItem(tilcod, title, memo),
)

/** Android非依存の確認・結果文言。対象の取り違えを単体テストで検証する。 */
object BookshelfEditingContentBuilder {
    fun confirmationTitle(confirmation: BookshelfEditingConfirmation): String = when (confirmation) {
        is BookshelfEditingConfirmation.CreateShelf -> "本棚を作成しますか？"
        is BookshelfEditingConfirmation.AddItem -> "本棚に追加しますか？"
        is BookshelfEditingConfirmation.EditShelf -> "本棚を編集しますか？"
        is BookshelfEditingConfirmation.DeleteItem -> "本棚から資料を削除しますか？"
        is BookshelfEditingConfirmation.DeleteShelf -> "本棚を削除しますか？"
    }

    fun confirmationMessage(confirmation: BookshelfEditingConfirmation): String = when (confirmation) {
        is BookshelfEditingConfirmation.CreateShelf ->
            "対象メンバー：${confirmation.memberName}\n本棚名：${confirmation.shelfName}"
        is BookshelfEditingConfirmation.AddItem ->
            "対象メンバー：${confirmation.memberName}\n本棚：${confirmation.shelfName}\n資料名：${confirmation.title}\nメモ：${confirmation.memo.ifEmpty { "（なし）" }}"
        is BookshelfEditingConfirmation.EditShelf ->
            "対象本棚：${confirmation.target.shelfName}\n新しい名前：${confirmation.newName}\n資料メモ：${confirmation.items.count { it.originalMemo != it.newMemo }}件変更"
        is BookshelfEditingConfirmation.DeleteItem ->
            "資料名：${confirmation.target.title}\n本棚：${confirmation.target.shelfName}\n\nこの資料を本棚から削除しますか？"
        is BookshelfEditingConfirmation.DeleteShelf ->
            "本棚「${confirmation.target.shelfName}」と登録資料${confirmation.target.itemCount}件を削除します。\nこの操作は元に戻せません。"
    }

    fun confirmLabel(confirmation: BookshelfEditingConfirmation): String = when (confirmation) {
        is BookshelfEditingConfirmation.CreateShelf -> "本棚を作成"
        is BookshelfEditingConfirmation.AddItem -> "この本棚に追加"
        is BookshelfEditingConfirmation.EditShelf -> "本棚を編集"
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
            message = outcome.reason.bookshelfLabel() + outcome.diagnosticCode?.let { "\n診断コード: $it" }.orEmpty(),
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
