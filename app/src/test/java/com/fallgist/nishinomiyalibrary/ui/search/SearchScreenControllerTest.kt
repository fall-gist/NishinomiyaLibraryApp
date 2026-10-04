package com.fallgist.nishinomiyalibrary.ui.search

import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.domain.model.MaterialKind
import com.fallgist.nishinomiyalibrary.domain.model.PublishedRange
import com.fallgist.nishinomiyalibrary.domain.model.SearchQuery
import com.fallgist.nishinomiyalibrary.domain.model.SearchQueryProblem
import com.fallgist.nishinomiyalibrary.domain.model.SearchSort
import com.fallgist.nishinomiyalibrary.domain.model.SearchSortKey
import com.fallgist.nishinomiyalibrary.domain.model.SortDirection
import com.fallgist.nishinomiyalibrary.domain.model.StockFilter
import com.fallgist.nishinomiyalibrary.domain.model.ClosedDay
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReadingInfo
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartAddSummary
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartItem
import com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationConfirmation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
import com.fallgist.nishinomiyalibrary.domain.model.SearchHit
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import com.fallgist.nishinomiyalibrary.domain.repository.CalendarRepository
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReadingRecordRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCartRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SearchRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SearchScreenController]の一斉カート追加(機能D、`docs/design/bulk-selection.md` §7・§8.2)のテスト。
 * 既存の検索・オートコンプリート挙動はここでは扱わない(専用テストが無かった機能を、Dの範囲だけ検証する)。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchScreenControllerTest {
    private val father = Member(1, "父", "#111111", "card-1", 0)

    private fun controller(
        searchRepository: SearchRepository,
        cartRepository: ReservationCartRepository,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        familyRepository: FamilyRepository = FakeFamilyRepository(listOf(father)),
        warnBeforeClearingSelection: Flow<Boolean> = flowOf(true),
        disableWarnBeforeClearingSelection: suspend () -> Unit = {},
        calendarRepository: CalendarRepository = FakeCalendarRepository(),
        defaultPickupLibraryCode: Flow<String> = flowOf("A"),
        now: () -> Long = { 1_000L },
    ) = SearchScreenController(
        searchRepository = searchRepository,
        readingRecordRepository = FakeReadingRecordRepository(),
        familyRepository = familyRepository,
        cartRepository = cartRepository,
        dispatcher = dispatcher,
        autocompleteDebounceMillis = 0L,
        warnBeforeClearingSelection = warnBeforeClearingSelection,
        disableWarnBeforeClearingSelection = disableWarnBeforeClearingSelection,
        calendarRepository = calendarRepository,
        defaultPickupLibraryCode = defaultPickupLibraryCode,
        now = now,
    )

    @Test
    fun `選択モード中のtoggleCartSelectionでtilcodが追加削除される`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val controller = controller(searchRepository, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
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
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val controller = controller(searchRepository, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()

        controller.toggleCartSelection("100")

        assertFalse("100" in controller.state.value.selectedCartTilcods)
        assertFalse(controller.state.value.selectionMode)
        controller.close()
    }

    @Test
    fun `長押しで選択モードに入りその行が選択される`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val controller = controller(searchRepository, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()

        controller.enterSelectionMode("100")

        assertTrue(controller.state.value.selectionMode)
        assertTrue("100" in controller.state.value.selectedCartTilcods)
        controller.close()
    }

    @Test
    fun `一覧に無いtilcodでは選択モードに入らない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val controller = controller(searchRepository, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()

        controller.enterSelectionMode("999")

        assertFalse(controller.state.value.selectionMode)
        assertTrue(controller.state.value.selectedCartTilcods.isEmpty())
        controller.close()
    }

    @Test
    fun `資料番号が空の行では選択モードに入らない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("", "資料A", "著者A", "図書")))
        val controller = controller(searchRepository, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()

        controller.enterSelectionMode("")

        assertFalse(controller.state.value.selectionMode)
        controller.close()
    }

    @Test
    fun `exitSelectionModeで選択が空になり選択モードから抜ける`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val controller = controller(searchRepository, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
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
        val searchRepository = FakeSearchRepository(
            hits = listOf(SearchHit("100", "資料A", "著者A", "図書"), SearchHit("101", "資料B", "著者B", "図書")),
        )
        val cartRepository = FakeCartRepository()
        val controller = controller(searchRepository, cartRepository, dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkCartAddition(controllerCartAdditionCandidates(controller))
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
    fun `候補0件では確認を開始しない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(FakeSearchRepository(), FakeCartRepository(), dispatcher)
        advanceUntilIdle()

        controller.requestBulkCartAddition(emptyList())

        assertNull(controller.state.value.bulkCartAdditionConfirmation)
        controller.close()
    }

    @Test
    fun `メンバー未選択ではconfirmBulkCartAdditionを呼んでも追加されない(design §7,2)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cartRepository = FakeCartRepository()
        val controller = controller(FakeSearchRepository(), cartRepository, dispatcher)
        advanceUntilIdle()

        controller.requestBulkCartAddition(listOf(BulkCartAdditionCandidateFixture.of("100", "資料A")))
        controller.confirmBulkCartAddition()
        advanceUntilIdle()

        assertEquals(0, cartRepository.calls)
        assertTrue(controller.state.value.bulkCartAdditionConfirmation != null)
        controller.close()
    }

    @Test
    fun `メンバー選択後の確定で対象memberIdを渡し選択と確認状態をクリアする`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val cartRepository = FakeCartRepository(summary = ReservationCartAddSummary(added = 1, skipped = 0))
        val controller = controller(searchRepository, cartRepository, dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        // Screen側の組み立て(SearchContentBuilder.cartAdditionCandidates)と同じ経路を使う。
        val candidates = controllerCartAdditionCandidates(controller)
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
    fun `一斉カート追加が0件追加0件スキップでも完了として選択モードから抜ける`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val cartRepository = FakeCartRepository(summary = ReservationCartAddSummary(added = 0, skipped = 1))
        val controller = controller(searchRepository, cartRepository, dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkCartAddition(controllerCartAdditionCandidates(controller))
        controller.selectBulkCartAdditionMember(father.id)
        controller.confirmBulkCartAddition()
        advanceUntilIdle()

        assertFalse(controller.state.value.selectionMode)
        controller.close()
    }

    @Test
    fun `一斉カート追加が通信例外で終わったときは選択状態と選択モードに触れない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val cartRepository = FailingCartRepository()
        val controller = controller(searchRepository, cartRepository, dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkCartAddition(controllerCartAdditionCandidates(controller))
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
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val cartRepository = FailingReserveNowCartRepository()
        val controller = controller(searchRepository, cartRepository, dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkDirectReservation(controllerCartAdditionCandidates(controller))
        controller.selectBulkDirectReservationMember(father.id)
        controller.confirmBulkDirectReservation()
        advanceUntilIdle()

        assertEquals(setOf("100"), controller.state.value.selectedCartTilcods)
        assertTrue(controller.state.value.selectionMode)
        controller.close()
    }

    @Test
    fun `確認待ちの間に一覧から消えたキーは実行時に無視される(design §4,3)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(
            hits = listOf(SearchHit("100", "資料A", "著者A", "図書"), SearchHit("101", "資料B", "著者B", "図書")),
        )
        val cartRepository = FakeCartRepository(summary = ReservationCartAddSummary(added = 1, skipped = 0))
        // このテストの主眼は§4.3(確認待ち中に一覧から消えたキーの無視)であり、§6.2の選択解除警告とは
        // 無関係。警告が割り込むと下の再検索が保留されてしまうため、ここでは警告設定をオフにする。
        val controller = controller(searchRepository, cartRepository, dispatcher, warnBeforeClearingSelection = flowOf(false))
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.toggleCartSelection("101")
        val candidates = controllerCartAdditionCandidates(controller)
        controller.requestBulkCartAddition(candidates)
        controller.selectBulkCartAdditionMember(father.id)

        // 確認待ちの間に一覧が変わり、101が無くなったことを模す(再検索で結果が入れ替わる)。
        searchRepository.hits = listOf(SearchHit("100", "資料A", "著者A", "図書"))
        controller.search("キーワード2")
        advanceUntilIdle()

        controller.confirmBulkCartAddition()
        advanceUntilIdle()

        assertEquals(1, cartRepository.receivedTargets.size)
        assertEquals("100", cartRepository.receivedTargets.single().tilcod)
        controller.close()
    }

    /**
     * 再検索前の選択解除警告(`docs/design/bulk-selection-followup.md` §6.2)のテスト。
     * 対象は再検索のみ(loadMore・自動巡回は対象外だが、SearchScreenControllerにloadMore以外の
     * 巡回操作は無いため、ここではsearch()だけを検証する)。
     */
    @Test
    fun `選択0件では確認を出さず即座に検索する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val controller = controller(searchRepository, FakeCartRepository(), dispatcher)
        advanceUntilIdle()

        controller.search("キーワード")
        advanceUntilIdle()

        assertNull(controller.state.value.pendingSearchQuery)
        assertEquals(SearchQuery.keywordOnly("キーワード"), controller.state.value.executedQuery)
        controller.close()
    }

    @Test
    fun `設定オフでは選択が残っていても確認を出さず即座に検索する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val controller = controller(
            searchRepository,
            FakeCartRepository(),
            dispatcher,
            warnBeforeClearingSelection = flowOf(false),
        )
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        controller.search("キーワード2")
        advanceUntilIdle()

        assertNull(controller.state.value.pendingSearchQuery)
        assertEquals(SearchQuery.keywordOnly("キーワード2"), controller.state.value.executedQuery)
        controller.close()
    }

    @Test
    fun `選択が残っていて設定オンなら確認を要求し検索を保留する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val controller = controller(searchRepository, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        controller.search("キーワード2")
        advanceUntilIdle()

        assertEquals(SearchQuery.keywordOnly("キーワード2"), controller.state.value.pendingSearchQuery)
        // 保留中は実行していない(直近の検索結果のまま)。
        assertEquals(SearchQuery.keywordOnly("キーワード"), controller.state.value.executedQuery)
        assertTrue("100" in controller.state.value.selectedCartTilcods)
        controller.close()
    }

    @Test
    fun `続けるで選択を解除し保留していた検索を実行する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val controller = controller(searchRepository, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.search("キーワード2")
        advanceUntilIdle()

        controller.confirmPendingSearch()
        advanceUntilIdle()

        assertNull(controller.state.value.pendingSearchQuery)
        assertTrue(controller.state.value.selectedCartTilcods.isEmpty())
        assertEquals(SearchQuery.keywordOnly("キーワード2"), controller.state.value.executedQuery)
        controller.close()
    }

    @Test
    fun `戻るで選択を残し検索を実行しない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val controller = controller(searchRepository, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.search("キーワード2")
        advanceUntilIdle()

        controller.dismissPendingSearch()
        advanceUntilIdle()

        assertNull(controller.state.value.pendingSearchQuery)
        assertTrue("100" in controller.state.value.selectedCartTilcods)
        assertEquals(SearchQuery.keywordOnly("キーワード"), controller.state.value.executedQuery)
        controller.close()
    }

    @Test
    fun `今後は表示しないで設定を無効化しつつ検索を実行する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        var disableCalls = 0
        val controller = controller(
            searchRepository,
            FakeCartRepository(),
            dispatcher,
            disableWarnBeforeClearingSelection = { disableCalls++ },
        )
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.search("キーワード2")
        advanceUntilIdle()

        controller.confirmPendingSearchAndDisableWarning()
        advanceUntilIdle()

        assertEquals(1, disableCalls)
        assertNull(controller.state.value.pendingSearchQuery)
        assertTrue(controller.state.value.selectedCartTilcods.isEmpty())
        assertFalse(controller.state.value.warnBeforeClearingSelection)
        assertEquals(SearchQuery.keywordOnly("キーワード2"), controller.state.value.executedQuery)
        controller.close()
    }

    // ------------------------------------------------------------------
    // 一斉直接予約(`docs/design/bulk-selection-followup.md` §5、機能F)。カートを経由しない(§5.2)。
    // ------------------------------------------------------------------

    @Test
    fun `一斉直接予約の確認要求時に受取館の初期値が既定館になる`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val controller = controller(searchRepository, FakeCartRepository(), dispatcher, defaultPickupLibraryCode = flowOf("B"))
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        controller.requestBulkDirectReservation(controllerCartAdditionCandidates(controller))

        assertEquals("B", controller.state.value.bulkDirectReservationConfirmation?.pickupLibraryCode)
        controller.close()
    }

    @Test
    fun `メンバー未選択ではconfirmBulkDirectReservationを呼んでも予約されない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val cartRepository = FakeCartRepository()
        val controller = controller(searchRepository, cartRepository, dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        controller.requestBulkDirectReservation(controllerCartAdditionCandidates(controller))
        controller.confirmBulkDirectReservation()
        advanceUntilIdle()

        assertEquals(0, cartRepository.reserveNowListCalls)
        assertTrue(controller.state.value.bulkDirectReservationConfirmation != null)
        controller.close()
    }

    @Test
    fun `受取館未選択ではconfirmBulkDirectReservationを呼んでも予約されない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val cartRepository = FakeCartRepository()
        // 既定館の一覧を空にし、初期選択も空文字になる状態を作る。
        val controller = controller(
            searchRepository, cartRepository, dispatcher,
            calendarRepository = FakeCalendarRepository(libraries = emptyList()),
            defaultPickupLibraryCode = flowOf(""),
        )
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        controller.requestBulkDirectReservation(controllerCartAdditionCandidates(controller))
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
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val cartRepository = FakeCartRepository()
        val controller = controller(searchRepository, cartRepository, dispatcher, defaultPickupLibraryCode = flowOf("A"))
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        controller.requestBulkDirectReservation(controllerCartAdditionCandidates(controller))
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
        val searchRepository = FakeSearchRepository(
            hits = listOf(SearchHit("100", "資料A", "著者A", "図書"), SearchHit("101", "資料B", "著者B", "図書")),
        )
        val cartRepository = FakeCartRepository()
        val controller = controller(searchRepository, cartRepository, dispatcher, warnBeforeClearingSelection = flowOf(false))
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.toggleCartSelection("101")
        controller.requestBulkDirectReservation(controllerCartAdditionCandidates(controller))
        controller.selectBulkDirectReservationMember(father.id)

        // 確認待ちの間に一覧が変わり、101が無くなったことを模す(再検索で結果が入れ替わる)。
        searchRepository.hits = listOf(SearchHit("100", "資料A", "著者A", "図書"))
        controller.search("キーワード2")
        advanceUntilIdle()

        controller.confirmBulkDirectReservation()
        advanceUntilIdle()

        assertEquals(1, cartRepository.receivedReserveNowTargets.size)
        assertEquals("100", cartRepository.receivedReserveNowTargets.single().tilcod)
        controller.close()
    }

    @Test
    fun `一斉直接予約とカート追加は互いに処理中の間実行できない(design追補§5,4)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val controller = controller(searchRepository, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        controller.requestBulkDirectReservation(controllerCartAdditionCandidates(controller))
        controller.selectBulkDirectReservationMember(father.id)
        controller.confirmBulkDirectReservation()
        // まだ処理中(scope.launchのbodyが進んでいない)の間はカート追加を要求できない。
        assertFalse(controller.state.value.canRequestBulkCartAddition)
        advanceUntilIdle()
        controller.close()
    }

    // ------------------------------------------------------------------
    // 検索結果と一時表示のリセット(`docs/design/search-result-reset.md`)。
    // ------------------------------------------------------------------

    @Test
    fun `カート追加の結果メッセージが出た後に新しい検索をすると結果メッセージがnullになる(design §3,1)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val cartRepository = FakeCartRepository(summary = ReservationCartAddSummary(added = 1, skipped = 0))
        val controller = controller(searchRepository, cartRepository, dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkCartAddition(controllerCartAdditionCandidates(controller))
        controller.selectBulkCartAdditionMember(father.id)
        controller.confirmBulkCartAddition()
        advanceUntilIdle()
        assertEquals("1件をカートへ追加しました", controller.state.value.bulkCartAdditionResultMessage)

        controller.search("キーワード2")
        advanceUntilIdle()

        assertNull(controller.state.value.bulkCartAdditionResultMessage)
        controller.close()
    }

    @Test
    fun `カート追加のエラーメッセージも新しい検索でnullになる(design §3,1)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val cartRepository = FailingCartRepository()
        // confirmBulkCartAdditionが失敗しても選択は解除されない(異常系)。次のsearch()が
        // 警告ダイアログで保留されないよう、ここでは警告設定をオフにする(このテストの主眼は
        // §3.1のエラーメッセージのリセットであり、§6.2の警告ダイアログとは無関係)。
        val controller = controller(searchRepository, cartRepository, dispatcher, warnBeforeClearingSelection = flowOf(false))
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkCartAddition(controllerCartAdditionCandidates(controller))
        controller.selectBulkCartAdditionMember(father.id)
        controller.confirmBulkCartAddition()
        advanceUntilIdle()
        assertEquals("カートへ追加できませんでした。もう一度お試しください。", controller.state.value.bulkCartAdditionErrorMessage)

        controller.search("キーワード2")
        advanceUntilIdle()

        assertNull(controller.state.value.bulkCartAdditionErrorMessage)
        controller.close()
    }

    @Test
    fun `警告設定オフで選択が残ったまま新しい検索をすると選択が空になる(design §1付随)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val controller = controller(
            searchRepository,
            FakeCartRepository(),
            dispatcher,
            warnBeforeClearingSelection = flowOf(false),
        )
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        assertTrue("100" in controller.state.value.selectedCartTilcods)

        controller.search("キーワード2")
        advanceUntilIdle()

        assertTrue(controller.state.value.selectedCartTilcods.isEmpty())
        // 新しいキーワードでの検索は選択モードから抜ける(design §3.8「新しい検索」)。
        assertFalse(controller.state.value.selectionMode)
        controller.close()
    }

    @Test
    fun `新しい検索ではbulkDirectReservationResultsとErrorMessageは残る(design §3,1)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val cartRepository = FakeCartRepository()
        val controller = controller(searchRepository, cartRepository, dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkDirectReservation(controllerCartAdditionCandidates(controller))
        controller.selectBulkDirectReservationMember(father.id)
        controller.confirmBulkDirectReservation()
        advanceUntilIdle()
        assertEquals(1, controller.state.value.bulkDirectReservationResults.size)

        controller.search("キーワード2")
        advanceUntilIdle()

        assertEquals(1, controller.state.value.bulkDirectReservationResults.size)
        controller.close()
    }

    @Test
    fun `resetOnLeaveで検索結果・選択・executedQuery・カート追加メッセージが初期状態になる(design §3,1)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val cartRepository = FakeCartRepository(summary = ReservationCartAddSummary(added = 1, skipped = 0))
        val controller = controller(searchRepository, cartRepository, dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkCartAddition(controllerCartAdditionCandidates(controller))
        controller.selectBulkCartAdditionMember(father.id)
        controller.confirmBulkCartAddition()
        advanceUntilIdle()

        controller.resetOnLeave()

        val state = controller.state.value
        assertNull(state.executedQuery)
        assertEquals(0, state.totalCount)
        assertTrue(state.results.isEmpty())
        assertFalse(state.hasNext)
        assertTrue(state.selectedCartTilcods.isEmpty())
        assertNull(state.bulkCartAdditionResultMessage)
        assertNull(state.bulkCartAdditionErrorMessage)
        controller.close()
    }

    @Test
    fun `resetOnLeaveで選択モードから抜ける(design §3,8-4)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val controller = controller(searchRepository, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        assertTrue(controller.state.value.selectionMode)

        controller.resetOnLeave()

        assertFalse(controller.state.value.selectionMode)
        controller.close()
    }

    @Test
    fun `resetOnLeaveでbulkDirectReservationResultsとErrorMessageは残る(design §3,1)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val cartRepository = FakeCartRepository()
        val controller = controller(searchRepository, cartRepository, dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkDirectReservation(controllerCartAdditionCandidates(controller))
        controller.selectBulkDirectReservationMember(father.id)
        controller.confirmBulkDirectReservation()
        advanceUntilIdle()
        assertEquals(1, controller.state.value.bulkDirectReservationResults.size)

        controller.resetOnLeave()

        assertEquals(1, controller.state.value.bulkDirectReservationResults.size)
        controller.close()
    }

    @Test
    fun `新しい検索ではbulkDirectReservationErrorMessageも残る(design §3,1)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val cartRepository = FailingReserveNowCartRepository()
        // confirmBulkDirectReservationが失敗しても選択は解除されない(異常系)。次のsearch()が
        // 警告ダイアログで保留されないよう、警告設定をオフにする(前回のレビュー指摘と同じ理由)。
        val controller = controller(searchRepository, cartRepository, dispatcher, warnBeforeClearingSelection = flowOf(false))
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkDirectReservation(controllerCartAdditionCandidates(controller))
        controller.selectBulkDirectReservationMember(father.id)
        controller.confirmBulkDirectReservation()
        advanceUntilIdle()
        assertEquals("予約できませんでした。もう一度お試しください。", controller.state.value.bulkDirectReservationErrorMessage)

        controller.search("キーワード2")
        advanceUntilIdle()

        assertEquals("予約できませんでした。もう一度お試しください。", controller.state.value.bulkDirectReservationErrorMessage)
        controller.close()
    }

    @Test
    fun `resetOnLeaveでbulkDirectReservationErrorMessageも残る(design §3,1)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val cartRepository = FailingReserveNowCartRepository()
        val controller = controller(searchRepository, cartRepository, dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkDirectReservation(controllerCartAdditionCandidates(controller))
        controller.selectBulkDirectReservationMember(father.id)
        controller.confirmBulkDirectReservation()
        advanceUntilIdle()
        assertEquals("予約できませんでした。もう一度お試しください。", controller.state.value.bulkDirectReservationErrorMessage)

        controller.resetOnLeave()

        assertEquals("予約できませんでした。もう一度お試しください。", controller.state.value.bulkDirectReservationErrorMessage)
        controller.close()
    }

    @Test
    fun `resetOnLeaveでmembersとwarnBeforeClearingSelectionは保たれる(design §3,1)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(FakeSearchRepository(), FakeCartRepository(), dispatcher, warnBeforeClearingSelection = flowOf(false))
        advanceUntilIdle()

        controller.resetOnLeave()

        assertEquals(listOf(father), controller.state.value.members)
        assertFalse(controller.state.value.warnBeforeClearingSelection)
        controller.close()
    }

    @Test
    fun `検索の応答待ちの間にresetOnLeaveすると応答が返ってもresultsとexecutedQueryは初期状態のまま(design §3,2)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val gate = CompletableDeferred<Unit>()
        searchRepository.gate = gate
        val controller = controller(searchRepository, FakeCartRepository(), dispatcher)
        advanceUntilIdle()

        controller.search("キーワード")
        // searchJobをgate.await()まで進める(runCurrent()はdelay等の仮想時間を進めないため、
        // 実際にawaitへ到達したかをsearching==trueで確かめる)。ここでresetOnLeave()せずに
        // advanceUntilIdle()すると、gateが未completeのままデッドロックする。
        runCurrent()
        assertTrue("gate.await()で止まっているはず", controller.state.value.searching)

        // 応答待ちの間にresetOnLeave()する(§3.2、戻ったときに古い結果が遅れて現れるのを防ぐ)。
        controller.resetOnLeave()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(controller.state.value.results.isEmpty())
        assertNull(controller.state.value.executedQuery)
        controller.close()
    }

    @Test
    fun `検索Aの応答待ち中に検索Bを実行しBが先に完了した後でAの応答が返ってもresultsはBのまま(design §3,2)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository()
        val gateA = CompletableDeferred<Unit>()
        searchRepository.hitsByKeyword = mapOf(
            "A" to listOf(SearchHit("100", "資料A", "著者A", "図書")),
            "B" to listOf(SearchHit("200", "資料B", "著者B", "図書")),
        )
        searchRepository.gatesByKeyword = mapOf("A" to gateA)
        val controller = controller(searchRepository, FakeCartRepository(), dispatcher)
        advanceUntilIdle()

        controller.search("A")
        runCurrent() // 検索AがgateA.await()で止まる
        controller.search("B") // executeSearch内でsearchJob(=検索A)をcancel()し、検索Bを開始する
        advanceUntilIdle() // 検索Bは即座に完了する(gateAはBには適用されない)

        assertEquals(SearchQuery.keywordOnly("B"), controller.state.value.executedQuery)
        assertEquals(listOf("200"), controller.state.value.results.map { it.tilcod })

        // 検索Aの応答を今になって返す。StandardTestDispatcherの下ではcancel()だけでも
        // このテストは通り得るが(キャンセルされた継続はsuccess本体を実行しない)、
        // 世代番号(searchGeneration)による保護も同時に効いている
        // (`docs/design/search-result-reset.md` §3.2)。マルチスレッドのDispatchers.Defaultでは
        // cancel()が協調的にしか止まらないため、世代番号がこの窓の主な防御になる。
        gateA.complete(Unit)
        advanceUntilIdle()

        assertEquals(SearchQuery.keywordOnly("B"), controller.state.value.executedQuery)
        assertEquals(listOf("200"), controller.state.value.results.map { it.tilcod })
        controller.close()
    }

    @Test
    fun `confirmBulkCartAdditionの直後にresetOnLeaveしても確定時点の候補がカートへ追加される(design §3,2)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val cartRepository = FakeCartRepository(summary = ReservationCartAddSummary(added = 1, skipped = 0))
        val controller = controller(searchRepository, cartRepository, dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkCartAddition(controllerCartAdditionCandidates(controller))
        controller.selectBulkCartAdditionMember(father.id)

        controller.confirmBulkCartAddition()
        // scope.launchの本体が進む前に画面を離れる(§3.2の競合の固定)。
        controller.resetOnLeave()
        advanceUntilIdle()

        assertEquals(1, cartRepository.calls)
        assertEquals("100", cartRepository.receivedTargets.single().tilcod)
        controller.close()
    }

    @Test
    fun `confirmBulkDirectReservationの直後にresetOnLeaveしても予約が実行され完了後に結果が設定される(design §3,2)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(hits = listOf(SearchHit("100", "資料A", "著者A", "図書")))
        val cartRepository = FakeCartRepository()
        val controller = controller(searchRepository, cartRepository, dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.enterSelectionMode("100")
        controller.requestBulkDirectReservation(controllerCartAdditionCandidates(controller))
        controller.selectBulkDirectReservationMember(father.id)

        controller.confirmBulkDirectReservation()
        controller.resetOnLeave()
        // 処理中フラグは完了まで true のまま(resetOnLeave()は処理中フラグを変更しない)。
        assertTrue(controller.state.value.bulkDirectReservationProcessing)
        advanceUntilIdle()

        assertEquals(1, cartRepository.reserveNowListCalls)
        assertEquals("100", cartRepository.receivedReserveNowTargets.single().tilcod)
        assertEquals(1, controller.state.value.bulkDirectReservationResults.size)
        assertFalse(controller.state.value.bulkDirectReservationProcessing)
        controller.close()
    }

    // ------------------------------------------------------------------
    // 並べ替え・絞り込み・詳細検索(`docs/design/search-sort-filter.md` §3.4・§5、段階2)
    // ------------------------------------------------------------------

    private fun hit(tilcod: String) = SearchHit(tilcod, "資料$tilcod", "著者", "図書")

    @Test
    fun `キーワード検索は並べ替え未指定のキーワードだけの条件で1ページ目を取る`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeSearchRepository(hits = listOf(hit("100")))
        val controller = controller(repo, FakeCartRepository(), dispatcher)
        advanceUntilIdle()

        controller.search("  ドラゴンボール ")
        advanceUntilIdle()

        assertEquals(listOf(SearchQuery.keywordOnly("ドラゴンボール") to 1), repo.calls)
        assertNull(controller.state.value.executedQuery?.sort)
        controller.close()
    }

    @Test
    fun `並べ替えボタンは昇順 降順 別項目の昇順の順に切り替わり毎回1ページ目から検索する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeSearchRepository(hits = listOf(hit("100")))
        val controller = controller(repo, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("本")
        advanceUntilIdle()

        controller.toggleSort(SearchSortKey.PUBLISHED)
        advanceUntilIdle()
        controller.toggleSort(SearchSortKey.PUBLISHED)
        advanceUntilIdle()
        controller.toggleSort(SearchSortKey.AUTHOR)
        advanceUntilIdle()

        val base = SearchQuery.keywordOnly("本")
        assertEquals(
            listOf(
                base to 1,
                base.copy(sort = SearchSort(SearchSortKey.PUBLISHED, SortDirection.ASCENDING)) to 1,
                base.copy(sort = SearchSort(SearchSortKey.PUBLISHED, SortDirection.DESCENDING)) to 1,
                base.copy(sort = SearchSort(SearchSortKey.AUTHOR, SortDirection.ASCENDING)) to 1,
            ),
            repo.calls,
        )
        val sort = controller.state.value.executedQuery?.sort
        assertEquals("著者 ▲", SearchContentBuilder.sortButtonLabel(SearchSortKey.AUTHOR, sort))
        assertEquals("出版年月", SearchContentBuilder.sortButtonLabel(SearchSortKey.PUBLISHED, sort))
        controller.close()
    }

    @Test
    fun `未検索では並べ替えても何もしない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeSearchRepository()
        val controller = controller(repo, FakeCartRepository(), dispatcher)
        advanceUntilIdle()

        controller.toggleSort(SearchSortKey.TITLE)
        advanceUntilIdle()

        assertTrue(repo.calls.isEmpty())
        controller.close()
    }

    @Test
    fun `追加読み込みは並べ替えを含む同じ条件で次のページを取り 並べ替えると1ページ目から読み直す`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeSearchRepository(hits = listOf(hit("100")))
        repo.hasNext = true
        val controller = controller(repo, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("本")
        advanceUntilIdle()
        controller.toggleSort(SearchSortKey.TITLE)
        advanceUntilIdle()

        controller.loadMore()
        advanceUntilIdle()
        val ascending = SearchQuery.keywordOnly("本").copy(sort = SearchSort(SearchSortKey.TITLE, SortDirection.ASCENDING))
        assertEquals(ascending to 2, repo.calls.last())
        assertEquals(2, controller.state.value.results.size)

        controller.toggleSort(SearchSortKey.TITLE)
        advanceUntilIdle()
        val descending = ascending.copy(sort = SearchSort(SearchSortKey.TITLE, SortDirection.DESCENDING))
        assertEquals(descending to 1, repo.calls.last())
        // 読み直しで結果は1ページ分に戻る。続きはまた2ページ目から。
        assertEquals(1, controller.state.value.results.size)
        controller.loadMore()
        advanceUntilIdle()
        assertEquals(descending to 2, repo.calls.last())
        controller.close()
    }

    @Test
    fun `絞り込みは今の条件と並べ替えを引き継いだ条件で検索し直す`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeSearchRepository(hits = listOf(hit("100")))
        val controller = controller(repo, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("ドラゴンボール")
        advanceUntilIdle()
        controller.toggleSort(SearchSortKey.PUBLISHED)
        advanceUntilIdle()

        // 画面は「絞り込み」で今の条件(executedQuery)を入力欄の初期値にする。
        val initial = controller.state.value.executedQuery!!
        assertEquals("ドラゴンボール", initial.keyword)
        val refined = initial.copy(materialKinds = setOf(MaterialKind.CHILDREN), published = PublishedRange(fromYear = 2010))
        controller.searchDetailed(refined)
        advanceUntilIdle()

        assertEquals(refined to 1, repo.calls.last())
        assertEquals(SearchSort(SearchSortKey.PUBLISHED, SortDirection.ASCENDING), repo.calls.last().first.sort)
        assertEquals(
            "キーワード: ドラゴンボール／書誌種別: 児童／出版年月: 2010年〜",
            SearchContentBuilder.conditionSummary(controller.state.value.executedQuery!!),
        )
        controller.close()
    }

    @Test
    fun `詳細検索は条件が無い 年月が誤り 月だけ 逆順のときは検索しない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeSearchRepository(hits = listOf(hit("100")))
        val controller = controller(repo, FakeCartRepository(), dispatcher)
        advanceUntilIdle()

        controller.searchDetailed(SearchQuery())
        controller.searchDetailed(SearchQuery(stock = StockFilter.LENDABLE_ONLY))
        controller.searchDetailed(SearchQuery(title = "本", published = PublishedRange(fromYear = 99)))
        controller.searchDetailed(SearchQuery(title = "本", published = PublishedRange(fromYear = 2010, fromMonth = 13)))
        controller.searchDetailed(SearchQuery(title = "本", published = PublishedRange(toMonth = 3)))
        controller.searchDetailed(SearchQuery(title = "本", published = PublishedRange(fromYear = 2012, toYear = 2010)))
        advanceUntilIdle()

        assertTrue(repo.calls.isEmpty())
        assertNull(controller.state.value.executedQuery)

        controller.searchDetailed(SearchQuery(title = "本", published = PublishedRange(fromYear = 2010, toYear = 2012)))
        advanceUntilIdle()
        assertEquals(1, repo.calls.size)
        controller.close()
    }

    @Test
    fun `入力欄の文言は検証の問題ごとに日本語で返り 数字でない年月は誤りになる`() {
        assertEquals(
            "語・分類・出版年月・書誌種別のどれかを指定してください",
            SearchContentBuilder.problemMessage(SearchQueryProblem.NO_CONDITION),
        )
        assertNull(SearchContentBuilder.parseNumberInput("  "))
        assertEquals(2010, SearchContentBuilder.parseNumberInput(" 2010 "))
        val problems = SearchQuery(
            title = "本",
            published = PublishedRange(fromYear = SearchContentBuilder.parseNumberInput("20ab")),
        ).validate()
        assertEquals(listOf(SearchQueryProblem.YEAR_INVALID), problems)
    }

    @Test
    fun `並べ替えを続けて押しても古い応答は混ざらず最後の指定の結果になる`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeSearchRepository()
        repo.hitsByKeyword = mapOf("本" to listOf(hit("200")))
        val ascending = SearchSort(SearchSortKey.TITLE, SortDirection.ASCENDING)
        val gateAscending = CompletableDeferred<Unit>()
        repo.gateFor = { query, _ -> if (query.sort == ascending) gateAscending else null }
        val controller = controller(repo, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("本")
        advanceUntilIdle()

        controller.toggleSort(SearchSortKey.TITLE) // 昇順(応答待ちで止まる)
        runCurrent()
        controller.toggleSort(SearchSortKey.TITLE) // 通信中の押下: 最新の指定(昇順)の逆 = 降順
        advanceUntilIdle()
        assertEquals(SortDirection.DESCENDING, controller.state.value.executedQuery?.sort?.direction)
        assertEquals(listOf("200"), controller.state.value.results.map { it.tilcod })

        repo.hitsByKeyword = mapOf("本" to listOf(hit("999")))
        gateAscending.complete(Unit)
        advanceUntilIdle()

        assertEquals(SortDirection.DESCENDING, controller.state.value.executedQuery?.sort?.direction)
        assertEquals(listOf("200"), controller.state.value.results.map { it.tilcod })
        assertFalse(controller.state.value.searching)
        controller.close()
    }

    @Test
    fun `追加読み込み中に並べ替えるとloadingMoreが残らず新しい結果が表示される`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeSearchRepository(hits = listOf(hit("100")))
        repo.hasNext = true
        val gatePage2 = CompletableDeferred<Unit>()
        repo.gateFor = { _, page -> if (page == 2) gatePage2 else null }
        val controller = controller(repo, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("本")
        advanceUntilIdle()
        controller.loadMore()
        runCurrent()
        assertTrue(controller.state.value.loadingMore)

        controller.toggleSort(SearchSortKey.CLASSIFICATION)
        advanceUntilIdle()
        gatePage2.complete(Unit)
        advanceUntilIdle()

        assertFalse(controller.state.value.loadingMore)
        assertEquals(1, controller.state.value.results.size)
        controller.close()
    }

    @Test
    fun `並べ替えは選択が残っていて設定オンなら確認を要求し 続けるで選択を解除して実行する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeSearchRepository(hits = listOf(hit("100")))
        val controller = controller(repo, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("本")
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        controller.toggleSort(SearchSortKey.TITLE)
        advanceUntilIdle()
        val sorted = SearchQuery.keywordOnly("本").copy(sort = SearchSort(SearchSortKey.TITLE, SortDirection.ASCENDING))
        assertEquals(sorted, controller.state.value.pendingSearchQuery)
        assertEquals(1, repo.calls.size) // 保留中は通信しない
        assertTrue("100" in controller.state.value.selectedCartTilcods)

        controller.confirmPendingSearch()
        advanceUntilIdle()
        assertEquals(sorted to 1, repo.calls.last())
        assertTrue(controller.state.value.selectedCartTilcods.isEmpty())
        assertFalse(controller.state.value.selectionMode)
        controller.close()
    }

    @Test
    fun `並べ替えと絞り込みは設定オフでも選択と選択モードを解除して実行する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeSearchRepository(hits = listOf(hit("100")))
        val controller = controller(repo, FakeCartRepository(), dispatcher, warnBeforeClearingSelection = flowOf(false))
        advanceUntilIdle()
        controller.search("本")
        advanceUntilIdle()
        controller.enterSelectionMode("100")

        controller.toggleSort(SearchSortKey.PUBLISHER)
        advanceUntilIdle()
        assertTrue(controller.state.value.selectedCartTilcods.isEmpty())
        assertFalse(controller.state.value.selectionMode)

        controller.enterSelectionMode("100")
        controller.searchDetailed(controller.state.value.executedQuery!!.copy(author = "鳥山"))
        advanceUntilIdle()
        assertTrue(controller.state.value.selectedCartTilcods.isEmpty())
        assertFalse(controller.state.value.selectionMode)
        assertEquals("鳥山", repo.calls.last().first.author)
        controller.close()
    }

    @Test
    fun `並べ替えの失敗は利用者向けの文言で表示され 条件は残るので別の並べ替えでやり直せる`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeSearchRepository(hits = listOf(hit("100")))
        repo.failureFor = { query, _ ->
            if (query.sort?.key == SearchSortKey.TITLE) LibraryError.Parse("search_sort", "x") else null
        }
        val controller = controller(repo, FakeCartRepository(), dispatcher)
        advanceUntilIdle()
        controller.search("本")
        advanceUntilIdle()

        controller.toggleSort(SearchSortKey.TITLE)
        advanceUntilIdle()
        assertEquals("検索結果の並べ替えができませんでした。時間をおいてやり直してください", controller.state.value.errorMessage)
        assertFalse(controller.state.value.searching)
        assertEquals(SearchSortKey.TITLE, controller.state.value.executedQuery?.sort?.key)

        controller.toggleSort(SearchSortKey.AUTHOR)
        advanceUntilIdle()
        assertNull(controller.state.value.errorMessage)
        assertEquals(1, controller.state.value.results.size)

        repo.failureFor = { _, _ -> LibraryError.Parse("search_stock", "x") }
        controller.toggleSort(SearchSortKey.TITLE)
        advanceUntilIdle()
        assertEquals("在庫状況での絞り込みができませんでした。時間をおいてやり直してください", controller.state.value.errorMessage)
        controller.close()
    }

    @Test
    fun `結果の行に書誌種別 出版者 出版年月 分類が入る`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeSearchRepository(
            hits = listOf(SearchHit("100", "資料A", "著者A", "児童図書", "集英社", "2016/02", "726", true)),
        )
        val controller = controller(repo, FakeCartRepository(), dispatcher)
        advanceUntilIdle()

        controller.search("本")
        advanceUntilIdle()

        val row = controller.state.value.results.single()
        assertEquals("児童図書", row.materialType)
        assertEquals("集英社", row.publisher)
        assertEquals("2016/02", row.publishedYearMonth)
        assertEquals("726", row.classification)
        assertEquals(true, row.lendable)
        controller.close()
    }

    @Test
    fun `条件の短い表示は指定した項目だけを区切って出し 並べ替えの表示は選択中の項目にだけ向きが付く`() {
        val query = SearchQuery(
            title = "ドラゴン",
            author = "鳥山",
            materialKinds = setOf(MaterialKind.CHILDREN, MaterialKind.GENERAL),
            published = PublishedRange(fromYear = 2010, fromMonth = 4, toYear = 2012),
            stock = StockFilter.LENDABLE_ONLY,
        )
        assertEquals(
            "書名: ドラゴン／著者: 鳥山／書誌種別: 一般・児童／出版年月: 2010年4月〜2012年／在庫: 貸出可のみ",
            SearchContentBuilder.conditionSummary(query),
        )
        assertEquals(
            "出版年月: 〜2012年3月",
            SearchContentBuilder.conditionSummary(SearchQuery(published = PublishedRange(toYear = 2012, toMonth = 3))),
        )
        val sort = SearchSort(SearchSortKey.TITLE, SortDirection.DESCENDING)
        assertEquals("書名 ▼", SearchContentBuilder.sortButtonLabel(SearchSortKey.TITLE, sort))
        assertEquals("分類", SearchContentBuilder.sortButtonLabel(SearchSortKey.CLASSIFICATION, sort))
        assertEquals("書名", SearchContentBuilder.sortButtonLabel(SearchSortKey.TITLE, null))
    }

    private fun controllerCartAdditionCandidates(controller: SearchScreenController) =
        SearchContentBuilder.cartAdditionCandidates(controller.state.value.results, controller.state.value.selectedCartTilcods)

    private object BulkCartAdditionCandidateFixture {
        fun of(tilcod: String, title: String) =
            com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkCartAdditionCandidate(tilcod, title, null)
    }

    private class FakeFamilyRepository(private val members: List<Member>) : FamilyRepository {
        override fun members(): Flow<List<Member>> = flowOf(members)
        override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) = Unit
        override suspend fun updateMember(member: Member, newPassword: String?) = Unit
        override suspend fun removeMember(memberId: Long) = Unit
    }

    private class FakeReadingRecordRepository : ReadingRecordRepository {
        override fun records(memberId: Long?) = flowOf(emptyList<com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord>())
        override fun search(query: String, memberId: Long?) = flowOf(emptyList<com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord>())
        override fun hasRead(tilcod: String): Flow<List<ReadingInfo>> = flowOf(emptyList())
    }

    private class FakeSearchRepository(var hits: List<SearchHit> = emptyList()) : SearchRepository {
        /** 応答待ちのテスト用。設定すると、応答が返る前にこのDeferredの完了を待つ。 */
        var gate: CompletableDeferred<Unit>? = null
        /** 検索Aと検索Bを別々に止めるテスト用。キーワードごとに個別のgateを持てる。 */
        var gatesByKeyword: Map<String, CompletableDeferred<Unit>> = emptyMap()
        /** キーワードごとに異なる結果を返すテスト用。指定が無いキーワードは[hits]を返す。 */
        var hitsByKeyword: Map<String, List<SearchHit>> = emptyMap()
        /** 呼ばれた検索条件とページ(呼ばれた順)。 */
        val calls = mutableListOf<Pair<SearchQuery, Int>>()
        /** 次ページがあるように見せるテスト用。 */
        var hasNext: Boolean = false
        /** 条件・ページごとに応答を止めるテスト用。 */
        var gateFor: (SearchQuery, Int) -> CompletableDeferred<Unit>? = { _, _ -> null }
        /** 条件・ページごとに失敗させるテスト用。 */
        var failureFor: (SearchQuery, Int) -> Exception? = { _, _ -> null }
        override suspend fun search(query: SearchQuery, page: Int): SearchPage {
            calls += query to page
            val keyword = query.keyword
            failureFor(query, page)?.let { throw it }
            gate?.await()
            gatesByKeyword[keyword]?.await()
            gateFor(query, page)?.await()
            val effectiveHits = hitsByKeyword[keyword] ?: hits
            return SearchPage(hits = effectiveHits, totalCount = effectiveHits.size, hasNext = hasNext)
        }
        override suspend fun autocomplete(keyword: String): List<String> = emptyList()
        override suspend fun isLendable(tilcod: String): Boolean? = null
        override suspend fun bookDetail(tilcod: String): BookDetail = error("未使用")
        override suspend fun coverUrl(isbn: String): String? = null
    }

    private class FakeCartRepository(private val summary: ReservationCartAddSummary = ReservationCartAddSummary(0, 0)) : ReservationCartRepository {
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
                    com.fallgist.nishinomiyalibrary.domain.model.MemberReservationResult(
                        memberId,
                        grouped.map {
                            com.fallgist.nishinomiyalibrary.domain.model.ReservationItemResult(
                                it,
                                com.fallgist.nishinomiyalibrary.domain.model.ReservationOutcome.Success,
                            )
                        },
                    )
                },
            )
        }
    }

    /** カート追加のエラーメッセージ表示(design §3,1のテスト2)用。addToCart(List)は必ず失敗する。 */
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

    /** 一斉直接予約のエラーメッセージ表示(design §3,1のテスト)用。reserveNow(List)は必ず失敗する。 */
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
