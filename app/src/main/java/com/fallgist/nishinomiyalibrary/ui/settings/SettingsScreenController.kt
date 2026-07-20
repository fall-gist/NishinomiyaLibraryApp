package com.fallgist.nishinomiyalibrary.ui.settings

import com.fallgist.nishinomiyalibrary.data.local.AppSettings
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.sync.SyncScheduleStarter
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.repository.CalendarRepository
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SyncLog
import com.fallgist.nishinomiyalibrary.ui.member.MemberRegistrationResult
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationForm
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationValidation
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationValidator
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 設定画面のメンバー一覧1行。 */
data class SettingsMemberRow(
    val member: Member,
    val maskedCardNumber: String,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

data class SettingsUiState(
    val initialized: Boolean = false,
    val memberRows: List<SettingsMemberRow> = emptyList(),
    val memberLimit: Int = SettingsScreenController.MAX_MEMBERS,
    val canAddMember: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val lastSyncText: String = "まだ同期していません",
    val lastSyncFailed: Boolean = false,
    val libraries: List<Library> = emptyList(),
)

/** 設定画面の表示整形の純関数。 */
object SettingsContentBuilder {
    private val syncFormatter = DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.JAPANESE)
    private val tokyoZone: ZoneId = ZoneId.of("Asia/Tokyo")

    /** カード番号は下4桁だけを見せる(spec: 認証情報を画面に出さない)。 */
    fun maskCardNumber(cardNumber: String): String = "****" + cardNumber.takeLast(4)

    fun memberRows(members: List<Member>): List<SettingsMemberRow> = members.mapIndexed { index, member ->
        SettingsMemberRow(
            member = member,
            maskedCardNumber = maskCardNumber(member.cardNumber),
            canMoveUp = index > 0,
            canMoveDown = index < members.lastIndex,
        )
    }

    fun lastSyncText(log: SyncLog?): String {
        if (log == null) return "まだ同期していません"
        val startedAt = Instant.ofEpochMilli(log.startedAtEpochMillis).atZone(tokyoZone)
        return syncFormatter.format(startedAt)
    }
}

/**
 * 設定画面のController。メンバー管理・同期時刻・通知・既定館の変更を担う。
 * 同期時刻の変更後はWorkManagerの自動同期スケジュールを引き直す。
 */
class SettingsScreenController(
    private val familyRepository: FamilyRepository,
    private val statusRepository: StatusRepository,
    private val settingsStore: SettingsStore,
    calendarRepository: CalendarRepository,
    private val scheduleStarter: SyncScheduleStarter,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(SettingsUiState(libraries = calendarRepository.libraries))
    val state: StateFlow<SettingsUiState> = _state

    private var members: List<Member> = emptyList()

    init {
        scope.launch {
            combine(
                familyRepository.members(),
                settingsStore.settings,
                statusRepository.lastSync(),
            ) { memberList, settings, lastSync ->
                Triple(memberList, settings, lastSync)
            }.collect { (memberList, settings, lastSync) ->
                members = memberList
                _state.value = _state.value.copy(
                    initialized = true,
                    memberRows = SettingsContentBuilder.memberRows(memberList),
                    canAddMember = memberList.size < MAX_MEMBERS,
                    settings = settings,
                    lastSyncText = SettingsContentBuilder.lastSyncText(lastSync),
                    lastSyncFailed = lastSync?.succeeded == false,
                )
            }
        }
    }

    /**
     * メンバーを追加または編集する。[editingMemberId] が null なら追加。
     * 編集時はパスワード空欄を「変更しない」として扱う。
     */
    suspend fun saveMember(editingMemberId: Long?, form: RegistrationForm): MemberRegistrationResult {
        val editing = editingMemberId?.let { id -> members.find { it.id == id } }
        if (editingMemberId != null && editing == null) return MemberRegistrationResult.Failed
        if (editing == null && members.size >= MAX_MEMBERS) return MemberRegistrationResult.Failed

        val validation = RegistrationValidator.validate(form, requirePassword = editing == null)
        if (validation is RegistrationValidation.Invalid) {
            return MemberRegistrationResult.Invalid(validation.errors)
        }
        val value = (validation as RegistrationValidation.Valid).value
        return try {
            withContext(dispatcher) {
                if (editing == null) {
                    familyRepository.addMember(
                        name = value.name,
                        colorHex = value.colorHex,
                        cardNumber = value.cardNumber,
                        password = value.password,
                    )
                } else {
                    familyRepository.updateMember(
                        member = editing.copy(
                            name = value.name,
                            colorHex = value.colorHex,
                            cardNumber = value.cardNumber,
                        ),
                        newPassword = value.password.takeIf { it.isNotEmpty() },
                    )
                }
            }
            MemberRegistrationResult.Saved
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            MemberRegistrationResult.Failed
        }
    }

    fun moveMemberUp(memberId: Long) = moveMember(memberId, -1)

    fun moveMemberDown(memberId: Long) = moveMember(memberId, +1)

    fun removeMember(memberId: Long) {
        scope.launch {
            runCatching { familyRepository.removeMember(memberId) }
        }
    }

    fun updateSyncTime(hour: Int, minute: Int) {
        scope.launch {
            runCatching {
                settingsStore.updateSyncTime(hour, minute)
                // 新しい時刻で自動同期を引き直す
                scheduleStarter.scheduleFromSettings()
            }
        }
    }

    fun setNotifyReturnReminder(enabled: Boolean) {
        scope.launch { runCatching { settingsStore.updateNotifyReturnReminder(enabled) } }
    }

    fun setNotifyPickupReady(enabled: Boolean) {
        scope.launch { runCatching { settingsStore.updateNotifyPickupReady(enabled) } }
    }

    fun setReturnReminderDaysBefore(days: Int) {
        scope.launch { runCatching { settingsStore.updateReturnReminderDaysBefore(days) } }
    }

    fun setDefaultCalendarLibrary(code: String) {
        scope.launch { runCatching { settingsStore.updateDefaultCalendarLibrary(code) } }
    }

    fun close() {
        scope.coroutineContext[Job]?.cancel()
    }

    /** 隣のメンバーと表示順を入れ替える。端では何もしない。 */
    private fun moveMember(memberId: Long, delta: Int) {
        val snapshot = members
        val index = snapshot.indexOfFirst { it.id == memberId }
        val neighborIndex = index + delta
        if (index < 0 || neighborIndex !in snapshot.indices) return
        val target = snapshot[index]
        val neighbor = snapshot[neighborIndex]
        scope.launch {
            runCatching {
                familyRepository.updateMember(target.copy(sortOrder = neighbor.sortOrder), newPassword = null)
                familyRepository.updateMember(neighbor.copy(sortOrder = target.sortOrder), newPassword = null)
            }
        }
    }

    companion object {
        /** spec §2: 家族5人分(拡張可能な設計)。 */
        const val MAX_MEMBERS = 5
    }
}
