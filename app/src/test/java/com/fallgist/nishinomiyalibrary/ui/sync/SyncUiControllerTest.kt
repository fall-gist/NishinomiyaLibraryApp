package com.fallgist.nishinomiyalibrary.ui.sync

import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.model.UserSummary
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SyncLog
import com.fallgist.nishinomiyalibrary.domain.repository.SyncResult
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncUiControllerTest {

    @Test
    fun requestManualSync_reportsCompletionMessageAndClearsIsSyncing() = runTest {
        val controller = SyncUiController(
            statusRepository = FakeStatusRepository(syncResult = SyncResult.Completed(2, 0)),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        controller.requestManualSync()
        advanceUntilIdle()

        assertTrue(controller.state.value.message!!.text.contains("同期が完了しました"))
        assertFalse(controller.state.value.isSyncing)
    }

    @Test
    fun requestManualSync_partialFailure_reportsFailedMemberCount() = runTest {
        val controller = SyncUiController(
            statusRepository = FakeStatusRepository(syncResult = SyncResult.Completed(3, 2)),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        controller.requestManualSync()
        advanceUntilIdle()

        assertEquals("同期が完了しました(一部失敗: 2人)", controller.state.value.message!!.text)
    }

    @Test
    fun requestManualSync_exception_reportsFailureMessageAndClearsIsSyncing() = runTest {
        val controller = SyncUiController(
            statusRepository = FakeStatusRepository(throwOnSync = true),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        controller.requestManualSync()
        advanceUntilIdle()

        assertEquals("同期に失敗しました。通信状況を確認してください", controller.state.value.message!!.text)
        assertFalse(controller.state.value.isSyncing)
    }

    @Test
    fun consumeMessage_withMatchingId_clearsMessage() = runTest {
        val controller = SyncUiController(
            statusRepository = FakeStatusRepository(syncResult = SyncResult.Completed(1, 0)),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        controller.requestManualSync()
        advanceUntilIdle()
        val id = controller.state.value.message!!.id

        controller.consumeMessage(id)

        assertNull(controller.state.value.message)
    }

    @Test
    fun consumeMessage_withStaleId_doesNotClearNewerMessage() = runTest {
        val controller = SyncUiController(
            statusRepository = FakeStatusRepository(syncResult = SyncResult.Completed(1, 0)),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        controller.requestManualSync()
        advanceUntilIdle()
        val staleId = controller.state.value.message!!.id

        controller.requestManualSync()
        advanceUntilIdle()

        controller.consumeMessage(staleId)

        assertTrue(controller.state.value.message != null)
    }

    @Test
    fun requestManualSync_reentrantCallWhileSyncing_doesNotSyncTwice() = runTest {
        // syncAllの完了をgateで止め、1件目が実行中のうちに2件目を呼ぶことで
        // tryLockによる後発の黙殺を再現する。
        val gate = CompletableDeferred<Unit>()
        val status = FakeStatusRepository(syncResult = SyncResult.Completed(0, 0), gate = gate)
        val controller = SyncUiController(
            statusRepository = status,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        controller.requestManualSync()
        runCurrent()
        assertEquals(1, status.syncCallCount)
        assertTrue(controller.state.value.isSyncing)

        // 1件目がまだgateで止まっている間の再入。tryLockが失敗し、黙って無視されるはず。
        controller.requestManualSync()
        runCurrent()
        assertEquals(1, status.syncCallCount)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, status.syncCallCount)
        assertFalse(controller.state.value.isSyncing)
    }

    private class FakeStatusRepository(
        private val syncResult: SyncResult = SyncResult.Completed(0, 0),
        private val throwOnSync: Boolean = false,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : StatusRepository {
        var syncCallCount = 0
            private set

        override fun loans(): Flow<List<Loan>> = flowOf(emptyList())

        override fun reservations(): Flow<List<Reservation>> = flowOf(emptyList())

        override fun pickupSubmissions(): Flow<List<com.fallgist.nishinomiyalibrary.domain.model.ReservationPickupSubmissionRecord>> =
            flowOf(emptyList())

        override fun shelf(memberId: Long): Flow<List<ShelfItem>> = flowOf(emptyList())

        override fun summaries(): Flow<List<UserSummary>> = flowOf(emptyList())

        override fun lastSync(): Flow<SyncLog?> = MutableStateFlow(null)

        override suspend fun syncAll(trigger: SyncTrigger): SyncResult {
            syncCallCount++
            gate?.await()
            if (throwOnSync) throw RuntimeException("sync failed")
            return syncResult
        }
    }
}
