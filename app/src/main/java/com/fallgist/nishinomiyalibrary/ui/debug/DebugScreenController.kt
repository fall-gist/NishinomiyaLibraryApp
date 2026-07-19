package com.fallgist.nishinomiyalibrary.ui.debug

import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReadingRecordRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SyncResult
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 自動同期の設定を開始するための、Android非依存の境界。 */
interface SyncScheduleStarter {
    suspend fun scheduleFromSettings()
}

data class DebugUiState(
    val display: DebugScreenDisplay = DebugScreenDisplay(),
    val isSyncInProgress: Boolean = false,
    val syncMessage: String = "同期待機中です",
    val scheduleWarning: String? = null,
)

sealed interface RegistrationAction {
    data object Saved : RegistrationAction

    data class ValidationFailed(val errors: RegistrationErrors) : RegistrationAction

    data object Failed : RegistrationAction
}

sealed interface ManualSyncAction {
    data class Completed(val result: SyncResult) : ManualSyncAction

    data object AlreadyInProgress : ManualSyncAction

    data object Failed : ManualSyncAction
}

/**
 * Room Flowを一つの画面状態へ集約する。フォームの秘密情報は状態に保持しない。
 */
class DebugScreenController(
    private val familyRepository: FamilyRepository,
    private val statusRepository: StatusRepository,
    private val readingRecordRepository: ReadingRecordRepository,
    private val scheduleStarter: SyncScheduleStarter,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(DebugUiState())
    val state: StateFlow<DebugUiState> = _state

    private val syncMutex = Mutex()
    private val launchMutex = Mutex()
    private var scheduleCompleted = false
    private val observationJob: Job
    private val readingRecordSearchQuery = MutableStateFlow("")

    init {
        observationJob = scope.launch {
            observeDisplay().collect { display ->
                _state.update { current -> current.copy(display = display) }
            }
        }
    }

    suspend fun onScreenLaunched() = launchMutex.withLock {
        if (scheduleCompleted) return@withLock

        try {
            scheduleStarter.scheduleFromSettings()
            scheduleCompleted = true
            _state.update { current -> current.copy(scheduleWarning = null) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            _state.update { current ->
                current.copy(scheduleWarning = DebugScreenFormatter.scheduleFailureMessage())
            }
        }
    }

    suspend fun register(form: RegistrationForm): RegistrationAction {
        val validation = RegistrationValidator.validate(form)
        if (validation is RegistrationValidation.Invalid) {
            return RegistrationAction.ValidationFailed(validation.errors)
        }
        val value = (validation as RegistrationValidation.Valid).value
        return try {
            familyRepository.addMember(
                name = value.name,
                colorHex = value.colorHex,
                cardNumber = value.cardNumber,
                password = value.password,
            )
            RegistrationAction.Saved
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            RegistrationAction.Failed
        }
    }

    suspend fun requestManualSync(): ManualSyncAction {
        if (!syncMutex.tryLock()) return ManualSyncAction.AlreadyInProgress
        _state.update { current -> current.copy(isSyncInProgress = true, syncMessage = "同期中です") }
        return try {
            val result = statusRepository.syncAll(SyncTrigger.MANUAL)
            _state.update { current ->
                current.copy(syncMessage = DebugScreenFormatter.formatManualSyncResult(result))
            }
            ManualSyncAction.Completed(result)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            _state.update { current ->
                current.copy(syncMessage = DebugScreenFormatter.syncFailureMessage())
            }
            ManualSyncAction.Failed
        } finally {
            _state.update { current -> current.copy(isSyncInProgress = false) }
            syncMutex.unlock()
        }
    }

    /** 検索欄の入力を正規化前のまま受け取り、Repositoryの検索Flowへ接続する。 */
    fun updateReadingRecordSearch(query: String) {
        readingRecordSearchQuery.value = query
    }

    fun close() {
        observationJob.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeDisplay(): Flow<DebugScreenDisplay> = familyRepository.members().flatMapLatest { members ->
        val statusDisplay = combine(
            statusRepository.loans(),
            statusRepository.reservations(),
            shelves(members),
            statusRepository.summaries(),
            statusRepository.lastSync(),
        ) { loans, reservations, shelves, summaries, lastSync ->
            DebugScreenFormatter.format(
                members = members,
                loans = loans,
                reservations = reservations,
                shelvesByMember = shelves,
                summaries = summaries,
                lastSync = lastSync,
            )
        }
        combine(
            statusDisplay,
            readingRecordRepository.records(),
            readingRecordSearchQuery.flatMapLatest { query -> readingRecordRepository.search(query) },
        ) { display, allReadingRecords, searchedReadingRecords ->
            display.copy(
                readingRecordCount = allReadingRecords.size,
                readingRecordLines = DebugScreenFormatter.formatReadingRecords(searchedReadingRecords, members),
            )
        }
    }

    private fun shelves(members: List<Member>): Flow<Map<Long, List<ShelfItem>>> {
        if (members.isEmpty()) return flowOf(emptyMap())
        return combine(
            members.map { member ->
                statusRepository.shelf(member.id).combine(flowOf(member.id)) { shelf, memberId ->
                    memberId to shelf
                }
            },
        ) { values -> values.toMap() }
    }
}
