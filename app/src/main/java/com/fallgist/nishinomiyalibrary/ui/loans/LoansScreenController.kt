package com.fallgist.nishinomiyalibrary.ui.loans

import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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

/** 貸出中一覧の1行。 */
data class LoanRow(
    val memberName: String,
    val memberColorHex: String,
    val title: String,
    val library: String,
    val dueLabel: String,
    val overdue: Boolean,
    val dueSoon: Boolean,
    /** 書誌詳細リンク用。空文字列のときは遷移しない。 */
    val tilcod: String = "",
)

data class LoansUiState(
    val initialized: Boolean = false,
    val members: List<Member> = emptyList(),
    val selectedMemberId: Long? = null,
    val rows: List<LoanRow> = emptyList(),
    /** 絞り込み選択に関係なく、メンバーごとの貸出中冊数。 */
    val countByMemberId: Map<Long, Int> = emptyMap(),
    val totalCount: Int = 0,
)

/** 貸出中の表示行を返却期限昇順で組み立てる純関数。Android非依存でテストする。 */
object LoansContentBuilder {
    private const val DUE_SOON_DAYS = 3L
    private const val FALLBACK_COLOR = "#6E675C"
    private val dateFormatter = DateTimeFormatter.ofPattern("M/d(E)", Locale.JAPANESE)

    fun build(
        members: List<Member>,
        loans: List<Loan>,
        selectedMemberId: Long?,
        today: LocalDate,
    ): List<LoanRow> {
        val activeIds = members.map { it.id }.toSet()
        val nameOf = members.associate { it.id to it.name }
        val colorOf = members.associate { it.id to it.colorHex }
        val orderOf = members.associate { it.id to it.sortOrder }

        return loans
            .filter { it.memberId in activeIds && (selectedMemberId == null || selectedMemberId == it.memberId) }
            .sortedWith(compareBy({ it.dueDate }, { orderOf[it.memberId] ?: Int.MAX_VALUE }))
            .map { loan ->
                val overdue = loan.dueDate.isBefore(today)
                val daysUntil = ChronoUnit.DAYS.between(today, loan.dueDate)
                LoanRow(
                    memberName = nameOf[loan.memberId] ?: "?",
                    memberColorHex = colorOf[loan.memberId]?.takeIf { it.isNotBlank() } ?: FALLBACK_COLOR,
                    title = loan.title,
                    library = loan.lendingLibrary,
                    dueLabel = dueLabel(loan.dueDate, today),
                    overdue = overdue,
                    dueSoon = !overdue && daysUntil <= DUE_SOON_DAYS,
                    tilcod = loan.tilcod,
                )
            }
    }

    /** 絞り込み前の全冊数を、メンバーごと・合計で集計する純関数。0件のメンバーはmapに含めない。 */
    fun countByMember(members: List<Member>, loans: List<Loan>): Map<Long, Int> {
        val activeIds = members.map { it.id }.toSet()
        return loans.filter { it.memberId in activeIds }.groupingBy { it.memberId }.eachCount()
    }

    private fun dueLabel(date: LocalDate, today: LocalDate): String {
        val formatted = dateFormatter.format(date)
        return when {
            date.isBefore(today) -> "$formatted まで(超過)"
            date == today -> "きょう $formatted まで"
            date == today.plusDays(1) -> "あす $formatted まで"
            else -> "$formatted まで"
        }
    }
}

/** 貸出中画面のFlowを集約するController。 */
class LoansScreenController(
    private val familyRepository: FamilyRepository,
    private val statusRepository: StatusRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.of("Asia/Tokyo")) },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(LoansUiState())
    val state: StateFlow<LoansUiState> = _state

    private val selectedMemberId = MutableStateFlow<Long?>(null)
    private val observationJob: Job

    init {
        observationJob = scope.launch {
            combine(
                familyRepository.members(),
                statusRepository.loans(),
                selectedMemberId,
            ) { members, loans, selectedId ->
                val effectiveSelection = selectedId?.takeIf { id -> members.any { it.id == id } }
                val countByMemberId = LoansContentBuilder.countByMember(members, loans)
                LoansUiState(
                    initialized = true,
                    members = members,
                    selectedMemberId = effectiveSelection,
                    rows = LoansContentBuilder.build(members, loans, effectiveSelection, today()),
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
