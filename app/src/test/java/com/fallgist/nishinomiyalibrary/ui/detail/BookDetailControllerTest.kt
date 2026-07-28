package com.fallgist.nishinomiyalibrary.ui.detail

import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReadingInfo
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReadingRecordRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SearchRepository
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

/**
 * 経路3(予約中一覧から開いた書誌詳細)の取消ボタン出し分けを検証する。
 * 出し分けの実体は [BookDetailUiState.cancelTarget] の有無であり、
 * [BookDetailController.open] の呼び出し元(経路)がこれを渡すかどうかで決まる。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BookDetailControllerTest {
    private val member = Member(id = 1, name = "パパ", colorHex = "#111111", cardNumber = "card", sortOrder = 0)

    private fun controller(dispatcher: kotlinx.coroutines.CoroutineDispatcher): BookDetailController = BookDetailController(
        searchRepository = FakeSearchRepository(),
        readingRecordRepository = FakeReadingRecordRepository(),
        familyRepository = FakeFamilyRepository(),
        dispatcher = dispatcher,
    )

    @Test
    fun `検索結果など経路3以外からの2引数openでは取消対象を持たない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(dispatcher)

        controller.open("1000001", "資料A")
        advanceUntilIdle()

        assertNull(controller.state.value.cancelTarget)
    }

    @Test
    fun `予約中一覧(経路3)からの3引数openでは渡した取消対象を保持する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(dispatcher)
        val cancelTarget = BookDetailCancelTarget(memberId = member.id, cancelCode = "cancel-1")

        controller.open("1000001", "資料A", cancelTarget)
        advanceUntilIdle()

        assertEquals(cancelTarget, controller.state.value.cancelTarget)
    }

    @Test
    fun `予約中一覧でもcancelCode空の行由来のnullを渡せば取消対象は出ない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(dispatcher)

        // ReservationsContentBuilder.cancelTargetForDetail は cancelCode 空の行に対して null を返す。
        // ここではその呼び出し結果を模して null を明示的に渡す。
        controller.open("1000002", "資料B", cancelTarget = null)
        advanceUntilIdle()

        assertNull(controller.state.value.cancelTarget)
    }

    // 予約セクションの出し分け(ホーム画面のうけとれる予約・返す本、貸出中一覧、予約中一覧から開いた
    // 場合だけ非表示)を検証する。取消ボタンの出し分け(cancelTarget)とは独立したフラグである。

    @Test
    fun `2引数openでは予約セクションは非表示にならない`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(dispatcher)

        controller.open("1000001", "資料A")
        advanceUntilIdle()

        assertFalse(controller.state.value.reservationSectionHiddenAsAlreadyReservedOrOnLoan)
    }

    @Test
    fun `非表示を指定して開いた場合は予約セクションが非表示になる`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(dispatcher)

        controller.open(
            "1000001",
            "資料A",
            cancelTarget = null,
            hideReservationSectionAsAlreadyReservedOrOnLoan = true,
        )
        advanceUntilIdle()

        assertTrue(controller.state.value.reservationSectionHiddenAsAlreadyReservedOrOnLoan)
    }

    @Test
    fun `予約中一覧から開いた場合は予約セクションは非表示だが取消ボタンは表示される`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(dispatcher)
        val cancelTarget = BookDetailCancelTarget(memberId = member.id, cancelCode = "cancel-1")

        controller.open(
            "1000001",
            "資料A",
            cancelTarget,
            hideReservationSectionAsAlreadyReservedOrOnLoan = true,
        )
        advanceUntilIdle()

        // 2つのフラグが独立していることの確認: 予約セクションは非表示、取消対象は保持されたまま。
        assertTrue(controller.state.value.reservationSectionHiddenAsAlreadyReservedOrOnLoan)
        assertEquals(cancelTarget, controller.state.value.cancelTarget)
    }

    private class FakeSearchRepository : SearchRepository {
        override suspend fun search(keyword: String, page: Int) =
            throw UnsupportedOperationException("not used in this test")

        override suspend fun autocomplete(keyword: String): List<String> = emptyList()

        override suspend fun isLendable(tilcod: String): Boolean? = null

        override suspend fun bookDetail(tilcod: String): BookDetail = BookDetail(
            tilcod = tilcod,
            fields = linkedMapOf("書名" to "資料A"),
            isbn = null,
            holdings = emptyList(),
            holdingCount = 0,
            availableCount = 1,
            reservationCount = 0,
        )

        override suspend fun coverUrl(isbn: String): String? = null
    }

    private class FakeReadingRecordRepository : ReadingRecordRepository {
        override fun records(memberId: Long?) = flowOf(emptyList<com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord>())

        override fun search(query: String, memberId: Long?) = flowOf(emptyList<com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord>())

        override fun hasRead(tilcod: String): Flow<List<ReadingInfo>> = flowOf(emptyList())
    }

    private inner class FakeFamilyRepository : FamilyRepository {
        override fun members(): Flow<List<Member>> = flowOf(listOf(member))
        override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) = Unit
        override suspend fun updateMember(member: Member, newPassword: String?) = Unit
        override suspend fun removeMember(memberId: Long) = Unit
    }
}
