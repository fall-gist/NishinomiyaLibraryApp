package com.fallgist.nishinomiyalibrary.ui.newarrivals

import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecordTitleNormalizer
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartAddSummary
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdateRunner
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdateResult
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdateTrigger
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdatePhase
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.NewArrivalRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCartRepository
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkCartAdditionCandidate
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkCartAdditionConfirmationRequest
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkCartAdditionContentBuilder
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
    /**
     * 一斉カート追加(`docs/design/bulk-selection.md` §7.4)用の著者表記。
     * authorとpublisherから組み立てる(subtitleはpublishedYearMonthも含むため流用しない)。
     * 両方空ならnull。
     */
    val writerLine: String? = null,
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
                writerLine = writerLine(item),
            )
        }
    }

    private fun titleWithVolume(item: NewArrival): String =
        if (item.volume.isBlank()) item.title else "${item.title} ${item.volume}"

    private fun subtitle(item: NewArrival): String =
        listOf(item.author, item.publisher, item.publishedYearMonth)
            .filter { it.isNotBlank() }
            .joinToString(" ・ ")

    /** `docs/design/bulk-selection.md` §7.4: publishedYearMonthは含めない。両方空ならnull。 */
    private fun writerLine(item: NewArrival): String? =
        listOf(item.author, item.publisher)
            .filter { it.isNotBlank() }
            .joinToString(" ・ ")
            .takeIf { it.isNotBlank() }

    /**
     * 選択済みキーに対応する一斉カート追加の候補を組み立てる(`docs/design/bulk-selection.md` §7.4)。
     * 一覧に存在しなくなったキーは無視する(§4.3)。
     */
    fun cartAdditionCandidates(rows: List<NewArrivalRow>, selectedTilcods: Set<String>): List<BulkCartAdditionCandidate> =
        rows.filter { it.tilcod in selectedTilcods }
            .map { row -> BulkCartAdditionCandidate(row.tilcod, row.title, row.writerLine) }
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
    /** 一斉カート追加(`docs/design/bulk-selection.md` §7)のメンバー選択に使う。 */
    familyRepository: FamilyRepository = NoOpFamilyRepository,
    private val cartRepository: ReservationCartRepository = NoOpBulkCartAdditionRepository,
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
            familyRepository.members().collect { members ->
                _state.value = _state.value.copy(members = members)
            }
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

    /** 一覧行のチェックボックスのタップ(`docs/design/bulk-selection.md` §7.3)。 */
    fun toggleCartSelection(tilcod: String) {
        if (state.value.bulkCartAdditionProcessing) return
        val current = state.value.selectedCartTilcods
        _state.value = state.value.copy(
            selectedCartTilcods = if (tilcod in current) current - tilcod else current + tilcod,
        )
    }

    /** 「カートへ追加」ボタン。選択済みキーに対応する候補はScreen側で組み立てて渡す。 */
    fun requestBulkCartAddition(candidates: List<BulkCartAdditionCandidate>) {
        if (state.value.bulkCartAdditionProcessing || candidates.isEmpty()) return
        _state.value = state.value.copy(bulkCartAdditionConfirmation = BulkCartAdditionConfirmationRequest(candidates))
    }

    /** 確認ダイアログでのメンバー選択(§7.2、1回の操作につき1人)。 */
    fun selectBulkCartAdditionMember(memberId: Long) {
        val request = state.value.bulkCartAdditionConfirmation ?: return
        if (state.value.members.none { it.id == memberId }) return
        _state.value = state.value.copy(bulkCartAdditionConfirmation = request.copy(selectedMemberId = memberId))
    }

    fun dismissBulkCartAdditionConfirmation() {
        if (!state.value.bulkCartAdditionProcessing) {
            _state.value = state.value.copy(bulkCartAdditionConfirmation = null)
        }
    }

    /** 確認ダイアログの「追加する」。メンバー未選択では実行しない(§7.2)。 */
    fun confirmBulkCartAddition() {
        val request = state.value.bulkCartAdditionConfirmation ?: return
        if (state.value.bulkCartAdditionProcessing || !request.canConfirm) return
        _state.value = state.value.copy(bulkCartAdditionConfirmation = null, bulkCartAdditionProcessing = true)
        scope.launch {
            try {
                // 一覧に存在しなくなった対象は無視する(§4.3)。確認待ちの間に一覧(検索語による絞り込み結果)が
                // 変わり得るため、通信直前(Roomアクセス直前)の一覧で改めて絞り込む。
                val currentTilcods = state.value.rows.map { it.tilcod }.toSet()
                val effectiveRequest = request.copy(candidates = request.candidates.filter { it.tilcod in currentTilcods })
                val summary = if (effectiveRequest.candidates.isEmpty()) {
                    ReservationCartAddSummary(added = 0, skipped = 0)
                } else {
                    cartRepository.addToCart(BulkCartAdditionContentBuilder.targets(effectiveRequest))
                }
                _state.value = _state.value.copy(
                    selectedCartTilcods = emptySet(),
                    bulkCartAdditionProcessing = false,
                    bulkCartAdditionResultMessage = BulkCartAdditionContentBuilder.resultMessage(summary),
                )
            } catch (exception: CancellationException) {
                _state.value = _state.value.copy(bulkCartAdditionProcessing = false)
                throw exception
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    bulkCartAdditionProcessing = false,
                    bulkCartAdditionErrorMessage = "カートへ追加できませんでした。もう一度お試しください。",
                )
            }
        }
    }

    fun clearBulkCartAdditionResult() {
        _state.value = state.value.copy(bulkCartAdditionResultMessage = null)
    }

    fun clearBulkCartAdditionError() {
        _state.value = state.value.copy(bulkCartAdditionErrorMessage = null)
    }

    fun close() {
        observationJob.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

}

/** [NewArrivalsScreenController]の既定引数用。familyRepositoryを渡さない既存呼び出しを壊さないためのno-op実装。 */
private object NoOpFamilyRepository : FamilyRepository {
    override fun members(): Flow<List<Member>> = flowOf(emptyList())
    override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) = Unit
    override suspend fun updateMember(member: Member, newPassword: String?) = Unit
    override suspend fun removeMember(memberId: Long) = Unit
}

/**
 * [NewArrivalsScreenController]の既定引数用。cartRepositoryを渡さない既存呼び出しを壊さないためのno-op実装。
 * 一斉カート追加を使わない画面(既存テスト等)では呼ばれない。
 */
private object NoOpBulkCartAdditionRepository : ReservationCartRepository {
    override fun cartItems(): Flow<List<com.fallgist.nishinomiyalibrary.domain.model.ReservationCartItem>> = flowOf(emptyList())
    override suspend fun addToCart(target: com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget) = Unit
    override suspend fun addToCart(
        targets: List<com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget>,
    ): ReservationCartAddSummary = ReservationCartAddSummary(0, 0)
    override suspend fun removeFromCart(cartItemId: Long) = Unit
    override suspend fun confirmCart(
        confirmation: com.fallgist.nishinomiyalibrary.domain.model.ReservationConfirmation,
    ): com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult =
        com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult(emptyList())
    override suspend fun reserveNow(
        target: com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget,
        confirmation: com.fallgist.nishinomiyalibrary.domain.model.ReservationConfirmation,
    ): com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult =
        com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult(emptyList())
}
