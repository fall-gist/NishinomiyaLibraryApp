package com.fallgist.nishinomiyalibrary.ui.debug

import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.domain.model.ClosedDay
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.model.UserSummary
import com.fallgist.nishinomiyalibrary.domain.repository.CalendarRepository
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReadingRecordRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SearchRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SyncLog
import com.fallgist.nishinomiyalibrary.domain.repository.SyncResult
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebugScreenControllerTest {
    @Test
    fun validator_rejectsAllRequiredInvalidValues() {
        val validation = RegistrationValidator.validate(
            RegistrationForm(name = "  ", colorHex = "#123", cardNumber = "12A", password = "  "),
        )

        assertTrue(validation is RegistrationValidation.Invalid)
        val errors = (validation as RegistrationValidation.Invalid).errors
        assertNotNull(errors.name)
        assertNotNull(errors.colorHex)
        assertNotNull(errors.cardNumber)
        assertNotNull(errors.password)
    }

    @Test
    fun invalidRegistration_neverCallsFamilyRepository() = runTest {
        val family = FakeFamilyRepository()
        val controller = controller(family = family)

        val action = controller.register(
            RegistrationForm(name = "", colorHex = "bad", cardNumber = "not-number", password = ""),
        )

        assertTrue(action is RegistrationAction.ValidationFailed)
        assertEquals(0, family.addCalls.size)
        controller.close()
    }

    @Test
    fun validRegistration_passesNormalizedValuesAndReturnsNoSecrets() = runTest {
        val family = FakeFamilyRepository()
        val controller = controller(family = family)
        val card = randomCardNumber()
        val password = randomSecret()

        val action = controller.register(
            RegistrationForm(name = "  太郎  ", colorHex = "#12ab34", cardNumber = " $card ", password = password),
        )

        assertEquals(RegistrationAction.Saved, action)
        val call = family.addCalls.single()
        assertEquals("太郎", call.name)
        assertEquals("#12AB34", call.colorHex)
        assertEquals(card, call.cardNumber)
        assertEquals(password, call.password)
        assertFalse(action.toString().contains(card))
        assertFalse(action.toString().contains(password))
        controller.close()
    }

    @Test
    fun manualSync_formatsCompletePartialSkippedAndFailureSafely() = runTest {
        val status = FakeStatusRepository()
        val controller = controller(status = status)

        status.syncResult = SyncResult.Completed(syncedMemberCount = 2, failedMemberCount = 0)
        assertTrue(controller.requestManualSync() is ManualSyncAction.Completed)
        assertTrue(controller.state.value.syncMessage.contains("同期が完了"))

        status.syncResult = SyncResult.Completed(syncedMemberCount = 1, failedMemberCount = 1)
        controller.requestManualSync()
        assertTrue(controller.state.value.syncMessage.contains("一部失敗: 1人"))

        status.syncResult = SyncResult.SkippedCooldown(nextAllowedAtEpochMillis = 0L)
        controller.requestManualSync()
        assertTrue(controller.state.value.syncMessage.contains("待機中"))

        status.syncFailure = IllegalStateException("下位例外の詳細")
        assertEquals(ManualSyncAction.Failed, controller.requestManualSync())
        assertEquals("同期に失敗しました。通信状況を確認して再試行してください", controller.state.value.syncMessage)
        controller.close()
    }

    @Test
    fun manualSync_preventsDuplicateRequestInController() = runTest {
        val status = FakeStatusRepository()
        val result = CompletableDeferred<SyncResult>()
        status.syncDeferred = result
        val controller = controller(status = status)

        val first = async { controller.requestManualSync() }
        runCurrent()
        assertTrue(controller.state.value.isSyncInProgress)
        assertEquals(ManualSyncAction.AlreadyInProgress, controller.requestManualSync())
        assertEquals(1, status.syncCalls)

        result.complete(SyncResult.Completed(syncedMemberCount = 1, failedMemberCount = 0))
        assertTrue(first.await() is ManualSyncAction.Completed)
        assertFalse(controller.state.value.isSyncInProgress)
        controller.close()
    }

    @Test
    fun aggregatedDisplay_hasMemberNamesAndNeverContainsCredentials() = runTest {
        val card = randomCardNumber()
        val password = randomSecret()
        val family = FakeFamilyRepository(
            members = listOf(Member(10L, "花子", "#1A2B3C", card, 0)),
        )
        val status = FakeStatusRepository().apply {
            loans.value = listOf(
                Loan(10L, "貸出資料", "図書", "中央図書館", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 25), "貸出中"),
                Loan(99L, "不明資料", "図書", "中央図書館", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 26), "貸出中"),
            )
            reservations.value = listOf(
                Reservation(10L, "予約資料", "図書", "北口図書館", LocalDate.of(2026, 7, 2), 3, ReservationState.READY, LocalDate.of(2026, 7, 30)),
            )
            shelves[10L] = MutableStateFlow(
                listOf(ShelfItem(10L, "1234567890123", "本棚資料", "メモ", LocalDate.of(2026, 7, 3), 1, "確認用本棚")),
            )
            summaries.value = listOf(UserSummary(10L, 1, 1, 1, 0))
            lastSync.value = SyncLog(1L, 0L, 1L, SyncTrigger.MANUAL, true, "memberId=10:成功")
        }
        val controller = controller(family = family, status = status)

        advanceUntilIdle()
        val rendered = controller.state.value.display.run {
            (memberLines + loanLines + reservationLines + shelfLines + summaryLines + lastSyncLine).joinToString("\n")
        }
        assertTrue(rendered.contains("花子"))
        assertTrue(rendered.contains("不明なメンバー"))
        assertTrue(rendered.contains("返却期限"))
        assertTrue(rendered.contains("取置期限"))
        assertTrue(rendered.contains("本棚資料"))
        assertTrue(rendered.contains("本棚: 確認用本棚"))
        assertFalse(rendered.contains(card))
        assertFalse(rendered.contains(password))
        assertFalse(rendered.contains("memberId=10"))
        controller.close()
    }

    @Test
    fun readingRecordSearch_displaysCountAndDelegatesInputToRepository() = runTest {
        val family = FakeFamilyRepository(members = listOf(Member(10L, "花子", "#1A2B3C", randomCardNumber(), 0)))
        val readingRecords = FakeReadingRecordRepository().apply {
            allRecords.value = listOf(
                ReadingRecord(10L, "1000000000001", "全角ＡＢＣ", LocalDate.of(2026, 7, 2), "中央図書館"),
                ReadingRecord(10L, "1000000000002", "別資料", LocalDate.of(2026, 7, 1), "北口図書館"),
            )
            searchResults.value = listOf(allRecords.value.first())
        }
        val controller = controller(family = family, readingRecords = readingRecords)

        controller.updateReadingRecordSearch("ａｂｃ")
        advanceUntilIdle()

        assertEquals(2, controller.state.value.display.readingRecordCount)
        assertEquals("ａｂｃ", readingRecords.searchQueries.last())
        assertTrue(controller.state.value.display.readingRecordLines.single().contains("全角ＡＢＣ"))
        controller.close()
    }

    @Test
    fun screenLaunch_retriesAfterExceptionAndOnlyCompletesAfterSuccess() = runTest {
        val scheduler = FakeSyncScheduleStarter(failure = IllegalStateException("下位例外の詳細"))
        val controller = controller(scheduleStarter = scheduler)

        controller.onScreenLaunched()
        assertEquals(1, scheduler.calls)
        assertEquals("自動同期の設定に失敗しました。次回画面を開いたときに再試行します", controller.state.value.scheduleWarning)

        scheduler.failure = null
        controller.onScreenLaunched()
        assertEquals(2, scheduler.calls)
        assertEquals(null, controller.state.value.scheduleWarning)

        controller.onScreenLaunched()
        assertEquals(2, scheduler.calls)
        controller.close()
    }

    @Test
    fun screenLaunch_retriesAfterCancellation() = runTest {
        val scheduler = FakeSyncScheduleStarter(failure = CancellationException("キャンセル"))
        val controller = controller(scheduleStarter = scheduler)

        val cancellation = supervisorScope {
            async {
                try {
                    controller.onScreenLaunched()
                    null
                } catch (exception: CancellationException) {
                    exception
                }
            }.await()
        }
        assertTrue(cancellation is CancellationException)
        assertEquals(1, scheduler.calls)

        scheduler.failure = null
        controller.onScreenLaunched()
        assertEquals(2, scheduler.calls)
        controller.close()
    }

    @Test
    fun screenLaunch_parallelCallsScheduleOnlyOnce() = runTest {
        val scheduler = FakeSyncScheduleStarter().apply {
            scheduleDeferred = CompletableDeferred()
        }
        val controller = controller(scheduleStarter = scheduler)

        val first = async { controller.onScreenLaunched() }
        runCurrent()
        val second = async { controller.onScreenLaunched() }
        runCurrent()
        assertEquals(1, scheduler.calls)

        scheduler.scheduleDeferred!!.complete(Unit)
        first.await()
        second.await()
        controller.onScreenLaunched()
        assertEquals(1, scheduler.calls)
        controller.close()
    }

    @Test
    fun screenLaunch_waitingSuccessorRetriesAfterPredecessorCancellation() = runTest {
        val scheduler = CancellationRaceScheduler()
        val controller = controller(scheduleStarter = scheduler)

        supervisorScope {
            val first = async { controller.onScreenLaunched() }
            scheduler.firstCallStarted.await()

            val second = async { controller.onScreenLaunched() }
            runCurrent()
            assertEquals(1, scheduler.calls)

            first.cancel()
            first.join()
            assertTrue(first.isCancelled)
            second.await()
        }

        assertEquals(2, scheduler.calls)
        controller.onScreenLaunched()
        assertEquals(2, scheduler.calls)
        controller.close()
    }

    private fun controller(
        family: FakeFamilyRepository = FakeFamilyRepository(),
        status: FakeStatusRepository = FakeStatusRepository(),
        readingRecords: FakeReadingRecordRepository = FakeReadingRecordRepository(),
        scheduleStarter: SyncScheduleStarter = FakeSyncScheduleStarter(),
    ): DebugScreenController = DebugScreenController(
        familyRepository = family,
        statusRepository = status,
        readingRecordRepository = readingRecords,
        scheduleStarter = scheduleStarter,
        dispatcher = UnconfinedTestDispatcher(),
    )

    private fun randomCardNumber(): String = UUID.randomUUID().toString().filter(Char::isDigit).take(12)

    private fun randomSecret(): String = "secret-${UUID.randomUUID()}"

    private data class AddCall(
        val name: String,
        val colorHex: String,
        val cardNumber: String,
        val password: String,
    )

    private class FakeFamilyRepository(
        members: List<Member> = emptyList(),
    ) : FamilyRepository {
        val members = MutableStateFlow(members)
        val addCalls = mutableListOf<AddCall>()

        override fun members(): Flow<List<Member>> = members

        override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) {
            addCalls += AddCall(name, colorHex, cardNumber, password)
        }

        override suspend fun updateMember(member: Member, newPassword: String?) = Unit

        override suspend fun removeMember(memberId: Long) = Unit
    }

    private class FakeStatusRepository : StatusRepository {
        val loans = MutableStateFlow<List<Loan>>(emptyList())
        val reservations = MutableStateFlow<List<Reservation>>(emptyList())
        val summaries = MutableStateFlow<List<UserSummary>>(emptyList())
        val lastSync = MutableStateFlow<SyncLog?>(null)
        val shelves = mutableMapOf<Long, MutableStateFlow<List<ShelfItem>>>()
        var syncResult: SyncResult = SyncResult.Completed(0, 0)
        var syncFailure: Exception? = null
        var syncDeferred: CompletableDeferred<SyncResult>? = null
        var syncCalls = 0

        override fun loans(): Flow<List<Loan>> = loans

        override fun reservations(): Flow<List<Reservation>> = reservations

        override fun shelf(memberId: Long): Flow<List<ShelfItem>> = shelves.getOrPut(memberId) { MutableStateFlow(emptyList()) }

        override fun summaries(): Flow<List<UserSummary>> = summaries

        override fun lastSync(): Flow<SyncLog?> = lastSync

        override suspend fun syncAll(trigger: SyncTrigger): SyncResult {
            syncCalls += 1
            syncFailure?.let { throw it }
            return syncDeferred?.await() ?: syncResult
        }
    }

    private class FakeReadingRecordRepository : ReadingRecordRepository {
        val allRecords = MutableStateFlow<List<ReadingRecord>>(emptyList())
        val searchResults = MutableStateFlow<List<ReadingRecord>>(emptyList())
        val searchQueries = mutableListOf<String>()

        override fun records(memberId: Long?): Flow<List<ReadingRecord>> = allRecords

        override fun search(query: String, memberId: Long?): Flow<List<ReadingRecord>> {
            searchQueries += query
            return searchResults
        }

        override fun hasRead(tilcod: String): Flow<List<com.fallgist.nishinomiyalibrary.domain.model.ReadingInfo>> =
            flowOf(emptyList())
    }

    private class FakeSyncScheduleStarter(
        var failure: Throwable? = null,
    ) : SyncScheduleStarter {
        var calls = 0
        var scheduleDeferred: CompletableDeferred<Unit>? = null

        override suspend fun scheduleFromSettings() {
            calls += 1
            failure?.let { throw it }
            scheduleDeferred?.await()
        }
    }

    private class CancellationRaceScheduler : SyncScheduleStarter {
        val firstCallStarted = CompletableDeferred<Unit>()
        private val firstCallBlocker = CompletableDeferred<Unit>()
        var calls = 0

        override suspend fun scheduleFromSettings() {
            calls += 1
            if (calls == 1) {
                firstCallStarted.complete(Unit)
                firstCallBlocker.await()
            }
        }
    }
}
