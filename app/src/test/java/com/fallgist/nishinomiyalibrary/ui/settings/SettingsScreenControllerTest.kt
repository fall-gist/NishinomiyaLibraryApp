package com.fallgist.nishinomiyalibrary.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.fallgist.nishinomiyalibrary.data.diagnostics.DiagnosticLog
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.sync.SyncScheduleStarter
import com.fallgist.nishinomiyalibrary.domain.model.ClosedDay
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.model.UserSummary
import com.fallgist.nishinomiyalibrary.domain.repository.CalendarRepository
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

    private suspend fun awaitInitialized(controller: SettingsScreenController) {
        withTimeout(5_000) { controller.state.first { it.initialized } }
    }

    private fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC)

    private fun controller(
        diagnosticLog: DiagnosticLog,
    ): SettingsScreenController = SettingsScreenController(
        familyRepository = FakeFamilyRepository(),
        statusRepository = FakeStatusRepository(),
        settingsStore = settingsStore(),
        calendarRepository = FakeCalendarRepository(),
        scheduleStarter = FakeScheduleStarter(),
        diagnosticLog = diagnosticLog,
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

    private class FakeFamilyRepository : FamilyRepository {
        override fun members(): Flow<List<Member>> = flowOf(emptyList())
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
}
