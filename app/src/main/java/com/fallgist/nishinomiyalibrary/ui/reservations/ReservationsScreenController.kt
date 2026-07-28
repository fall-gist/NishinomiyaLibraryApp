package com.fallgist.nishinomiyalibrary.ui.reservations

import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelTarget
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 予約中一覧の1行。受取可能は先頭に集める。 */
data class ReservationRow(
    val memberId: Long,
    val memberName: String,
    val memberColorHex: String,
    val title: String,
    val isReady: Boolean,
    val statusLabel: String,
    val pickupLabel: String,
    val queueLabel: String?,
    val holdExpiryLabel: String?,
    /** 書誌詳細リンク用。空文字列のときは遷移しない。 */
    val tilcod: String = "",
    /** 取消ボタン(yoykCancel)由来の予約コード。空文字列の行は取消不可(提供可能・移送中など)。 */
    val cancelCode: String = "",
) {
    /** 取消ボタン・チェックボックスを表示できる行か。取消にはtilcodとcancelCodeの両方が要る。 */
    val cancellable: Boolean get() = tilcod.isNotBlank() && cancelCode.isNotBlank()

    /** チェックボックス選択・取消対象の指定に使う一意キー。[cancellable]な行でのみ意味を持つ。 */
    val cancelKey: ReservationCancelKey get() = ReservationCancelKey(memberId, tilcod, cancelCode)
}

data class ReservationsUiState(
    val initialized: Boolean = false,
    val members: List<Member> = emptyList(),
    val selectedMemberId: Long? = null,
    val rows: List<ReservationRow> = emptyList(),
    /** 絞り込み選択に関係なく、メンバーごとの予約中冊数。 */
    val countByMemberId: Map<Long, Int> = emptyMap(),
    val totalCount: Int = 0,
)

/** 予約中の表示行を組み立てる純関数。受取可能→順番待ちの順に並べる。 */
object ReservationsContentBuilder {
    private const val FALLBACK_COLOR = "#6E675C"
    private val dateFormatter = DateTimeFormatter.ofPattern("M/d(E)", Locale.JAPANESE)

    fun build(
        members: List<Member>,
        reservations: List<Reservation>,
        selectedMemberId: Long?,
    ): List<ReservationRow> {
        val activeIds = members.map { it.id }.toSet()
        val nameOf = members.associate { it.id to it.name }
        val colorOf = members.associate { it.id to it.colorHex }
        val orderOf = members.associate { it.id to it.sortOrder }

        val visible = reservations
            .filter { it.memberId in activeIds && (selectedMemberId == null || selectedMemberId == it.memberId) }

        val ready = visible.filter { it.state == ReservationState.READY }
            .sortedWith(compareBy(nullsLast<LocalDate>()) { it.holdExpiryDate })
        val others = visible.filter { it.state != ReservationState.READY }
            .sortedWith(
                compareBy(
                    { it.queuePosition ?: Int.MAX_VALUE },
                    { orderOf[it.memberId] ?: Int.MAX_VALUE },
                ),
            )

        return (ready + others).map { reservation ->
            ReservationRow(
                memberId = reservation.memberId,
                memberName = nameOf[reservation.memberId] ?: "?",
                memberColorHex = colorOf[reservation.memberId]?.takeIf { it.isNotBlank() } ?: FALLBACK_COLOR,
                title = reservation.title,
                isReady = reservation.state == ReservationState.READY,
                statusLabel = statusLabel(reservation.state),
                pickupLabel = reservation.pickupLibrary.takeIf { it.isNotBlank() } ?: "未定",
                queueLabel = reservation.queuePosition?.let { "予約順位 ${it}番目" },
                // 提供可能でもEmail連絡前はサイト側で取置期限が未設定のため、受取可能行では「未定」を明示する
                holdExpiryLabel = reservation.holdExpiryDate?.let { "取置期限 ${dateFormatter.format(it)} まで" }
                    ?: "取置期限 未定".takeIf { reservation.state == ReservationState.READY },
                tilcod = reservation.tilcod,
                cancelCode = reservation.cancelCode,
            )
        }
    }

    /**
     * チェック選択済みキーに対応する行だけを、取消候補へ変換する純関数。
     * 取消不可の行(cancelCodeまたはtilcodが空)は、選択されていても含めない。
     */
    fun cancelCandidates(rows: List<ReservationRow>, selectedKeys: Set<ReservationCancelKey>): List<ReservationCancelCandidate> =
        rows.filter { it.cancellable && it.cancelKey in selectedKeys }
            .map { row ->
                ReservationCancelCandidate(
                    target = ReservationCancelTarget(row.memberId, row.tilcod, row.cancelCode),
                    title = row.title,
                )
            }

    /** 絞り込み前の全冊数を、メンバーごと・合計で集計する純関数。0件のメンバーはmapに含めない。 */
    fun countByMember(members: List<Member>, reservations: List<Reservation>): Map<Long, Int> {
        val activeIds = members.map { it.id }.toSet()
        return reservations.filter { it.memberId in activeIds }.groupingBy { it.memberId }.eachCount()
    }

    private fun statusLabel(state: ReservationState): String = when (state) {
        ReservationState.READY -> "受取可能"
        ReservationState.WAITING -> "順番待ち"
        ReservationState.UNKNOWN -> "状態不明"
        // 12回目のライブ取消＋一覧観測(2026-07-28)で確定。以前はUNKNOWN(状態不明)扱いだった状態を
        // 区別できるようになっただけで、一覧への表示可否(フィルタ)は変えていない。
        ReservationState.CANCELLED -> "取消済み"
        ReservationState.IN_TRANSIT -> "移送中"
    }
}

/** 予約中画面のFlowを集約するController。 */
class ReservationsScreenController(
    private val familyRepository: FamilyRepository,
    private val statusRepository: StatusRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(ReservationsUiState())
    val state: StateFlow<ReservationsUiState> = _state

    private val selectedMemberId = MutableStateFlow<Long?>(null)
    private val observationJob: Job

    init {
        observationJob = scope.launch {
            combine(
                familyRepository.members(),
                statusRepository.reservations(),
                selectedMemberId,
            ) { members, reservations, selectedId ->
                val effectiveSelection = selectedId?.takeIf { id -> members.any { it.id == id } }
                val countByMemberId = ReservationsContentBuilder.countByMember(members, reservations)
                ReservationsUiState(
                    initialized = true,
                    members = members,
                    selectedMemberId = effectiveSelection,
                    rows = ReservationsContentBuilder.build(members, reservations, effectiveSelection),
                    countByMemberId = countByMemberId,
                    totalCount = countByMemberId.values.sum(),
                )
            }.collect { newState -> _state.value = newState }
        }
    }

    fun selectMember(memberId: Long?) {
        selectedMemberId.value = memberId
    }

    fun close() {
        observationJob.cancel()
        scope.coroutineContext[Job]?.cancel()
    }
}
