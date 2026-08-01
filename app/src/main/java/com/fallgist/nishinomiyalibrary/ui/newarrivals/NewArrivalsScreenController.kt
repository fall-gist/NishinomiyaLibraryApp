package com.fallgist.nishinomiyalibrary.ui.newarrivals

import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecordTitleNormalizer
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdateRunner
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdateResult
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdateTrigger
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdatePhase
import com.fallgist.nishinomiyalibrary.domain.repository.NewArrivalRepository
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

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
    val autoReservationEnabled: Boolean = false,
    val updatePhase: NewArrivalUpdatePhase? = null,
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
    private val updateCoordinator: NewArrivalUpdateRunner,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val autoReservationEnabled: Flow<Boolean> = flowOf(false),
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(NewArrivalsUiState())
    val state: StateFlow<NewArrivalsUiState> = _state

    private val query = MutableStateFlow("")
    private val displayRefreshMutex = Mutex()
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
        scope.launch {
            autoReservationEnabled.collect { enabled ->
                _state.value = _state.value.copy(autoReservationEnabled = enabled)
            }
        }
    }

    /** 画面表示時に1回だけSCREEN_AUTO更新を依頼する。鮮度判定は更新Coordinatorが行う。 */
    fun onScreenLaunched() {
        if (refreshedThisSession) return
        refreshedThisSession = true
        scope.launch {
            performRefresh(NewArrivalUpdateTrigger.SCREEN_AUTO)
        }
    }

    /** 「更新」操作。鮮度に関わらず必ず巡回する。取得に失敗した場合は次回また試せるようにする。 */
    fun refresh() {
        scope.launch { performRefresh(NewArrivalUpdateTrigger.SCREEN_MANUAL) }
    }

    private suspend fun performRefresh(trigger: NewArrivalUpdateTrigger) {
        if (!displayRefreshMutex.tryLock()) return
        try {
            _state.value = _state.value.copy(refreshing = true, updatePhase = null)
            when (updateCoordinator.refresh(trigger) { phase ->
                _state.value = _state.value.copy(updatePhase = phase)
            }) {
                NewArrivalUpdateResult.RefreshFailed -> {
                    refreshedThisSession = false
                    _state.value = _state.value.copy(refreshing = false, refreshFailed = true, updatePhase = null)
                    return
                }
                NewArrivalUpdateResult.AlreadyRunning -> {
                    _state.value = _state.value.copy(refreshing = false, updatePhase = null)
                    return
                }
                is NewArrivalUpdateResult.Completed,
                is NewArrivalUpdateResult.AutomaticFailed,
                NewArrivalUpdateResult.FreshnessSkipped,
                -> Unit
            }
            _state.value = _state.value.copy(
                refreshing = false,
                refreshFailed = false,
                updatePhase = null,
                lastFetchedAtEpochMillis = newArrivalRepository.lastFetchedAtEpochMillis(),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            refreshedThisSession = false
            _state.value = _state.value.copy(refreshing = false, refreshFailed = true, updatePhase = null)
        } finally {
            displayRefreshMutex.unlock()
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
