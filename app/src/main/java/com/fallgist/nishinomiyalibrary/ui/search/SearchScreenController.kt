package com.fallgist.nishinomiyalibrary.ui.search

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReadingInfo
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartAddSummary
import com.fallgist.nishinomiyalibrary.domain.model.SearchHit
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReadingRecordRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCartRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SearchRepository
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkCartAdditionCandidate
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkCartAdditionConfirmationRequest
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkCartAdditionContentBuilder
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 既読バッジの1人分(「パパ 2025/3」)。 */
data class ReadBadgeEntry(
    val memberName: String,
    val memberColorHex: String,
    val loanMonthLabel: String,
)

/** 検索結果の1行。貸出可否は取得完了までnull。 */
data class SearchResultRow(
    val tilcod: String,
    val title: String,
    val writerLine: String,
    val materialType: String,
    val lendable: Boolean? = null,
    val readEntries: List<ReadBadgeEntry> = emptyList(),
)

data class SearchUiState(
    val suggestions: List<String> = emptyList(),
    val searching: Boolean = false,
    /** 直近に実行した検索キーワード。null は未検索。 */
    val executedQuery: String? = null,
    val totalCount: Int = 0,
    val results: List<SearchResultRow> = emptyList(),
    val hasNext: Boolean = false,
    val loadingMore: Boolean = false,
    val errorMessage: String? = null,
    /** 一斉カート追加(`docs/design/bulk-selection.md` §7)のメンバー選択ダイアログに使う。 */
    val members: List<Member> = emptyList(),
    val selectedCartTilcods: Set<String> = emptySet(),
    val bulkCartAdditionConfirmation: BulkCartAdditionConfirmationRequest? = null,
    val bulkCartAdditionProcessing: Boolean = false,
    val bulkCartAdditionResultMessage: String? = null,
    val bulkCartAdditionErrorMessage: String? = null,
) {
    val canRequestBulkCartAddition: Boolean get() = !bulkCartAdditionProcessing && selectedCartTilcods.isNotEmpty()
}

/** 検索結果・既読バッジの表示を組み立てる純関数。 */
object SearchContentBuilder {
    private const val FALLBACK_COLOR = "#6E675C"
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy/M", Locale.JAPANESE)

    fun resultRows(
        hits: List<SearchHit>,
        members: List<Member>,
        readInfoByTilcod: Map<String, List<ReadingInfo>>,
    ): List<SearchResultRow> = hits.map { hit ->
        SearchResultRow(
            tilcod = hit.tilcod,
            title = hit.title,
            writerLine = hit.writerLine,
            materialType = hit.materialType,
            readEntries = readBadges(members, readInfoByTilcod[hit.tilcod].orEmpty()),
        )
    }

    /** メンバーごとに最新の貸出だけを残し、貸出月の古い順に並べる。 */
    fun readBadges(members: List<Member>, infos: List<ReadingInfo>): List<ReadBadgeEntry> =
        latestPerMember(infos).map { info ->
            val member = members.find { it.id == info.memberId }
            ReadBadgeEntry(
                memberName = member?.name ?: "?",
                memberColorHex = member?.colorHex?.takeIf { it.isNotBlank() } ?: FALLBACK_COLOR,
                loanMonthLabel = monthFormatter.format(info.loanDate),
            )
        }

    private fun latestPerMember(infos: List<ReadingInfo>): List<ReadingInfo> = infos
        .groupBy { it.memberId }
        .map { (_, records) -> records.maxBy { it.loanDate } }
        .sortedBy { it.loanDate }

    /**
     * 選択済みキーに対応する一斉カート追加の候補を組み立てる(`docs/design/bulk-selection.md` §7.4)。
     * 一覧に存在しなくなったキーは無視する(§4.3)。tilcodが空の行は選択対象にならないため、
     * ここでは行の存在確認だけ行う。
     */
    fun cartAdditionCandidates(rows: List<SearchResultRow>, selectedTilcods: Set<String>): List<BulkCartAdditionCandidate> =
        rows.filter { it.tilcod in selectedTilcods }
            .map { row -> BulkCartAdditionCandidate(row.tilcod, row.title, row.writerLine.takeIf(String::isNotBlank)) }
}

/**
 * 蔵書検索画面のController。検索・オートコンプリート・書誌詳細を公式サイトへ都度問い合わせる。
 * 検索欄の入力値は画面ローカルに保持し、ここへは通知のみ渡す(IME合成対策)。
 */
class SearchScreenController(
    private val searchRepository: SearchRepository,
    private val readingRecordRepository: ReadingRecordRepository,
    familyRepository: FamilyRepository,
    private val cartRepository: ReservationCartRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val autocompleteDebounceMillis: Long = 300L,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state

    // _stateは購読側(init、UIスレッドからのtoggleCartSelection等)と、検索・追加読み込み
    // (search/loadMore、fetchLendabilityForの貸出可否取得ループ)の複数のscope.launchから
    // 更新される。検索結果が返るまでの間や貸出可否を1件ずつ取得している間も、利用者は
    // チェックボックスを操作できる。`_state.value = _state.value.copy(...)`は読みと書きの間に
    // 別コルーチンの書きが挟まるとそれを取りこぼす(lost update)。CalendarScreenController・
    // SettingsScreenController・LoanExtensionUiController・NewArrivalsScreenControllerで
    // 実際に踏んだ不具合と同型(docs/handoff.md参照)。
    // **必ず`update {}`(CASループ)を使うこと。`_state.value = ...`を書いてはならない。**

    private val members = MutableStateFlow<List<Member>>(emptyList())
    private var autocompleteJob: Job? = null
    private var searchJob: Job? = null
    private var lendableJob: Job? = null
    private var currentPage = 1

    init {
        scope.launch {
            familyRepository.members().collect { list ->
                members.value = list
                _state.update { it.copy(members = list) }
            }
        }
    }

    /** 一覧行のチェックボックスのタップ(`docs/design/bulk-selection.md` §7.3)。 */
    fun toggleCartSelection(tilcod: String) {
        if (state.value.bulkCartAdditionProcessing) return
        _state.update { current ->
            current.copy(
                selectedCartTilcods = if (tilcod in current.selectedCartTilcods) {
                    current.selectedCartTilcods - tilcod
                } else {
                    current.selectedCartTilcods + tilcod
                },
            )
        }
    }

    /** 「カートへ追加」ボタン。選択済みキーに対応する候補はScreen側で組み立てて渡す。 */
    fun requestBulkCartAddition(candidates: List<BulkCartAdditionCandidate>) {
        if (state.value.bulkCartAdditionProcessing || candidates.isEmpty()) return
        _state.update { it.copy(bulkCartAdditionConfirmation = BulkCartAdditionConfirmationRequest(candidates)) }
    }

    /** 確認ダイアログでのメンバー選択(§7.2、1回の操作につき1人)。 */
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

    /** 確認ダイアログの「追加する」。メンバー未選択では実行しない(§7.2)。 */
    fun confirmBulkCartAddition() {
        val request = state.value.bulkCartAdditionConfirmation ?: return
        if (state.value.bulkCartAdditionProcessing || !request.canConfirm) return
        _state.update { it.copy(bulkCartAdditionConfirmation = null, bulkCartAdditionProcessing = true) }
        scope.launch {
            try {
                // 一覧に存在しなくなった対象は無視する(§4.3)。確認待ちの間に一覧が変わり得るため、
                // 通信直前(Roomアクセス直前)の一覧で改めて絞り込む。
                val currentTilcods = state.value.results.map { it.tilcod }.toSet()
                val effectiveRequest = request.copy(candidates = request.candidates.filter { it.tilcod in currentTilcods })
                val summary = if (effectiveRequest.candidates.isEmpty()) {
                    ReservationCartAddSummary(added = 0, skipped = 0)
                } else {
                    cartRepository.addToCart(BulkCartAdditionContentBuilder.targets(effectiveRequest))
                }
                _state.update {
                    it.copy(
                        selectedCartTilcods = emptySet(),
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

    /** 入力変化の通知。デバウンス後にオートコンプリート候補を取得する。 */
    fun updateQuery(text: String) {
        autocompleteJob?.cancel()
        if (text.isBlank()) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        autocompleteJob = scope.launch {
            delay(autocompleteDebounceMillis)
            val suggestions = try {
                searchRepository.autocomplete(text.trim())
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                emptyList()
            }
            _state.update { it.copy(suggestions = suggestions) }
        }
    }

    fun search(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        autocompleteJob?.cancel()
        searchJob?.cancel()
        lendableJob?.cancel()
        _state.update {
            it.copy(
                suggestions = emptyList(),
                searching = true,
                errorMessage = null,
            )
        }
        searchJob = scope.launch {
            try {
                val page = searchRepository.search(trimmed, page = 1)
                currentPage = 1
                val rows = buildRows(page.hits)
                _state.update {
                    it.copy(
                        searching = false,
                        executedQuery = trimmed,
                        totalCount = page.totalCount,
                        results = rows,
                        hasNext = page.hasNext,
                    )
                }
                fetchLendabilityFor(rows.map { it.tilcod })
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _state.update {
                    it.copy(
                        searching = false,
                        executedQuery = trimmed,
                        totalCount = 0,
                        results = emptyList(),
                        hasNext = false,
                        errorMessage = errorMessage(exception),
                    )
                }
            }
        }
    }

    fun loadMore() {
        val snapshot = _state.value
        if (!snapshot.hasNext || snapshot.loadingMore || snapshot.searching) return
        val keyword = snapshot.executedQuery ?: return
        _state.update { it.copy(loadingMore = true) }
        searchJob = scope.launch {
            try {
                val page = searchRepository.search(keyword, page = currentPage + 1)
                currentPage += 1
                val newRows = buildRows(page.hits)
                _state.update {
                    it.copy(
                        loadingMore = false,
                        results = it.results + newRows,
                        hasNext = page.hasNext,
                    )
                }
                // 前ページ分で未取得の行が残っていれば、それも含めて取り直す
                fetchLendabilityFor(_state.value.results.filter { it.lendable == null }.map { it.tilcod })
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _state.update { it.copy(loadingMore = false, errorMessage = errorMessage(exception)) }
            }
        }
    }

    fun close() {
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun buildRows(hits: List<SearchHit>): List<SearchResultRow> {
        val readInfoByTilcod = hits.associate { hit ->
            hit.tilcod to readingRecordRepository.hasRead(hit.tilcod).first()
        }
        return SearchContentBuilder.resultRows(hits, members.value, readInfoByTilcod)
    }

    /** 貸出可否を1件ずつ取得して行を更新する。失敗したらそれ以降は諦める(表示は不明のまま)。 */
    private fun fetchLendabilityFor(tilcods: List<String>) {
        lendableJob?.cancel()
        lendableJob = scope.launch {
            for (tilcod in tilcods) {
                val lendable = try {
                    searchRepository.isLendable(tilcod)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    break
                } ?: continue
                _state.update { current ->
                    current.copy(
                        results = current.results.map { row ->
                            if (row.tilcod == tilcod) row.copy(lendable = lendable) else row
                        },
                    )
                }
            }
        }
    }

    private fun errorMessage(exception: Exception): String = when (exception) {
        is LibraryError.Maintenance -> "図書館システムはメンテナンス中です。時間をおいて再試行してください"
        is LibraryError.Parse -> "検索結果を読み取れませんでした。サイト改修の可能性があります"
        is LibraryError.Network -> "通信に失敗しました。接続状況を確認してください"
        else -> "検索に失敗しました。時間をおいて再試行してください"
    }
}
