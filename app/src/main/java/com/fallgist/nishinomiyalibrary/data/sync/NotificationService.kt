package com.fallgist.nishinomiyalibrary.data.sync

import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.local.dao.LoanDao
import com.fallgist.nishinomiyalibrary.data.local.dao.MemberDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReservationDao
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationService @Inject constructor(
    private val settingsStore: SettingsStore,
    private val memberDao: MemberDao,
    private val loanDao: LoanDao,
    private val reservationDao: ReservationDao,
    private val clock: Clock,
    private val sink: NotificationSink,
) : PostSyncNotifier {
    override suspend fun notifyAfterSuccessfulSync(successfulMemberIds: Set<Long>) {
        if (successfulMemberIds.isEmpty()) return

        val settings = settingsStore.settings.first()
        val memberNames = memberDao.getAll()
            .filter { it.id in successfulMemberIds }
            .associate { it.id to it.name }

        if (settings.notifyReturnReminder) {
            val reminderPlan = NotificationPlanner.returnReminder(
                today = java.time.LocalDate.now(clock),
                loans = loanDao.getAll().asSequence()
                    .filter { it.memberId in successfulMemberIds }
                    .map { loan ->
                        LoanNotificationSource(
                            memberId = loan.memberId,
                            memberName = memberNames[loan.memberId] ?: "メンバー",
                            title = loan.title,
                            dueDate = loan.dueDate,
                        )
                    }.toList(),
            )
            if (reminderPlan != null) {
                postSafely { sink.postReturnReminder(reminderPlan) }
            }
        }

        if (settings.notifyPickupReady) {
            val pickupPlan = NotificationPlanner.pickupReady(
                reservationDao.getAll().asSequence()
                    .filter { it.memberId in successfulMemberIds }
                    .map { reservation ->
                        ReservationNotificationSource(
                            reservationId = reservation.id,
                            memberName = memberNames[reservation.memberId] ?: "メンバー",
                            title = reservation.title,
                            pickupLibrary = reservation.pickupLibrary,
                            holdExpiryDate = reservation.holdExpiryDate,
                            state = reservation.state,
                            firstReadyNotifiedAt = reservation.firstReadyNotifiedAt,
                        )
                    }.toList(),
            )
            if (pickupPlan != null && postSafely { sink.postPickupReady(pickupPlan) }) {
                reservationDao.markReadyNotified(
                    reservationIds = pickupPlan.items.map { it.reservationId },
                    notifiedAt = clock.millis(),
                )
            }
        }
    }

    private suspend fun postSafely(post: suspend () -> Boolean): Boolean = try {
        post()
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        false
    }
}
