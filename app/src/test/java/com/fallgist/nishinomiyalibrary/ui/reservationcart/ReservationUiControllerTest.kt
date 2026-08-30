package com.fallgist.nishinomiyalibrary.ui.reservationcart

import com.fallgist.nishinomiyalibrary.data.local.AppSettings
import com.fallgist.nishinomiyalibrary.data.repository.ReservationOperationGate
import com.fallgist.nishinomiyalibrary.data.repository.ReservationOperationType
import com.fallgist.nishinomiyalibrary.domain.model.ClosedDay
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartItem
import com.fallgist.nishinomiyalibrary.domain.model.ReservationConfirmation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationItemResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
import com.fallgist.nishinomiyalibrary.domain.repository.CalendarRepository
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCartRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReservationUiControllerTest {
    private val father = Member(1, "父", "#111111", "card", 0)
    private val child = Member(2, "子", "#222222", "card", 1)

    @Test
    fun `カートはメンバー別にグループ化する`() {
        val groups = ReservationCartContentBuilder.groups(
            listOf(cart(1, father.id), cart(2, child.id), cart(3, father.id)),
            listOf(father, child),
        )

        assertEquals(listOf("父", "子"), groups.map { it.member?.name })
        assertEquals(listOf(2, 1), groups.map { it.items.size })
    }

    @Test
    fun `既定館を初期化し不正な既定館は先頭館へフォールバックする`() = runTest {
        val cart = FakeCartRepository()
        val settings = MutableStateFlow(AppSettings(defaultCalendarLibrary = "invalid"))
        val controller = controller(cart, settings, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals("A", controller.state.value.pickupLibraryCode)

        settings.value = AppSettings(defaultCalendarLibrary = "B")
        val second = controller(FakeCartRepository(), settings, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals("B", second.state.value.pickupLibraryCode)
        controller.close()
        second.close()
    }

    @Test
    fun `追加と削除はカートリポジトリへ委譲する`() = runTest {
        val cart = FakeCartRepository()
        val settings = MutableStateFlow(AppSettings(defaultCalendarLibrary = "A"))
        val controller = controller(cart, settings, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val target = ReservationTarget(null, father.id, "100", "資料", "著者")

        controller.addToCart(target)
        advanceUntilIdle()
        assertEquals(listOf(target), cart.added)

        controller.removeFromCart(44)
        advanceUntilIdle()
        assertEquals(listOf(44L), cart.removed)
        controller.close()
    }

    // ------------------------------------------------------------------
    // カートの一括削除・「カートを空にする」(`docs/design/bulk-selection.md` §6・§8.2)
    // ------------------------------------------------------------------

    @Test
    fun `選択の切り替えでcartItemIdが追加削除される`() = runTest {
        val cart = FakeCartRepository()
        val settings = MutableStateFlow(AppSettings(defaultCalendarLibrary = "A"))
        val controller = controller(cart, settings, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.toggleCartItemSelection(1)
        assertTrue(1L in controller.state.value.selectedCartItemIds)
        controller.toggleCartItemSelection(1)
        assertFalse(1L in controller.state.value.selectedCartItemIds)
        controller.close()
    }

    @Test
    fun `候補0件では一括削除の確認を開始しない`() = runTest {
        val cart = FakeCartRepository()
        val settings = MutableStateFlow(AppSettings(defaultCalendarLibrary = "A"))
        val controller = controller(cart, settings, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestBulkCartDeleteConfirmation(emptyList())

        assertNull(controller.state.value.bulkCartDeleteConfirmation)
        controller.close()
    }

    @Test
    fun `一括削除の確定でremoveFromCart(List)を呼び選択と確認状態をクリアする`() = runTest {
        val cart = FakeCartRepository(items = listOf(cart(1, father.id), cart(2, father.id)))
        val settings = MutableStateFlow(AppSettings(defaultCalendarLibrary = "A"))
        val controller = controller(cart, settings, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        controller.toggleCartItemSelection(1)
        controller.toggleCartItemSelection(2)

        val candidates = ReservationCartContentBuilder.deleteCandidates(controller.state.value.cartGroups, controller.state.value.selectedCartItemIds)
        controller.requestBulkCartDeleteConfirmation(candidates)
        assertNotNull(controller.state.value.bulkCartDeleteConfirmation)
        controller.confirmBulkCartDelete()
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), cart.removedBulk.sorted())
        assertTrue(controller.state.value.selectedCartItemIds.isEmpty())
        assertNull(controller.state.value.bulkCartDeleteConfirmation)
        controller.close()
    }

    @Test
    fun `確認待ちの間にカートから消えたidは実行時に無視される(design §4,3)`() = runTest {
        val cart = FakeCartRepository(items = listOf(cart(1, father.id), cart(2, father.id)))
        val settings = MutableStateFlow(AppSettings(defaultCalendarLibrary = "A"))
        val controller = controller(cart, settings, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        controller.toggleCartItemSelection(1)
        controller.toggleCartItemSelection(2)
        val candidates = ReservationCartContentBuilder.deleteCandidates(controller.state.value.cartGroups, controller.state.value.selectedCartItemIds)
        controller.requestBulkCartDeleteConfirmation(candidates)

        // 確認待ちの間にid=2がカートから消えたことを模す(既存の行内削除ボタン等による変化)。
        cart.setItems(listOf(cart(1, father.id)))
        advanceUntilIdle()

        controller.confirmBulkCartDelete()
        advanceUntilIdle()

        assertEquals(listOf(1L), cart.removedBulk)
        controller.close()
    }

    @Test
    fun `カートが空ではカートを空にする確認を開始しない`() = runTest {
        val cart = FakeCartRepository()
        val settings = MutableStateFlow(AppSettings(defaultCalendarLibrary = "A"))
        val controller = controller(cart, settings, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestClearCartConfirmation()

        assertFalse(controller.state.value.clearCartConfirmationPending)
        controller.close()
    }

    @Test
    fun `カートを空にするの確定でclearCartを呼び選択と確認状態をクリアする(design §6,3・確認必須)`() = runTest {
        val cart = FakeCartRepository(items = listOf(cart(1, father.id), cart(2, father.id)))
        val settings = MutableStateFlow(AppSettings(defaultCalendarLibrary = "A"))
        val controller = controller(cart, settings, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        controller.toggleCartItemSelection(1)

        controller.requestClearCartConfirmation()
        assertTrue(controller.state.value.clearCartConfirmationPending)
        // 確定するまでclearCartは呼ばれない。
        assertEquals(0, cart.clearCartCalls)

        controller.confirmClearCart()
        advanceUntilIdle()

        assertEquals(1, cart.clearCartCalls)
        assertFalse(controller.state.value.clearCartConfirmationPending)
        assertTrue(controller.state.value.selectedCartItemIds.isEmpty())
        controller.close()
    }

    // ------------------------------------------------------------------
    // processing(予約確定中)はカートの一括削除・空にするを開始しない(独立レビュー指摘)
    // ------------------------------------------------------------------

    @Test
    fun `予約確定中はrequestBulkCartDeleteConfirmationを呼んでも確認ダイアログを開始しない`() = runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cart = FakeCartRepository(
            items = listOf(cart(1, father.id)),
            beforeReserveNowResult = { entered.complete(Unit); release.await() },
        )
        val settings = MutableStateFlow(AppSettings(defaultCalendarLibrary = "A"))
        val controller = controller(cart, settings, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        controller.requestImmediateConfirmation(ReservationTarget(null, father.id, "immediate", "即時予約"))
        controller.confirmPending()
        runCurrent()
        entered.await()
        assertTrue(controller.state.value.processing)

        controller.requestBulkCartDeleteConfirmation(listOf(ReservationCartDeleteCandidate(1, "資料1")))

        assertNull(controller.state.value.bulkCartDeleteConfirmation)
        release.complete(Unit)
        advanceUntilIdle()
        controller.close()
    }

    @Test
    fun `予約確定中はconfirmBulkCartDeleteを呼んでも削除しない`() = runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cart = FakeCartRepository(
            items = listOf(cart(1, father.id)),
            beforeReserveNowResult = { entered.complete(Unit); release.await() },
        )
        val settings = MutableStateFlow(AppSettings(defaultCalendarLibrary = "A"))
        val controller = controller(cart, settings, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        controller.toggleCartItemSelection(1)
        val candidates = ReservationCartContentBuilder.deleteCandidates(controller.state.value.cartGroups, controller.state.value.selectedCartItemIds)
        controller.requestBulkCartDeleteConfirmation(candidates)
        assertNotNull(controller.state.value.bulkCartDeleteConfirmation)

        controller.requestImmediateConfirmation(ReservationTarget(null, father.id, "immediate", "即時予約"))
        controller.confirmPending()
        runCurrent()
        entered.await()
        assertTrue(controller.state.value.processing)

        controller.confirmBulkCartDelete()
        runCurrent()

        assertTrue(cart.removedBulk.isEmpty())
        // 予約確定中に呼んでも一括削除の確認は保留されたまま消えない(ガードでreturnしたため)。
        assertNotNull(controller.state.value.bulkCartDeleteConfirmation)

        release.complete(Unit)
        advanceUntilIdle()
        controller.close()
    }

    @Test
    fun `予約確定中はconfirmClearCartを呼んでも空にしない`() = runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cart = FakeCartRepository(
            items = listOf(cart(1, father.id)),
            beforeReserveNowResult = { entered.complete(Unit); release.await() },
        )
        val settings = MutableStateFlow(AppSettings(defaultCalendarLibrary = "A"))
        val controller = controller(cart, settings, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        controller.requestClearCartConfirmation()
        assertTrue(controller.state.value.clearCartConfirmationPending)

        controller.requestImmediateConfirmation(ReservationTarget(null, father.id, "immediate", "即時予約"))
        controller.confirmPending()
        runCurrent()
        entered.await()
        assertTrue(controller.state.value.processing)

        controller.confirmClearCart()
        runCurrent()

        assertEquals(0, cart.clearCartCalls)
        // 予約確定中に呼んでも「カートを空にする」の確認は保留されたまま消えない(ガードでreturnしたため)。
        assertTrue(controller.state.value.clearCartConfirmationPending)

        release.complete(Unit)
        advanceUntilIdle()
        controller.close()
    }

    @Test
    fun `カート変更のエラーはcartMutationErrorMessageへ変換する`() = runTest {
        val cart = FakeCartRepository(items = listOf(cart(1, father.id)), throwOnBulkRemove = true)
        val settings = MutableStateFlow(AppSettings(defaultCalendarLibrary = "A"))
        val controller = controller(cart, settings, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        controller.toggleCartItemSelection(1)
        val candidates = ReservationCartContentBuilder.deleteCandidates(controller.state.value.cartGroups, controller.state.value.selectedCartItemIds)
        controller.requestBulkCartDeleteConfirmation(candidates)

        controller.confirmBulkCartDelete()
        advanceUntilIdle()

        assertFalse(controller.state.value.cartMutationProcessing)
        assertEquals(
            "カートから削除できませんでした。もう一度お試しください。",
            controller.state.value.cartMutationErrorMessage,
        )
        controller.close()
    }

    @Test
    fun `最終確認前は送信せず肯定後だけカート確定する`() = runTest {
        val target = ReservationTarget(1, father.id, "1001", "資料1", "著者")
        val cart = FakeCartRepository(items = listOf(cart(1, father.id)))
        val settings = MutableStateFlow(AppSettings(defaultCalendarLibrary = "A"))
        val controller = controller(cart, settings, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestCartConfirmation()
        assertEquals(0, cart.confirmCalls)
        assertNotNull(controller.state.value.pendingConfirmation)

        controller.confirmPending()
        controller.confirmPending()
        assertEquals(0, cart.confirmCalls)
        advanceUntilIdle()
        assertEquals(1, cart.confirmCalls)
        assertNull(controller.state.value.pendingConfirmation)
        assertEquals("予約成立", controller.state.value.feedback?.results?.single()?.outcomeLabel)
        assertEquals(target.title, controller.state.value.feedback?.results?.single()?.title)
        controller.close()
    }

    @Test
    fun `即時予約はカート追加せず肯定後だけ送信する`() = runTest {
        val cart = FakeCartRepository()
        val settings = MutableStateFlow(AppSettings(defaultCalendarLibrary = "A"))
        val controller = controller(cart, settings, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val target = ReservationTarget(null, father.id, "100", "資料")

        controller.requestImmediateConfirmation(target)
        assertEquals(0, cart.reserveNowCalls)
        assertTrue(cart.added.isEmpty())
        controller.confirmPending()
        advanceUntilIdle()

        assertEquals(1, cart.reserveNowCalls)
        assertTrue(cart.added.isEmpty())
        controller.close()
    }

    @Test
    fun `予約通信のキャンセルを結果へ変換せず処理中状態を解除する`() = runTest {
        val cart = FakeCartRepository(cancelOnReserve = true)
        val settings = MutableStateFlow(AppSettings(defaultCalendarLibrary = "A"))
        val controller = controller(cart, settings, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestImmediateConfirmation(ReservationTarget(null, father.id, "100", "資料"))
        controller.confirmPending()
        advanceUntilIdle()

        assertEquals(1, cart.reserveNowCalls)
        assertFalse(controller.state.value.processing)
        assertTrue(controller.state.value.feedback?.results.orEmpty().isEmpty())
        controller.close()
    }

    @Test
    fun `自動予約の保持中に手動予約が待機すると待機表示をゲート状態から反映する`() = runTest {
        val gate = ReservationOperationGate()
        val automaticEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseAutomatic = kotlinx.coroutines.CompletableDeferred<Unit>()
        val automatic = async {
            gate.withOperation(ReservationOperationType.AUTOMATIC_RESERVATION) {
                automaticEntered.complete(Unit)
                releaseAutomatic.await()
            }
        }
        runCurrent()
        automaticEntered.await()
        val cart = FakeCartRepository(operationGate = gate)
        val controller = controller(cart, MutableStateFlow(AppSettings(defaultCalendarLibrary = "A")), StandardTestDispatcher(testScheduler), operationGate = gate)
        advanceUntilIdle()

        controller.requestImmediateConfirmation(ReservationTarget(null, father.id, "100", "資料"))
        controller.confirmPending()
        runCurrent()

        assertTrue(controller.state.value.processing)
        assertTrue(controller.state.value.waitingForAutomaticReservation)

        releaseAutomatic.complete(Unit)
        automatic.await()
        advanceUntilIdle()
        assertFalse(controller.state.value.processing)
        assertFalse(controller.state.value.waitingForAutomaticReservation)
        controller.close()
    }

    @Test
    fun `キャンセルは予約実行境界から再送出する`() = runTest {
        val cart = FakeCartRepository(cancelOnReserve = true)
        val controller = controller(cart, MutableStateFlow(AppSettings(defaultCalendarLibrary = "A")), StandardTestDispatcher(testScheduler))
        val request = ReservationConfirmationRequest.Immediate(listOf(ReservationTarget(null, father.id, "100", "資料")))

        assertCancellationPropagates {
            controller.executeReservation(request, ReservationConfirmation("A", 1L))
        }
        assertEquals(1, cart.reserveNowCalls)
        controller.close()
    }

    @Test
    fun `結果表示は起点と書誌コードで絞り込む`() {
        val row = ReservationResultRow(father.id, "資料A", "予約成立", null, true)
        val immediateA = ReservationFeedback(ReservationFeedbackOrigin.IMMEDIATE, "A", listOf(row))
        val cart = ReservationFeedback(ReservationFeedbackOrigin.CART, results = listOf(row))

        assertEquals(immediateA, ReservationCartContentBuilder.feedbackForDetail(immediateA, "A"))
        assertNull(ReservationCartContentBuilder.feedbackForDetail(immediateA, "B"))
        assertNull(ReservationCartContentBuilder.feedbackForDetail(cart, "A"))
        assertEquals(cart, ReservationCartContentBuilder.feedbackForCart(cart))
    }

    @Test
    fun `受取館が空なら確認と送信を開始しない`() = runTest {
        val cart = FakeCartRepository(items = listOf(cart(1, father.id)))
        val controller = controller(
            cart,
            MutableStateFlow(AppSettings(defaultCalendarLibrary = "invalid")),
            StandardTestDispatcher(testScheduler),
            FakeCalendarRepository(emptyList()),
        )
        advanceUntilIdle()

        assertFalse(controller.state.value.hasValidPickupLibrary)
        assertFalse(controller.state.value.canConfirmCart)
        controller.requestCartConfirmation()

        assertNull(controller.state.value.pendingConfirmation)
        assertEquals(0, cart.confirmCalls)
        assertEquals("有効な受取館を選択してください", controller.state.value.feedback?.errorMessage)
        controller.close()
    }

    @Test
    fun `結果は成功済み失敗不明を日本語表示へ分類する`() {
        val target = ReservationTarget(null, father.id, "100", "資料")
        val rows = ReservationCartContentBuilder.resultRows(
            ReservationBatchResult(
                listOf(
                    com.fallgist.nishinomiyalibrary.domain.model.MemberReservationResult(
                        father.id,
                        listOf(
                            ReservationItemResult(target, ReservationOutcome.Success),
                            ReservationItemResult(target, ReservationOutcome.AlreadyReserved),
                            ReservationItemResult(target, ReservationOutcome.Unknown(com.fallgist.nishinomiyalibrary.domain.model.UnknownReason.POST_CONNECTION_LOST)),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf("予約成立", "予約済み", "予約状態を確認できません"), rows.map { it.outcomeLabel })
        assertFalse(rows.last().completed)
    }

    private fun controller(
        cart: FakeCartRepository,
        settings: Flow<AppSettings>,
        dispatcher: CoroutineDispatcher,
        calendarRepository: CalendarRepository = FakeCalendarRepository(),
        operationGate: ReservationOperationGate = ReservationOperationGate(),
    ): ReservationUiController =
        ReservationUiController(
            cartRepository = cart,
            familyRepository = FakeFamilyRepository(),
            calendarRepository = calendarRepository,
            settings = settings,
            dispatcher = dispatcher,
            now = { 1L },
            operationGate = operationGate,
        )

    private suspend fun assertCancellationPropagates(block: suspend () -> Unit) {
        try {
            block()
            fail("CancellationExceptionが再送出されませんでした")
        } catch (_: CancellationException) {
            // 再送出を確認する。
        }
    }

    private fun cart(id: Long, memberId: Long) = ReservationCartItem(id, memberId, "100$id", "資料$id", "著者", 0)

    private inner class FakeFamilyRepository : FamilyRepository {
        override fun members(): Flow<List<Member>> = flowOf(listOf(father, child))
        override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) = Unit
        override suspend fun updateMember(member: Member, newPassword: String?) = Unit
        override suspend fun removeMember(memberId: Long) = Unit
    }

    private class FakeCalendarRepository(override val libraries: List<Library> = listOf(Library("A", "中央"), Library("B", "北口"))) : CalendarRepository {
        override fun closedDays(libraryCode: String): Flow<List<ClosedDay>> = flowOf(emptyList())
        override suspend fun refreshClosedDays(libraryCode: String) = Unit
    }

    private class FakeCartRepository(
        items: List<ReservationCartItem> = emptyList(),
        private val cancelOnReserve: Boolean = false,
        private val operationGate: ReservationOperationGate? = null,
        private val throwOnBulkRemove: Boolean = false,
        private val beforeReserveNowResult: suspend () -> Unit = {},
    ) : ReservationCartRepository {
        private val flow = MutableStateFlow(items)
        val added = mutableListOf<ReservationTarget>()
        val removed = mutableListOf<Long>()
        val removedBulk = mutableListOf<Long>()
        var clearCartCalls = 0
        var confirmCalls = 0
        var reserveNowCalls = 0

        /** テストからカート内容を変える(一覧から消えたキーの無視挙動を検証するため)。 */
        fun setItems(newItems: List<ReservationCartItem>) {
            flow.value = newItems
        }

        override fun cartItems(): Flow<List<ReservationCartItem>> = flow
        override suspend fun addToCart(target: ReservationTarget) { added += target }
        override suspend fun addToCart(targets: List<ReservationTarget>): com.fallgist.nishinomiyalibrary.domain.model.ReservationCartAddSummary {
            added += targets
            return com.fallgist.nishinomiyalibrary.domain.model.ReservationCartAddSummary(added = targets.size, skipped = 0)
        }
        override suspend fun removeFromCart(cartItemId: Long) { removed += cartItemId }
        override suspend fun removeFromCart(cartItemIds: List<Long>) {
            if (throwOnBulkRemove) throw IllegalStateException("test")
            removedBulk += cartItemIds
        }
        override suspend fun clearCart() { clearCartCalls++ }
        override suspend fun confirmCart(confirmation: ReservationConfirmation): ReservationBatchResult {
            confirmCalls++
            return result(flow.value.map { ReservationTarget(it.id, it.memberId, it.tilcod, it.title, it.writerLine) })
        }
        override suspend fun reserveNow(target: ReservationTarget, confirmation: ReservationConfirmation): ReservationBatchResult {
            reserveNowCalls++
            operationGate?.let { gate ->
                return gate.withOperation(ReservationOperationType.MANUAL_RESERVATION) {
                    result(listOf(target))
                }
            }
            if (cancelOnReserve) throw CancellationException("test")
            beforeReserveNowResult()
            return result(listOf(target))
        }
        private fun result(targets: List<ReservationTarget>) = ReservationBatchResult(
            targets.groupBy { it.memberId }.map { (memberId, grouped) ->
                com.fallgist.nishinomiyalibrary.domain.model.MemberReservationResult(
                    memberId,
                    grouped.map { ReservationItemResult(it, ReservationOutcome.Success) },
                )
            },
        )
    }
}
