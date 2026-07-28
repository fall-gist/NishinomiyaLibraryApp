package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 同期時の取消済み(CANCELLED)予約除外(docs/ui-design.md「方針: 予約取消の導線」)を検証する。
 * StatusRepositoryImpl本体はAppDatabase/複数DAO/Gatewayに依存し単体構築が重いため、
 * 除外ロジックだけを[excludeCancelledReservations]という純関数に切り出してテストする。
 */
class StatusRepositoryImplSyncTest {
    private val today = LocalDate.of(2026, 7, 28)

    @Test
    fun `取消済みの予約だけを除外する`() {
        val waiting = reservation(1, ReservationState.WAITING)
        val ready = reservation(2, ReservationState.READY)
        val cancelled = reservation(3, ReservationState.CANCELLED)
        val inTransit = reservation(4, ReservationState.IN_TRANSIT)
        val unknown = reservation(5, ReservationState.UNKNOWN)

        val result = excludeCancelledReservations(listOf(waiting, ready, cancelled, inTransit, unknown))

        assertEquals(listOf(waiting, ready, inTransit, unknown), result)
    }

    @Test
    fun `取消済みが無ければ全件そのまま残す`() {
        val reservations = listOf(reservation(1, ReservationState.WAITING), reservation(2, ReservationState.READY))

        val result = excludeCancelledReservations(reservations)

        assertEquals(reservations, result)
    }

    @Test
    fun `全件取消済みなら空になる`() {
        val result = excludeCancelledReservations(listOf(reservation(1, ReservationState.CANCELLED)))

        assertEquals(emptyList<Reservation>(), result)
    }

    private fun reservation(memberId: Long, state: ReservationState) = Reservation(
        memberId = memberId,
        title = "資料$memberId",
        materialType = "本",
        pickupLibrary = "中央",
        reservedDate = today,
        queuePosition = 1,
        state = state,
        holdExpiryDate = null,
    )
}
