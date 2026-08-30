package com.fallgist.nishinomiyalibrary.ui.search

import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReadingInfo
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartAddSummary
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartItem
import com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationConfirmation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
import com.fallgist.nishinomiyalibrary.domain.model.SearchHit
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReadingRecordRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCartRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
    ) = SearchScreenController(
        searchRepository = searchRepository,
        readingRecordRepository = FakeReadingRecordRepository(),
        familyRepository = familyRepository,
        cartRepository = cartRepository,
        dispatcher = dispatcher,
        autocompleteDebounceMillis = 0L,
    )

    @Test
    fun `選択の切り替えでtilcodが追加削除される`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(FakeSearchRepository(), FakeCartRepository(), dispatcher)
        advanceUntilIdle()

        controller.toggleCartSelection("100")
        assertTrue("100" in controller.state.value.selectedCartTilcods)

        controller.toggleCartSelection("100")
        assertFalse("100" in controller.state.value.selectedCartTilcods)
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
        controller.toggleCartSelection("100")

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
        assertNull(controller.state.value.bulkCartAdditionConfirmation)
        assertEquals("1件をカートへ追加しました", controller.state.value.bulkCartAdditionResultMessage)
        controller.close()
    }

    @Test
    fun `確認待ちの間に一覧から消えたキーは実行時に無視される(design §4,3)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchRepository = FakeSearchRepository(
            hits = listOf(SearchHit("100", "資料A", "著者A", "図書"), SearchHit("101", "資料B", "著者B", "図書")),
        )
        val cartRepository = FakeCartRepository(summary = ReservationCartAddSummary(added = 1, skipped = 0))
        val controller = controller(searchRepository, cartRepository, dispatcher)
        advanceUntilIdle()
        controller.search("キーワード")
        advanceUntilIdle()
        controller.toggleCartSelection("100")
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
        override suspend fun search(keyword: String, page: Int): SearchPage = SearchPage(hits = hits, totalCount = hits.size, hasNext = false)
        override suspend fun autocomplete(keyword: String): List<String> = emptyList()
        override suspend fun isLendable(tilcod: String): Boolean? = null
        override suspend fun bookDetail(tilcod: String): BookDetail = error("未使用")
        override suspend fun coverUrl(isbn: String): String? = null
    }

    private class FakeCartRepository(private val summary: ReservationCartAddSummary = ReservationCartAddSummary(0, 0)) : ReservationCartRepository {
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
}
