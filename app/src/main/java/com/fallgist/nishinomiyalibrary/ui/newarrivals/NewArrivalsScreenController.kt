package com.fallgist.nishinomiyalibrary.ui.newarrivals

import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecordTitleNormalizer
import com.fallgist.nishinomiyalibrary.domain.repository.NewArrivalRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
    /** 最終取得時刻(epoch millis)。未取得ならnull。 */
    val lastFetchedAtEpochMillis: Long? = null,
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
 * 新着資料の最終取得時刻を画面表示用の文言に整形する純関数。
 * [SettingsScreenController.lastSyncText]と同じくAsia/Tokyoの絶対表記に統一する。
 */
object NewArrivalsLastFetchedTextBuilder {
    private val formatter = DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.JAPANESE)
    private val tokyoZone: ZoneId = ZoneId.of("Asia/Tokyo")

    fun build(lastFetchedAtEpochMillis: Long?): String {
        if (lastFetchedAtEpochMillis == null) return "未取得"
        val fetchedAt = Instant.ofEpochMilli(lastFetchedAtEpochMillis).atZone(tokyoZone)
        return "最終取得: ${formatter.format(fetchedAt)}"
    }
}

/**
 * 新着資料画面のController。開いたセッションで1回だけ公式サイトから取得し直し、
 * 以降はローカルキャッシュを購読する。検索語は保持リストに対する画面内絞り込み。
 */
class NewArrivalsScreenController(
    private val newArrivalRepository: NewArrivalRepository,
    private val clock: Clock,
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
        // 最終取得時刻は購読対象ではないため、初期表示時に一度だけ読み込む(以降はrefresh成功時に更新)。
        scope.launch {
            _state.value = _state.value.copy(lastFetchedAtEpochMillis = newArrivalRepository.lastFetchedAtEpochMillis())
        }
    }

    /**
     * 画面表示時に1回だけ裏で取得する。以降の明示更新は[refresh]。
     * さらに、最終取得から[AUTO_REFRESH_FRESHNESS_THRESHOLD_MILLIS]以内であれば
     * 巡回自体を省略する(28ジャンル巡回は約15秒かかり、予約処理等と並行して走ると負荷になるため)。
     */
    fun onScreenLaunched() {
        if (refreshedThisSession) return
        refreshedThisSession = true
        scope.launch {
            if (!shouldAutoRefresh()) return@launch
            performRefresh()
        }
    }

    /** 「更新」操作。鮮度に関わらず必ず巡回する。取得に失敗した場合は次回また試せるようにする。 */
    fun refresh() {
        scope.launch { performRefresh() }
    }

    private suspend fun shouldAutoRefresh(): Boolean {
        // 新着資料が1件も保持されていない(初回起動など)場合は経過時間を問わず巡回する。
        if (!newArrivalRepository.hasCachedItems()) return true
        val lastFetchedAt = newArrivalRepository.lastFetchedAtEpochMillis() ?: return true
        val elapsedMillis = clock.millis() - lastFetchedAt
        return elapsedMillis >= AUTO_REFRESH_FRESHNESS_THRESHOLD_MILLIS
    }

    private suspend fun performRefresh() {
        if (!refreshMutex.tryLock()) return
        try {
            _state.value = _state.value.copy(refreshing = true)
            newArrivalRepository.refresh()
            _state.value = _state.value.copy(
                refreshing = false,
                refreshFailed = false,
                lastFetchedAtEpochMillis = newArrivalRepository.lastFetchedAtEpochMillis(),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            refreshedThisSession = false
            _state.value = _state.value.copy(refreshing = false, refreshFailed = true)
        } finally {
            refreshMutex.unlock()
        }
    }

    fun updateQuery(text: String) {
        query.value = text
    }

    fun close() {
        observationJob.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        /** 画面表示時の自動巡回を省略してよい鮮度のしきい値。 */
        val AUTO_REFRESH_FRESHNESS_THRESHOLD_MILLIS: Long = Duration.ofHours(12).toMillis()
    }
}
