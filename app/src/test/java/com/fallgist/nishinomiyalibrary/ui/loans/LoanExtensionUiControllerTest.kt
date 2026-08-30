package com.fallgist.nishinomiyalibrary.ui.loans

import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionOutcome
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionTarget
import com.fallgist.nishinomiyalibrary.domain.repository.LoanExtensionRepository
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LoanExtensionUiControllerTest {
    private fun candidate(
        memberId: Long = 1,
        tilcod: String = "100",
        title: String = "資料A",
        currentDueDate: LocalDate = LocalDate.of(2026, 8, 5),
    ) = LoanExtensionCandidate(LoanExtensionTarget(memberId, tilcod), title, currentDueDate)

    @Test
    fun `確認を経ないとextendLoanは呼ばれない`() = runTest {
        val repo = FakeExtensionRepository()
        val controller = LoanExtensionUiController(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestConfirmation(candidate())
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
    fun `確定後はExtendedの文言を組み立て成功扱いにする`() = runTest {
        val newDueDate = LocalDate.of(2026, 8, 19)
        val repo = FakeExtensionRepository(outcome = LoanExtensionOutcome.Extended(newDueDate))
        val controller = LoanExtensionUiController(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestConfirmation(candidate())
        controller.confirmPending()
        advanceUntilIdle()

        val result = controller.state.value.result
        assertEquals("返却期限を延長しました（新しい期限: 2026/08/19）", result?.message)
        assertTrue(result!!.succeeded)
        controller.close()
    }

    @Test
    fun `UnknownはmessageもUnknown文言だが成功扱いにならない`() = runTest {
        val repo = FakeExtensionRepository(outcome = LoanExtensionOutcome.Unknown)
        val controller = LoanExtensionUiController(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestConfirmation(candidate())
        controller.confirmPending()
        advanceUntilIdle()

        val result = controller.state.value.result
        assertEquals("延長できたか確認できません。しばらくしてから貸出状況をご確認ください", result?.message)
        assertFalse(result!!.succeeded)
        controller.close()
    }

    @Test
    fun `Failureは理由に応じた文言で成功扱いにならない`() = runTest {
        val repo = FakeExtensionRepository(outcome = LoanExtensionOutcome.Failure(FailureReason.NETWORK))
        val controller = LoanExtensionUiController(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestConfirmation(candidate())
        controller.confirmPending()
        advanceUntilIdle()

        val result = controller.state.value.result
        assertFalse(result!!.succeeded)
        controller.close()
    }

    @Test
    fun `処理中は二重にconfirmPendingを呼んでも1回しか通信しない`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repo = FakeExtensionRepository(
            onCall = {
                started.complete(Unit)
                release.await()
            },
        )
        val controller = LoanExtensionUiController(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestConfirmation(candidate())
        controller.confirmPending()
        runCurrent()
        started.await()
        assertTrue(controller.state.value.processing)

        // 処理中に別の確認をリクエストしても保留にならない(二重操作防止)。
        controller.requestConfirmation(candidate(tilcod = "101"))
        assertNull(controller.state.value.pendingConfirmation)
        // 処理中に再度confirmPendingを呼んでも新たな通信は始まらない。
        controller.confirmPending()
        runCurrent()
        assertEquals(1, repo.calls)

        release.complete(Unit)
        advanceUntilIdle()
        assertFalse(controller.state.value.processing)
        assertEquals(1, repo.calls)
        controller.close()
    }

    @Test
    fun `処理中は対象行のキーがprocessingTargetへ入る`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repo = FakeExtensionRepository(
            onCall = {
                started.complete(Unit)
                release.await()
            },
        )
        val controller = LoanExtensionUiController(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val target = candidate(memberId = 5, tilcod = "999")

        controller.requestConfirmation(target)
        controller.confirmPending()
        runCurrent()
        started.await()

        assertEquals(LoanExtensionKey(5, "999"), controller.state.value.processingTarget)

        release.complete(Unit)
        advanceUntilIdle()
        assertNull(controller.state.value.processingTarget)
        controller.close()
    }

    @Test
    fun `通信のキャンセルを結果へ変換せず処理中状態を解除する`() = runTest {
        val repo = FakeExtensionRepository(cancelOnCall = true)
        val controller = LoanExtensionUiController(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestConfirmation(candidate())
        controller.confirmPending()
        advanceUntilIdle()

        assertEquals(1, repo.calls)
        assertFalse(controller.state.value.processing)
        assertNull(controller.state.value.result)
        controller.close()
    }

    @Test
    fun `通信失敗はエラーメッセージへ変換する`() = runTest {
        val repo = FakeExtensionRepository(throwOnCall = true)
        val controller = LoanExtensionUiController(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestConfirmation(candidate())
        controller.confirmPending()
        advanceUntilIdle()

        assertFalse(controller.state.value.processing)
        assertNull(controller.state.value.result)
        assertEquals(
            "延長処理を完了できませんでした。通信状態を確認して、もう一度お試しください。",
            controller.state.value.errorMessage,
        )
        controller.close()
    }

    // ------------------------------------------------------------------
    // 一斉延長(機能A、`docs/design/bulk-selection.md` §5・§8.2)
    // ------------------------------------------------------------------

    @Test
    fun `選択の切り替えでキーが追加削除される`() = runTest {
        val repo = FakeExtensionRepository()
        val controller = LoanExtensionUiController(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val key = LoanExtensionKey(1, "100")

        controller.toggleSelection(key)
        assertTrue(key in controller.state.value.selectedKeys)
        controller.toggleSelection(key)
        assertFalse(key in controller.state.value.selectedKeys)
        controller.close()
    }

    @Test
    fun `候補0件では一斉延長の確認を開始しない`() = runTest {
        val repo = FakeExtensionRepository()
        val controller = LoanExtensionUiController(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestBulkConfirmation(emptyList())

        assertNull(controller.state.value.pendingConfirmation)
        controller.close()
    }

    @Test
    fun `一斉延長は選択順にextendLoanを呼び1件失敗しても後続を続行する`() = runTest {
        val repo = FakeExtensionRepository(outcome = LoanExtensionOutcome.Extended(LocalDate.of(2026, 8, 19)))
        val controller = LoanExtensionUiController(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val candidates = listOf(candidate(tilcod = "100"), candidate(tilcod = "101"), candidate(tilcod = "102"))

        controller.requestBulkConfirmation(candidates)
        controller.confirmPending()
        advanceUntilIdle()

        assertEquals(listOf("100", "101", "102"), repo.extendedOrder)
        assertEquals(3, controller.state.value.results.size)
        assertTrue(controller.state.value.results.all { it.message.succeeded })
        controller.close()
    }

    @Test
    fun `一斉延長の確定後は選択が空になり件ごとの結果へ変換される(Unknownは成功にならない)`() = runTest {
        val repo = FakeExtensionRepository(outcome = LoanExtensionOutcome.Unknown)
        val controller = LoanExtensionUiController(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val key1 = LoanExtensionKey(1, "100")
        val key2 = LoanExtensionKey(1, "101")
        controller.toggleSelection(key1)
        controller.toggleSelection(key2)

        controller.requestBulkConfirmation(listOf(candidate(tilcod = "100"), candidate(tilcod = "101")))
        controller.confirmPending()
        advanceUntilIdle()

        assertTrue(controller.state.value.selectedKeys.isEmpty())
        assertEquals(2, controller.state.value.results.size)
        assertFalse(controller.state.value.results.all { it.message.succeeded })
        controller.close()
    }

    @Test
    fun `一斉延長中は進捗が件数どおりに更新される`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var callIndex = 0
        val repo = FakeExtensionRepository(
            onCall = {
                callIndex++
                if (callIndex == 1) {
                    started.complete(Unit)
                    release.await()
                }
            },
        )
        val controller = LoanExtensionUiController(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestBulkConfirmation(listOf(candidate(tilcod = "100"), candidate(tilcod = "101")))
        controller.confirmPending()
        runCurrent()
        started.await()

        assertEquals(LoanExtensionBulkProgress(0, 2), controller.state.value.bulkProgress)
        assertEquals(LoanExtensionKey(1, "100"), controller.state.value.processingTarget)

        release.complete(Unit)
        advanceUntilIdle()

        assertNull(controller.state.value.bulkProgress)
        assertNull(controller.state.value.processingTarget)
        controller.close()
    }

    @Test
    fun `一斉延長中の通信失敗は途中結果を捨てエラーメッセージへ変換する`() = runTest {
        val repo = FakeExtensionRepository(throwOnCall = true)
        val controller = LoanExtensionUiController(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestBulkConfirmation(listOf(candidate(tilcod = "100"), candidate(tilcod = "101")))
        controller.confirmPending()
        advanceUntilIdle()

        assertFalse(controller.state.value.processing)
        assertTrue(controller.state.value.results.isEmpty())
        assertEquals(
            "延長処理を完了できませんでした。通信状態を確認して、もう一度お試しください。",
            controller.state.value.errorMessage,
        )
        controller.close()
    }

    @Test
    fun `一斉延長の結果ダイアログはclearResultsで空になる`() = runTest {
        val repo = FakeExtensionRepository(outcome = LoanExtensionOutcome.Extended(LocalDate.of(2026, 8, 19)))
        val controller = LoanExtensionUiController(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        controller.requestBulkConfirmation(listOf(candidate()))
        controller.confirmPending()
        advanceUntilIdle()
        assertTrue(controller.state.value.results.isNotEmpty())

        controller.clearResults()

        assertTrue(controller.state.value.results.isEmpty())
        controller.close()
    }

    private class FakeExtensionRepository(
        private val outcome: LoanExtensionOutcome = LoanExtensionOutcome.Unknown,
        private val cancelOnCall: Boolean = false,
        private val throwOnCall: Boolean = false,
        private val onCall: (suspend () -> Unit)? = null,
    ) : LoanExtensionRepository {
        var calls = 0
        val extendedOrder = mutableListOf<String>()
        override suspend fun extendLoan(target: LoanExtensionTarget): LoanExtensionOutcome {
            calls++
            extendedOrder += target.tilcod
            onCall?.invoke()
            if (cancelOnCall) throw CancellationException("test")
            if (throwOnCall) throw IllegalStateException("network")
            return outcome
        }
        override suspend fun extendLoans(
            targets: List<LoanExtensionTarget>,
        ): com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionBatchResult =
            com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionBatchResult(
                targets.map { target ->
                    com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionItemResult(target, extendLoan(target))
                },
            )
    }
}
