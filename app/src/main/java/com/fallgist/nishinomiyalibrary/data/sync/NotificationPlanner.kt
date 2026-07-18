package com.fallgist.nishinomiyalibrary.data.sync

import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import java.time.LocalDate

/** Android APIに依存しない、同期後の通知内容の判定結果。 */
data class ReturnReminderPlan(
    val itemsByMember: List<MemberLoanItems>,
    val hasOverdue: Boolean,
)

data class MemberLoanItems(
    val memberId: Long,
    val memberName: String,
    val titles: List<String>,
)

data class PickupReadyPlan(val items: List<PickupReadyItem>)

data class PickupReadyItem(
    val reservationId: Long,
    val memberName: String,
    val title: String,
    val pickupLibrary: String,
    val holdExpiryDate: LocalDate?,
)

data class LoanNotificationSource(
    val memberId: Long,
    val memberName: String,
    val title: String,
    val dueDate: LocalDate,
)

data class ReservationNotificationSource(
    val reservationId: Long,
    val memberName: String,
    val title: String,
    val pickupLibrary: String,
    val holdExpiryDate: LocalDate?,
    val state: ReservationState,
    val firstReadyNotifiedAt: Long?,
)

object NotificationPlanner {
    /** 明日返却と当日以前の返却期限を、メンバーごとの一通へまとめる。 */
    fun returnReminder(today: LocalDate, loans: List<LoanNotificationSource>): ReturnReminderPlan? {
        val relevant = loans.filter { loan ->
            loan.dueDate == today.plusDays(1) || !loan.dueDate.isAfter(today)
        }
        if (relevant.isEmpty()) return null

        return ReturnReminderPlan(
            itemsByMember = relevant
                .groupBy { it.memberId to it.memberName }
                .map { (member, items) ->
                    MemberLoanItems(
                        memberId = member.first,
                        memberName = member.second,
                        titles = items.map { it.title },
                    )
                }
                .sortedBy { it.memberId },
            hasOverdue = relevant.any { !it.dueDate.isAfter(today) },
        )
    }

    /** READYかつ未通知の予約だけを抽出する。 */
    fun pickupReady(reservations: List<ReservationNotificationSource>): PickupReadyPlan? {
        val items = reservations
            .filter { it.state == ReservationState.READY && it.firstReadyNotifiedAt == null }
            .map {
                PickupReadyItem(
                    reservationId = it.reservationId,
                    memberName = it.memberName,
                    title = it.title,
                    pickupLibrary = it.pickupLibrary,
                    holdExpiryDate = it.holdExpiryDate,
                )
            }
        return items.takeIf { it.isNotEmpty() }?.let(::PickupReadyPlan)
    }
}
