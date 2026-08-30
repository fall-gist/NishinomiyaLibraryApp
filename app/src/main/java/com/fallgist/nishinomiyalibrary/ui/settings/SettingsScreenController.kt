package com.fallgist.nishinomiyalibrary.ui.settings

import com.fallgist.nishinomiyalibrary.data.backup.BackupExportPort
import com.fallgist.nishinomiyalibrary.data.backup.BackupImportPort
import com.fallgist.nishinomiyalibrary.data.backup.BackupImportResult
import com.fallgist.nishinomiyalibrary.data.backup.NoOpBackupImportPort
import com.fallgist.nishinomiyalibrary.data.diagnostics.DiagnosticLog
import com.fallgist.nishinomiyalibrary.data.diagnostics.DiagnosticLogEntry
import com.fallgist.nishinomiyalibrary.data.local.AppSettings
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.sync.SyncScheduleStarter
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.repository.CalendarRepository
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.domain.repository.AutoReservationRepository
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationMatcher
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationRule
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [SettingsScreenController.exportBackup]の結果。 */
sealed interface BackupExportOutcome {
    data class Success(val json: String) : BackupExportOutcome
    data class Failed(val message: String) : BackupExportOutcome
}

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
    val diagnosticLogEnabled: Boolean = false,
    val diagnosticLogLineCount: Int = 0,
    val buildGitSha: String = "unknown",
    val buildTime: String = "unknown",
    val autoReservationRules: List<AutoReservationRule> = emptyList(),
    val autoReservationError: String? = null,
    val autoReservationWarning: String? = null,
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
    private val diagnosticLog: DiagnosticLog,
    private val autoReservationRepository: AutoReservationRepository = NoOpSettingsAutoReservationRepository,
    private val backupExporter: BackupExportPort = NoOpBackupExportPort,
    private val backupImporter: BackupImportPort = NoOpBackupImportPort,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    // ビルド識別子はプロセス起動時に固定されるため、combine購読とは別に初期値としてここで設定する。
    private val _state = MutableStateFlow(
        SettingsUiState(
            libraries = calendarRepository.libraries,
            buildGitSha = diagnosticLog.buildIdentityGitSha,
            buildTime = diagnosticLog.buildIdentityBuildTime,
        ),
    )
    val state: StateFlow<SettingsUiState> = _state

    // _stateは複数のコルーチン(init購読・reloadRules・mutateRules・setAutoReservationEnabled)から
    // 更新される。`_state.value = _state.value.copy(...)`は読みと書きの間に別コルーチンの書きが挟まると
    // それを取りこぼす(lost update)。取りこぼした値は再発行されないため、待っている側は永久に待つ。
    // **必ず`update {}`(CASループ)を使うこと。`_state.value = ...`を書いてはならない。**
    // 2026-08-15/08-18のCI失敗(SettingsScreenControllerTest)の原因候補。docs/handoff.md参照。

    // 購読コルーチンが書き、setAutoReservationEnabled/mutateRules/reloadRulesの別コルーチンが読む。
    // 非volatileだと古い空リストを読み、誤って「メンバーを1人以上登録してください」へ倒れうる。
    @Volatile
    private var members: List<Member> = emptyList()
    private var rules: List<AutoReservationRule> = emptyList()
    private val rulesMutex = Mutex()
    private val initialRulesLoaded = CompletableDeferred<Boolean>(scope.coroutineContext[Job])

    /** テストが初回ルール読込の決着を決定論的に待つための観測点。本番コードからは参照しない。 */
    internal val initialRulesSettled: Deferred<Boolean> get() = initialRulesLoaded

    // combine購読の初回発行(members/settingsが確定するタイミング)を待てるようにする。
    // members・settingsはinitのcombine購読の初回発行でしか埋まらない(settingsStoreは実DataStore
    // =実ディスクI/O)ため、これより前に検証すると実アプリでも初期化前のトグル操作で誤ったエラー文言
    // (「メンバーを1人以上登録してください」)を出しうる。検証はこの2つが確定してから行う。
    private val initialStateLoaded = CompletableDeferred<Unit>(scope.coroutineContext[Job])

    init {
        scope.launch {
            combine(
                familyRepository.members(),
                settingsStore.settings,
                statusRepository.lastSync(),
                diagnosticLog.entries,
            ) { memberList, settings, lastSync, diagnosticEntries ->
                SettingsCombinedState(memberList, settings, lastSync, diagnosticEntries)
            }.collect { combined ->
                members = combined.memberList
                // 設定の diagnosticLogEnabled を記録可否の唯一の正とし、アプリ再起動時もここで反映する。
                diagnosticLog.recording = combined.settings.diagnosticLogEnabled
                val currentRules = rulesMutex.withLock { rules }
                _state.update { current ->
                    buildState(
                        current.copy(
                            initialized = true,
                            memberRows = SettingsContentBuilder.memberRows(combined.memberList),
                            canAddMember = combined.memberList.size < MAX_MEMBERS,
                            settings = combined.settings,
                            lastSyncText = SettingsContentBuilder.lastSyncText(combined.lastSync),
                            lastSyncFailed = combined.lastSync?.succeeded == false,
                            diagnosticLogEnabled = combined.settings.diagnosticLogEnabled,
                            diagnosticLogLineCount = combined.diagnosticEntries.size,
                        ), combined.settings, combined.memberList, currentRules)
                }
                initialStateLoaded.complete(Unit)
            }
        }
        scope.launch { reloadRules() }
    }

    private data class SettingsCombinedState(
        val memberList: List<Member>,
        val settings: AppSettings,
        val lastSync: SyncLog?,
        val diagnosticEntries: List<DiagnosticLogEntry>,
    )

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

    /** 一斉操作の選択解除前の確認ダイアログ設定(`docs/design/bulk-selection-followup.md` §6.3)。 */
    fun setWarnBeforeClearingSelection(enabled: Boolean) {
        scope.launch { runCatching { settingsStore.updateWarnBeforeClearingSelection(enabled) } }
    }

    /** マスターON時だけ必要条件を検証し、不足なら保存せず理由を表示する。 */
    fun setAutoReservationEnabled(enabled: Boolean) {
        scope.launch {
            if (enabled) {
                initialStateLoaded.await()
                if (!initialRulesLoaded.await()) return@launch
                val reason = rulesMutex.withLock {
                    // settingsとlibrariesは同一スナップショットから読む(2回読むと途中で差し替わりうる)。
                    val snapshot = _state.value
                    autoReservationEnableReason(snapshot.settings, members, rules, snapshot.libraries)
                }
                if (reason != null) {
                    _state.update { it.copy(autoReservationError = reason) }
                    return@launch
                }
            }
            try {
                settingsStore.updateAutoReservationEnabled(enabled)
                _state.update { it.copy(autoReservationError = null) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                _state.update { it.copy(autoReservationError = "自動予約の設定を保存できませんでした") }
            }
        }
    }

    /** ルール一覧を全置換で保存する。Repository側の検証にも従う。 */
    fun saveAutoReservationRule(editingId: Long?, includeTerms: List<String>, excludeTerms: List<String>) {
        mutateRules { snapshot ->
            val editingIndex = editingId?.let { id -> snapshot.indexOfFirst { it.id == id } } ?: -1
            if (editingIndex >= 0) {
                val existing = snapshot[editingIndex]
                snapshot.toMutableList().also { rules ->
                    rules[editingIndex] = existing.copy(
                        includeTerms = includeTerms,
                        excludeTerms = excludeTerms,
                    )
                }
            } else {
                val id = (snapshot.maxOfOrNull { it.id } ?: 0L) + 1L
                snapshot + AutoReservationRule(id, true, snapshot.size, includeTerms, excludeTerms)
            }
        }
    }

    fun removeAutoReservationRule(id: Long) = mutateRules { it.filterNot { rule -> rule.id == id }.mapIndexed { index, rule -> rule.copy(sortOrder = index) } }
    fun setAutoReservationRuleEnabled(id: Long, enabled: Boolean) = mutateRules { it.map { rule -> if (rule.id == id) rule.copy(enabled = enabled) else rule } }
    fun moveAutoReservationRule(id: Long, delta: Int) {
        mutateRules { snapshot ->
            val index = snapshot.indexOfFirst { it.id == id }; val target = index + delta
            if (index !in snapshot.indices || target !in snapshot.indices) snapshot else snapshot.toMutableList().also { list -> val item = list.removeAt(index); list.add(target, item) }.mapIndexed { order, rule -> rule.copy(sortOrder = order) }
        }
    }

    private fun mutateRules(transform: (List<AutoReservationRule>) -> List<AutoReservationRule>) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!initialRulesLoaded.await()) return@launch
            rulesMutex.withLock {
              try {
                val next = transform(rules).mapIndexed { index, rule -> rule.copy(sortOrder = index) }
                next.forEach(AutoReservationMatcher::validate)
                autoReservationRepository.replaceRules(next)
                rules = autoReservationRepository.rules().sortedBy { it.sortOrder }
                _state.update { current -> buildState(current.copy(autoReservationRules = rules), current.settings, members, rules) }
                _state.update { it.copy(autoReservationError = null) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                _state.update { it.copy(autoReservationError = exception.message ?: "ルールを保存できませんでした") }
            } catch (_: Exception) {
                _state.update { it.copy(autoReservationError = "ルールを保存できませんでした") }
              }
            }
        }
    }

    private suspend fun reloadRules() {
        try {
            // 読込のsuspend区間ではrulesMutexを保持しない。combine購読の本体も同じmutexを取るため、
            // 保持したままだと設定画面全体の初期化がルール読込の完了までブロックされる(実アプリでも
            // 読込が遅い間は画面が初期化されない)。
            val loaded = autoReservationRepository.rules().sortedBy { it.sortOrder }
            rulesMutex.withLock {
                rules = loaded
                _state.update { current -> buildState(current.copy(autoReservationRules = rules), current.settings, members, rules) }
                initialRulesLoaded.complete(true)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            _state.update { it.copy(autoReservationError = "ルールを読み込めませんでした") }
            initialRulesLoaded.complete(false)
        }
    }

    private fun autoReservationEnableReason(settings: AppSettings, members: List<Member>, rules: List<AutoReservationRule>, libraries: List<Library>): String? = when {
        rules.none { it.enabled } -> "有効なキーワードルールを1件以上登録してください"
        members.isEmpty() -> "メンバーを1人以上登録してください"
        libraries.none { it.code == settings.defaultCalendarLibrary } -> "既定受取館を有効な図書館から選択してください"
        else -> null
    }

    private fun buildState(base: SettingsUiState, settings: AppSettings, members: List<Member>, rules: List<AutoReservationRule>): SettingsUiState = base.copy(
        autoReservationWarning = if (settings.autoReservationEnabled && autoReservationEnableReason(settings, members, rules, base.libraries) != null) "自動予約はONのままですが、設定が不足しています。次の更新前に見直してください" else null,
    )

    /** 設定への保存を唯一の正とする。反映はinitのcombine購読を通して行う。 */
    fun setDiagnosticLogEnabled(enabled: Boolean) {
        scope.launch { runCatching { settingsStore.updateDiagnosticLogEnabled(enabled) } }
    }

    /**
     * バックアップJSONを組み立てる。SAFのファイル書き込み自体はCompose側(BackupSection)が行い、
     * ここでは文字列を返すだけに留める。
     */
    suspend fun exportBackup(): BackupExportOutcome = withContext(dispatcher) {
        try {
            BackupExportOutcome.Success(backupExporter.export())
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            BackupExportOutcome.Failed("書き出しに失敗しました")
        }
    }

    /** バックアップJSONを検証し、全置換でインポートする。既存データが壊れないことは実装側で担保する。 */
    suspend fun importBackup(jsonText: String): BackupImportResult = withContext(dispatcher) {
        backupImporter.import(jsonText)
    }

    /** クリップボードへコピーする文字列を返す純粋な取得。副作用はない。 */
    fun formattedDiagnosticLog(): String = diagnosticLog.formatted()

    fun clearDiagnosticLog() {
        diagnosticLog.clear()
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

private object NoOpSettingsAutoReservationRepository : AutoReservationRepository {
    override suspend fun rules() = emptyList<AutoReservationRule>()
    override suspend fun replaceRules(rules: List<AutoReservationRule>) = Unit
    override suspend fun removeExpiredControls(today: java.time.LocalDate) = 0
    override suspend fun markPreparedControlsUnknown() = 0
    override suspend fun control(tilcod: String) = null
    override suspend fun saveControl(control: com.fallgist.nishinomiyalibrary.domain.model.AutoReservationControl) = Unit
    override fun latestRun() = kotlinx.coroutines.flow.flowOf<com.fallgist.nishinomiyalibrary.domain.model.AutoReservationLatestRun?>(null)
    override suspend fun replaceLatestRun(run: com.fallgist.nishinomiyalibrary.domain.model.AutoReservationLatestRun) = Unit
    override suspend fun markLatestRunAcknowledged(runId: Long) = false
}

/** backupExporterが未設定のテスト等での既定実装。呼ばれることを想定しない。 */
private object NoOpBackupExportPort : BackupExportPort {
    override suspend fun export(): String = error("バックアップのエクスポートが設定されていません")
}

