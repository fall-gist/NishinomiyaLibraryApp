package com.fallgist.nishinomiyalibrary.ui.newarrivals

import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import com.fallgist.nishinomiyalibrary.domain.repository.NewArrivalRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewArrivalsScreenControllerTest {
    @Test
    fun `最終取得から12時間以内なら画面表示時に巡回しない`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val now = Instant.parse("2030-01-02T00:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val repository = FakeNewArrivalRepository(
            items = listOf(newArrival("1")),
            lastFetchedAtEpochMillis = now.minusMillis(Duration.ofHours(1).toMillis()).toEpochMilli(),
        )
        val controller = NewArrivalsScreenController(repository, clock, dispatcher)

        controller.onScreenLaunched()
        advanceUntilIdle()

        assertEquals(0, repository.refreshCallCount)
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
        val controller = NewArrivalsScreenController(repository, clock, dispatcher)

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
        val controller = NewArrivalsScreenController(repository, clock, dispatcher)

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
        val controller = NewArrivalsScreenController(repository, clock, dispatcher)

        controller.refresh()
        advanceUntilIdle()
        controller.refresh()
        advanceUntilIdle()

        assertEquals(2, repository.refreshCallCount)
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
}
