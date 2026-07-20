package com.fallgist.nishinomiyalibrary.ui.search

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReadingInfo
import com.fallgist.nishinomiyalibrary.domain.model.SearchHit
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReadingRecordRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SearchRepository
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
)

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
}

/**
 * 蔵書検索画面のController。検索・オートコンプリート・書誌詳細を公式サイトへ都度問い合わせる。
 * 検索欄の入力値は画面ローカルに保持し、ここへは通知のみ渡す(IME合成対策)。
 */
class SearchScreenController(
    private val searchRepository: SearchRepository,
    private val readingRecordRepository: ReadingRecordRepository,
    familyRepository: FamilyRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val autocompleteDebounceMillis: Long = 300L,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state

    private val members = MutableStateFlow<List<Member>>(emptyList())
    private var autocompleteJob: Job? = null
    private var searchJob: Job? = null
    private var lendableJob: Job? = null
    private var currentPage = 1

    init {
        scope.launch {
            familyRepository.members().collect { members.value = it }
        }
    }

    /** 入力変化の通知。デバウンス後にオートコンプリート候補を取得する。 */
    fun updateQuery(text: String) {
        autocompleteJob?.cancel()
        if (text.isBlank()) {
            _state.value = _state.value.copy(suggestions = emptyList())
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
            _state.value = _state.value.copy(suggestions = suggestions)
        }
    }

    fun search(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        autocompleteJob?.cancel()
        searchJob?.cancel()
        lendableJob?.cancel()
        _state.value = _state.value.copy(
            suggestions = emptyList(),
            searching = true,
            errorMessage = null,
        )
        searchJob = scope.launch {
            try {
                val page = searchRepository.search(trimmed, page = 1)
                currentPage = 1
                val rows = buildRows(page.hits)
                _state.value = _state.value.copy(
                    searching = false,
                    executedQuery = trimmed,
                    totalCount = page.totalCount,
                    results = rows,
                    hasNext = page.hasNext,
                )
                fetchLendabilityFor(rows.map { it.tilcod })
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _state.value = _state.value.copy(
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

    fun loadMore() {
        val snapshot = _state.value
        if (!snapshot.hasNext || snapshot.loadingMore || snapshot.searching) return
        val keyword = snapshot.executedQuery ?: return
        _state.value = snapshot.copy(loadingMore = true)
        searchJob = scope.launch {
            try {
                val page = searchRepository.search(keyword, page = currentPage + 1)
                currentPage += 1
                val newRows = buildRows(page.hits)
                _state.value = _state.value.copy(
                    loadingMore = false,
                    results = _state.value.results + newRows,
                    hasNext = page.hasNext,
                )
                // 前ページ分で未取得の行が残っていれば、それも含めて取り直す
                fetchLendabilityFor(_state.value.results.filter { it.lendable == null }.map { it.tilcod })
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _state.value = _state.value.copy(loadingMore = false, errorMessage = errorMessage(exception))
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
                _state.value = _state.value.copy(
                    results = _state.value.results.map { row ->
                        if (row.tilcod == tilcod) row.copy(lendable = lendable) else row
                    },
                )
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
