package com.fallgist.nishinomiyalibrary.ui.shelf

import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import java.time.format.DateTimeFormatter
import java.util.Locale
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 本棚の1冊。 */
data class ShelfBook(
    val title: String,
    val memo: String,
    val registeredDateLabel: String,
)

/** 横並びに表示する1本棚(メンバー×本棚名)。タイトル頭にメンバー識別色を付ける。 */
data class ShelfColumn(
    val memberName: String,
    val memberColorHex: String,
    val shelfName: String,
    val books: List<ShelfBook>,
)

data class BookshelfUiState(
    val initialized: Boolean = false,
    val members: List<Member> = emptyList(),
    val selectedMemberId: Long? = null,
    val columns: List<ShelfColumn> = emptyList(),
)

/** 本棚の表示列を組み立てる純関数。メンバー順→本棚番号順で横に並べる。 */
object BookshelfContentBuilder {
    private const val FALLBACK_COLOR = "#6E675C"
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/M/d", Locale.JAPANESE)

    fun build(
        members: List<Member>,
        shelvesByMember: Map<Long, List<ShelfItem>>,
        selectedMemberId: Long?,
    ): List<ShelfColumn> {
        val visibleMembers = members
            .filter { selectedMemberId == null || it.id == selectedMemberId }
            .sortedBy { it.sortOrder }

        return visibleMembers.flatMap { member ->
            val items = shelvesByMember[member.id].orEmpty()
            items
                .groupBy { it.shelfNo to it.shelfName }
                .toSortedMap(compareBy { it.first })
                .map { (key, shelfItems) ->
                    ShelfColumn(
                        memberName = member.name,
                        memberColorHex = member.colorHex.takeIf { it.isNotBlank() } ?: FALLBACK_COLOR,
                        shelfName = key.second.takeIf { it.isNotBlank() } ?: "マイ本棚",
                        books = shelfItems
                            .sortedByDescending { it.registeredDate }
                            .map { item ->
                                ShelfBook(
                                    title = item.title,
                                    memo = item.memo,
                                    registeredDateLabel = dateFormatter.format(item.registeredDate),
                                )
                            },
                    )
                }
        }
    }
}

/** 本棚画面のFlowを集約するController。 */
class BookshelfScreenController(
    private val familyRepository: FamilyRepository,
    private val statusRepository: StatusRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(BookshelfUiState())
    val state: StateFlow<BookshelfUiState> = _state

    private val selectedMemberId = MutableStateFlow<Long?>(null)
    private val observationJob: Job

    init {
        @OptIn(ExperimentalCoroutinesApi::class)
        observationJob = scope.launch {
            familyRepository.members()
                .flatMapLatest { members ->
                    combine(shelves(members), selectedMemberId) { shelvesByMember, selectedId ->
                        val effectiveSelection = selectedId?.takeIf { id -> members.any { it.id == id } }
                        BookshelfUiState(
                            initialized = true,
                            members = members,
                            selectedMemberId = effectiveSelection,
                            columns = BookshelfContentBuilder.build(members, shelvesByMember, effectiveSelection),
                        )
                    }
                }
                .collect { newState -> _state.value = newState }
        }
    }

    fun selectMember(memberId: Long?) {
        selectedMemberId.value = memberId
    }

    fun close() {
        observationJob.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun shelves(members: List<Member>): Flow<Map<Long, List<ShelfItem>>> {
        if (members.isEmpty()) return flowOf(emptyMap())
        return combine(
            members.map { member ->
                statusRepository.shelf(member.id).map { shelf -> member.id to shelf }
            },
        ) { values -> values.toMap() }
    }
}
