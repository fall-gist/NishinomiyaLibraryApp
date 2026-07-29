package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import com.fallgist.nishinomiyalibrary.domain.repository.NewArrivalRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewArrivalUpdateCoordinatorTest {
    private val now = Instant.parse("2030-01-02T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test fun `SCREEN_AUTOだけ12時間鮮度抑止し空cacheは取得する`() = runTest {
        val fresh = Repository(hasCache = true, lastFetched = now.minus(Duration.ofHours(1)).toEpochMilli())
        val auto = Automatic()
        val coordinator = NewArrivalUpdateCoordinator(fresh, auto, clock)
        assertEquals(NewArrivalUpdateResult.FreshnessSkipped, coordinator.refresh(NewArrivalUpdateTrigger.SCREEN_AUTO))
        assertEquals(0, fresh.refreshes)
        assertEquals(0, auto.calls)

        coordinator.refresh(NewArrivalUpdateTrigger.SCREEN_MANUAL)
        coordinator.refresh(NewArrivalUpdateTrigger.SCHEDULED)
        assertEquals(2, fresh.refreshes)
        assertEquals(2, auto.calls)

        val empty = Repository(hasCache = false, lastFetched = now.toEpochMilli())
        NewArrivalUpdateCoordinator(empty, Automatic(), clock).refresh(NewArrivalUpdateTrigger.SCREEN_AUTO)
        assertEquals(1, empty.refreshes)

        val exactlyTwelveHours = Repository(hasCache = true, lastFetched = now.minus(Duration.ofHours(12)).toEpochMilli())
        assertTrue(NewArrivalUpdateCoordinator(exactlyTwelveHours, Automatic(), clock).refresh(NewArrivalUpdateTrigger.SCREEN_AUTO) is NewArrivalUpdateResult.Completed)
        assertEquals(1, exactlyTwelveHours.refreshes)

        val unknownFreshness = Repository(hasCache = true, lastFetched = null)
        assertTrue(NewArrivalUpdateCoordinator(unknownFreshness, Automatic(), clock).refresh(NewArrivalUpdateTrigger.SCREEN_AUTO) is NewArrivalUpdateResult.Completed)
        assertEquals(1, unknownFreshness.refreshes)
    }

    @Test fun `refresh成功後だけRoom置換の次にautoを実行する`() = runTest {
        val events = mutableListOf<String>()
        val repository = Repository(events = events)
        val automatic = Automatic(events = events)
        val result = NewArrivalUpdateCoordinator(repository, automatic, clock).refresh(NewArrivalUpdateTrigger.SCHEDULED)
        assertTrue(result is NewArrivalUpdateResult.Completed)
        assertEquals(listOf("refresh", "auto"), events)

        val failed = Repository(failure = IllegalStateException("network"))
        val unusedAuto = Automatic()
        assertEquals(NewArrivalUpdateResult.RefreshFailed, NewArrivalUpdateCoordinator(failed, unusedAuto, clock).refresh(NewArrivalUpdateTrigger.SCHEDULED))
        assertEquals(0, unusedAuto.calls)
    }

    @Test fun `tryLockは重複呼出しを待たせずAlreadyRunningにする`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = Repository(beforeRefresh = { entered.complete(Unit); release.await() })
        val coordinator = NewArrivalUpdateCoordinator(repository, Automatic(), clock)
        val first = async { coordinator.refresh(NewArrivalUpdateTrigger.SCREEN_MANUAL) }
        entered.await()
        assertEquals(NewArrivalUpdateResult.AlreadyRunning, coordinator.refresh(NewArrivalUpdateTrigger.SCHEDULED))
        release.complete(Unit)
        assertTrue(first.await() is NewArrivalUpdateResult.Completed)
    }

    @Test fun `PREPARED直後のauto例外を結果へ保持しCancellationは伝播する`() = runTest {
        val preparedFailure = Automatic { callback -> callback(); throw IllegalStateException("after prepared") }
        val result = NewArrivalUpdateCoordinator(Repository(), preparedFailure, clock).refresh(NewArrivalUpdateTrigger.SCHEDULED)
        assertEquals(NewArrivalUpdateResult.AutomaticFailed(true), result)

        val cancellation = Automatic { throw CancellationException("cancel") }
        try {
            NewArrivalUpdateCoordinator(Repository(), cancellation, clock).refresh(NewArrivalUpdateTrigger.SCHEDULED)
            throw AssertionError("CancellationExceptionが必要")
        } catch (_: CancellationException) {
            Unit
        }

        val preparedCancellation = Automatic { callback -> callback(); throw CancellationException("after prepared") }
        try {
            NewArrivalUpdateCoordinator(Repository(), preparedCancellation, clock).refresh(NewArrivalUpdateTrigger.SCHEDULED)
            throw AssertionError("PREPARED後CancellationExceptionが必要")
        } catch (_: CancellationException) {
            Unit
        }

        val refreshCancellation = Repository(failure = CancellationException("refresh cancel"))
        try {
            NewArrivalUpdateCoordinator(refreshCancellation, Automatic(), clock).refresh(NewArrivalUpdateTrigger.SCREEN_MANUAL)
            throw AssertionError("refreshのCancellationExceptionが必要")
        } catch (_: CancellationException) {
            Unit
        }
    }

    private class Repository(
        private val hasCache: Boolean = false,
        private val lastFetched: Long? = null,
        private val failure: Exception? = null,
        private val events: MutableList<String>? = null,
        private val beforeRefresh: suspend () -> Unit = {},
    ) : NewArrivalRepository {
        var refreshes = 0
        override fun newArrivals() = MutableStateFlow(emptyList<NewArrival>())
        override suspend fun refresh() {
            beforeRefresh()
            failure?.let { throw it }
            refreshes++
            events?.add("refresh")
        }
        override suspend fun lastFetchedAtEpochMillis() = lastFetched
        override suspend fun hasCachedItems() = hasCache
    }

    private class Automatic(
        private val events: MutableList<String>? = null,
        private val block: suspend (() -> Unit) -> AutomaticReservationRunResult = { AutomaticReservationRunResult.NoMatch },
    ) : AutomaticReservationRunner {
        constructor(block: suspend (() -> Unit) -> AutomaticReservationRunResult) : this(null, block)
        var calls = 0
        override suspend fun run(onPreparedPersisted: () -> Unit): AutomaticReservationRunResult {
            calls++
            events?.add("auto")
            return block(onPreparedPersisted)
        }
    }
}
