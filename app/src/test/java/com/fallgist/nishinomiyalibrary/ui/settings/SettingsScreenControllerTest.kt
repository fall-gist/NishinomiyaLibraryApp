package com.fallgist.nishinomiyalibrary.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.fallgist.nishinomiyalibrary.data.diagnostics.DiagnosticLog
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.sync.SyncScheduleStarter
import com.fallgist.nishinomiyalibrary.domain.model.ClosedDay
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationControl
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationLatestRun
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationRule
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.model.UserSummary
import com.fallgist.nishinomiyalibrary.domain.repository.CalendarRepository
import com.fallgist.nishinomiyalibrary.domain.repository.AutoReservationRepository
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SyncLog
import com.fallgist.nishinomiyalibrary.domain.repository.SyncResult
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** 設定画面Controllerの、診断ログのオン/オフ・件数表示が設定と連動することの確認。 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenControllerTest {
    // 2026-08-16のCI再発(調査結果は2026-08-18、docs/handoff.md参照)を受けての値。ローカル20回連続で
    // 失敗0/0%、実測12〜18msのため根本原因ではなく先延ばしの対処。次回調整はこの1箇所で済む。
    private companion object {
        const val STATE_TIMEOUT_MILLIS = 15_000L
    }
    private lateinit var context: Context
    private val dataStoreScopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        dataStoreScopes.forEach(CoroutineScope::cancel)
    }

    @Test
    fun `初期状態は診断ログ記録オフで件数0`() = runBlocking {
        val diagnosticLog = DiagnosticLog(fixedClock())
        val controller = controller(diagnosticLog = diagnosticLog)
        awaitInitialized(controller)

        assertFalse(controller.state.value.diagnosticLogEnabled)
        assertEquals(0, controller.state.value.diagnosticLogLineCount)
        assertFalse(diagnosticLog.recording)
        controller.close()
    }

    @Test
    fun `setDiagnosticLogEnabledは設定へ保存されDiagnosticLogのrecordingへ反映される`() = runBlocking {
        val diagnosticLog = DiagnosticLog(fixedClock())
        val controller = controller(diagnosticLog = diagnosticLog)
        awaitInitialized(controller)

        controller.setDiagnosticLogEnabled(true)
        awaitState(controller, "setDiagnosticLogEnabled(true)後のdiagnosticLogEnabled反映") { it.diagnosticLogEnabled }

        assertTrue(controller.state.value.diagnosticLogEnabled)
        assertTrue(diagnosticLog.recording)

        controller.setDiagnosticLogEnabled(false)
        awaitState(controller, "setDiagnosticLogEnabled(false)後のdiagnosticLogEnabled反映") { !it.diagnosticLogEnabled }

        assertFalse(controller.state.value.diagnosticLogEnabled)
        assertFalse(diagnosticLog.recording)
        controller.close()
    }

    @Test
    fun `setWarnBeforeClearingSelectionは設定へ保存される(design追補§6,3)`() = runBlocking {
        val diagnosticLog = DiagnosticLog(fixedClock())
        val controller = controller(diagnosticLog = diagnosticLog)
        awaitInitialized(controller)

        // 既定はオン
        assertTrue(controller.state.value.settings.warnBeforeClearingSelection)

        controller.setWarnBeforeClearingSelection(false)
        awaitState(controller, "setWarnBeforeClearingSelection(false)後の反映") { !it.settings.warnBeforeClearingSelection }
        assertFalse(controller.state.value.settings.warnBeforeClearingSelection)

        controller.setWarnBeforeClearingSelection(true)
        awaitState(controller, "setWarnBeforeClearingSelection(true)後の反映") { it.settings.warnBeforeClearingSelection }
        assertTrue(controller.state.value.settings.warnBeforeClearingSelection)
        controller.close()
    }

    @Test
    fun `診断ログの件数は記録内容に連動しclearで0へ戻る`() = runBlocking {
        val diagnosticLog = DiagnosticLog(fixedClock())
        val controller = controller(diagnosticLog = diagnosticLog)
        awaitInitialized(controller)

        controller.setDiagnosticLogEnabled(true)
        awaitState(controller, "setDiagnosticLogEnabled(true)後のdiagnosticLogEnabled反映") { it.diagnosticLogEnabled }
        diagnosticLog.record("request", "GET /foo")
        diagnosticLog.record("response", "GET /foo status=200 redirect=-")
        awaitState(controller, "record 2件後のdiagnosticLogLineCount反映") { it.diagnosticLogLineCount == 2 }

        assertEquals(2, controller.state.value.diagnosticLogLineCount)
        assertTrue(controller.formattedDiagnosticLog().contains("GET /foo"))

        controller.clearDiagnosticLog()
        awaitState(controller, "clearDiagnosticLog後のdiagnosticLogLineCount反映") { it.diagnosticLogLineCount == 0 }

        assertEquals(0, controller.state.value.diagnosticLogLineCount)
        controller.close()
    }

    @Test
    fun `有効なルールとメンバーと既定館がある場合は自動予約をONに保存できる`() = runBlocking {
        val settingsStore = settingsStore()
        val autoReservationRepository = FakeAutoReservationRepository(listOf(rule(1, "AI")))
        val controller = controller(
            diagnosticLog = DiagnosticLog(fixedClock()),
            familyRepository = FakeFamilyRepository(listOf(member())),
            settingsStore = settingsStore,
            autoReservationRepository = autoReservationRepository,
        )
        awaitInitialized(controller)
        awaitRuleIds(controller, listOf(1))

        controller.setAutoReservationEnabled(true)
        awaitState(controller, "setAutoReservationEnabled(true)後のautoReservationEnabled反映") {
            it.settings.autoReservationEnabled
        }

        assertTrue(settingsStore.settings.first().autoReservationEnabled)
        assertEquals(null, controller.state.value.autoReservationError)
        controller.close()
    }

    @Test
    fun `初期化完了前のON操作は初期化完了を待ってから検証され保存される`() = runBlocking {
        val settingsStore = settingsStore()
        val controller = controller(
            diagnosticLog = DiagnosticLog(fixedClock()),
            familyRepository = DelayedMembersFamilyRepository(listOf(member()), 500),
            settingsStore = settingsStore,
            autoReservationRepository = FakeAutoReservationRepository(listOf(rule(1, "AI"))),
        )

        // 初期化完了前(awaitInitialized・awaitRuleIds未実施)にONを押しても、membersが空のまま
        // 検証が走って誤ったエラーへ倒れないこと。setAutoReservationEnabledのinitialStateLoaded.await()
        // がこれを担保する。
        controller.setAutoReservationEnabled(true)
        awaitState(controller, "初期化完了前のON操作後のautoReservationEnabled反映") {
            it.settings.autoReservationEnabled
        }

        assertTrue(settingsStore.settings.first().autoReservationEnabled)
        assertEquals(null, controller.state.value.autoReservationError)
        controller.close()
    }

    @Test
    fun `有効なルールがない場合は自動予約をONに保存せずエラーを表示する`() = runBlocking {
        val settingsStore = settingsStore()
        val controller = controller(
            diagnosticLog = DiagnosticLog(fixedClock()),
            familyRepository = FakeFamilyRepository(listOf(member())),
            settingsStore = settingsStore,
        )
        awaitInitialized(controller)

        controller.setAutoReservationEnabled(true)
        awaitState(controller, "有効なルールがない場合のautoReservationError表示") { it.autoReservationError != null }

        assertFalse(settingsStore.settings.first().autoReservationEnabled)
        assertTrue(controller.state.value.autoReservationError!!.contains("有効"))
        controller.close()
    }

    @Test
    fun `メンバーがいない場合は自動予約をONに保存せずエラーを表示する`() = runBlocking {
        val settingsStore = settingsStore()
        val controller = controller(
            diagnosticLog = DiagnosticLog(fixedClock()),
            settingsStore = settingsStore,
            autoReservationRepository = FakeAutoReservationRepository(listOf(rule(1, "AI"))),
        )
        awaitInitialized(controller)
        awaitRuleIds(controller, listOf(1))

        controller.setAutoReservationEnabled(true)
        awaitState(controller, "メンバーがいない場合のautoReservationError表示") { it.autoReservationError != null }

        assertFalse(settingsStore.settings.first().autoReservationEnabled)
        assertTrue(controller.state.value.autoReservationError!!.contains("メンバー"))
        controller.close()
    }

    @Test
    fun `既定館が無効な場合は自動予約をONに保存せずエラーを表示する`() = runBlocking {
        val settingsStore = settingsStore()
        settingsStore.updateDefaultCalendarLibrary("unknown")
        val controller = controller(
            diagnosticLog = DiagnosticLog(fixedClock()),
            familyRepository = FakeFamilyRepository(listOf(member())),
            settingsStore = settingsStore,
            autoReservationRepository = FakeAutoReservationRepository(listOf(rule(1, "AI"))),
        )
        awaitInitialized(controller)
        awaitRuleIds(controller, listOf(1))

        controller.setAutoReservationEnabled(true)
        awaitState(controller, "既定館が無効な場合のautoReservationError表示") { it.autoReservationError != null }

        assertFalse(settingsStore.settings.first().autoReservationEnabled)
        assertTrue(controller.state.value.autoReservationError!!.contains("既定受取館"))
        controller.close()
    }

    @Test
    fun `自動予約がONのまま最後の有効ルールをOFFにすると警告を表示する`() = runBlocking {
        val settingsStore = settingsStore()
        settingsStore.updateAutoReservationEnabled(true)
        val autoReservationRepository = FakeAutoReservationRepository(listOf(rule(1, "AI")))
        val controller = controller(
            diagnosticLog = DiagnosticLog(fixedClock()),
            familyRepository = FakeFamilyRepository(listOf(member())),
            settingsStore = settingsStore,
            autoReservationRepository = autoReservationRepository,
        )
        awaitInitialized(controller)
        awaitRuleIds(controller, listOf(1))

        controller.setAutoReservationRuleEnabled(1, false)
        awaitState(controller, "最後の有効ルールOFF後のautoReservationWarning表示") { it.autoReservationWarning != null }

        assertTrue(settingsStore.settings.first().autoReservationEnabled)
        assertFalse(autoReservationRepository.currentRules.single().enabled)
        assertTrue(controller.state.value.autoReservationWarning!!.contains("ON"))
        controller.close()
    }

    @Test
    fun `初回ルール読込中の追加とONは読込完了後に既存ルールを保持して保存する`() = runBlocking {
        val initialRulesGate = CompletableDeferred<Unit>()
        val settingsStore = settingsStore()
        val autoReservationRepository = FakeAutoReservationRepository(
            initialRules = listOf(rule(1, "既存")),
            initialRulesGate = initialRulesGate,
        )
        val controller = controller(
            diagnosticLog = DiagnosticLog(fixedClock()),
            familyRepository = FakeFamilyRepository(listOf(member())),
            settingsStore = settingsStore,
            autoReservationRepository = autoReservationRepository,
        )
        // combineの初回発行(members/settingsの確定)を待つ。初回ルール読込ゲートとは独立した経路であり、
        // テストの意図(初回ルール読込中の追加・ONが読込完了後まで保存されないこと)は変わらない。
        awaitInitialized(controller)
        withTimeout(STATE_TIMEOUT_MILLIS) { autoReservationRepository.firstRulesReadStarted.await() }

        controller.saveAutoReservationRule(null, listOf("追加"), emptyList())
        controller.setAutoReservationEnabled(true)

        assertEquals(0, autoReservationRepository.replaceRulesCalls)
        assertFalse(settingsStore.settings.first().autoReservationEnabled)

        initialRulesGate.complete(Unit)
        awaitRuleIds(controller, listOf(1, 2))
        awaitState(controller, "初回ルール読込完了後のautoReservationEnabled反映") {
            it.settings.autoReservationEnabled
        }

        val expected = listOf(rule(1, "既存"), rule(2, "追加", sortOrder = 1))
        assertEquals(expected, autoReservationRepository.currentRules)
        assertEquals(expected, controller.state.value.autoReservationRules)
        controller.close()
    }

    @Test
    fun `初回ルール読込失敗後はルール操作とONを保存せず読込エラーを維持する`() = runBlocking {
        val settingsStore = settingsStore()
        val autoReservationRepository = FakeAutoReservationRepository(failInitialRules = true)
        val controller = controller(
            diagnosticLog = DiagnosticLog(fixedClock()),
            familyRepository = FakeFamilyRepository(listOf(member())),
            settingsStore = settingsStore,
            autoReservationRepository = autoReservationRepository,
        )
        awaitInitialized(controller)
        val initialError = awaitState(controller, "初回ルール読込失敗後のautoReservationError表示") {
            it.autoReservationError == "ルールを読み込めませんでした"
        }.autoReservationError

        // initialRulesSettledは初回ルール読込の決着(成功/失敗)を表すDeferred。失敗確定後はfalseで
        // 完了済みのため、ここでのawaitは中断しない。mutateRulesはCoroutineStart.UNDISPATCHEDで
        // 起動するため、完了済みDeferredのawait()は呼び出しスレッドで同期的に返り、この後の
        // saveAutoReservationRule呼び出しは戻ってきた時点でreplaceRulesまでの判定が完了している。
        // したがって実時間待機(withTimeoutOrNull)なしに直後のアサーションが決定論的になる。
        assertFalse(withTimeout(STATE_TIMEOUT_MILLIS) { controller.initialRulesSettled.await() })

        controller.saveAutoReservationRule(null, listOf("追加"), emptyList())
        controller.setAutoReservationEnabled(true)

        assertEquals(0, autoReservationRepository.replaceRulesCalls)
        assertFalse(settingsStore.settings.first().autoReservationEnabled)
        assertEquals(initialError, controller.state.value.autoReservationError)
        controller.close()
    }

    @Test
    fun `ルールの追加編集削除並べ替えは連番の順序で保存され状態へ反映される`() = runBlocking {
        val autoReservationRepository = FakeAutoReservationRepository(listOf(rule(99, "初期")))
        val controller = controller(
            diagnosticLog = DiagnosticLog(fixedClock()),
            autoReservationRepository = autoReservationRepository,
        )
        awaitInitialized(controller)
        awaitRuleIds(controller, listOf(99))
        controller.removeAutoReservationRule(99)
        awaitRuleIds(controller, emptyList())
        val replaceRulesCallsBeforeMutations = autoReservationRepository.replaceRulesCalls

        controller.saveAutoReservationRule(null, listOf("A"), emptyList())
        awaitRuleIds(controller, listOf(1))
        controller.saveAutoReservationRule(null, listOf("B"), emptyList())
        awaitRuleIds(controller, listOf(1, 2))
        controller.saveAutoReservationRule(1, listOf("C"), emptyList())
        awaitRuleIds(controller, listOf(1, 2))
        assertEquals(listOf(1L, 2L), controller.state.value.autoReservationRules.map(AutoReservationRule::id))
        assertEquals(listOf(0, 1), controller.state.value.autoReservationRules.map(AutoReservationRule::sortOrder))
        controller.removeAutoReservationRule(2)
        awaitRuleIds(controller, listOf(1))
        controller.saveAutoReservationRule(null, listOf("D"), emptyList())
        awaitRuleIds(controller, listOf(1, 2))
        controller.moveAutoReservationRule(2, -1)
        awaitRuleIds(controller, listOf(2, 1))

        val expected = listOf(
            rule(2, "D", sortOrder = 0),
            rule(1, "C", sortOrder = 1),
        )
        assertEquals(expected, autoReservationRepository.currentRules)
        assertEquals(expected, controller.state.value.autoReservationRules)
        assertEquals(6, autoReservationRepository.replaceRulesCalls - replaceRulesCallsBeforeMutations)
        controller.close()
    }

    @Test
    fun `空の含める語は保存せずエラーを表示する`() = runBlocking {
        val autoReservationRepository = FakeAutoReservationRepository(listOf(rule(99, "初期")))
        val controller = controller(
            diagnosticLog = DiagnosticLog(fixedClock()),
            autoReservationRepository = autoReservationRepository,
        )
        awaitInitialized(controller)
        awaitRuleIds(controller, listOf(99))
        controller.removeAutoReservationRule(99)
        awaitRuleIds(controller, emptyList())
        val replaceRulesCallsBeforeMutation = autoReservationRepository.replaceRulesCalls

        controller.saveAutoReservationRule(null, listOf(" "), emptyList())
        awaitState(controller, "空の含める語保存後のautoReservationError表示") { it.autoReservationError != null }

        assertEquals(replaceRulesCallsBeforeMutation, autoReservationRepository.replaceRulesCalls)
        assertTrue(controller.state.value.autoReservationError!!.contains("含める語"))
        controller.close()
    }

    @Test
    fun `連続してルールを追加しても更新が失われない`() = runBlocking {
        val autoReservationRepository = FakeAutoReservationRepository(listOf(rule(99, "初期")))
        val controller = controller(
            diagnosticLog = DiagnosticLog(fixedClock()),
            autoReservationRepository = autoReservationRepository,
        )
        awaitInitialized(controller)
        awaitRuleIds(controller, listOf(99))
        controller.removeAutoReservationRule(99)
        awaitRuleIds(controller, emptyList())

        controller.saveAutoReservationRule(null, listOf("A"), emptyList())
        controller.saveAutoReservationRule(null, listOf("B"), emptyList())
        controller.saveAutoReservationRule(null, listOf("C"), emptyList())
        awaitRuleIds(controller, listOf(1, 2, 3))

        assertEquals(listOf("A", "B", "C"), autoReservationRepository.currentRules.map { it.includeTerms.single() })
        assertEquals(listOf(0, 1, 2), autoReservationRepository.currentRules.map { it.sortOrder })
        controller.close()
    }

    private suspend fun awaitInitialized(controller: SettingsScreenController) {
        awaitState(controller, "初期化完了(initialized=true)") { it.initialized }
    }

    private suspend fun awaitRuleIds(controller: SettingsScreenController, ids: List<Long>) {
        awaitState(controller, "autoReservationRulesがid=${ids}になること") {
            it.autoReservationRules.map(AutoReservationRule::id) == ids
        }
    }

    /**
     * controller.stateが条件を満たすまで待つ共通ヘルパー。
     * タイムアウト時は`TimeoutCancellationException`だけでなく、判断材料になる状態の要点を
     * メッセージに含めて失敗させる(2026-08-16 CI再発・2026-08-18調査、docs/handoff.md参照)。
     * 機微情報(カード番号等)は含めない。
     */
    private suspend fun awaitState(
        controller: SettingsScreenController,
        description: String,
        predicate: (SettingsUiState) -> Boolean,
    ): SettingsUiState {
        val result = withTimeoutOrNull(STATE_TIMEOUT_MILLIS) { controller.state.first(predicate) }
        if (result != null) return result
        val current = controller.state.value
        fail(
            "「$description」が${STATE_TIMEOUT_MILLIS}ms以内に成立しませんでした。" +
                "現在値: initialized=${current.initialized}, " +
                "autoReservationEnabled=${current.settings.autoReservationEnabled}, " +
                "autoReservationError=${current.autoReservationError}, " +
                "autoReservationWarning=${current.autoReservationWarning}, " +
                "autoReservationRuleIds=${current.autoReservationRules.map(AutoReservationRule::id)}, " +
                "diagnosticLogEnabled=${current.diagnosticLogEnabled}, " +
                "diagnosticLogLineCount=${current.diagnosticLogLineCount}"
        )
        error("到達しない: org.junit.Assert.failは例外を投げる")
    }

    private fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC)

    private fun controller(
        diagnosticLog: DiagnosticLog,
        familyRepository: FamilyRepository = FakeFamilyRepository(),
        settingsStore: SettingsStore = settingsStore(),
        calendarRepository: CalendarRepository = FakeCalendarRepository(),
        autoReservationRepository: AutoReservationRepository = FakeAutoReservationRepository(),
    ): SettingsScreenController = SettingsScreenController(
        familyRepository = familyRepository,
        statusRepository = FakeStatusRepository(),
        settingsStore = settingsStore,
        calendarRepository = calendarRepository,
        scheduleStarter = FakeScheduleStarter(),
        diagnosticLog = diagnosticLog,
        autoReservationRepository = autoReservationRepository,
        dispatcher = Dispatchers.Default,
    )

    private fun settingsStore(): SettingsStore {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStoreScopes += scope
        return SettingsStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(context.filesDir, "settings-controller-${UUID.randomUUID()}.preferences_pb") },
            ),
        )
    }

    private fun member() = Member(1, "テスト利用者", "#000000", "1234", 0)

    private fun rule(id: Long, term: String, sortOrder: Int = 0) = AutoReservationRule(
        id = id,
        enabled = true,
        sortOrder = sortOrder,
        includeTerms = listOf(term),
    )

    private class FakeFamilyRepository(initialMembers: List<Member> = emptyList()) : FamilyRepository {
        private val memberFlow = MutableStateFlow(initialMembers)

        override fun members(): Flow<List<Member>> = memberFlow
        override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) = Unit
        override suspend fun updateMember(member: Member, newPassword: String?) = Unit
        override suspend fun removeMember(memberId: Long) = Unit
    }

    /** membersの初回発行を遅らせ、combine購読の初期化完了前の操作を再現する。 */
    private class DelayedMembersFamilyRepository(
        private val members: List<Member>,
        private val delayMillis: Long,
    ) : FamilyRepository {
        override fun members(): Flow<List<Member>> = flow { delay(delayMillis); emit(members) }
        override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) = Unit
        override suspend fun updateMember(member: Member, newPassword: String?) = Unit
        override suspend fun removeMember(memberId: Long) = Unit
    }

    private class FakeStatusRepository : StatusRepository {
        override fun loans(): Flow<List<Loan>> = flowOf(emptyList())
        override fun reservations(): Flow<List<Reservation>> = flowOf(emptyList())
        override fun pickupSubmissions(): Flow<List<com.fallgist.nishinomiyalibrary.domain.model.ReservationPickupSubmissionRecord>> =
            flowOf(emptyList())
        override fun shelf(memberId: Long): Flow<List<ShelfItem>> = flowOf(emptyList())
        override fun summaries(): Flow<List<UserSummary>> = flowOf(emptyList())
        override fun lastSync(): Flow<SyncLog?> = MutableStateFlow(null)
        override suspend fun syncAll(trigger: SyncTrigger): SyncResult = SyncResult.Completed(0, 0)
    }

    private class FakeCalendarRepository(
        override val libraries: List<Library> = listOf(Library("106", "中央図書館")),
    ) : CalendarRepository {
        override fun closedDays(libraryCode: String): Flow<List<ClosedDay>> = flowOf(emptyList())
        override suspend fun refreshClosedDays(libraryCode: String) = Unit
    }

    private class FakeScheduleStarter : SyncScheduleStarter {
        override suspend fun scheduleFromSettings() = Unit
    }

    private class FakeAutoReservationRepository(
        initialRules: List<AutoReservationRule> = emptyList(),
        private val initialRulesGate: CompletableDeferred<Unit>? = null,
        private val failInitialRules: Boolean = false,
    ) : AutoReservationRepository {
        var currentRules: List<AutoReservationRule> = initialRules.sortedBy { it.sortOrder }
            private set
        var replaceRulesCalls: Int = 0
            private set
        val firstRulesReadStarted = CompletableDeferred<Unit>()
        private var rulesCalls = 0

        override suspend fun rules(): List<AutoReservationRule> {
            val isInitialRulesRead = rulesCalls++ == 0
            if (isInitialRulesRead) {
                firstRulesReadStarted.complete(Unit)
                initialRulesGate?.await()
                if (failInitialRules) error("初回ルール読込失敗")
            }
            return currentRules
        }

        override suspend fun replaceRules(rules: List<AutoReservationRule>) {
            replaceRulesCalls += 1
            currentRules = rules.sortedBy { it.sortOrder }
        }

        override suspend fun removeExpiredControls(today: java.time.LocalDate): Int = 0
        override suspend fun markPreparedControlsUnknown(): Int = 0
        override suspend fun control(tilcod: String): AutoReservationControl? = null
        override suspend fun saveControl(control: AutoReservationControl) = Unit
        override fun latestRun(): Flow<AutoReservationLatestRun?> = flowOf(null)
        override suspend fun replaceLatestRun(run: AutoReservationLatestRun) = Unit
        override suspend fun markLatestRunAcknowledged(runId: Long) = false
    }
}
