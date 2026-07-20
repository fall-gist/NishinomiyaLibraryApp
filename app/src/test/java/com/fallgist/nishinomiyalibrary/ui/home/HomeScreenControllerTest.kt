package com.fallgist.nishinomiyalibrary.ui.home

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
import java.time.LocalDate
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

    @Test
    fun requestManualSync_reportsCompletionMessage() = runTest {
        val controller = HomeScreenController(
            familyRepository = FakeFamilyRepository(MutableStateFlow(emptyList())),
            statusRepository = FakeStatusRepository(syncResult = SyncResult.Completed(2, 0)),
            scheduleStarter = FakeScheduleStarter(),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            today = { today },
        )
        advanceUntilIdle()

        controller.requestManualSync()
        advanceUntilIdle()

        assertTrue(controller.state.value.syncMessage!!.contains("同期が完了"))
        assertEquals(false, controller.state.value.isSyncing)
        controller.close()
    }

    private class FakeFamilyRepository(
        private val membersFlow: MutableStateFlow<List<Member>>,
    ) : FamilyRepository {
        override fun members(): Flow<List<Member>> = membersFlow

        override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) = Unit

        override suspend fun updateMember(member: Member, newPassword: String?) = Unit

        override suspend fun removeMember(memberId: Long) = Unit
    }

    private class FakeStatusRepository(
        private val loans: MutableStateFlow<List<Loan>> = MutableStateFlow(emptyList()),
        private val reservations: MutableStateFlow<List<Reservation>> = MutableStateFlow(emptyList()),
        private val lastSync: MutableStateFlow<SyncLog?> = MutableStateFlow(null),
        private val syncResult: SyncResult = SyncResult.Completed(0, 0),
    ) : StatusRepository {
        override fun loans(): Flow<List<Loan>> = loans

        override fun reservations(): Flow<List<Reservation>> = reservations

        override fun shelf(memberId: Long): Flow<List<ShelfItem>> = flowOf(emptyList())

        override fun summaries(): Flow<List<UserSummary>> = flowOf(emptyList())

        override fun lastSync(): Flow<SyncLog?> = lastSync

        override suspend fun syncAll(trigger: SyncTrigger): SyncResult = syncResult
    }

    private class FakeScheduleStarter : SyncScheduleStarter {
        var count = 0

        override suspend fun scheduleFromSettings() {
            count++
        }
    }
}
