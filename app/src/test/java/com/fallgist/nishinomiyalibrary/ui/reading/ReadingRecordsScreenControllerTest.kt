package com.fallgist.nishinomiyalibrary.ui.reading

import com.fallgist.nishinomiyalibrary.domain.model.ClosedDay
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.MemberReservationResult
import com.fallgist.nishinomiyalibrary.domain.model.ReadingInfo
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord
import com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartAddSummary
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartItem
import com.fallgist.nishinomiyalibrary.domain.model.ReservationConfirmation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationItemResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
import com.fallgist.nishinomiyalibrary.domain.repository.CalendarRepository
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReadingRecordRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCartRepository
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReadingRecordsScreenController]の複数選択・一斉カート追加・一斉直接予約のテスト
 * (`docs/design/reading-records-selection.md` §4)。決定論的([StandardTestDispatcher] + [runTest])。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReadingRecordsScreenControllerTest {
    private val father = Member(1, "父", "#111111", "card-1", 0)

    private fun controller(
        dispatcher: TestDispatcher,
        records: List<ReadingRecord> = listOf(ReadingRecord(father.id, "100", "資料A", LocalDate.of(2026, 1, 1), "中央図書館")),
        familyRepository: FamilyRepository = FakeFamilyRepository(listOf(father)),
        readingRecordRepository: ReadingRecordRepository = FakeReadingRecordRepository(records),
        cartRepository: ReservationCartRepository = FakeCartRepository(),
        calendarRepository: CalendarRepository = FakeCalendarRepository(),
        defaultPickupLibraryCode: Flow<String> = flowOf("A"),
        now: () -> Long = { 1_000L },
    ): ReadingRecordsScreenController = ReadingRecordsScreenController(
        familyRepository = familyRepository,
        readingRecordRepository = readingRecordRepository,
        cartRepository = cartRepository,
        dispatcher = dispatcher,
        calendarRepository = calendarRepository,
        defaultPickupLibraryCode = defaultPickupLibraryCode,
        now = now,
    )

    // ------------------------------------------------------------------
    // §4-1〜4: 選択モードの基本挙動
    // ------------------------------------------------------------------

    @Test
    fun `長押しで選択モードに入りその資料が選択される`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(dispatcher)
        advanceUntilIdle()

        controller.enterSelectionMode("100")

        assertTrue(controller.state.value.selectionMode)
        assertTrue("100" in controller.state.value.selectedTilcods)
        controller.close()
    }

    @Test
    fun `tilcodが空または一覧に無いtilcodでは選択モードに入らない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(dispatcher)
        advanceUntilIdle()

        controller.enterSelectionMode("")
        assertFalse(controller.state.value.selectionMode)

        controller.enterSelectionMode("999")
        assertFalse(controller.state.value.selectionMode)
        assertTrue(controller.state.value.selectedTilcods.isEmpty())
        controller.close()
    }

    @Test
    fun `選択モード中のtoggleSelectionで選択が増減し0件でもモードは続く`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(dispatcher)
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        controller.toggleSelection("100")
        assertFalse("100" in controller.state.value.selectedTilcods)
        assertTrue(controller.state.value.selectionMode)

        controller.toggleSelection("100")
        assertTrue("100" in controller.state.value.selectedTilcods)
        controller.close()
    }

    @Test
    fun `選択モード中でないtoggleSelectionは働かない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(dispatcher)
        advanceUntilIdle()

        controller.toggleSelection("100")

        assertFalse("100" in controller.state.value.selectedTilcods)
        assertFalse(controller.state.value.selectionMode)
        controller.close()
    }

    @Test
    fun `exitSelectionModeで選択が空になり選択モードから抜ける`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(dispatcher)
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        controller.exitSelectionMode()

        assertFalse(controller.state.value.selectionMode)
        assertTrue(controller.state.value.selectedTilcods.isEmpty())
        controller.close()
    }

    // ------------------------------------------------------------------
    // §4-5: 同じ資料が複数行にあるときの連動(design §3.2)
    // ------------------------------------------------------------------

    @Test
    fun `同じ資料が複数行にあるとき選択は1件として数えられ候補も1件だけ渡る`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val records = listOf(
            ReadingRecord(father.id, "100", "資料A", LocalDate.of(2026, 1, 1), "中央図書館"),
            ReadingRecord(father.id, "100", "資料A", LocalDate.of(2025, 6, 1), "北口図書館"),
        )
        val controller = controller(dispatcher, records = records)
        advanceUntilIdle()

        controller.enterSelectionMode("100")

        assertEquals(setOf("100"), controller.state.value.selectedTilcods)
        val candidates = ReadingRecordsContentBuilder.cartAdditionCandidates(
            controller.state.value.rows,
            controller.state.value.selectedTilcods,
        )
        assertEquals(1, candidates.size)
        controller.close()
    }

    // ------------------------------------------------------------------
    // §4-6・7: 一斉カート追加・一斉直接予約の完了で選択モードから抜ける
    // ------------------------------------------------------------------

    @Test
    fun `一斉カート追加の完了(成功)で選択モードから抜ける`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cartRepository = FakeCartRepository(summary = ReservationCartAddSummary(added = 1, skipped = 0))
        val controller = controller(dispatcher, cartRepository = cartRepository)
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkCartAddition(candidatesOf(controller))
        controller.selectBulkCartAdditionMember(father.id)

        controller.confirmBulkCartAddition()
        advanceUntilIdle()

        assertFalse(controller.state.value.selectionMode)
        assertTrue(controller.state.value.selectedTilcods.isEmpty())
        assertEquals("1件をカートへ追加しました", controller.state.value.bulkCartAdditionResultMessage)
        controller.close()
    }

    @Test
    fun `一斉カート追加が0件追加0件スキップでも完了として選択モードから抜ける`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cartRepository = FakeCartRepository(summary = ReservationCartAddSummary(added = 0, skipped = 1))
        val controller = controller(dispatcher, cartRepository = cartRepository)
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkCartAddition(candidatesOf(controller))
        controller.selectBulkCartAdditionMember(father.id)

        controller.confirmBulkCartAddition()
        advanceUntilIdle()

        // 一斉カート追加の完了(成功・失敗のどちらでも)で選択モードから抜ける
        // (`docs/design/selection-mode.md` §3.8-3)。通信例外はこれに含めない(別テストで検証)。
        assertFalse(controller.state.value.selectionMode)
        controller.close()
    }

    @Test
    fun `一斉カート追加が通信例外で終わったときは選択状態と選択モードに触れない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cartRepository = FailingCartRepository()
        val controller = controller(dispatcher, cartRepository = cartRepository)
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkCartAddition(candidatesOf(controller))
        controller.selectBulkCartAdditionMember(father.id)

        controller.confirmBulkCartAddition()
        advanceUntilIdle()

        assertEquals(setOf("100"), controller.state.value.selectedTilcods)
        assertTrue(controller.state.value.selectionMode)
        assertEquals(
            "カートへ追加できませんでした。もう一度お試しください。",
            controller.state.value.bulkCartAdditionErrorMessage,
        )
        controller.close()
    }

    @Test
    fun `一斉直接予約の完了(成功)で選択モードから抜ける`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cartRepository = FakeCartRepository()
        val controller = controller(dispatcher, cartRepository = cartRepository)
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkDirectReservation(candidatesOf(controller))
        controller.selectBulkDirectReservationMember(father.id)

        controller.confirmBulkDirectReservation()
        advanceUntilIdle()

        assertFalse(controller.state.value.selectionMode)
        assertTrue(controller.state.value.selectedTilcods.isEmpty())
        assertEquals(1, controller.state.value.bulkDirectReservationResults.size)
        assertEquals(1, cartRepository.reserveNowListCalls)
        assertEquals(father.id, cartRepository.receivedReserveNowTargets.single().memberId)
        assertEquals("100", cartRepository.receivedReserveNowTargets.single().tilcod)
        controller.close()
    }

    @Test
    fun `一斉直接予約が通信例外で終わったときは選択状態と選択モードに触れない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cartRepository = FailingReserveNowCartRepository()
        val controller = controller(dispatcher, cartRepository = cartRepository)
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkDirectReservation(candidatesOf(controller))
        controller.selectBulkDirectReservationMember(father.id)

        controller.confirmBulkDirectReservation()
        advanceUntilIdle()

        assertEquals(setOf("100"), controller.state.value.selectedTilcods)
        assertTrue(controller.state.value.selectionMode)
        assertEquals("予約できませんでした。もう一度お試しください。", controller.state.value.bulkDirectReservationErrorMessage)
        controller.close()
    }

    // ------------------------------------------------------------------
    // §4-8: 処理中は選択操作を受け付けない
    // ------------------------------------------------------------------

    @Test
    fun `処理中はenterSelectionModeもtoggleSelectionも受け付けない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val records = listOf(
            ReadingRecord(father.id, "100", "資料A", LocalDate.of(2026, 1, 1), "中央図書館"),
            ReadingRecord(father.id, "101", "資料B", LocalDate.of(2026, 1, 2), "中央図書館"),
        )
        val cartRepository = FakeCartRepository()
        val controller = controller(dispatcher, records = records, cartRepository = cartRepository)
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkCartAddition(candidatesOf(controller))
        controller.selectBulkCartAdditionMember(father.id)
        controller.confirmBulkCartAddition()
        assertTrue(controller.state.value.anyBulkActionProcessing)

        controller.enterSelectionMode("101")
        assertFalse("101" in controller.state.value.selectedTilcods)
        controller.toggleSelection("100")
        assertTrue("100" in controller.state.value.selectedTilcods)

        advanceUntilIdle()
        controller.close()
    }

    // ------------------------------------------------------------------
    // §4-9: 確定の直後に一覧が入れ替わっても確定時点の候補が処理される
    // ------------------------------------------------------------------

    @Test
    fun `確認待ちの間に一覧から消えたキーは実行時に無視される`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val readingRecordRepository = MutableReadingRecordRepository(
            listOf(
                ReadingRecord(father.id, "100", "資料A", LocalDate.of(2026, 1, 1), "中央図書館"),
                ReadingRecord(father.id, "101", "資料B", LocalDate.of(2026, 1, 2), "中央図書館"),
            ),
        )
        val cartRepository = FakeCartRepository(summary = ReservationCartAddSummary(added = 1, skipped = 0))
        val controller = controller(dispatcher, readingRecordRepository = readingRecordRepository, cartRepository = cartRepository)
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.toggleSelection("101")
        controller.requestBulkCartAddition(candidatesOf(controller))
        controller.selectBulkCartAdditionMember(father.id)

        // 確認待ちの間に一覧が変わり、101が無くなったことを模す。
        readingRecordRepository.records = listOf(ReadingRecord(father.id, "100", "資料A", LocalDate.of(2026, 1, 1), "中央図書館"))
        advanceUntilIdle()

        controller.confirmBulkCartAddition()
        advanceUntilIdle()

        assertEquals(1, cartRepository.receivedTargets.size)
        assertEquals("100", cartRepository.receivedTargets.single().tilcod)
        controller.close()
    }

    // ------------------------------------------------------------------
    // §4-10: 絞り込みの更新でlost updateが起きない
    // ------------------------------------------------------------------

    @Test
    fun `絞り込みの更新で選択が消えない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val members = listOf(father, Member(2, "母", "#222222", "card-2", 1))
        val records = listOf(
            ReadingRecord(father.id, "100", "資料A", LocalDate.of(2026, 1, 1), "中央図書館"),
            ReadingRecord(2, "100", "資料A", LocalDate.of(2026, 1, 3), "北口図書館"),
        )
        val controller = controller(
            dispatcher,
            records = records,
            familyRepository = FakeFamilyRepository(members),
        )
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        assertTrue(controller.state.value.selectionMode)

        // メンバー切り替え(絞り込みの更新)。
        controller.selectMember(father.id)
        advanceUntilIdle()

        assertTrue(controller.state.value.selectionMode)
        assertEquals(setOf("100"), controller.state.value.selectedTilcods)

        // 検索語の変更(絞り込みの更新)。
        controller.updateQuery("資料")
        advanceUntilIdle()

        assertTrue(controller.state.value.selectionMode)
        assertEquals(setOf("100"), controller.state.value.selectedTilcods)
        controller.close()
    }

    // ------------------------------------------------------------------
    // §4-11: 表示されなくなった資料は件数と操作対象から外れる
    // ------------------------------------------------------------------

    @Test
    fun `表示されなくなった資料は件数と操作対象から外れる`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val readingRecordRepository = MutableReadingRecordRepository(
            listOf(
                ReadingRecord(father.id, "100", "資料A", LocalDate.of(2026, 1, 1), "中央図書館"),
                ReadingRecord(father.id, "101", "資料B", LocalDate.of(2026, 1, 2), "中央図書館"),
            ),
        )
        val controller = controller(dispatcher, readingRecordRepository = readingRecordRepository)
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.toggleSelection("101")
        assertEquals(setOf("100", "101"), controller.state.value.selectedTilcods)

        // 絞り込みの変更で101が一覧から消える(表示されなくなる)。
        readingRecordRepository.records = listOf(ReadingRecord(father.id, "100", "資料A", LocalDate.of(2026, 1, 1), "中央図書館"))
        advanceUntilIdle()

        val candidates = ReadingRecordsContentBuilder.cartAdditionCandidates(
            controller.state.value.rows,
            controller.state.value.selectedTilcods,
        )
        assertEquals(1, candidates.size)
        assertEquals("100", candidates.single().tilcod)
        controller.close()
    }

    private fun candidatesOf(controller: ReadingRecordsScreenController) =
        ReadingRecordsContentBuilder.cartAdditionCandidates(controller.state.value.rows, controller.state.value.selectedTilcods)

    private class FakeFamilyRepository(private val members: List<Member>) : FamilyRepository {
        override fun members() = flowOf(members)
        override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) = Unit
        override suspend fun updateMember(member: Member, newPassword: String?) = Unit
        override suspend fun removeMember(memberId: Long) = Unit
    }

    private class FakeReadingRecordRepository(private val records: List<ReadingRecord>) : ReadingRecordRepository {
        override fun records(memberId: Long?) = flowOf(if (memberId == null) records else records.filter { it.memberId == memberId })
        override fun search(query: String, memberId: Long?) =
            flowOf(records.filter { it.title.contains(query) }.let { list -> if (memberId == null) list else list.filter { it.memberId == memberId } })
        override fun hasRead(tilcod: String): Flow<List<ReadingInfo>> = flowOf(emptyList())
    }

    /** 絞り込みの合間に一覧を書き換えるテスト用(§4-9・§4-11)。 */
    private class MutableReadingRecordRepository(initial: List<ReadingRecord>) : ReadingRecordRepository {
        private val recordsFlow = MutableStateFlow(initial)
        var records: List<ReadingRecord>
            get() = recordsFlow.value
            set(value) { recordsFlow.value = value }
        override fun records(memberId: Long?) = recordsFlow
        override fun search(query: String, memberId: Long?) = recordsFlow
        override fun hasRead(tilcod: String): Flow<List<ReadingInfo>> = flowOf(emptyList())
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

    /** カート追加のエラーメッセージ表示用。addToCart(List)は必ず失敗する。 */
    private class FailingCartRepository : ReservationCartRepository {
        override fun cartItems(): Flow<List<ReservationCartItem>> = flowOf(emptyList())
        override suspend fun addToCart(target: ReservationTarget) = Unit
        override suspend fun addToCart(targets: List<ReservationTarget>): ReservationCartAddSummary = error("カート追加失敗(テスト用)")
        override suspend fun removeFromCart(cartItemId: Long) = Unit
        override suspend fun removeFromCart(cartItemIds: List<Long>) = Unit
        override suspend fun clearCart() = Unit
        override suspend fun confirmCart(confirmation: ReservationConfirmation): ReservationBatchResult = ReservationBatchResult(emptyList())
        override suspend fun reserveNow(target: ReservationTarget, confirmation: ReservationConfirmation): ReservationBatchResult =
            ReservationBatchResult(emptyList())
        override suspend fun reserveNow(targets: List<ReservationTarget>, confirmation: ReservationConfirmation): ReservationBatchResult =
            ReservationBatchResult(emptyList())
    }

    /** 一斉直接予約のエラーメッセージ表示用。reserveNow(List)は必ず失敗する。 */
    private class FailingReserveNowCartRepository : ReservationCartRepository {
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
            error("予約失敗(テスト用)")
    }

    private class FakeCalendarRepository(
        override val libraries: List<Library> = listOf(Library("A", "中央図書館"), Library("B", "北口図書館")),
    ) : CalendarRepository {
        override fun closedDays(libraryCode: String): Flow<List<ClosedDay>> = flowOf(emptyList())
        override suspend fun refreshClosedDays(libraryCode: String) = Unit
    }
}
