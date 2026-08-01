package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.sync.AutoReservationCompletionNotifier
import com.fallgist.nishinomiyalibrary.data.sync.NoOpAutoReservationCompletionNotifier
import com.fallgist.nishinomiyalibrary.domain.repository.NewArrivalRepository
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

enum class NewArrivalUpdateTrigger { SCHEDULED, SCREEN_AUTO, SCREEN_MANUAL }

interface NewArrivalUpdateRunner {
    suspend fun refresh(trigger: NewArrivalUpdateTrigger): NewArrivalUpdateResult
}

interface AutomaticReservationRunner {
    suspend fun run(onPreparedPersisted: () -> Unit = {}): AutomaticReservationRunResult
}

sealed interface NewArrivalUpdateResult {
    data object AlreadyRunning : NewArrivalUpdateResult
    data object FreshnessSkipped : NewArrivalUpdateResult
    data object RefreshFailed : NewArrivalUpdateResult
    data class Completed(val automaticReservation: AutomaticReservationRunResult) : NewArrivalUpdateResult
    data class AutomaticFailed(val preparedReached: Boolean) : NewArrivalUpdateResult
}

@Singleton
class NewArrivalUpdateCoordinator @Inject constructor(
    private val newArrivals: NewArrivalRepository,
    private val automaticReservations: AutomaticReservationRunner,
    private val clock: Clock,
    private val completionNotifier: AutoReservationCompletionNotifier = NoOpAutoReservationCompletionNotifier,
) : NewArrivalUpdateRunner {
    private val mutex = Mutex()

    override suspend fun refresh(trigger: NewArrivalUpdateTrigger): NewArrivalUpdateResult {
        if (!mutex.tryLock()) return NewArrivalUpdateResult.AlreadyRunning
        try {
            if (trigger == NewArrivalUpdateTrigger.SCREEN_AUTO && isFreshScreenCache()) {
                return NewArrivalUpdateResult.FreshnessSkipped
            }
            try {
                newArrivals.refresh()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                return NewArrivalUpdateResult.RefreshFailed
            }
            var preparedReached = false
            return try {
                val automaticResult = automaticReservations.run { preparedReached = true }
                try {
                    completionNotifier.notifyCompletion(automaticResult)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    // 通知失敗は自動予約および更新の完了結果を変更しない。
                }
                NewArrivalUpdateResult.Completed(automaticResult)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                NewArrivalUpdateResult.AutomaticFailed(preparedReached)
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun isFreshScreenCache(): Boolean {
        if (!newArrivals.hasCachedItems()) return false
        val lastFetchedAt = newArrivals.lastFetchedAtEpochMillis() ?: return false
        return clock.millis() - lastFetchedAt < SCREEN_AUTO_FRESHNESS_MILLIS
    }

    companion object {
        val SCREEN_AUTO_FRESHNESS_MILLIS: Long = Duration.ofHours(12).toMillis()
    }
}
