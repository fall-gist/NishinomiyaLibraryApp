package com.fallgist.nishinomiyalibrary.ui.newarrivals

import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartAddSummary
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartItem
import com.fallgist.nishinomiyalibrary.domain.model.ReservationConfirmation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
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
    fun `選択の切り替えでtilcodが追加削除される`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = NewArrivalsScreenController(
            FakeNewArrivalRepository(emptyList(), null), FakeUpdateRunner(FakeNewArrivalRepository(emptyList(), null)), dispatcher,
        )
        advanceUntilIdle()

        controller.toggleCartSelection("100")
        assertTrue("100" in controller.state.value.selectedCartTilcods)
        controller.toggleCartSelection("100")
        assertFalse("100" in controller.state.value.selectedCartTilcods)
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
        controller.toggleCartSelection("100")

        val candidates = NewArrivalsContentBuilder.cartAdditionCandidates(controller.state.value.rows, controller.state.value.selectedCartTilcods)
        controller.requestBulkCartAddition(candidates)
        controller.selectBulkCartAdditionMember(father.id)
        controller.confirmBulkCartAddition()
        advanceUntilIdle()

        assertEquals(1, cartRepository.calls)
        assertEquals(father.id, cartRepository.receivedTargets.single().memberId)
        assertEquals("100", cartRepository.receivedTargets.single().tilcod)
        assertTrue(controller.state.value.selectedCartTilcods.isEmpty())
        assertNull(controller.state.value.bulkCartAdditionConfirmation)
        assertEquals("1件をカートへ追加しました", controller.state.value.bulkCartAdditionResultMessage)
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
        controller.toggleCartSelection("100")
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
        override fun cartItems(): Flow<List<ReservationCartItem>> = flowOf(emptyList())
        override suspend fun addToCart(target: ReservationTarget) = Unit
        override suspend fun addToCart(targets: List<ReservationTarget>): ReservationCartAddSummary {
            calls++
            receivedTargets += targets
            return summary
        }
        override suspend fun removeFromCart(cartItemId: Long) = Unit
        override suspend fun confirmCart(confirmation: ReservationConfirmation): ReservationBatchResult = ReservationBatchResult(emptyList())
        override suspend fun reserveNow(target: ReservationTarget, confirmation: ReservationConfirmation): ReservationBatchResult =
            ReservationBatchResult(emptyList())
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
