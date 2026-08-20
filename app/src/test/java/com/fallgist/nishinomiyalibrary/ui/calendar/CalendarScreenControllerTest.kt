package com.fallgist.nishinomiyalibrary.ui.calendar

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.domain.model.ClosedDay
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationPickupSubmissionRecord
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.model.UserSummary
import com.fallgist.nishinomiyalibrary.domain.repository.CalendarRepository
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SyncLog
import com.fallgist.nishinomiyalibrary.domain.repository.SyncResult
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `combine`の3ソース(closedDays・loans・members)結線の確認に絞った最小限のテスト
 * (設計 `docs/design/calendar-due-dates.md` §5.2)。決定論的に駆動するため、SettingsStoreの
 * 内部DataStoreも含めて全コルーチンを同一の[StandardTestDispatcher]/testSchedulerへ載せ、
 * 実時間待ち・実スレッド間の競合が起きないようにする。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CalendarScreenControllerTest {
    private val dataStoreScopes = mutableListOf<CoroutineScope>()
    private val today: LocalDate = LocalDate.of(2026, 7, 20)

    @After
    fun tearDown() {
        dataStoreScopes.forEach(CoroutineScope::cancel)
    }

    @Test
    fun `貸出が流れてきたら該当月のセルに色が入る`() = runTest {
        val member = Member(1, "パパ", "#3D6DB5", "card", 0)
        val dueDate = LocalDate.of(2026, 7, 25)
        val loans = MutableStateFlow(listOf(loan(member.id, dueDate)))
        val members = MutableStateFlow(listOf(member))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(
            familyRepository = FakeFamilyRepository(members),
            statusRepository = FakeStatusRepository(loans),
            dispatcher = dispatcher,
        )

        advanceUntilIdle()

        val day25 = controller.state.value.months[0].weeks.flatten().first { it.dayOfMonth == 25 }
        assertEquals(listOf("#3D6DB5"), day25.dueMemberColors)
        controller.close()
    }

    @Test
    fun `メンバーが空なら色が入らない`() = runTest {
        val dueDate = LocalDate.of(2026, 7, 25)
        val loans = MutableStateFlow(listOf(loan(memberId = 1, dueDate = dueDate)))
        val members = MutableStateFlow(emptyList<Member>())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(
            familyRepository = FakeFamilyRepository(members),
            statusRepository = FakeStatusRepository(loans),
            dispatcher = dispatcher,
        )

        advanceUntilIdle()

        val allCells = controller.state.value.months.flatMap { it.weeks.flatten() }
        assertTrue(allCells.all { it.dueMemberColors.isEmpty() })
        controller.close()
    }

    private fun loan(memberId: Long, dueDate: LocalDate) = Loan(
        memberId = memberId,
        title = "本",
        materialType = "図書",
        lendingLibrary = "中央図書館",
        loanDate = dueDate.minusDays(14),
        dueDate = dueDate,
        status = "貸出中",
    )

    private fun TestScope.controller(
        familyRepository: FamilyRepository,
        statusRepository: StatusRepository,
        dispatcher: TestDispatcher,
        calendarRepository: CalendarRepository = FakeCalendarRepository(),
    ): CalendarScreenController = CalendarScreenController(
        calendarRepository = calendarRepository,
        settingsStore = settingsStore(dispatcher),
        familyRepository = familyRepository,
        statusRepository = statusRepository,
        dispatcher = dispatcher,
        today = { today },
    )

    /**
     * SettingsStoreの内部DataStoreも同一のtestSchedulerへ載せる。実DataStore(Dispatchers.IO)を
     * 使うと、controller側のStandardTestDispatcherと実スレッドが混在してadvanceUntilIdle()で
     * 決定論的に待てなくなる(実時間待ちの持ち込みになるため設計の禁止事項に触れる)。
     */
    private fun settingsStore(dispatcher: TestDispatcher): SettingsStore {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        dataStoreScopes += scope
        return SettingsStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File.createTempFile("calendar-controller-${UUID.randomUUID()}", ".preferences_pb") },
            ),
        )
    }

    private class FakeFamilyRepository(private val members: Flow<List<Member>>) : FamilyRepository {
        override fun members(): Flow<List<Member>> = members
        override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) = Unit
        override suspend fun updateMember(member: Member, newPassword: String?) = Unit
        override suspend fun removeMember(memberId: Long) = Unit
    }

    private class FakeStatusRepository(private val loans: Flow<List<Loan>>) : StatusRepository {
        override fun loans(): Flow<List<Loan>> = loans
        override fun reservations(): Flow<List<Reservation>> = flowOf(emptyList())
        override fun pickupSubmissions(): Flow<List<ReservationPickupSubmissionRecord>> = flowOf(emptyList())
        override fun shelf(memberId: Long): Flow<List<ShelfItem>> = flowOf(emptyList())
        override fun summaries(): Flow<List<UserSummary>> = flowOf(emptyList())
        override fun lastSync(): Flow<SyncLog?> = flowOf(null)
        override suspend fun syncAll(trigger: SyncTrigger): SyncResult = SyncResult.Completed(0, 0)
    }

    private class FakeCalendarRepository(
        override val libraries: List<Library> = listOf(Library("106", "中央図書館")),
    ) : CalendarRepository {
        override fun closedDays(libraryCode: String): Flow<List<ClosedDay>> = flowOf(emptyList())
        override suspend fun refreshClosedDays(libraryCode: String) = Unit
    }
}
