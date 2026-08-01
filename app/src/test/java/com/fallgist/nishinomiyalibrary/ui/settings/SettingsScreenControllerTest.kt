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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** 設定画面Controllerの、診断ログのオン/オフ・件数表示が設定と連動することの確認。 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenControllerTest {
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
        withTimeout(5_000) { controller.state.first { it.diagnosticLogEnabled } }

        assertTrue(controller.state.value.diagnosticLogEnabled)
        assertTrue(diagnosticLog.recording)

        controller.setDiagnosticLogEnabled(false)
        withTimeout(5_000) { controller.state.first { !it.diagnosticLogEnabled } }

        assertFalse(controller.state.value.diagnosticLogEnabled)
        assertFalse(diagnosticLog.recording)
        controller.close()
    }

    @Test
    fun `診断ログの件数は記録内容に連動しclearで0へ戻る`() = runBlocking {
        val diagnosticLog = DiagnosticLog(fixedClock())
        val controller = controller(diagnosticLog = diagnosticLog)
        awaitInitialized(controller)

        controller.setDiagnosticLogEnabled(true)
        withTimeout(5_000) { controller.state.first { it.diagnosticLogEnabled } }
        diagnosticLog.record("request", "GET /foo")
        diagnosticLog.record("response", "GET /foo status=200 redirect=-")
        withTimeout(5_000) { controller.state.first { it.diagnosticLogLineCount == 2 } }

        assertEquals(2, controller.state.value.diagnosticLogLineCount)
        assertTrue(controller.formattedDiagnosticLog().contains("GET /foo"))

        controller.clearDiagnosticLog()
        withTimeout(5_000) { controller.state.first { it.diagnosticLogLineCount == 0 } }

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
        withTimeout(5_000) { controller.state.first { it.settings.autoReservationEnabled } }

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
        withTimeout(5_000) { controller.state.first { it.autoReservationError != null } }

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
        withTimeout(5_000) { controller.state.first { it.autoReservationError != null } }

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
        withTimeout(5_000) { controller.state.first { it.autoReservationError != null } }

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
        withTimeout(5_000) { controller.state.first { it.autoReservationWarning != null } }

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
        withTimeout(5_000) { autoReservationRepository.firstRulesReadStarted.await() }

        controller.saveAutoReservationRule(null, listOf("追加"), emptyList())
        controller.setAutoReservationEnabled(true)

        assertEquals(0, autoReservationRepository.replaceRulesCalls)
        assertFalse(settingsStore.settings.first().autoReservationEnabled)

        initialRulesGate.complete(Unit)
        awaitRuleIds(controller, listOf(1, 2))
        withTimeout(5_000) { controller.state.first { it.settings.autoReservationEnabled } }

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
        val initialError = withTimeout(5_000) {
            controller.state.first { it.autoReservationError == "ルールを読み込めませんでした" }.autoReservationError
        }

        controller.saveAutoReservationRule(null, listOf("追加"), emptyList())
        controller.setAutoReservationEnabled(true)

        assertEquals(null, withTimeoutOrNull(250) { autoReservationRepository.replaceRulesAttempted.await() })
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
        withTimeout(5_000) { controller.state.first { it.autoReservationError != null } }

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
        withTimeout(5_000) { controller.state.first { it.initialized } }
    }

    private suspend fun awaitRuleIds(controller: SettingsScreenController, ids: List<Long>) {
        withTimeout(5_000) { controller.state.first { it.autoReservationRules.map(AutoReservationRule::id) == ids } }
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

    private class FakeStatusRepository : StatusRepository {
        override fun loans(): Flow<List<Loan>> = flowOf(emptyList())
        override fun reservations(): Flow<List<Reservation>> = flowOf(emptyList())
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
        val replaceRulesAttempted = CompletableDeferred<Unit>()
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
            replaceRulesAttempted.complete(Unit)
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
