package com.fallgist.nishinomiyalibrary.ui.home

import com.fallgist.nishinomiyalibrary.data.sync.SyncScheduleStarter
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SyncResult
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ホーム画面のRoom Flowを1つの[HomeUiState]へ集約する、Android非依存のController。
 * 表示計算は[HomeContentBuilder]に委ね、ここでは同期状態とメンバー絞り込みだけを保持する。
 */
class HomeScreenController(
    private val familyRepository: FamilyRepository,
    private val statusRepository: StatusRepository,
    private val scheduleStarter: SyncScheduleStarter,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.of("Asia/Tokyo")) },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    private val selectedMemberId = MutableStateFlow<Long?>(null)
    private val syncMutex = Mutex()
    private val launchMutex = Mutex()
    private var scheduleCompleted = false
    private val observationJob: Job

    init {
        observationJob = scope.launch {
            combine(
                familyRepository.members(),
                statusRepository.loans(),
                statusRepository.reservations(),
                statusRepository.lastSync(),
                selectedMemberId,
            ) { members, loans, reservations, lastSync, selectedId ->
                val effectiveSelection = selectedId?.takeIf { id -> members.any { it.id == id } }
                val content = HomeContentBuilder.build(
                    members = members,
                    loans = loans,
                    reservations = reservations,
                    lastSync = lastSync,
                    selectedMemberId = effectiveSelection,
                    today = today(),
                )
                Triple(members, effectiveSelection, content)
            }.collect { (members, selection, content) ->
                _state.update { current ->
                    current.copy(
                        members = members,
                        selectedMemberId = selection,
                        lastSyncText = content.lastSyncText,
                        lastSyncFailed = content.lastSyncFailed,
                        readyReservations = content.readyReservations,
                        dueGroups = content.dueGroups,
                    )
                }
            }
        }
    }

    /** メンバー絞り込みを更新する。null は「みんな」を表す。 */
    fun selectMember(memberId: Long?) {
        selectedMemberId.value = memberId
    }

    /** 画面表示時に一度だけ自動同期スケジュールを確立する。失敗は静かに次回へ持ち越す。 */
    suspend fun onScreenLaunched() = launchMutex.withLock {
        if (scheduleCompleted) return@withLock
        try {
            scheduleStarter.scheduleFromSettings()
            scheduleCompleted = true
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // 次回 onScreenLaunched で再試行する
        }
    }

    suspend fun requestManualSync() {
        if (!syncMutex.tryLock()) return
        _state.update { it.copy(isSyncing = true, syncMessage = "同期中です") }
        try {
            val result = statusRepository.syncAll(SyncTrigger.MANUAL)
            _state.update { it.copy(syncMessage = manualSyncMessage(result)) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            _state.update { it.copy(syncMessage = "同期に失敗しました。通信状況を確認してください") }
        } finally {
            _state.update { it.copy(isSyncing = false) }
            syncMutex.unlock()
        }
    }

    fun close() {
        observationJob.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun manualSyncMessage(result: SyncResult): String = when (result) {
        is SyncResult.Completed -> if (result.failedMemberCount == 0) {
            "同期が完了しました"
        } else {
            "同期が完了しました(一部失敗: ${result.failedMemberCount}人)"
        }

        is SyncResult.SkippedCooldown -> "少し前に同期済みです。しばらくしてから再試行してください"
    }
}
