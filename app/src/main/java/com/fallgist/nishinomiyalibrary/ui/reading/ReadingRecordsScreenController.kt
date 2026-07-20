package com.fallgist.nishinomiyalibrary.ui.reading

import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReadingRecordRepository
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** 読書記録一覧の1行。 */
data class ReadingRow(
    val memberName: String,
    val memberColorHex: String,
    val title: String,
    val loanDateLabel: String,
    val library: String,
)

data class ReadingRecordsUiState(
    val initialized: Boolean = false,
    val members: List<Member> = emptyList(),
    val selectedMemberId: Long? = null,
    val query: String = "",
    val rows: List<ReadingRow> = emptyList(),
    /** 検索なし・特定メンバー選択で0件のとき、履歴未有効化の案内を出す。 */
    val showActivationHint: Boolean = false,
)

/** 読書記録の表示行を組み立てる純関数。リポジトリが新しい順を保証する前提で表示整形のみ行う。 */
object ReadingRecordsContentBuilder {
    private const val FALLBACK_COLOR = "#6E675C"
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/M/d", Locale.JAPANESE)

    fun build(members: List<Member>, records: List<ReadingRecord>): List<ReadingRow> {
        val nameOf = members.associate { it.id to it.name }
        val colorOf = members.associate { it.id to it.colorHex }
        return records.map { record ->
            ReadingRow(
                memberName = nameOf[record.memberId] ?: "?",
                memberColorHex = colorOf[record.memberId]?.takeIf { it.isNotBlank() } ?: FALLBACK_COLOR,
                title = record.title,
                loanDateLabel = dateFormatter.format(record.loanDate),
                library = record.library,
            )
        }
    }
}

/** 読書記録画面のFlowを集約するController。検索欄の入力はそのままリポジトリの正規化検索へ渡す。 */
class ReadingRecordsScreenController(
    private val familyRepository: FamilyRepository,
    private val readingRecordRepository: ReadingRecordRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(ReadingRecordsUiState())
    val state: StateFlow<ReadingRecordsUiState> = _state

    private val selectedMemberId = MutableStateFlow<Long?>(null)
    private val query = MutableStateFlow("")
    private val observationJob: Job

    init {
        @OptIn(ExperimentalCoroutinesApi::class)
        observationJob = scope.launch {
            combine(selectedMemberId, query) { memberId, text -> memberId to text }
                .flatMapLatest { (memberId, text) ->
                    val recordsFlow = if (text.isBlank()) {
                        readingRecordRepository.records(memberId)
                    } else {
                        readingRecordRepository.search(text, memberId)
                    }
                    combine(familyRepository.members(), recordsFlow) { members, records ->
                        val effectiveSelection = memberId?.takeIf { id -> members.any { it.id == id } }
                        ReadingRecordsUiState(
                            initialized = true,
                            members = members,
                            selectedMemberId = effectiveSelection,
                            query = text,
                            rows = ReadingRecordsContentBuilder.build(members, records),
                            showActivationHint = text.isBlank() && effectiveSelection != null && records.isEmpty(),
                        )
                    }
                }
                .collect { newState -> _state.value = newState }
        }
    }

    fun selectMember(memberId: Long?) {
        selectedMemberId.value = memberId
    }

    fun updateQuery(text: String) {
        query.value = text
    }

    fun close() {
        observationJob.cancel()
        scope.coroutineContext[Job]?.cancel()
    }
}
