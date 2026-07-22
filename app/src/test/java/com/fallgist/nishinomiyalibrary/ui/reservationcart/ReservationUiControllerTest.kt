package com.fallgist.nishinomiyalibrary.ui.reservationcart

import com.fallgist.nishinomiyalibrary.data.local.AppSettings
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
    ): ReservationUiController =
        ReservationUiController(
            cartRepository = cart,
            familyRepository = FakeFamilyRepository(),
            calendarRepository = calendarRepository,
            settings = settings,
            dispatcher = dispatcher,
            now = { 1L },
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
    ) : ReservationCartRepository {
        private val flow = MutableStateFlow(items)
        val added = mutableListOf<ReservationTarget>()
        val removed = mutableListOf<Long>()
        var confirmCalls = 0
        var reserveNowCalls = 0

        override fun cartItems(): Flow<List<ReservationCartItem>> = flow
        override suspend fun addToCart(target: ReservationTarget) { added += target }
        override suspend fun removeFromCart(cartItemId: Long) { removed += cartItemId }
        override suspend fun confirmCart(confirmation: ReservationConfirmation): ReservationBatchResult {
            confirmCalls++
            return result(flow.value.map { ReservationTarget(it.id, it.memberId, it.tilcod, it.title, it.writerLine) })
        }
        override suspend fun reserveNow(target: ReservationTarget, confirmation: ReservationConfirmation): ReservationBatchResult {
            reserveNowCalls++
            if (cancelOnReserve) throw CancellationException("test")
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
