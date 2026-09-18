package com.fallgist.nishinomiyalibrary.ui.shelf

import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutation
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutationExpectation
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfExpectedItem
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfExpectedShelf
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfEditItem
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfContent
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddItem
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddItemOutcome
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddRequest
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
    /**
     * 検索・新着からの一斉本棚追加(`docs/design/bulk-bookshelf-add.md` §5.2)。
     * メンバー選択と本棚監視の仕組みは[AddItem]と共有するが、メモ欄は持たない(一斉追加は常に空文字)。
     */
    data class BulkAddItems(
        val items: List<BookshelfBulkAddItem>,
        val memberId: Long? = null,
        val shelfNo: Int? = null,
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

enum class BookshelfEditingResultKind { APPLIED, ALREADY_REGISTERED, UNKNOWN, FAILURE, NOT_ATTEMPTED }

data class BookshelfEditingResultMessage(
    val title: String,
    val message: String,
    val kind: BookshelfEditingResultKind,
)

/** 一斉本棚追加の最終確認(`docs/design/bulk-bookshelf-add.md` §5.2)。単件の[BookshelfEditingConfirmation]とは
 * 別建てにする。1件の[BookshelfMutation]に対応しないため既存のsealed interfaceには載せない。 */
data class BookshelfBulkAddConfirmation(
    val memberName: String,
    val shelfName: String,
    val request: BookshelfBulkAddRequest,
) {
    val itemCount: Int get() = request.items.size
}

/** 一斉本棚追加の処理中に出す進捗(`docs/design/bulk-bookshelf-add.md` §5.3「N件目/M件」)。 */
data class BookshelfBulkAddProgress(val completed: Int, val total: Int)

/** 一斉本棚追加の結果ダイアログの1行。 */
data class BookshelfBulkAddResultRow(val title: String, val message: BookshelfEditingResultMessage)

data class BookshelfBulkAddResultSummary(
    val rows: List<BookshelfBulkAddResultRow>,
    val localRefreshRequired: Boolean,
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
    // ------------------------------------------------------------------
    // 一斉本棚追加(`docs/design/bulk-bookshelf-add.md` §5、検索・新着からの複数書誌追加)
    // ------------------------------------------------------------------
    val bulkAddPendingConfirmation: BookshelfBulkAddConfirmation? = null,
    val bulkAddProgress: BookshelfBulkAddProgress? = null,
    val bulkAddResults: BookshelfBulkAddResultSummary? = null,
) {
    /** 単件の処理中・一斉追加の処理中のどちらでも真になる(§5.4「処理中フラグも無効化条件に含める」)。 */
    val processing: Boolean get() = processingMutation != null || bulkAddProgress != null
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

    /**
     * 一斉本棚追加の完了(成否を問わない)を呼び出し元(検索・新着のController)へ伝えるコールバック
     * (`docs/design/bulk-bookshelf-add.md` §5.4)。[requestBulkAddItems]で受け取り、[completeBulkAdd]
     * (通信が実際に始まった後、またはその直前の再検証で失敗した後)でのみ呼ぶ。
     * 確認前の「戻る」操作では呼ばない(選択を残したままにするため、他の一斉操作の確認ダイアログと同じ扱い)。
     */
    private var bulkAddCompletionCallback: (() -> Unit)? = null

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

    /**
     * 検索・新着からの一斉本棚追加を開始する(`docs/design/bulk-bookshelf-add.md` §5.2)。
     * [onCompleted]は、実際に送信が始まった後(成功・失敗・打ち切りを問わない)にだけ呼ぶ。
     * ダイアログを開けなかった場合(処理中・メンバー不在)は何も始まっていないため呼ばない。
     */
    fun requestBulkAddItems(items: List<BookshelfBulkAddItem>, onCompleted: () -> Unit) {
        if (items.isEmpty()) return
        var opened = false
        _state.update { current ->
            opened = false
            if (current.processing || current.members.isEmpty()) current else {
                opened = true
                current.copy(
                    dialog = BookshelfEditingDialog.BulkAddItems(items = items),
                    addItemShelves = emptyList(),
                    addItemShelvesLoadedForMemberId = null,
                    inputError = null,
                )
            }
        }
        if (opened) {
            stopAddItemShelfObservation()
            bulkAddCompletionCallback = onCompleted
        }
    }

    /** [BookshelfEditingDialog.AddItem]・[BookshelfEditingDialog.BulkAddItems]のどちらでもメンバーIDを取り出す。 */
    private fun BookshelfEditingDialog?.addItemMemberId(): Long? = when (this) {
        is BookshelfEditingDialog.AddItem -> memberId
        is BookshelfEditingDialog.BulkAddItems -> memberId
        else -> null
    }

    fun selectAddItemMember(memberId: Long) {
        var selected = false
        _state.update { current ->
            selected = false
            val dialog = current.dialog
            val applicable = dialog is BookshelfEditingDialog.AddItem || dialog is BookshelfEditingDialog.BulkAddItems
            if (current.processing || !applicable || current.members.none { it.id == memberId }) current else {
                selected = true
                val updatedDialog = when (dialog) {
                    is BookshelfEditingDialog.AddItem -> dialog.copy(memberId = memberId, shelfNo = null)
                    is BookshelfEditingDialog.BulkAddItems -> dialog.copy(memberId = memberId, shelfNo = null)
                    else -> dialog
                }
                current.copy(
                    dialog = updatedDialog,
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
                    val dialogMemberId = current.dialog.addItemMemberId()
                    val pending = current.pendingConfirmation as? BookshelfEditingConfirmation.AddItem
                    val bulkPendingMemberId = current.bulkAddPendingConfirmation?.request?.memberId
                    if (dialogMemberId == memberId || pending?.mutation?.memberId == memberId || bulkPendingMemberId == memberId) {
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
            val dialog = current.dialog
            val memberId = dialog.addItemMemberId()
            if (current.processing || memberId == null || current.addItemShelves.none { it.shelfNo == shelfNo }) current
            else when (dialog) {
                is BookshelfEditingDialog.AddItem -> current.copy(dialog = dialog.copy(shelfNo = shelfNo), inputError = null)
                is BookshelfEditingDialog.BulkAddItems -> current.copy(dialog = dialog.copy(shelfNo = shelfNo), inputError = null)
                else -> current
            }
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

    /**
     * 資料削除の確認へ進む。本棚編集ダイアログの資料行から呼ばれる想定のため、
     * 開いていた編集ダイアログ(名前・メモの入力中の内容)は確認へ切り替わる時点で閉じる。
     * 資料削除は編集の一括更新とは別のPOST経路であり、入力中のメモを一緒に送ることはない。
     */
    fun requestDeleteItem(target: BookshelfItemTarget) {
        _state.update { current ->
            if (current.processing) current else current.copy(
                dialog = null,
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
                // 一斉追加にメモ欄は無い(`docs/design/bulk-bookshelf-add.md` §5.2)。呼ばれても何もしない。
                is BookshelfEditingDialog.BulkAddItems -> dialog
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
            is BookshelfEditingDialog.BulkAddItems -> {
                val member = current.members.find { it.id == dialog.memberId }
                val shelf = dialog.shelfNo?.let { shelfNo -> current.addItemShelves.find { it.shelfNo == shelfNo } }
                when {
                    member == null -> current.copy(inputError = "対象メンバーを選択してください")
                    current.addItemShelvesLoadedForMemberId != member.id -> current.copy(inputError = "本棚を読み込んでいます")
                    current.addItemShelves.isEmpty() -> current.copy(inputError = "先に本棚を作成してください")
                    shelf == null -> current.copy(inputError = "追加先の本棚を選択してください")
                    else -> current.copy(
                        dialog = null,
                        inputError = null,
                        bulkAddPendingConfirmation = BookshelfBulkAddConfirmation(
                            memberName = member.name,
                            shelfName = shelf.name,
                            request = BookshelfBulkAddRequest(
                                memberId = member.id,
                                shelfNo = shelf.shelfNo,
                                items = dialog.items,
                                confirmed = BookshelfMutationExpectation(
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
                stopObservation = current.dialog is BookshelfEditingDialog.AddItem || current.dialog is BookshelfEditingDialog.BulkAddItems
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

    // ------------------------------------------------------------------
    // 一斉本棚追加(`docs/design/bulk-bookshelf-add.md` §5.2〜§5.4)
    // ------------------------------------------------------------------

    /** 確認ダイアログの「戻る」。選択(検索・新着側)は残したままにするため、完了通知は行わない。 */
    fun dismissBulkAddConfirmation() {
        var stopObservation = false
        _state.update { current ->
            stopObservation = false
            if (current.processing) current else {
                stopObservation = current.bulkAddPendingConfirmation != null
                current.copy(
                    bulkAddPendingConfirmation = null,
                    addItemShelves = if (stopObservation) emptyList() else current.addItemShelves,
                    addItemShelvesLoadedForMemberId = if (stopObservation) null else current.addItemShelvesLoadedForMemberId,
                )
            }
        }
        if (stopObservation) stopAddItemShelfObservation()
    }

    /**
     * 一斉本棚追加の最終確認の肯定操作。単件の[confirmPending]と同じく、確認時の値を現在の状態で
     * 再検証してから[BookshelfRepository.addItems]を呼ぶ(`docs/design/bulk-bookshelf-add.md` §5.2)。
     * 再検証で弾いた場合も含め、通信を試みた(または試みるはずだった)経路に入ったら必ず
     * [completeBulkAdd]で呼び出し元へ完了を伝える(§5.4「成否を問わず選択を空にする」)。
     */
    fun confirmBulkAdd() {
        var requestToSend: BookshelfBulkAddRequest? = null
        var stopObservation = false
        var rejected = false
        _state.update { current ->
            requestToSend = null
            stopObservation = false
            rejected = false
            val confirmation = current.bulkAddPendingConfirmation ?: return@update current
            if (current.processing) return@update current
            val member = current.members.find { it.id == confirmation.request.memberId }
            fun reject(message: String): BookshelfEditingUiState {
                stopObservation = true
                rejected = true
                return current.copy(
                    bulkAddPendingConfirmation = null,
                    errorMessage = message,
                    addItemShelves = emptyList(),
                    addItemShelvesLoadedForMemberId = null,
                )
            }
            when {
                member == null -> reject("対象メンバーが見つかりません。内容を確認してからやり直してください")
                member.name != confirmation.memberName -> reject("対象メンバーの名前が変更されました。もう一度選択してください")
                current.addItemShelvesLoadedForMemberId != confirmation.request.memberId ->
                    reject("本棚を読み込めませんでした。もう一度選択してください")
                else -> {
                    val shelf = current.addItemShelves.find { it.shelfNo == confirmation.request.shelfNo }
                    when {
                        shelf == null -> reject("追加先の本棚が見つかりません。もう一度選択してください")
                        shelf.name != confirmation.shelfName -> reject("追加先の本棚名が変更されました。もう一度選択してください")
                        else -> {
                            requestToSend = confirmation.request
                            current.copy(
                                bulkAddPendingConfirmation = null,
                                bulkAddProgress = BookshelfBulkAddProgress(0, confirmation.request.items.size),
                            )
                        }
                    }
                }
            }
        }
        if (stopObservation) stopAddItemShelfObservation()
        if (rejected) completeBulkAdd()
        val request = requestToSend ?: return
        scope.launch {
            try {
                val result = bookshelfRepository.addItems(request) { completed, total ->
                    _state.update { it.copy(bulkAddProgress = BookshelfBulkAddProgress(completed, total)) }
                }
                _state.update { current ->
                    current.copy(
                        bulkAddProgress = null,
                        bulkAddResults = BookshelfBulkAddResultSummary(
                            rows = result.items.map { itemResult ->
                                BookshelfBulkAddResultRow(
                                    title = itemResult.item.title,
                                    message = BookshelfEditingContentBuilder.bulkAddResultMessage(itemResult.outcome),
                                )
                            },
                            localRefreshRequired = result.localRefreshRequired,
                        ),
                    )
                }
                clearAddItemShelfObservation()
            } catch (exception: CancellationException) {
                _state.update { it.copy(bulkAddProgress = null) }
                clearAddItemShelfObservation()
                throw exception
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        bulkAddProgress = null,
                        errorMessage = "本棚への追加を完了できませんでした。通信状態を確認してください。",
                    )
                }
                clearAddItemShelfObservation()
            } finally {
                completeBulkAdd()
            }
        }
    }

    fun clearBulkAddResults() {
        _state.update { it.copy(bulkAddResults = null) }
    }

    /** 一斉本棚追加が(成否を問わず)終わったことを呼び出し元へ伝える。1回きりの通知にするため呼んだら破棄する。 */
    private fun completeBulkAdd() {
        val callback = bulkAddCompletionCallback
        bulkAddCompletionCallback = null
        callback?.invoke()
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
            "資料名：${confirmation.target.title}\n本棚：${confirmation.target.shelfName}\n\nこの資料を本棚から削除しますか？\n" +
                "編集ダイアログで入力中の本棚名・資料メモは保存されません。"
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
            message = appendRefreshGuidance("この資料はすでに選択した本棚に登録されています", outcome.localRefreshRequired),
            kind = BookshelfEditingResultKind.ALREADY_REGISTERED,
        )
        BookshelfMutationOutcome.Unknown -> BookshelfEditingResultMessage(
            title = "処理結果を確認できません",
            message = appendGuidance("処理結果を確認できません。自動では再送しません。", ResultGuidance.CHECK_RESULT),
            kind = BookshelfEditingResultKind.UNKNOWN,
        )
        is BookshelfMutationOutcome.Failure -> BookshelfEditingResultMessage(
            title = "本棚の操作を完了できませんでした",
            message = appendGuidance(
                outcome.reason.bookshelfLabel() + outcome.diagnosticCode?.let { "\n診断コード: $it" }.orEmpty(),
                outcome.reason.resultGuidance(),
            ),
            kind = BookshelfEditingResultKind.FAILURE,
        )
    }

    /**
     * 一斉本棚追加の件ごとの結果文言(`docs/design/bulk-bookshelf-add.md` §5.3の表どおり)。
     * 単件の[resultMessage]と異なり、行ごとには「本棚を更新して〜」等の案内を付けない
     * (`localRefreshRequired`は一斉分をまとめて1回だけ、結果一覧の末尾に付記する。§5.3)。
     */
    fun bulkAddResultMessage(outcome: BookshelfBulkAddItemOutcome): BookshelfEditingResultMessage = when (outcome) {
        BookshelfBulkAddItemOutcome.Added -> BookshelfEditingResultMessage(
            title = "追加しました",
            message = "追加しました",
            kind = BookshelfEditingResultKind.APPLIED,
        )
        BookshelfBulkAddItemOutcome.AlreadyRegistered -> BookshelfEditingResultMessage(
            title = "すでに登録済みです",
            message = "すでにこの本棚に登録されています",
            kind = BookshelfEditingResultKind.ALREADY_REGISTERED,
        )
        BookshelfBulkAddItemOutcome.Unknown -> BookshelfEditingResultMessage(
            title = "処理結果を確認できません",
            message = "追加できたか確認できません。本棚画面でご確認ください",
            kind = BookshelfEditingResultKind.UNKNOWN,
        )
        is BookshelfBulkAddItemOutcome.Failed -> BookshelfEditingResultMessage(
            title = "追加できませんでした",
            message = outcome.reason.bookshelfLabel(),
            kind = BookshelfEditingResultKind.FAILURE,
        )
        BookshelfBulkAddItemOutcome.NotAttempted -> BookshelfEditingResultMessage(
            title = "処理を中断しました",
            message = "前の資料で処理を中断したため、追加していません",
            kind = BookshelfEditingResultKind.NOT_ATTEMPTED,
        )
    }

    private fun successMessage(message: String, refreshRequired: Boolean) = BookshelfEditingResultMessage(
        title = "本棚へ反映しました",
        message = appendRefreshGuidance(message, refreshRequired),
        kind = BookshelfEditingResultKind.APPLIED,
    )

    /**
     * サイトへは反映済みだが端末の表示（本棚一覧のキャッシュ）が古い可能性がある場合の案内。
     * サイト側は成功済みのため、再試行ではなく結果確認を促す（B分類）。
     */
    private fun appendRefreshGuidance(message: String, refreshRequired: Boolean): String =
        if (refreshRequired) {
            appendGuidance("$message\nサイトへは反映済みですが、端末の表示が古い可能性があります。", ResultGuidance.CHECK_RESULT)
        } else {
            message
        }

    private fun appendGuidance(message: String, guidance: ResultGuidance): String = when (guidance) {
        ResultGuidance.RETRY -> "$message\n本棚を更新してから、もう一度お試しください。"
        ResultGuidance.CHECK_RESULT -> "$message\n本棚を更新して、結果をご確認ください。"
        ResultGuidance.NONE -> message
    }
}

/**
 * 結果ダイアログの末尾に付ける案内の分類。
 * RETRY: 送信前に停止した、またはサイトが明確に拒否した（サイト側の状態は変化していない）→再試行を促してよい。
 * CHECK_RESULT: 送信後の結果が不明、またはサイト側はすでに成功済み→再試行させず、まず結果確認を促す。
 * NONE: サイト構造変更の疑いなど、自動再試行の案内自体を出さないと設計で決定済み。
 */
private enum class ResultGuidance { RETRY, CHECK_RESULT, NONE }

/** [FailureReason] を案内の分類へ振り分ける。新しい理由が増えたらここでコンパイルエラーになる。 */
private fun FailureReason.resultGuidance(): ResultGuidance = when (this) {
    FailureReason.NETWORK -> ResultGuidance.RETRY
    FailureReason.SITE_MAINTENANCE -> ResultGuidance.RETRY
    FailureReason.AUTH -> ResultGuidance.RETRY
    FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT -> ResultGuidance.RETRY
    FailureReason.REJECTED_BY_SITE -> ResultGuidance.RETRY
    FailureReason.RESERVATION_LIMIT_EXCEEDED -> ResultGuidance.RETRY
    FailureReason.INVALID_PICKUP_LIBRARY -> ResultGuidance.RETRY
    FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE -> ResultGuidance.CHECK_RESULT
    FailureReason.SITE_RESPONSE_CHANGED -> ResultGuidance.NONE
}

private fun FailureReason.bookshelfLabel(): String = when (this) {
    FailureReason.AUTH -> "メンバーの認証に失敗しました"
    FailureReason.INVALID_PICKUP_LIBRARY -> "操作内容が無効です"
    FailureReason.REJECTED_BY_SITE -> "図書館サイトが本棚の操作を受け付けませんでした"
    FailureReason.RESERVATION_LIMIT_EXCEEDED -> "本棚の操作を受け付けられませんでした"
    FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT -> "ログイン状態が失効しました"
    FailureReason.SITE_RESPONSE_CHANGED -> "図書館サイトの表示が変更された可能性があるため、安全に停止しました。自動では再試行しません"
    FailureReason.SITE_MAINTENANCE -> "図書館サイトがメンテナンス中です"
    FailureReason.NETWORK -> "通信に失敗しました。接続を確認してください"
    FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE -> "サイト上の本棚の状態が変わったため、操作を停止しました"
}
