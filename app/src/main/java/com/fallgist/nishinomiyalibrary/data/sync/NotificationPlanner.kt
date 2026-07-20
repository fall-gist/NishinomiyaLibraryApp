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
    /**
     * 返却期限が[daysBefore]日後以内(期限超過を含む)の貸出を、メンバーごとの一通へまとめる。
     * 既定の1(前日)では従来どおり「明日返却+当日以前」が対象になる。
     */
    fun returnReminder(
        today: LocalDate,
        loans: List<LoanNotificationSource>,
        daysBefore: Int = 1,
    ): ReturnReminderPlan? {
        require(daysBefore >= 1) { "通知日数は1以上で指定してください" }
        val relevant = loans.filter { loan ->
            !loan.dueDate.isAfter(today.plusDays(daysBefore.toLong()))
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
