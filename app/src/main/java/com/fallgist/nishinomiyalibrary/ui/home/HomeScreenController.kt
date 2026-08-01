package com.fallgist.nishinomiyalibrary.ui.home

import com.fallgist.nishinomiyalibrary.data.sync.SyncScheduleStarter
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.domain.repository.AutoReservationRepository
import com.fallgist.nishinomiyalibrary.ui.autoreservation.AutoReservationRunPresentationBuilder
import com.fallgist.nishinomiyalibrary.ui.member.MemberRegistrationResult
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationForm
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationValidation
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationValidator
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ホーム画面のRoom Flowを1つの[HomeUiState]へ集約する、Android非依存のController。
 * 表示計算は[HomeContentBuilder]に委ね、ここでは同期状態とメンバー絞り込みだけを保持する。
 */
class HomeScreenController(
    private val familyRepository: FamilyRepository,
    private val statusRepository: StatusRepository,
    private val scheduleStarter: SyncScheduleStarter,
    private val autoReservationRepository: AutoReservationRepository = NoOpAutoReservationRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.of("Asia/Tokyo")) },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    private val selectedMemberId = MutableStateFlow<Long?>(null)
    private val launchMutex = Mutex()
    private var scheduleCompleted = false
    private var homeVisible = false
    private var locallyDismissedAutoReservationRunId: Long? = null
    private val observationJob: Job

    init {
        observationJob = scope.launch {
            val contentFlow = combine(
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
                HomeCombined(members, effectiveSelection, content, null)
            }
            combine(contentFlow, autoReservationRepository.latestRun()) { combined, latestRun ->
                combined.copy(latestRun = latestRun)
            }.collect { combined ->
                _state.update { current ->
                    val latest = combined.latestRun?.let(AutoReservationRunPresentationBuilder::build)
                    current.copy(
                        initialized = true,
                        members = combined.members,
                        selectedMemberId = combined.selection,
                        lastSyncText = combined.content.lastSyncText,
                        lastSyncFailed = combined.content.lastSyncFailed,
                        readyGroups = combined.content.readyGroups,
                        dueGroups = combined.content.dueGroups,
                        latestAutoReservationRun = latest,
                        latestAutoReservationAcknowledged = combined.latestRun?.acknowledged ?: true,
                        showAutoReservationDialog = homeVisible &&
                            combined.latestRun?.acknowledged == false &&
                            combined.latestRun.runId != locallyDismissedAutoReservationRunId,
                    )
                }
            }
        }
    }

    /** メンバー絞り込みを更新する。null は「みんな」を表す。 */
    fun selectMember(memberId: Long?) {
        selectedMemberId.value = memberId
    }

    /** ホーム表示時だけ未確認の最新結果をダイアログ化する。 */
    fun onHomeVisible() {
        homeVisible = true
        _state.update { current ->
            current.copy(
                showAutoReservationDialog = current.latestAutoReservationRun != null &&
                    !current.latestAutoReservationAcknowledged &&
                    current.latestAutoReservationRun.runId != locallyDismissedAutoReservationRunId,
            )
        }
    }

    fun onHomeHidden() {
        homeVisible = false
        _state.update { it.copy(showAutoReservationDialog = false) }
    }

    /** 閉じる・履歴・予約一覧のいずれからも、表示した実行IDだけを確認済みにする。 */
    fun acknowledgeLatestAutoReservationRun(targetRunId: Long) {
        _state.update { current ->
            if (current.latestAutoReservationRun?.runId == targetRunId) {
                locallyDismissedAutoReservationRunId = targetRunId
                current.copy(showAutoReservationDialog = false)
            } else {
                current
            }
        }
        scope.launch {
            try {
                autoReservationRepository.markLatestRunAcknowledged(targetRunId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) { }
        }
    }

    /**
     * 認証情報を検証し、家族メンバーを1人登録する。秘密情報は状態に保持せず、結果のみ返す。
     * 登録に成功するとメンバーFlowが更新され、ホームは自動で通常表示へ切り替わる。
     */
    suspend fun register(form: RegistrationForm): MemberRegistrationResult {
        val validation = RegistrationValidator.validate(form)
        if (validation is RegistrationValidation.Invalid) {
            return MemberRegistrationResult.Invalid(validation.errors)
        }
        val value = (validation as RegistrationValidation.Valid).value
        return try {
            withContext(dispatcher) {
                familyRepository.addMember(
                    name = value.name,
                    colorHex = value.colorHex,
                    cardNumber = value.cardNumber,
                    password = value.password,
                )
            }
            MemberRegistrationResult.Saved
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            MemberRegistrationResult.Failed
        }
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

    fun close() {
        observationJob.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    private data class HomeCombined(
        val members: List<com.fallgist.nishinomiyalibrary.domain.model.Member>,
        val selection: Long?,
        val content: HomeContent,
        val latestRun: com.fallgist.nishinomiyalibrary.domain.model.AutoReservationLatestRun?,
    )
}

/** 既存画面単体テストで履歴を必要としない場合の安全な空実装。 */
private object NoOpAutoReservationRepository : AutoReservationRepository {
    override suspend fun rules() = emptyList<com.fallgist.nishinomiyalibrary.domain.model.AutoReservationRule>()
    override suspend fun replaceRules(rules: List<com.fallgist.nishinomiyalibrary.domain.model.AutoReservationRule>) = Unit
    override suspend fun removeExpiredControls(today: LocalDate) = 0
    override suspend fun markPreparedControlsUnknown() = 0
    override suspend fun control(tilcod: String) = null
    override suspend fun saveControl(control: com.fallgist.nishinomiyalibrary.domain.model.AutoReservationControl) = Unit
    override fun latestRun() = flowOf<com.fallgist.nishinomiyalibrary.domain.model.AutoReservationLatestRun?>(null)
    override suspend fun replaceLatestRun(run: com.fallgist.nishinomiyalibrary.domain.model.AutoReservationLatestRun) = Unit
    override suspend fun markLatestRunAcknowledged(runId: Long) = false
}
