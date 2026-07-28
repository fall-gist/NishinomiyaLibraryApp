package com.fallgist.nishinomiyalibrary.ui.reservations

import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.MemberReservationCancelResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelItemResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelOutcome
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelTarget
import com.fallgist.nishinomiyalibrary.domain.model.UnknownReason
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCancelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReservationCancelUiControllerTest {
    private val father = Member(1, "父", "#111111", "card", 0)
    private val child = Member(2, "子", "#222222", "card", 1)

    private fun candidate(memberId: Long, tilcod: String, cancelCode: String, title: String) =
        ReservationCancelCandidate(ReservationCancelTarget(memberId, tilcod, cancelCode), title)

    @Test
    fun `確認を経ないとcancelReservationsは呼ばれない`() = runTest {
        val repo = FakeCancelRepository()
        val controller = controller(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestSingleCancelConfirmation(candidate(father.id, "100", "c1", "資料A"))
        assertEquals(0, repo.calls)
        advanceUntilIdle()
        assertEquals(0, repo.calls)

        controller.confirmPending()
        assertEquals(0, repo.calls)
        advanceUntilIdle()
        assertEquals(1, repo.calls)
        assertNull(controller.state.value.pendingConfirmation)
        controller.close()
    }

    @Test
    fun `選択0件では一斉取消の確認を開始しない`() = runTest {
        val repo = FakeCancelRepository()
        val controller = controller(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestBulkCancelConfirmation(emptyList())

        assertNull(controller.state.value.pendingConfirmation)
        assertEquals(0, repo.calls)
        controller.close()
    }

    @Test
    fun `選択の切り替えでキーが追加削除される`() = runTest {
        val repo = FakeCancelRepository()
        val controller = controller(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val key = ReservationCancelKey(father.id, "100", "c1")

        controller.toggleSelection(key)
        assertTrue(key in controller.state.value.selectedKeys)

        controller.toggleSelection(key)
        assertFalse(key in controller.state.value.selectedKeys)
        controller.close()
    }

    @Test
    fun `確定後は選択状態をクリアする`() = runTest {
        val repo = FakeCancelRepository()
        val controller = controller(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val key = ReservationCancelKey(father.id, "100", "c1")
        controller.toggleSelection(key)

        controller.requestBulkCancelConfirmation(listOf(candidate(father.id, "100", "c1", "資料A")))
        controller.confirmPending()
        advanceUntilIdle()

        assertTrue(controller.state.value.selectedKeys.isEmpty())
        controller.close()
    }

    @Test
    fun `結果は各Outcomeを期待どおりの文言と成功扱いへ変換しUnknownは成功にならない`() = runTest {
        val repo = FakeCancelRepository(
            result = ReservationCancelBatchResult(
                listOf(
                    MemberReservationCancelResult(
                        father.id,
                        listOf(
                            ReservationCancelItemResult(ReservationCancelTarget(father.id, "100", "c1"), ReservationCancelOutcome.Cancelled),
                            ReservationCancelItemResult(ReservationCancelTarget(father.id, "101", "c2"), ReservationCancelOutcome.CancelledAndHidden),
                            ReservationCancelItemResult(
                                ReservationCancelTarget(father.id, "102", "c3"),
                                ReservationCancelOutcome.Unknown(UnknownReason.VERIFICATION_UNAVAILABLE),
                            ),
                            ReservationCancelItemResult(
                                ReservationCancelTarget(father.id, "103", "c4"),
                                ReservationCancelOutcome.Failure(FailureReason.NETWORK),
                            ),
                            ReservationCancelItemResult(
                                ReservationCancelTarget(father.id, "104", "c5"),
                                ReservationCancelOutcome.ConfirmationRequired("確認"),
                            ),
                            ReservationCancelItemResult(
                                ReservationCancelTarget(father.id, "105", "c6"),
                                ReservationCancelOutcome.Rejected("拒否"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val controller = controller(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val candidates = listOf(
            candidate(father.id, "100", "c1", "資料A"),
            candidate(father.id, "101", "c2", "資料B"),
            candidate(father.id, "102", "c3", "資料C"),
            candidate(father.id, "103", "c4", "資料D"),
            candidate(father.id, "104", "c5", "資料E"),
            candidate(father.id, "105", "c6", "資料F"),
        )

        controller.requestBulkCancelConfirmation(candidates)
        controller.confirmPending()
        advanceUntilIdle()

        val results = controller.state.value.results
        assertEquals(6, results.size)
        // Cancelled/CancelledAndHiddenは区別せず同じ表示・成功扱い
        assertEquals("取り消しました", results[0].outcomeLabel)
        assertTrue(results[0].completed)
        assertEquals("取り消しました", results[1].outcomeLabel)
        assertTrue(results[1].completed)
        // Unknownは成否不明であり成功扱いにしてはならない(最優先要件)
        assertEquals("取り消せたか確認できません", results[2].outcomeLabel)
        assertEquals("予約状況を再同期して確認してください", results[2].detail)
        assertFalse(results[2].completed)
        assertEquals(ReservationCancelResultCategory.UNKNOWN, results[2].category)
        // Failure
        assertEquals("取り消せませんでした", results[3].outcomeLabel)
        assertFalse(results[3].completed)
        // ConfirmationRequired
        assertEquals("取り消せませんでした（確認画面が返りました）", results[4].outcomeLabel)
        assertFalse(results[4].completed)
        // Rejected
        assertEquals("取り消せませんでした", results[5].outcomeLabel)
        assertEquals("拒否", results[5].detail)
        assertFalse(results[5].completed)

        val summary = controller.state.value.summary
        assertEquals(2, summary.cancelledCount)
        assertEquals(1, summary.unknownCount)
        assertEquals(3, summary.failedCount)
        assertEquals(ReservationCancelResultOrigin.BULK, controller.state.value.resultOrigin)
        controller.close()
    }

    @Test
    fun `通信のキャンセルを結果へ変換せず処理中状態を解除する`() = runTest {
        val repo = FakeCancelRepository(cancelOnCall = true)
        val controller = controller(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestSingleCancelConfirmation(candidate(father.id, "100", "c1", "資料A"))
        controller.confirmPending()
        advanceUntilIdle()

        assertEquals(1, repo.calls)
        assertFalse(controller.state.value.processing)
        assertTrue(controller.state.value.results.isEmpty())
        controller.close()
    }

    @Test
    fun `通信失敗はエラーメッセージへ変換する`() = runTest {
        val repo = FakeCancelRepository(throwOnCall = true)
        val controller = controller(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestSingleCancelConfirmation(candidate(father.id, "100", "c1", "資料A"))
        controller.confirmPending()
        advanceUntilIdle()

        assertFalse(controller.state.value.processing)
        assertTrue(controller.state.value.results.isEmpty())
        assertEquals(
            "取消処理を完了できませんでした。通信状態を確認して、残っている項目を再度お試しください。",
            controller.state.value.errorMessage,
        )
        controller.close()
    }

    private fun controller(
        repo: FakeCancelRepository,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): ReservationCancelUiController = ReservationCancelUiController(
        cancelRepository = repo,
        familyRepository = FakeFamilyRepository(),
        dispatcher = dispatcher,
    )

    private inner class FakeFamilyRepository : FamilyRepository {
        override fun members(): Flow<List<Member>> = flowOf(listOf(father, child))
        override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) = Unit
        override suspend fun updateMember(member: Member, newPassword: String?) = Unit
        override suspend fun removeMember(memberId: Long) = Unit
    }

    private class FakeCancelRepository(
        private val result: ReservationCancelBatchResult = ReservationCancelBatchResult(emptyList()),
        private val cancelOnCall: Boolean = false,
        private val throwOnCall: Boolean = false,
    ) : ReservationCancelRepository {
        var calls = 0
        override suspend fun cancelReservations(targets: List<ReservationCancelTarget>): ReservationCancelBatchResult {
            calls++
            if (cancelOnCall) throw CancellationException("test")
            if (throwOnCall) throw IllegalStateException("network")
            return result
        }
    }
}
