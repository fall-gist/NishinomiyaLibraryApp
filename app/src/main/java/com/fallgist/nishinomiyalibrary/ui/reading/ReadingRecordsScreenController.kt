package com.fallgist.nishinomiyalibrary.ui.reading

import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartAddSummary
import com.fallgist.nishinomiyalibrary.domain.model.ReservationConfirmation
import com.fallgist.nishinomiyalibrary.domain.repository.CalendarRepository
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReadingRecordRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCartRepository
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkCartAdditionCandidate
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkCartAdditionConfirmationRequest
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkCartAdditionContentBuilder
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkDirectReservationConfirmationRequest
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkDirectReservationContentBuilder
import com.fallgist.nishinomiyalibrary.ui.reservationcart.ReservationCartContentBuilder
import com.fallgist.nishinomiyalibrary.ui.reservationcart.ReservationResultRow
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 読書記録一覧の1行。 */
data class ReadingRow(
    val memberColorHex: String,
    val title: String,
    val loanDateLabel: String,
    val library: String,
    /** 書誌詳細リンク用。空文字列のときは遷移しない。 */
    val tilcod: String = "",
)

data class ReadingRecordsUiState(
    val initialized: Boolean = false,
    val members: List<Member> = emptyList(),
    val selectedMemberId: Long? = null,
    val query: String = "",
    val rows: List<ReadingRow> = emptyList(),
    /** 検索なし・特定メンバー選択で0件のとき、履歴未有効化の案内を出す。 */
    val showActivationHint: Boolean = false,
    // ------------------------------------------------------------------
    // 複数選択(`docs/design/reading-records-selection.md` §3、`docs/design/selection-mode.md` §3)。
    // 選択キーはtilcod(§3.2)。同じ資料が複数行にあっても選択は1件として扱う。
    // ------------------------------------------------------------------
    val selectionMode: Boolean = false,
    val selectedTilcods: Set<String> = emptySet(),
    /** 一斉直接予約の受取館選択(`PickupLibrarySelector`)に使う。 */
    val libraries: List<Library> = emptyList(),
    /** 確認ダイアログの受取館の初期値(`SettingsStore.defaultCalendarLibrary`)。 */
    val defaultPickupLibraryCode: String = "",
    val bulkCartAdditionConfirmation: BulkCartAdditionConfirmationRequest? = null,
    val bulkCartAdditionProcessing: Boolean = false,
    val bulkCartAdditionResultMessage: String? = null,
    val bulkCartAdditionErrorMessage: String? = null,
    val bulkDirectReservationConfirmation: BulkDirectReservationConfirmationRequest? = null,
    val bulkDirectReservationProcessing: Boolean = false,
    /** 件ごとの成否。既存の予約結果表示(`ReservationResultsDialog`)を再利用する。 */
    val bulkDirectReservationResults: List<ReservationResultRow> = emptyList(),
    val bulkDirectReservationErrorMessage: String? = null,
) {
    /** カートへ追加・直接予約のどちらかが処理中の間は、もう一方も選択操作も行わせない(蔵書検索と同じ規則)。 */
    val anyBulkActionProcessing: Boolean get() = bulkCartAdditionProcessing || bulkDirectReservationProcessing
    val canRequestBulkCartAddition: Boolean get() = !anyBulkActionProcessing && selectedTilcods.isNotEmpty()
    val canRequestBulkDirectReservation: Boolean get() = !anyBulkActionProcessing && selectedTilcods.isNotEmpty()
}

/** 読書記録の表示行を組み立てる純関数。リポジトリが新しい順を保証する前提で表示整形のみ行う。 */
object ReadingRecordsContentBuilder {
    private const val FALLBACK_COLOR = "#6E675C"
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/M/d", Locale.JAPANESE)

    fun build(members: List<Member>, records: List<ReadingRecord>): List<ReadingRow> {
        val colorOf = members.associate { it.id to it.colorHex }
        return records.map { record ->
            ReadingRow(
                memberColorHex = colorOf[record.memberId]?.takeIf { it.isNotBlank() } ?: FALLBACK_COLOR,
                title = record.title,
                loanDateLabel = dateFormatter.format(record.loanDate),
                library = record.library,
                tilcod = record.tilcod,
            )
        }
    }

    /**
     * 選択済みキーに対応する一斉操作の候補を組み立てる純関数
     * (`docs/design/reading-records-selection.md` §3.2)。
     * **同じ資料(tilcod)が複数行にあっても、候補は1件だけ渡す**(distinctByで重複を除く)。
     * 一覧に存在しなくなったキーは無視する(蔵書検索の§4.3と同じ規則)。
     * writerLineは読書記録が持たないためnullを渡す(design §3.5)。
     */
    fun cartAdditionCandidates(rows: List<ReadingRow>, selectedTilcods: Set<String>): List<BulkCartAdditionCandidate> =
        rows.filter { it.tilcod in selectedTilcods }
            .distinctBy { it.tilcod }
            .map { row -> BulkCartAdditionCandidate(row.tilcod, row.title, null) }
}

/**
 * 読書記録画面のController。検索欄の入力はそのままリポジトリの正規化検索へ渡す。
 * 複数選択・一斉カート追加・一斉直接予約は蔵書検索の[com.fallgist.nishinomiyalibrary.ui.search.SearchScreenController]
 * と同じ作りを写している(`docs/design/reading-records-selection.md` §3.1)。
 */
class ReadingRecordsScreenController(
    private val familyRepository: FamilyRepository,
    private val readingRecordRepository: ReadingRecordRepository,
    private val cartRepository: ReservationCartRepository = NoOpCartRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** 一斉直接予約の受取館選択に使う。既定館はcalendarRepository.librariesと同じ一覧から選ぶ。 */
    private val calendarRepository: CalendarRepository = NoOpCalendarRepository,
    /** 一斉直接予約の確認ダイアログの受取館初期値(`SettingsStore.defaultCalendarLibrary`)。 */
    private val defaultPickupLibraryCode: Flow<String> = flowOf(""),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(ReadingRecordsUiState(libraries = calendarRepository.libraries))
    val state: StateFlow<ReadingRecordsUiState> = _state

    // _stateは、一覧の絞り込み(selectedMemberId/queryのflatMapLatestのcollect)と、選択操作
    // (enterSelectionMode等、UIスレッドからの呼び出し)、一斉操作(scope.launch)の複数の経路から
    // 更新される。以前はflatMapLatestのcollectが`_state.value = newState`で状態全体を置き換えており、
    // 絞り込みが変わるたびに選択(selectionMode/selectedTilcods等)が失われるlost updateがあった
    // (`docs/design/reading-records-selection.md` §3.4、他の4コントローラで踏んだものと同型)。
    // **必ず`update {}`(CASループ)を使うこと。`_state.value = ...`を書いてはならない。**

    private val selectedMemberId = MutableStateFlow<Long?>(null)
    private val query = MutableStateFlow("")
    private val observationJob: Job

    init {
        @OptIn(ExperimentalCoroutinesApi::class)
        observationJob = scope.launch {
            combine(selectedMemberId, query) { memberId, text -> memberId to text }
                .flatMapLatest { (memberId, text) ->
                    val recordsFlow = if (text.isBlank()) {
                        readingRecordRepository.records(memberId)
                    } else {
                        readingRecordRepository.search(text, memberId)
                    }
                    combine(familyRepository.members(), recordsFlow) { members, records ->
                        val effectiveSelection = memberId?.takeIf { id -> members.any { it.id == id } }
                        ReadingRecordsUiState(
                            initialized = true,
                            members = members,
                            selectedMemberId = effectiveSelection,
                            query = text,
                            rows = ReadingRecordsContentBuilder.build(members, records),
                            showActivationHint = text.isBlank() && effectiveSelection != null && records.isEmpty(),
                        )
                    }
                }
                .collect { built ->
                    // 一覧に関する項目だけを差し込む。選択・一斉操作関連の項目には触れない
                    // (`docs/design/reading-records-selection.md` §3.4)。
                    _state.update { current ->
                        current.copy(
                            initialized = built.initialized,
                            members = built.members,
                            selectedMemberId = built.selectedMemberId,
                            query = built.query,
                            rows = built.rows,
                            showActivationHint = built.showActivationHint,
                        )
                    }
                }
        }
        scope.launch {
            defaultPickupLibraryCode.collect { code ->
                _state.update { it.copy(defaultPickupLibraryCode = code) }
            }
        }
    }

    fun selectMember(memberId: Long?) {
        selectedMemberId.value = memberId
    }

    fun updateQuery(text: String) {
        query.value = text
    }

    // ------------------------------------------------------------------
    // 複数選択(`docs/design/selection-mode.md` §3.3、`reading-records-selection.md` §3.2)。
    // 選択キーはtilcod。同じ資料の行は連動する。
    // ------------------------------------------------------------------

    /**
     * 書誌タイルの長押し。選択モードに入り、その資料(tilcod)を選択する。
     * 資料番号が空、または現在の一覧に存在しないtilcodでは何もしない。
     */
    fun enterSelectionMode(tilcod: String) {
        if (state.value.anyBulkActionProcessing || tilcod.isBlank()) return
        if (state.value.rows.none { it.tilcod == tilcod }) return
        _state.update { it.copy(selectionMode = true, selectedTilcods = it.selectedTilcods + tilcod) }
    }

    /** 選択モード中のタップ。処理中・選択モード外では働かない。 */
    fun toggleSelection(tilcod: String) {
        if (state.value.anyBulkActionProcessing || !state.value.selectionMode) return
        _state.update { current ->
            current.copy(
                selectedTilcods = if (tilcod in current.selectedTilcods) {
                    current.selectedTilcods - tilcod
                } else {
                    current.selectedTilcods + tilcod
                },
            )
        }
    }

    /** 「選択解除」・戻るキー・他画面への遷移で選択モードから抜ける(`selection-mode.md` §3.8)。選択も空にする。 */
    fun exitSelectionMode() {
        _state.update { it.copy(selectionMode = false, selectedTilcods = emptySet()) }
    }

    /**
     * 選択・選択モードを空にする。一斉本棚追加は
     * [com.fallgist.nishinomiyalibrary.ui.shelf.BookshelfEditingUiController]が処理するため、
     * 完了(成否を問わない)をこの画面へ伝える手段としてここへ完了時コールバックから呼ばれる。
     */
    fun clearSelection() {
        _state.update { it.copy(selectedTilcods = emptySet(), selectionMode = false) }
    }

    // ------------------------------------------------------------------
    // 一斉カート追加。蔵書検索の同名処理と同じ流儀。
    // ------------------------------------------------------------------

    fun requestBulkCartAddition(candidates: List<BulkCartAdditionCandidate>) {
        if (state.value.anyBulkActionProcessing || candidates.isEmpty()) return
        _state.update { it.copy(bulkCartAdditionConfirmation = BulkCartAdditionConfirmationRequest(candidates)) }
    }

    fun selectBulkCartAdditionMember(memberId: Long) {
        val request = state.value.bulkCartAdditionConfirmation ?: return
        if (state.value.members.none { it.id == memberId }) return
        _state.update { it.copy(bulkCartAdditionConfirmation = request.copy(selectedMemberId = memberId)) }
    }

    fun dismissBulkCartAdditionConfirmation() {
        if (!state.value.bulkCartAdditionProcessing) {
            _state.update { it.copy(bulkCartAdditionConfirmation = null) }
        }
    }

    fun confirmBulkCartAddition() {
        val request = state.value.bulkCartAdditionConfirmation ?: return
        if (state.value.anyBulkActionProcessing || !request.canConfirm) return
        // 一覧に存在しなくなった対象は無視する。確認(このメソッド呼び出し)と同じ同期区間で
        // 一覧を読む(`docs/design/search-result-reset.md` §3.2と同じ理由)。
        val currentTilcods = state.value.rows.map { it.tilcod }.toSet()
        val effectiveRequest = request.copy(candidates = request.candidates.filter { it.tilcod in currentTilcods })
        _state.update { it.copy(bulkCartAdditionConfirmation = null, bulkCartAdditionProcessing = true) }
        scope.launch {
            try {
                val summary = if (effectiveRequest.candidates.isEmpty()) {
                    ReservationCartAddSummary(added = 0, skipped = 0)
                } else {
                    cartRepository.addToCart(BulkCartAdditionContentBuilder.targets(effectiveRequest))
                }
                _state.update {
                    it.copy(
                        selectedTilcods = emptySet(),
                        // 一斉カート追加の完了(成功・失敗のどちらでも)で選択モードから抜ける
                        // (`docs/design/selection-mode.md` §3.8-3)。通信例外(下のcatch)は含めない。
                        selectionMode = false,
                        bulkCartAdditionProcessing = false,
                        bulkCartAdditionResultMessage = BulkCartAdditionContentBuilder.resultMessage(summary),
                    )
                }
            } catch (exception: CancellationException) {
                _state.update { it.copy(bulkCartAdditionProcessing = false) }
                throw exception
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        bulkCartAdditionProcessing = false,
                        bulkCartAdditionErrorMessage = "カートへ追加できませんでした。もう一度お試しください。",
                    )
                }
            }
        }
    }

    fun clearBulkCartAdditionResult() {
        _state.update { it.copy(bulkCartAdditionResultMessage = null) }
    }

    fun clearBulkCartAdditionError() {
        _state.update { it.copy(bulkCartAdditionErrorMessage = null) }
    }

    // ------------------------------------------------------------------
    // 一斉直接予約。カートを経由しない(`docs/design/bulk-selection-followup.md` §5.2)。
    // サイトに実データを作る、取り返しのつかない操作である。明示操作・最終確認の後にだけ通信する。
    // ------------------------------------------------------------------

    fun requestBulkDirectReservation(candidates: List<BulkCartAdditionCandidate>) {
        if (state.value.anyBulkActionProcessing || candidates.isEmpty()) return
        val snapshot = state.value
        val initialCode = snapshot.libraries.firstOrNull { it.code == snapshot.defaultPickupLibraryCode }?.code
            ?: snapshot.libraries.firstOrNull()?.code.orEmpty()
        _state.update {
            it.copy(
                bulkDirectReservationConfirmation = BulkDirectReservationConfirmationRequest(
                    candidates = candidates,
                    pickupLibraryCode = initialCode,
                ),
            )
        }
    }

    fun selectBulkDirectReservationMember(memberId: Long) {
        val request = state.value.bulkDirectReservationConfirmation ?: return
        if (state.value.members.none { it.id == memberId }) return
        _state.update { it.copy(bulkDirectReservationConfirmation = request.copy(selectedMemberId = memberId)) }
    }

    fun selectBulkDirectReservationPickupLibrary(code: String) {
        val request = state.value.bulkDirectReservationConfirmation ?: return
        if (state.value.libraries.none { it.code == code }) return
        _state.update { it.copy(bulkDirectReservationConfirmation = request.copy(pickupLibraryCode = code)) }
    }

    fun dismissBulkDirectReservationConfirmation() {
        if (!state.value.bulkDirectReservationProcessing) {
            _state.update { it.copy(bulkDirectReservationConfirmation = null) }
        }
    }

    /**
     * 確認ダイアログの「予約する」。ここが唯一の通信開始点であり、UIの明示操作(このメソッド呼び出し)
     * の後にだけ動く。confirmCartは使わない(カート内の全項目を巻き込むため)。
     */
    fun confirmBulkDirectReservation() {
        val request = state.value.bulkDirectReservationConfirmation ?: return
        if (state.value.anyBulkActionProcessing || !request.canConfirm) return
        val currentTilcods = state.value.rows.map { it.tilcod }.toSet()
        val effectiveRequest = request.copy(candidates = request.candidates.filter { it.tilcod in currentTilcods })
        _state.update { it.copy(bulkDirectReservationConfirmation = null, bulkDirectReservationProcessing = true) }
        scope.launch {
            try {
                if (effectiveRequest.candidates.isEmpty()) {
                    _state.update { it.copy(bulkDirectReservationProcessing = false) }
                    return@launch
                }
                val targets = BulkDirectReservationContentBuilder.targets(effectiveRequest)
                val confirmation = ReservationConfirmation(effectiveRequest.pickupLibraryCode, now())
                val result = cartRepository.reserveNow(targets, confirmation)
                _state.update {
                    it.copy(
                        selectedTilcods = emptySet(),
                        // 一斉直接予約の完了(成功・失敗のどちらでも)で選択モードから抜ける
                        // (`docs/design/selection-mode.md` §3.8-3)。通信例外(下のcatch)は含めない。
                        selectionMode = false,
                        bulkDirectReservationProcessing = false,
                        bulkDirectReservationResults = ReservationCartContentBuilder.resultRows(result),
                    )
                }
            } catch (exception: CancellationException) {
                _state.update { it.copy(bulkDirectReservationProcessing = false) }
                throw exception
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        bulkDirectReservationProcessing = false,
                        bulkDirectReservationErrorMessage = "予約できませんでした。もう一度お試しください。",
                    )
                }
            }
        }
    }

    fun clearBulkDirectReservationResults() {
        _state.update { it.copy(bulkDirectReservationResults = emptyList()) }
    }

    fun clearBulkDirectReservationError() {
        _state.update { it.copy(bulkDirectReservationErrorMessage = null) }
    }

    fun close() {
        observationJob.cancel()
        scope.coroutineContext[Job]?.cancel()
    }
}

/**
 * [ReadingRecordsScreenController]の既定引数用。cartRepositoryを渡さない既存呼び出しを壊さないためのno-op実装。
 * 一斉カート追加・一斉直接予約を使わない呼び出し(既存テスト等)では呼ばれない。
 */
private object NoOpCartRepository : ReservationCartRepository {
    override fun cartItems() = flowOf(emptyList<com.fallgist.nishinomiyalibrary.domain.model.ReservationCartItem>())
    override suspend fun addToCart(target: com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget) = Unit
    override suspend fun addToCart(targets: List<com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget>) =
        ReservationCartAddSummary(added = 0, skipped = 0)
    override suspend fun removeFromCart(cartItemId: Long) = Unit
    override suspend fun removeFromCart(cartItemIds: List<Long>) = Unit
    override suspend fun clearCart() = Unit
    override suspend fun confirmCart(confirmation: ReservationConfirmation) =
        com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult(emptyList())
    override suspend fun reserveNow(
        target: com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget,
        confirmation: ReservationConfirmation,
    ) = com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult(emptyList())
    override suspend fun reserveNow(
        targets: List<com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget>,
        confirmation: ReservationConfirmation,
    ) = com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult(emptyList())
}

/**
 * [ReadingRecordsScreenController]の既定引数用。calendarRepositoryを渡さない既存呼び出しを壊さないためのno-op実装。
 * 一斉直接予約を使わない画面(既存テスト等)では呼ばれない。
 */
private object NoOpCalendarRepository : CalendarRepository {
    override fun closedDays(libraryCode: String) = flowOf(emptyList<com.fallgist.nishinomiyalibrary.domain.model.ClosedDay>())
    override suspend fun refreshClosedDays(libraryCode: String) = Unit
    override val libraries: List<Library> = emptyList()
}
