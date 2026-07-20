package com.fallgist.nishinomiyalibrary.ui.reservations

import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
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
    val memberName: String,
    val memberColorHex: String,
    val title: String,
    val isReady: Boolean,
    val statusLabel: String,
    val pickupLabel: String,
    val queueLabel: String?,
    val holdExpiryLabel: String?,
)

data class ReservationsUiState(
    val initialized: Boolean = false,
    val members: List<Member> = emptyList(),
    val selectedMemberId: Long? = null,
    val rows: List<ReservationRow> = emptyList(),
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
                compareBy<Reservation>(nullsLast<Int>()) { it.queuePosition }
                    .thenBy { orderOf[it.memberId] ?: Int.MAX_VALUE },
            )

        return (ready + others).map { reservation ->
            ReservationRow(
                memberName = nameOf[reservation.memberId] ?: "?",
                memberColorHex = colorOf[reservation.memberId]?.takeIf { it.isNotBlank() } ?: FALLBACK_COLOR,
                title = reservation.title,
                isReady = reservation.state == ReservationState.READY,
                statusLabel = statusLabel(reservation.state),
                pickupLabel = reservation.pickupLibrary.takeIf { it.isNotBlank() } ?: "未定",
                queueLabel = reservation.queuePosition?.let { "予約順位 ${it}番目" },
                holdExpiryLabel = reservation.holdExpiryDate?.let { "取置期限 ${dateFormatter.format(it)} まで" },
            )
        }
    }

    private fun statusLabel(state: ReservationState): String = when (state) {
        ReservationState.READY -> "受取可能"
        ReservationState.WAITING -> "順番待ち"
        ReservationState.UNKNOWN -> "状態不明"
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
                ReservationsUiState(
                    initialized = true,
                    members = members,
                    selectedMemberId = effectiveSelection,
                    rows = ReservationsContentBuilder.build(members, reservations, effectiveSelection),
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
