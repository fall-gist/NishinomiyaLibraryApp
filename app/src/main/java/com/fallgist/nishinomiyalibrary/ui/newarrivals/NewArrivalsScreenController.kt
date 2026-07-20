package com.fallgist.nishinomiyalibrary.ui.newarrivals

import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecordTitleNormalizer
import com.fallgist.nishinomiyalibrary.domain.repository.NewArrivalRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 新着資料一覧の1行。ジャンルは持たず、書誌の要点のみ表示する。 */
data class NewArrivalRow(
    val tilcod: String,
    val title: String,
    val subtitle: String,
    val lendable: Boolean?,
)

data class NewArrivalsUiState(
    val initialized: Boolean = false,
    val query: String = "",
    val rows: List<NewArrivalRow> = emptyList(),
    /** 絞り込み前の総保持件数。 */
    val totalCount: Int = 0,
    val refreshing: Boolean = false,
    /** 直近の取得が失敗した(既存キャッシュは表示し続ける)。 */
    val refreshFailed: Boolean = false,
)

/** 保持済みの新着資料に検索語で絞り込みをかけ、表示行へ整形する純関数。 */
object NewArrivalsContentBuilder {
    fun build(items: List<NewArrival>, query: String): List<NewArrivalRow> {
        val normalizedQuery = ReadingRecordTitleNormalizer.normalize(query)
        val filtered = if (normalizedQuery.isEmpty()) {
            items
        } else {
            items.filter { item ->
                ReadingRecordTitleNormalizer.normalize(item.title + item.author).contains(normalizedQuery)
            }
        }
        return filtered.map { item ->
            NewArrivalRow(
                tilcod = item.tilcod,
                title = titleWithVolume(item),
                subtitle = subtitle(item),
                lendable = item.lendable,
            )
        }
    }

    private fun titleWithVolume(item: NewArrival): String =
        if (item.volume.isBlank()) item.title else "${item.title} ${item.volume}"

    private fun subtitle(item: NewArrival): String =
        listOf(item.author, item.publisher, item.publishedYearMonth)
            .filter { it.isNotBlank() }
            .joinToString(" ・ ")
}

/**
 * 新着資料画面のController。開いたセッションで1回だけ公式サイトから取得し直し、
 * 以降はローカルキャッシュを購読する。検索語は保持リストに対する画面内絞り込み。
 */
class NewArrivalsScreenController(
    private val newArrivalRepository: NewArrivalRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(NewArrivalsUiState())
    val state: StateFlow<NewArrivalsUiState> = _state

    private val query = MutableStateFlow("")
    private val refreshMutex = Mutex()
    private var refreshedThisSession = false
    private val observationJob: Job

    init {
        observationJob = scope.launch {
            combine(newArrivalRepository.newArrivals(), query) { items, text ->
                items to text
            }.collect { (items, text) ->
                _state.value = _state.value.copy(
                    initialized = true,
                    query = text,
                    rows = NewArrivalsContentBuilder.build(items, text),
                    totalCount = items.size,
                )
            }
        }
    }

    /** 画面表示時に1回だけ裏で取得する。以降の明示更新は[refresh]。 */
    fun onScreenLaunched() {
        if (refreshedThisSession) return
        refreshedThisSession = true
        launchRefresh()
    }

    /** 「更新」操作。取得に失敗した場合は次回また試せるようにする。 */
    fun refresh() {
        launchRefresh()
    }

    private fun launchRefresh() {
        scope.launch {
            if (!refreshMutex.tryLock()) return@launch
            try {
                _state.value = _state.value.copy(refreshing = true)
                newArrivalRepository.refresh()
                _state.value = _state.value.copy(refreshing = false, refreshFailed = false)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                refreshedThisSession = false
                _state.value = _state.value.copy(refreshing = false, refreshFailed = true)
            } finally {
                refreshMutex.unlock()
            }
        }
    }

    fun updateQuery(text: String) {
        query.value = text
    }

    fun close() {
        observationJob.cancel()
        scope.coroutineContext[Job]?.cancel()
    }
}
