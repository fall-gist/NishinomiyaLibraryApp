package com.fallgist.nishinomiyalibrary.ui.newarrivals

import com.fallgist.nishinomiyalibrary.domain.model.ClosedDay
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.MemberReservationResult
import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartAddSummary
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartItem
import com.fallgist.nishinomiyalibrary.domain.model.ReservationConfirmation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationItemResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
import com.fallgist.nishinomiyalibrary.domain.repository.CalendarRepository
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.NewArrivalRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCartRepository
import com.fallgist.nishinomiyalibrary.data.repository.AutomaticReservationRunResult
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdateResult
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdateRunner
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdateTrigger
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkCartAdditionCandidate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewArrivalsScreenControllerTest {
    @Test
    fun `自動予約ONと進捗を反映し完了時に消す`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val enabled = MutableStateFlow(false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = FakeNewArrivalRepository(emptyList(), null)
        val updater = object : NewArrivalUpdateRunner {
            override suspend fun refresh(trigger: NewArrivalUpdateTrigger) = NewArrivalUpdateResult.Completed(AutomaticReservationRunResult.NoMatch)
            override suspend fun refresh(trigger: NewArrivalUpdateTrigger, onPhaseChanged: (com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdatePhase) -> Unit): NewArrivalUpdateResult {
                onPhaseChanged(com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdatePhase.FETCHING)
                onPhaseChanged(com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdatePhase.AUTOMATIC_RESERVATION)
                entered.complete(Unit); release.await(); return NewArrivalUpdateResult.Completed(AutomaticReservationRunResult.NoMatch)
            }
        }
        val controller = NewArrivalsScreenController(repository, updater, dispatcher, enabled)
        enabled.value = true; advanceUntilIdle(); assertEquals(true, controller.state.value.autoReservationEnabled)
        controller.refresh(); entered.await()
        assertEquals(com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdatePhase.AUTOMATIC_RESERVATION, controller.state.value.updatePhase)
        release.complete(Unit); advanceUntilIdle(); assertEquals(null, controller.state.value.updatePhase)
    }
    @Test
    fun `最終取得から12時間以内なら画面表示時に巡回しない`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val now = Instant.parse("2030-01-02T00:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val repository = FakeNewArrivalRepository(
            items = listOf(newArrival("1")),
            lastFetchedAtEpochMillis = now.minusMillis(Duration.ofHours(1).toMillis()).toEpochMilli(),
        )
        val updater = FakeUpdateRunner(repository, NewArrivalUpdateResult.FreshnessSkipped)
        val controller = NewArrivalsScreenController(repository, updater, dispatcher)

        controller.onScreenLaunched()
        advanceUntilIdle()

        assertEquals(0, repository.refreshCallCount)
        assertEquals(listOf(NewArrivalUpdateTrigger.SCREEN_AUTO), updater.triggers)
    }

    @Test
    fun `最終取得から12時間を超えていれば画面表示時に巡回する`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val now = Instant.parse("2030-01-02T00:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val repository = FakeNewArrivalRepository(
            items = listOf(newArrival("1")),
            lastFetchedAtEpochMillis = now.minusMillis(Duration.ofHours(13).toMillis()).toEpochMilli(),
        )
        val updater = FakeUpdateRunner(repository)
        val controller = NewArrivalsScreenController(repository, updater, dispatcher)

        controller.onScreenLaunched()
        advanceUntilIdle()

        assertEquals(1, repository.refreshCallCount)
    }

    @Test
    fun `新着資料が0件なら経過時間に関わらず巡回する`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val now = Instant.parse("2030-01-02T00:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val repository = FakeNewArrivalRepository(
            items = emptyList(),
            lastFetchedAtEpochMillis = now.minusMillis(Duration.ofMinutes(1).toMillis()).toEpochMilli(),
        )
        val controller = NewArrivalsScreenController(repository, FakeUpdateRunner(repository), dispatcher)

        controller.onScreenLaunched()
        advanceUntilIdle()

        assertEquals(1, repository.refreshCallCount)
    }

    @Test
    fun `refreshは経過時間に関わらず常に巡回する`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val now = Instant.parse("2030-01-02T00:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val repository = FakeNewArrivalRepository(
            items = listOf(newArrival("1")),
            lastFetchedAtEpochMillis = now.minusMillis(Duration.ofMinutes(1).toMillis()).toEpochMilli(),
        )
        val updater = FakeUpdateRunner(repository)
        val controller = NewArrivalsScreenController(repository, updater, dispatcher)

        controller.refresh()
        advanceUntilIdle()
        controller.refresh()
        advanceUntilIdle()

        assertEquals(2, repository.refreshCallCount)
        assertEquals(listOf(NewArrivalUpdateTrigger.SCREEN_MANUAL, NewArrivalUpdateTrigger.SCREEN_MANUAL), updater.triggers)
    }

    @Test
    fun `先発更新中のrefreshとscreen launchは表示状態を崩さず追加通信しない`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repository = FakeNewArrivalRepository(emptyList(), null)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val updater = FakeUpdateRunner(repository, beforeResult = {
            entered.complete(Unit)
            release.await()
        })
        val controller = NewArrivalsScreenController(repository, updater, dispatcher)

        controller.refresh()
        entered.await()
        assertEquals(true, controller.state.value.refreshing)

        controller.refresh()
        controller.onScreenLaunched()
        assertEquals(true, controller.state.value.refreshing)
        assertEquals(listOf(NewArrivalUpdateTrigger.SCREEN_MANUAL), updater.triggers)

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(false, controller.state.value.refreshing)
        assertEquals(1, repository.refreshCallCount)
    }

    // ------------------------------------------------------------------
    // 一斉カート追加(機能D、`docs/design/bulk-selection.md` §7・§8.2)
    // ------------------------------------------------------------------

    private val father = Member(1, "父", "#111111", "card-1", 0)

    @Test
    fun `選択モード中のtoggleCartSelectionでtilcodが追加削除される`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = NewArrivalsScreenController(
            FakeNewArrivalRepository(listOf(newArrival("100")), null), FakeUpdateRunner(FakeNewArrivalRepository(emptyList(), null)), dispatcher,
        )
        advanceUntilIdle()

        controller.enterSelectionMode("100")
        assertTrue(controller.state.value.selectionMode)
        assertTrue("100" in controller.state.value.selectedCartTilcods)

        controller.toggleCartSelection("100")
        assertFalse("100" in controller.state.value.selectedCartTilcods)
        // 0件になっても選択モードは続く(design §2-5)。
        assertTrue(controller.state.value.selectionMode)

        controller.toggleCartSelection("100")
        assertTrue("100" in controller.state.value.selectedCartTilcods)
        controller.close()
    }

    @Test
    fun `選択モード中でないtoggleCartSelectionは働かない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = NewArrivalsScreenController(
            FakeNewArrivalRepository(listOf(newArrival("100")), null), FakeUpdateRunner(FakeNewArrivalRepository(emptyList(), null)), dispatcher,
        )
        advanceUntilIdle()

        controller.toggleCartSelection("100")

        assertFalse("100" in controller.state.value.selectedCartTilcods)
        assertFalse(controller.state.value.selectionMode)
        controller.close()
    }

    @Test
    fun `長押しで選択モードに入りその行が選択される`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = NewArrivalsScreenController(
            FakeNewArrivalRepository(listOf(newArrival("100")), null), FakeUpdateRunner(FakeNewArrivalRepository(emptyList(), null)), dispatcher,
        )
        advanceUntilIdle()

        controller.enterSelectionMode("100")

        assertTrue(controller.state.value.selectionMode)
        assertTrue("100" in controller.state.value.selectedCartTilcods)
        controller.close()
    }

    @Test
    fun `一覧に無いtilcodでは選択モードに入らない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = NewArrivalsScreenController(
            FakeNewArrivalRepository(listOf(newArrival("100")), null), FakeUpdateRunner(FakeNewArrivalRepository(emptyList(), null)), dispatcher,
        )
        advanceUntilIdle()

        controller.enterSelectionMode("999")

        assertFalse(controller.state.value.selectionMode)
        assertTrue(controller.state.value.selectedCartTilcods.isEmpty())
        controller.close()
    }

    @Test
    fun `資料番号が空の行では選択モードに入らない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = NewArrivalsScreenController(
            FakeNewArrivalRepository(listOf(newArrival("")), null), FakeUpdateRunner(FakeNewArrivalRepository(emptyList(), null)), dispatcher,
        )
        advanceUntilIdle()

        controller.enterSelectionMode("")

        assertFalse(controller.state.value.selectionMode)
        controller.close()
    }

    @Test
    fun `exitSelectionModeで選択が空になり選択モードから抜ける`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = NewArrivalsScreenController(
            FakeNewArrivalRepository(listOf(newArrival("100")), null), FakeUpdateRunner(FakeNewArrivalRepository(emptyList(), null)), dispatcher,
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        controller.exitSelectionMode()

        assertFalse(controller.state.value.selectionMode)
        assertTrue(controller.state.value.selectedCartTilcods.isEmpty())
        controller.close()
    }

    @Test
    fun `処理中はenterSelectionModeもtoggleCartSelectionも受け付けない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cartRepository = FakeCartRepository()
        val repository = FakeNewArrivalRepository(listOf(newArrival("100"), newArrival("101")), null)
        val controller = NewArrivalsScreenController(
            repository,
            FakeUpdateRunner(FakeNewArrivalRepository(emptyList(), null)),
            dispatcher,
            familyRepository = FakeFamilyRepository(listOf(father)),
            cartRepository = cartRepository,
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        val candidates = NewArrivalsContentBuilder.cartAdditionCandidates(controller.state.value.rows, controller.state.value.selectedCartTilcods)
        controller.requestBulkCartAddition(candidates)
        controller.selectBulkCartAdditionMember(father.id)
        controller.confirmBulkCartAddition()
        // scope.launch本体がまだ進んでいない(processing=trueになった直後)を模す。
        assertTrue(controller.state.value.anyBulkActionProcessing)

        // 処理中は選択モードへの新規参加(enterSelectionMode)も選択の切り替え(toggleCartSelection)も働かない。
        controller.enterSelectionMode("101")
        assertFalse("101" in controller.state.value.selectedCartTilcods)
        controller.toggleCartSelection("100")
        assertTrue("100" in controller.state.value.selectedCartTilcods)

        advanceUntilIdle()
        controller.close()
    }

    @Test
    fun `メンバー未選択ではconfirmBulkCartAdditionを呼んでも追加されない(design §7,2)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cartRepository = FakeCartRepository()
        val controller = NewArrivalsScreenController(
            FakeNewArrivalRepository(listOf(newArrival("100")), null),
            FakeUpdateRunner(FakeNewArrivalRepository(emptyList(), null)),
            dispatcher,
            familyRepository = FakeFamilyRepository(listOf(father)),
            cartRepository = cartRepository,
        )
        advanceUntilIdle()

        controller.requestBulkCartAddition(listOf(BulkCartAdditionCandidate("100", "資料A", null)))
        controller.confirmBulkCartAddition()
        advanceUntilIdle()

        assertEquals(0, cartRepository.calls)
        assertTrue(controller.state.value.bulkCartAdditionConfirmation != null)
        controller.close()
    }

    @Test
    fun `メンバー選択後の確定で対象memberIdを渡し選択と確認状態をクリアする`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cartRepository = FakeCartRepository(summary = ReservationCartAddSummary(added = 1, skipped = 0))
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val controller = NewArrivalsScreenController(
            repository,
            FakeUpdateRunner(repository),
            dispatcher,
            familyRepository = FakeFamilyRepository(listOf(father)),
            cartRepository = cartRepository,
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        val candidates = NewArrivalsContentBuilder.cartAdditionCandidates(controller.state.value.rows, controller.state.value.selectedCartTilcods)
        controller.requestBulkCartAddition(candidates)
        controller.selectBulkCartAdditionMember(father.id)
        controller.confirmBulkCartAddition()
        advanceUntilIdle()

        assertEquals(1, cartRepository.calls)
        assertEquals(father.id, cartRepository.receivedTargets.single().memberId)
        assertEquals("100", cartRepository.receivedTargets.single().tilcod)
        assertTrue(controller.state.value.selectedCartTilcods.isEmpty())
        // 一斉カート追加の完了(成功)で選択モードから抜ける(design §3.8-3)。
        assertFalse(controller.state.value.selectionMode)
        assertNull(controller.state.value.bulkCartAdditionConfirmation)
        assertEquals("1件をカートへ追加しました", controller.state.value.bulkCartAdditionResultMessage)
        controller.close()
    }

    @Test
    fun `一斉カート追加が通信例外で終わったときは選択状態と選択モードに触れない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cartRepository = object : ReservationCartRepository {
            override fun cartItems(): Flow<List<ReservationCartItem>> = flowOf(emptyList())
            override suspend fun addToCart(target: ReservationTarget) = Unit
            override suspend fun addToCart(targets: List<ReservationTarget>): ReservationCartAddSummary = throw RuntimeException("boom")
            override suspend fun removeFromCart(cartItemId: Long) = Unit
            override suspend fun removeFromCart(cartItemIds: List<Long>) = Unit
            override suspend fun clearCart() = Unit
            override suspend fun confirmCart(confirmation: ReservationConfirmation): ReservationBatchResult = ReservationBatchResult(emptyList())
            override suspend fun reserveNow(target: ReservationTarget, confirmation: ReservationConfirmation): ReservationBatchResult =
                ReservationBatchResult(emptyList())
            override suspend fun reserveNow(targets: List<ReservationTarget>, confirmation: ReservationConfirmation): ReservationBatchResult =
                ReservationBatchResult(emptyList())
        }
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val controller = NewArrivalsScreenController(
            repository,
            FakeUpdateRunner(repository),
            dispatcher,
            familyRepository = FakeFamilyRepository(listOf(father)),
            cartRepository = cartRepository,
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        val candidates = NewArrivalsContentBuilder.cartAdditionCandidates(controller.state.value.rows, controller.state.value.selectedCartTilcods)
        controller.requestBulkCartAddition(candidates)
        controller.selectBulkCartAdditionMember(father.id)
        controller.confirmBulkCartAddition()
        advanceUntilIdle()

        assertEquals(setOf("100"), controller.state.value.selectedCartTilcods)
        assertTrue(controller.state.value.selectionMode)
        controller.close()
    }

    @Test
    fun `一斉直接予約が通信例外で終わったときは選択状態と選択モードに触れない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cartRepository = object : ReservationCartRepository {
            override fun cartItems(): Flow<List<ReservationCartItem>> = flowOf(emptyList())
            override suspend fun addToCart(target: ReservationTarget) = Unit
            override suspend fun addToCart(targets: List<ReservationTarget>): ReservationCartAddSummary = ReservationCartAddSummary(0, 0)
            override suspend fun removeFromCart(cartItemId: Long) = Unit
            override suspend fun removeFromCart(cartItemIds: List<Long>) = Unit
            override suspend fun clearCart() = Unit
            override suspend fun confirmCart(confirmation: ReservationConfirmation): ReservationBatchResult = ReservationBatchResult(emptyList())
            override suspend fun reserveNow(target: ReservationTarget, confirmation: ReservationConfirmation): ReservationBatchResult =
                ReservationBatchResult(emptyList())
            override suspend fun reserveNow(targets: List<ReservationTarget>, confirmation: ReservationConfirmation): ReservationBatchResult =
                throw RuntimeException("boom")
        }
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val controller = NewArrivalsScreenController(
            repository,
            FakeUpdateRunner(repository),
            dispatcher,
            familyRepository = FakeFamilyRepository(listOf(father)),
            cartRepository = cartRepository,
            calendarRepository = FakeCalendarRepository(),
            defaultPickupLibraryCode = flowOf("A"),
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        val candidates = NewArrivalsContentBuilder.cartAdditionCandidates(controller.state.value.rows, controller.state.value.selectedCartTilcods)
        controller.requestBulkDirectReservation(candidates)
        controller.selectBulkDirectReservationMember(father.id)
        controller.confirmBulkDirectReservation()
        advanceUntilIdle()

        assertEquals(setOf("100"), controller.state.value.selectedCartTilcods)
        assertTrue(controller.state.value.selectionMode)
        controller.close()
    }

    @Test
    fun `一覧から消えたキーは実行時に無視される(design §4,3)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cartRepository = FakeCartRepository(summary = ReservationCartAddSummary(added = 1, skipped = 0))
        val itemsFlow = MutableStateFlow(listOf(newArrival("100"), newArrival("101")))
        val repository = object : NewArrivalRepository {
            override fun newArrivals(): Flow<List<NewArrival>> = itemsFlow
            override suspend fun refresh() = Unit
            override suspend fun lastFetchedAtEpochMillis(): Long? = null
            override suspend fun hasCachedItems(): Boolean = true
        }
        val controller = NewArrivalsScreenController(
            repository,
            FakeUpdateRunner(FakeNewArrivalRepository(emptyList(), null)),
            dispatcher,
            familyRepository = FakeFamilyRepository(listOf(father)),
            cartRepository = cartRepository,
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.toggleCartSelection("101")
        val candidates = NewArrivalsContentBuilder.cartAdditionCandidates(controller.state.value.rows, controller.state.value.selectedCartTilcods)
        controller.requestBulkCartAddition(candidates)
        controller.selectBulkCartAdditionMember(father.id)

        // 確認待ちの間に一覧から101が消えたことを模す。
        itemsFlow.value = listOf(newArrival("100"))
        advanceUntilIdle()

        controller.confirmBulkCartAddition()
        advanceUntilIdle()

        assertEquals(1, cartRepository.receivedTargets.size)
        assertEquals("100", cartRepository.receivedTargets.single().tilcod)
        controller.close()
    }

    // ------------------------------------------------------------------
    // 一斉直接予約(`docs/design/bulk-selection-followup.md` §5、機能F)。カートを経由しない(§5.2)。
    // ------------------------------------------------------------------

    @Test
    fun `一斉直接予約の確認要求時に受取館の初期値が既定館になる`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val controller = NewArrivalsScreenController(
            repository,
            FakeUpdateRunner(FakeNewArrivalRepository(emptyList(), null)),
            dispatcher,
            familyRepository = FakeFamilyRepository(listOf(father)),
            cartRepository = FakeCartRepository(),
            calendarRepository = FakeCalendarRepository(),
            defaultPickupLibraryCode = flowOf("B"),
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        val candidates = NewArrivalsContentBuilder.cartAdditionCandidates(controller.state.value.rows, controller.state.value.selectedCartTilcods)
        controller.requestBulkDirectReservation(candidates)

        assertEquals("B", controller.state.value.bulkDirectReservationConfirmation?.pickupLibraryCode)
        controller.close()
    }

    @Test
    fun `メンバー未選択ではconfirmBulkDirectReservationを呼んでも予約されない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val cartRepository = FakeCartRepository()
        val controller = NewArrivalsScreenController(
            repository,
            FakeUpdateRunner(FakeNewArrivalRepository(emptyList(), null)),
            dispatcher,
            familyRepository = FakeFamilyRepository(listOf(father)),
            cartRepository = cartRepository,
            calendarRepository = FakeCalendarRepository(),
            defaultPickupLibraryCode = flowOf("A"),
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        val candidates = NewArrivalsContentBuilder.cartAdditionCandidates(controller.state.value.rows, controller.state.value.selectedCartTilcods)
        controller.requestBulkDirectReservation(candidates)
        controller.confirmBulkDirectReservation()
        advanceUntilIdle()

        assertEquals(0, cartRepository.reserveNowListCalls)
        assertTrue(controller.state.value.bulkDirectReservationConfirmation != null)
        controller.close()
    }

    @Test
    fun `受取館未選択ではconfirmBulkDirectReservationを呼んでも予約されない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val cartRepository = FakeCartRepository()
        val controller = NewArrivalsScreenController(
            repository,
            FakeUpdateRunner(FakeNewArrivalRepository(emptyList(), null)),
            dispatcher,
            familyRepository = FakeFamilyRepository(listOf(father)),
            cartRepository = cartRepository,
            calendarRepository = FakeCalendarRepository(libraries = emptyList()),
            defaultPickupLibraryCode = flowOf(""),
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        val candidates = NewArrivalsContentBuilder.cartAdditionCandidates(controller.state.value.rows, controller.state.value.selectedCartTilcods)
        controller.requestBulkDirectReservation(candidates)
        controller.selectBulkDirectReservationMember(father.id)
        controller.confirmBulkDirectReservation()
        advanceUntilIdle()

        assertEquals(0, cartRepository.reserveNowListCalls)
        assertTrue(controller.state.value.bulkDirectReservationConfirmation != null)
        controller.close()
    }

    @Test
    fun `メンバーと受取館選択後の確定で対象memberIdと受取館を渡し選択と確認状態をクリアする`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val cartRepository = FakeCartRepository()
        val controller = NewArrivalsScreenController(
            repository,
            FakeUpdateRunner(FakeNewArrivalRepository(emptyList(), null)),
            dispatcher,
            familyRepository = FakeFamilyRepository(listOf(father)),
            cartRepository = cartRepository,
            calendarRepository = FakeCalendarRepository(),
            defaultPickupLibraryCode = flowOf("A"),
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        val candidates = NewArrivalsContentBuilder.cartAdditionCandidates(controller.state.value.rows, controller.state.value.selectedCartTilcods)
        controller.requestBulkDirectReservation(candidates)
        controller.selectBulkDirectReservationMember(father.id)
        controller.selectBulkDirectReservationPickupLibrary("B")
        controller.confirmBulkDirectReservation()
        advanceUntilIdle()

        assertEquals(1, cartRepository.reserveNowListCalls)
        assertEquals(father.id, cartRepository.receivedReserveNowTargets.single().memberId)
        assertEquals("100", cartRepository.receivedReserveNowTargets.single().tilcod)
        assertEquals("B", cartRepository.receivedReserveNowConfirmation?.pickupLibraryCode)
        assertTrue(controller.state.value.selectedCartTilcods.isEmpty())
        // 一斉直接予約の完了で選択モードから抜ける(design §3.8-3)。
        assertFalse(controller.state.value.selectionMode)
        assertNull(controller.state.value.bulkDirectReservationConfirmation)
        assertEquals(1, controller.state.value.bulkDirectReservationResults.size)
        controller.close()
    }

    @Test
    fun `一斉直接予約の確認待ちの間に一覧から消えたキーは実行時に無視される(design §4,3)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cartRepository = FakeCartRepository()
        val itemsFlow = MutableStateFlow(listOf(newArrival("100"), newArrival("101")))
        val repository = object : NewArrivalRepository {
            override fun newArrivals(): Flow<List<NewArrival>> = itemsFlow
            override suspend fun refresh() = Unit
            override suspend fun lastFetchedAtEpochMillis(): Long? = null
            override suspend fun hasCachedItems(): Boolean = true
        }
        val controller = NewArrivalsScreenController(
            repository,
            FakeUpdateRunner(FakeNewArrivalRepository(emptyList(), null)),
            dispatcher,
            familyRepository = FakeFamilyRepository(listOf(father)),
            cartRepository = cartRepository,
            calendarRepository = FakeCalendarRepository(),
            defaultPickupLibraryCode = flowOf("A"),
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.toggleCartSelection("101")
        val candidates = NewArrivalsContentBuilder.cartAdditionCandidates(controller.state.value.rows, controller.state.value.selectedCartTilcods)
        controller.requestBulkDirectReservation(candidates)
        controller.selectBulkDirectReservationMember(father.id)

        // 確認待ちの間に一覧から101が消えたことを模す。
        itemsFlow.value = listOf(newArrival("100"))
        advanceUntilIdle()

        controller.confirmBulkDirectReservation()
        advanceUntilIdle()

        assertEquals(1, cartRepository.receivedReserveNowTargets.size)
        assertEquals("100", cartRepository.receivedReserveNowTargets.single().tilcod)
        controller.close()
    }

    // ------------------------------------------------------------------
    // 選択解除警告(`docs/design/bulk-selection-followup.md` §6.2)。対象操作は巡回(refresh、「更新」)のみ。
    // onScreenLaunchedの自動巡回・loadMoreに相当する操作はNewArrivalsScreenControllerには無いため対象外。
    // ------------------------------------------------------------------

    @Test
    fun `選択0件では確認を出さず即座に巡回する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val updater = FakeUpdateRunner(repository)
        val controller = NewArrivalsScreenController(repository, updater, dispatcher)
        advanceUntilIdle()

        controller.refresh()
        advanceUntilIdle()

        assertFalse(controller.state.value.pendingRefreshConfirmation)
        assertEquals(1, repository.refreshCallCount)
        controller.close()
    }

    @Test
    fun `設定オフでは選択が残っていても確認を出さず即座に巡回する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val updater = FakeUpdateRunner(repository)
        val controller = NewArrivalsScreenController(
            repository, updater, dispatcher,
            warnBeforeClearingSelection = flowOf(false),
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        controller.refresh()
        advanceUntilIdle()

        assertFalse(controller.state.value.pendingRefreshConfirmation)
        assertEquals(1, repository.refreshCallCount)
        controller.close()
    }

    @Test
    fun `選択が残っていて設定オンなら確認を要求し巡回を保留する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val updater = FakeUpdateRunner(repository)
        val controller = NewArrivalsScreenController(repository, updater, dispatcher)
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        controller.refresh()
        advanceUntilIdle()

        assertTrue(controller.state.value.pendingRefreshConfirmation)
        assertEquals(0, repository.refreshCallCount)
        assertTrue("100" in controller.state.value.selectedCartTilcods)
        controller.close()
    }

    @Test
    fun `続けるで選択を解除し保留していた巡回を実行する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val updater = FakeUpdateRunner(repository)
        val controller = NewArrivalsScreenController(repository, updater, dispatcher)
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.refresh()
        advanceUntilIdle()

        controller.confirmPendingRefresh()
        advanceUntilIdle()

        assertFalse(controller.state.value.pendingRefreshConfirmation)
        assertTrue(controller.state.value.selectedCartTilcods.isEmpty())
        assertEquals(1, repository.refreshCallCount)
        controller.close()
    }

    @Test
    fun `戻るで選択を残し巡回を実行しない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val updater = FakeUpdateRunner(repository)
        val controller = NewArrivalsScreenController(repository, updater, dispatcher)
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.refresh()
        advanceUntilIdle()

        controller.dismissPendingRefresh()
        advanceUntilIdle()

        assertFalse(controller.state.value.pendingRefreshConfirmation)
        assertTrue("100" in controller.state.value.selectedCartTilcods)
        assertEquals(0, repository.refreshCallCount)
        controller.close()
    }

    @Test
    fun `今後は表示しないで設定を無効化しつつ巡回を実行する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val updater = FakeUpdateRunner(repository)
        var disableCalls = 0
        val controller = NewArrivalsScreenController(
            repository, updater, dispatcher,
            disableWarnBeforeClearingSelection = { disableCalls++ },
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.refresh()
        advanceUntilIdle()

        controller.confirmPendingRefreshAndDisableWarning()
        advanceUntilIdle()

        assertEquals(1, disableCalls)
        assertFalse(controller.state.value.pendingRefreshConfirmation)
        assertTrue(controller.state.value.selectedCartTilcods.isEmpty())
        assertFalse(controller.state.value.warnBeforeClearingSelection)
        assertEquals(1, repository.refreshCallCount)
        controller.close()
    }

    // ------------------------------------------------------------------
    // 一時表示のリセット(`docs/design/search-result-reset.md` §8)
    // ------------------------------------------------------------------

    @Test
    fun `カート追加の結果メッセージが出た後にrefreshすると結果メッセージがnullになる(design §8,4-1)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cartRepository = FakeCartRepository(summary = ReservationCartAddSummary(added = 1, skipped = 0))
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val controller = NewArrivalsScreenController(
            repository,
            FakeUpdateRunner(repository),
            dispatcher,
            familyRepository = FakeFamilyRepository(listOf(father)),
            cartRepository = cartRepository,
            warnBeforeClearingSelection = flowOf(false),
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        val candidates = NewArrivalsContentBuilder.cartAdditionCandidates(controller.state.value.rows, controller.state.value.selectedCartTilcods)
        controller.requestBulkCartAddition(candidates)
        controller.selectBulkCartAdditionMember(father.id)
        controller.confirmBulkCartAddition()
        advanceUntilIdle()
        assertEquals("1件をカートへ追加しました", controller.state.value.bulkCartAdditionResultMessage)

        controller.refresh()
        advanceUntilIdle()

        assertNull(controller.state.value.bulkCartAdditionResultMessage)
        controller.close()
    }

    @Test
    fun `カート追加のエラーメッセージが出た後にrefreshするとエラーメッセージがnullになる(design §8,4-2)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cartRepository = object : ReservationCartRepository {
            override fun cartItems(): Flow<List<ReservationCartItem>> = flowOf(emptyList())
            override suspend fun addToCart(target: ReservationTarget) = Unit
            override suspend fun addToCart(targets: List<ReservationTarget>): ReservationCartAddSummary = throw RuntimeException("boom")
            override suspend fun removeFromCart(cartItemId: Long) = Unit
            override suspend fun removeFromCart(cartItemIds: List<Long>) = Unit
            override suspend fun clearCart() = Unit
            override suspend fun confirmCart(confirmation: ReservationConfirmation): ReservationBatchResult = ReservationBatchResult(emptyList())
            override suspend fun reserveNow(target: ReservationTarget, confirmation: ReservationConfirmation): ReservationBatchResult =
                ReservationBatchResult(emptyList())
            override suspend fun reserveNow(targets: List<ReservationTarget>, confirmation: ReservationConfirmation): ReservationBatchResult =
                ReservationBatchResult(emptyList())
        }
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val controller = NewArrivalsScreenController(
            repository,
            FakeUpdateRunner(repository),
            dispatcher,
            familyRepository = FakeFamilyRepository(listOf(father)),
            cartRepository = cartRepository,
            warnBeforeClearingSelection = flowOf(false),
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        val candidates = NewArrivalsContentBuilder.cartAdditionCandidates(controller.state.value.rows, controller.state.value.selectedCartTilcods)
        controller.requestBulkCartAddition(candidates)
        controller.selectBulkCartAdditionMember(father.id)
        controller.confirmBulkCartAddition()
        advanceUntilIdle()
        assertEquals("カートへ追加できませんでした。もう一度お試しください。", controller.state.value.bulkCartAdditionErrorMessage)

        controller.refresh()
        advanceUntilIdle()

        assertNull(controller.state.value.bulkCartAdditionErrorMessage)
        controller.close()
    }

    @Test
    fun `警告設定オフで選択が残ったままrefreshすると選択が空になる(design §8,4-3)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val updater = FakeUpdateRunner(repository)
        val controller = NewArrivalsScreenController(
            repository, updater, dispatcher,
            warnBeforeClearingSelection = flowOf(false),
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        controller.refresh()
        advanceUntilIdle()

        assertTrue(controller.state.value.selectedCartTilcods.isEmpty())
        // 巡回(「更新」)は選択モードからも抜ける(design §3.8「新着の巡回」)。
        assertFalse(controller.state.value.selectionMode)
        controller.close()
    }

    @Test
    fun `refreshではbulkDirectReservationResultsとエラーメッセージが残る(design §8,4-4)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val cartRepository = FakeCartRepository()
        val controller = NewArrivalsScreenController(
            repository,
            FakeUpdateRunner(repository),
            dispatcher,
            familyRepository = FakeFamilyRepository(listOf(father)),
            cartRepository = cartRepository,
            calendarRepository = FakeCalendarRepository(),
            defaultPickupLibraryCode = flowOf("A"),
            warnBeforeClearingSelection = flowOf(false),
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        val candidates = NewArrivalsContentBuilder.cartAdditionCandidates(controller.state.value.rows, controller.state.value.selectedCartTilcods)
        controller.requestBulkDirectReservation(candidates)
        controller.selectBulkDirectReservationMember(father.id)
        controller.selectBulkDirectReservationPickupLibrary("A")
        controller.confirmBulkDirectReservation()
        advanceUntilIdle()
        assertEquals(1, controller.state.value.bulkDirectReservationResults.size)

        controller.refresh()
        advanceUntilIdle()

        assertEquals(1, controller.state.value.bulkDirectReservationResults.size)
        controller.close()
    }

    @Test
    fun `resetOnLeaveで選択・カート追加のメッセージ・queryが初期状態になる(design §8,4-5)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cartRepository = FakeCartRepository(summary = ReservationCartAddSummary(added = 1, skipped = 0))
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val controller = NewArrivalsScreenController(
            repository,
            FakeUpdateRunner(repository),
            dispatcher,
            familyRepository = FakeFamilyRepository(listOf(father)),
            cartRepository = cartRepository,
        )
        advanceUntilIdle()
        controller.updateQuery("書名")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        val candidates = NewArrivalsContentBuilder.cartAdditionCandidates(controller.state.value.rows, controller.state.value.selectedCartTilcods)
        controller.requestBulkCartAddition(candidates)
        controller.selectBulkCartAdditionMember(father.id)
        controller.confirmBulkCartAddition()
        advanceUntilIdle()
        assertEquals("1件をカートへ追加しました", controller.state.value.bulkCartAdditionResultMessage)

        controller.resetOnLeave()

        // combine(query)がDispatchers.Default上のコルーチンを介して反映される前(advanceUntilIdle前)
        // でも、state.queryは同期的に空になっていること(レビュー指摘: 通知タップ等の自動遷移で
        // 反映前に画面へ戻ると入力欄と一覧表示が食い違う不具合の再発防止)。
        assertEquals("", controller.state.value.query)

        advanceUntilIdle()

        assertTrue(controller.state.value.selectedCartTilcods.isEmpty())
        assertNull(controller.state.value.bulkCartAdditionResultMessage)
        assertNull(controller.state.value.bulkCartAdditionErrorMessage)
        assertEquals("", controller.state.value.query)
        controller.close()
    }

    @Test
    fun `resetOnLeaveで選択モードから抜ける(design §3,8-4)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val controller = NewArrivalsScreenController(repository, FakeUpdateRunner(repository), dispatcher)
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        assertTrue(controller.state.value.selectionMode)

        controller.resetOnLeave()

        assertFalse(controller.state.value.selectionMode)
        controller.close()
    }

    @Test
    fun `resetOnLeaveでbulkDirectReservationResultsとエラーメッセージが残る(design §8,4-6)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = FakeNewArrivalRepository(listOf(newArrival("100")), null)
        val cartRepository = FakeCartRepository()
        val controller = NewArrivalsScreenController(
            repository,
            FakeUpdateRunner(repository),
            dispatcher,
            familyRepository = FakeFamilyRepository(listOf(father)),
            cartRepository = cartRepository,
            calendarRepository = FakeCalendarRepository(),
            defaultPickupLibraryCode = flowOf("A"),
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        val candidates = NewArrivalsContentBuilder.cartAdditionCandidates(controller.state.value.rows, controller.state.value.selectedCartTilcods)
        controller.requestBulkDirectReservation(candidates)
        controller.selectBulkDirectReservationMember(father.id)
        controller.selectBulkDirectReservationPickupLibrary("A")
        controller.confirmBulkDirectReservation()
        advanceUntilIdle()
        assertEquals(1, controller.state.value.bulkDirectReservationResults.size)

        controller.resetOnLeave()
        advanceUntilIdle()

        assertEquals(1, controller.state.value.bulkDirectReservationResults.size)
        controller.close()
    }

    @Test
    fun `resetOnLeaveでrowsとtotalCountとlastFetchedAtEpochMillisが保たれる(design §8,4-7)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val now = Instant.parse("2030-01-02T00:00:00Z")
        val repository = FakeNewArrivalRepository(
            listOf(newArrival("100")),
            now.toEpochMilli(),
        )
        val controller = NewArrivalsScreenController(repository, FakeUpdateRunner(repository), dispatcher)
        advanceUntilIdle()
        val rowsBefore = controller.state.value.rows
        val totalCountBefore = controller.state.value.totalCount
        val lastFetchedBefore = controller.state.value.lastFetchedAtEpochMillis

        controller.resetOnLeave()
        advanceUntilIdle()

        assertEquals(rowsBefore, controller.state.value.rows)
        assertEquals(totalCountBefore, controller.state.value.totalCount)
        assertEquals(lastFetchedBefore, controller.state.value.lastFetchedAtEpochMillis)
        controller.close()
    }

    // このテストだけUnconfinedTestDispatcherを使う。StandardTestDispatcherではrefresh()が
    // launchした巡回コルーチンをentered.await()の前に進める手段(advanceUntilIdle等)がなく、
    // 「巡回の最中(release待ちで一時停止した状態)」を作れないため。他のテストと違う実行モデルを
    // 使っているだけで、書き間違いではない。
    @Test
    fun `巡回中にresetOnLeaveしても巡回は続き完了後にrefreshingがfalseになる(design §8,4-8)`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repository = FakeNewArrivalRepository(emptyList(), null)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val updater = FakeUpdateRunner(repository, beforeResult = {
            entered.complete(Unit)
            release.await()
        })
        val controller = NewArrivalsScreenController(repository, updater, dispatcher)

        controller.refresh()
        entered.await()
        assertEquals(true, controller.state.value.refreshing)

        controller.resetOnLeave()

        assertEquals(true, controller.state.value.refreshing)
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(false, controller.state.value.refreshing)
        controller.close()
    }

    private class FakeFamilyRepository(private val members: List<Member>) : FamilyRepository {
        override fun members(): Flow<List<Member>> = flowOf(members)
        override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) = Unit
        override suspend fun updateMember(member: Member, newPassword: String?) = Unit
        override suspend fun removeMember(memberId: Long) = Unit
    }

    private class FakeCartRepository(
        private val summary: ReservationCartAddSummary = ReservationCartAddSummary(0, 0),
    ) : ReservationCartRepository {
        var calls = 0
        val receivedTargets = mutableListOf<ReservationTarget>()
        var reserveNowListCalls = 0
        val receivedReserveNowTargets = mutableListOf<ReservationTarget>()
        var receivedReserveNowConfirmation: ReservationConfirmation? = null
        override fun cartItems(): Flow<List<ReservationCartItem>> = flowOf(emptyList())
        override suspend fun addToCart(target: ReservationTarget) = Unit
        override suspend fun addToCart(targets: List<ReservationTarget>): ReservationCartAddSummary {
            calls++
            receivedTargets += targets
            return summary
        }
        override suspend fun removeFromCart(cartItemId: Long) = Unit
        override suspend fun removeFromCart(cartItemIds: List<Long>) = Unit
        override suspend fun clearCart() = Unit
        override suspend fun confirmCart(confirmation: ReservationConfirmation): ReservationBatchResult = ReservationBatchResult(emptyList())
        override suspend fun reserveNow(target: ReservationTarget, confirmation: ReservationConfirmation): ReservationBatchResult =
            ReservationBatchResult(emptyList())
        override suspend fun reserveNow(targets: List<ReservationTarget>, confirmation: ReservationConfirmation): ReservationBatchResult {
            reserveNowListCalls++
            receivedReserveNowTargets += targets
            receivedReserveNowConfirmation = confirmation
            return ReservationBatchResult(
                targets.groupBy { it.memberId }.map { (memberId, grouped) ->
                    MemberReservationResult(memberId, grouped.map { ReservationItemResult(it, ReservationOutcome.Success) })
                },
            )
        }
    }

    private class FakeCalendarRepository(
        override val libraries: List<Library> = listOf(Library("A", "中央図書館"), Library("B", "北口図書館")),
    ) : CalendarRepository {
        override fun closedDays(libraryCode: String): Flow<List<ClosedDay>> = flowOf(emptyList())
        override suspend fun refreshClosedDays(libraryCode: String) = Unit
    }

    private fun newArrival(tilcod: String) = NewArrival(
        tilcod = tilcod,
        title = "書名$tilcod",
        volume = "",
        author = "",
        publisher = "",
        publishedYearMonth = "",
        classification = "",
        lendable = null,
    )

    private class FakeNewArrivalRepository(
        private var items: List<NewArrival>,
        private var lastFetchedAtEpochMillis: Long?,
    ) : NewArrivalRepository {
        var refreshCallCount = 0
        private val flow = MutableStateFlow(items)

        override fun newArrivals() = flow

        override suspend fun refresh() {
            refreshCallCount += 1
        }

        override suspend fun lastFetchedAtEpochMillis(): Long? = lastFetchedAtEpochMillis

        override suspend fun hasCachedItems(): Boolean = items.isNotEmpty()
    }

    private class FakeUpdateRunner(
        private val repository: FakeNewArrivalRepository,
        private val result: NewArrivalUpdateResult = NewArrivalUpdateResult.Completed(AutomaticReservationRunResult.NoMatch),
        private val beforeResult: suspend () -> Unit = {},
    ) : NewArrivalUpdateRunner {
        val triggers = mutableListOf<NewArrivalUpdateTrigger>()
        override suspend fun refresh(trigger: NewArrivalUpdateTrigger): NewArrivalUpdateResult {
            triggers += trigger
            beforeResult()
            if (result is NewArrivalUpdateResult.Completed) repository.refresh()
            return result
        }
    }
}
