package com.fallgist.nishinomiyalibrary.ui.home

import com.fallgist.nishinomiyalibrary.data.backup.BackupImportPort
import com.fallgist.nishinomiyalibrary.data.backup.BackupImportResult
import com.fallgist.nishinomiyalibrary.data.sync.SyncScheduleStarter
import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.model.UserSummary
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SyncLog
import com.fallgist.nishinomiyalibrary.domain.repository.SyncResult
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger
import com.fallgist.nishinomiyalibrary.domain.repository.AutoReservationRepository
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationLatestRun
import com.fallgist.nishinomiyalibrary.ui.member.MemberRegistrationResult
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationForm
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeScreenControllerTest {
    @Test fun `未確認履歴はホーム表示時だけ出し確認済みと置換を追随する`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val history = FakeHistory(AutoReservationLatestRun(1, 0, "{\"success\":0,\"skipped\":0,\"error\":0}", false))
        val controller = HomeScreenController(FakeFamilyRepository(MutableStateFlow(listOf(papa))), FakeStatusRepository(), FakeScheduleStarter(), history, dispatcher, { today })
        advanceUntilIdle(); assertEquals(false, controller.state.value.showAutoReservationDialog)
        controller.onHomeVisible(); assertEquals(true, controller.state.value.showAutoReservationDialog)
        controller.acknowledgeLatestAutoReservationRun(1); advanceUntilIdle(); assertEquals(1, history.acks); assertEquals(false, controller.state.value.showAutoReservationDialog)
        history.flow.value = history.flow.value!!.copy(runId = 2, acknowledged = false); advanceUntilIdle(); assertEquals(true, controller.state.value.showAutoReservationDialog)
        history.flow.value = history.flow.value!!.copy(acknowledged = true); advanceUntilIdle(); assertEquals(false, controller.state.value.showAutoReservationDialog)
    }
    @Test
    fun concurrentAcknowledgementKeepsNewRunVisible() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val history = FakeHistory(AutoReservationLatestRun(1, 0, "{\"success\":0,\"skipped\":0,\"error\":0}", false))
        val acknowledgementGate = CompletableDeferred<Unit>()
        history.acknowledgementGate = acknowledgementGate
        val controller = HomeScreenController(FakeFamilyRepository(MutableStateFlow(listOf(papa))), FakeStatusRepository(), FakeScheduleStarter(), history, dispatcher, { today })

        advanceUntilIdle()
        controller.onHomeVisible()
        controller.acknowledgeLatestAutoReservationRun(1)
        assertEquals(listOf(1L), history.acknowledgedRunIds)
        assertEquals(false, controller.state.value.showAutoReservationDialog)

        history.flow.value = AutoReservationLatestRun(2, 0, "{\"success\":0,\"skipped\":0,\"error\":0}", false)
        advanceUntilIdle()
        assertEquals(2L, controller.state.value.latestAutoReservationRun!!.runId)
        assertTrue(controller.state.value.showAutoReservationDialog)

        acknowledgementGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(1L), history.acknowledgedRunIds)
        assertEquals(2L, history.flow.value!!.runId)
        assertEquals(false, history.flow.value!!.acknowledged)
        assertTrue(controller.state.value.showAutoReservationDialog)
        controller.close()
    }

    @Test
    fun acknowledgingOldRunDoesNotHideNewRun() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val history = FakeHistory(AutoReservationLatestRun(1, 0, "{\"success\":0,\"skipped\":0,\"error\":0}", false))
        val controller = HomeScreenController(FakeFamilyRepository(MutableStateFlow(listOf(papa))), FakeStatusRepository(), FakeScheduleStarter(), history, dispatcher, { today })

        advanceUntilIdle()
        controller.onHomeVisible()
        history.flow.value = AutoReservationLatestRun(2, 0, "{\"success\":0,\"skipped\":0,\"error\":0}", false)
        advanceUntilIdle()
        assertEquals(2L, controller.state.value.latestAutoReservationRun!!.runId)
        assertTrue(controller.state.value.showAutoReservationDialog)

        controller.acknowledgeLatestAutoReservationRun(1)
        advanceUntilIdle()

        assertEquals(listOf(1L), history.acknowledgedRunIds)
        assertEquals(2L, controller.state.value.latestAutoReservationRun!!.runId)
        assertTrue(!controller.state.value.latestAutoReservationAcknowledged)
        assertTrue(controller.state.value.showAutoReservationDialog)
        controller.close()
    }

    private val today = LocalDate.of(2026, 7, 20)
    private val papa = Member(id = 1, name = "パパ", colorHex = "#3D6DB5", cardNumber = "", sortOrder = 0)
    private val hana = Member(id = 2, name = "はな", colorHex = "#5FA05A", cardNumber = "", sortOrder = 1)

    private fun loan(memberId: Long, title: String) = Loan(
        memberId = memberId,
        title = title,
        materialType = "本",
        lendingLibrary = "高須分室",
        loanDate = today.minusDays(7),
        dueDate = today.plusDays(1),
        status = "貸出中",
    )

    @Test
    fun selectMember_filtersDueGroupsToSelectedMember() = runTest {
        val status = FakeStatusRepository(
            loans = MutableStateFlow(listOf(loan(papa.id, "パパの本"), loan(hana.id, "はなの本"))),
        )
        val controller = HomeScreenController(
            familyRepository = FakeFamilyRepository(MutableStateFlow(listOf(papa, hana))),
            statusRepository = status,
            scheduleStarter = FakeScheduleStarter(),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            today = { today },
        )
        advanceUntilIdle()

        assertEquals(2, controller.state.value.members.size)
        assertEquals(2, controller.state.value.dueGroups.flatMap { it.books }.size)

        controller.selectMember(hana.id)
        advanceUntilIdle()

        val books = controller.state.value.dueGroups.flatMap { it.books }
        assertEquals(1, books.size)
        assertEquals("はな", books.single().memberName)

        controller.close()
    }

    @Test
    fun onScreenLaunched_startsScheduleOnce() = runTest {
        val scheduler = FakeScheduleStarter()
        val controller = HomeScreenController(
            familyRepository = FakeFamilyRepository(MutableStateFlow(emptyList())),
            statusRepository = FakeStatusRepository(),
            scheduleStarter = scheduler,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            today = { today },
        )

        controller.onScreenLaunched()
        controller.onScreenLaunched()

        assertEquals(1, scheduler.count)
        controller.close()
    }

    data class AddedMember(val name: String, val colorHex: String, val cardNumber: String, val password: String)

    private class FakeFamilyRepository(
        private val membersFlow: MutableStateFlow<List<Member>>,
    ) : FamilyRepository {
        val added = mutableListOf<AddedMember>()

        override fun members(): Flow<List<Member>> = membersFlow

        override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) {
            added += AddedMember(name, colorHex, cardNumber, password)
        }

        override suspend fun updateMember(member: Member, newPassword: String?) = Unit

        override suspend fun removeMember(memberId: Long) = Unit
    }

    @Test
    fun register_validForm_normalizesAndSavesMember() = runTest {
        val family = FakeFamilyRepository(MutableStateFlow(emptyList()))
        val controller = HomeScreenController(
            familyRepository = family,
            statusRepository = FakeStatusRepository(),
            scheduleStarter = FakeScheduleStarter(),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            today = { today },
        )

        val result = controller.register(
            RegistrationForm(name = "  太郎  ", colorHex = "#12ab34", cardNumber = " 123 ", password = "pw"),
        )

        assertEquals(MemberRegistrationResult.Saved, result)
        assertEquals(1, family.added.size)
        val added = family.added.single()
        assertEquals("太郎", added.name)
        assertEquals("#12AB34", added.colorHex)
        assertEquals("123", added.cardNumber)
        assertEquals("pw", added.password)
        controller.close()
    }

    @Test
    fun register_invalidForm_returnsErrorsAndDoesNotSave() = runTest {
        val family = FakeFamilyRepository(MutableStateFlow(emptyList()))
        val controller = HomeScreenController(
            familyRepository = family,
            statusRepository = FakeStatusRepository(),
            scheduleStarter = FakeScheduleStarter(),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            today = { today },
        )

        val result = controller.register(
            RegistrationForm(name = "", colorHex = "bad", cardNumber = "12A", password = ""),
        )

        assertTrue(result is MemberRegistrationResult.Invalid)
        val errors = (result as MemberRegistrationResult.Invalid).errors
        assertTrue(errors.name != null)
        assertTrue(errors.colorHex != null)
        assertTrue(errors.cardNumber != null)
        assertTrue(errors.password != null)
        assertTrue(family.added.isEmpty())
        controller.close()
    }

    private class FakeStatusRepository(
        private val loans: MutableStateFlow<List<Loan>> = MutableStateFlow(emptyList()),
        private val reservations: MutableStateFlow<List<Reservation>> = MutableStateFlow(emptyList()),
        private val lastSync: MutableStateFlow<SyncLog?> = MutableStateFlow(null),
        private val syncResult: SyncResult = SyncResult.Completed(0, 0),
    ) : StatusRepository {
        override fun loans(): Flow<List<Loan>> = loans

        override fun reservations(): Flow<List<Reservation>> = reservations

        override fun pickupSubmissions(): Flow<List<com.fallgist.nishinomiyalibrary.domain.model.ReservationPickupSubmissionRecord>> =
            flowOf(emptyList())

        override fun shelf(memberId: Long): Flow<List<ShelfItem>> = flowOf(emptyList())

        override fun summaries(): Flow<List<UserSummary>> = flowOf(emptyList())

        override fun lastSync(): Flow<SyncLog?> = lastSync

        override suspend fun syncAll(trigger: SyncTrigger): SyncResult = syncResult
    }

    // 第3段(§5.1): 初期画面(メンバー未登録)からのバックアップ復元導線。
    // 処理そのものはBackupImportPortの委譲先(設定画面と共通)なので、ここではControllerが
    // 正しく委譲すること・失敗時にmembersが変化しない(登録フォームが残る)ことを検証する。
    private class FakeBackupImportPort(
        private val onImport: (String) -> BackupImportResult,
    ) : BackupImportPort {
        val importedJsonTexts = mutableListOf<String>()

        override suspend fun import(jsonText: String): BackupImportResult {
            importedJsonTexts += jsonText
            return onImport(jsonText)
        }
    }

    @Test
    fun importBackup_success_delegatesToPortAndReflectsMembers() = runTest {
        // 実装(BackupImporter)はRoomトランザクションでmembersを書き換え、
        // familyRepository.members()のFlowがそれを反映する。ここではそのFlowをテスト側で
        // 直接更新することで同じ観測結果を再現する。
        val membersFlow = MutableStateFlow<List<Member>>(emptyList())
        val family = FakeFamilyRepository(membersFlow)
        val importer = FakeBackupImportPort { json ->
            membersFlow.value = listOf(papa)
            BackupImportResult.Success(importedMemberCount = 1, requestNotificationPermission = false)
        }
        val controller = HomeScreenController(
            familyRepository = family,
            statusRepository = FakeStatusRepository(),
            scheduleStarter = FakeScheduleStarter(),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            today = { today },
            backupImporter = importer,
        )
        advanceUntilIdle()
        assertTrue(controller.state.value.members.isEmpty())

        val result = controller.importBackup("{\"formatVersion\":1}")
        advanceUntilIdle()

        assertTrue(result is BackupImportResult.Success)
        assertEquals(listOf("{\"formatVersion\":1}"), importer.importedJsonTexts)
        assertEquals(listOf(papa), controller.state.value.members)
        controller.close()
    }

    @Test
    fun importBackup_rejected_leavesMembersEmpty() = runTest {
        val membersFlow = MutableStateFlow<List<Member>>(emptyList())
        val family = FakeFamilyRepository(membersFlow)
        val importer = FakeBackupImportPort { BackupImportResult.Rejected("パースできませんでした") }
        val controller = HomeScreenController(
            familyRepository = family,
            statusRepository = FakeStatusRepository(),
            scheduleStarter = FakeScheduleStarter(),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            today = { today },
            backupImporter = importer,
        )
        advanceUntilIdle()

        val result = controller.importBackup("broken")
        advanceUntilIdle()

        assertTrue(result is BackupImportResult.Rejected)
        assertEquals("パースできませんでした", (result as BackupImportResult.Rejected).message)
        // 拒否は既存データを一切変更しない(§7)。membersが空のままであることは、
        // HomeScreenのwhen分岐(state.members.isEmpty())により登録フォームが残ることに対応する。
        assertTrue(controller.state.value.members.isEmpty())
        controller.close()
    }

    private class FakeScheduleStarter : SyncScheduleStarter {
        var count = 0

        override suspend fun scheduleFromSettings() {
            count++
        }
    }

    private class FakeHistory(run: AutoReservationLatestRun?) : AutoReservationRepository {
        val flow = MutableStateFlow(run)
        val acknowledgedRunIds = mutableListOf<Long>()
        var acknowledgementGate: CompletableDeferred<Unit>? = null
        val acks: Int get() = acknowledgedRunIds.size
        override suspend fun rules() = emptyList<com.fallgist.nishinomiyalibrary.domain.model.AutoReservationRule>()
        override suspend fun replaceRules(rules: List<com.fallgist.nishinomiyalibrary.domain.model.AutoReservationRule>) = Unit
        override suspend fun removeExpiredControls(today: java.time.LocalDate) = 0; override suspend fun markPreparedControlsUnknown() = 0
        override suspend fun control(tilcod: String) = null; override suspend fun saveControl(control: com.fallgist.nishinomiyalibrary.domain.model.AutoReservationControl) = Unit
        override fun latestRun() = flow; override suspend fun replaceLatestRun(run: AutoReservationLatestRun) = Unit
        override suspend fun markLatestRunAcknowledged(runId: Long): Boolean {
            acknowledgedRunIds += runId
            acknowledgementGate?.await()
            val latest = flow.value ?: return false
            if (latest.runId != runId) return false
            flow.value = latest.copy(acknowledged = true)
            return true
        }
    }
}
