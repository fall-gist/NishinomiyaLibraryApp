package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import javax.inject.Inject
import javax.inject.Singleton

/** 自動予約が取得した現在利用状況を、同期済み表示データへ安全に反映する境界。 */
interface CurrentCirculationSnapshotStore {
    suspend fun replaceLoans(memberId: Long, loans: List<Loan>)
    suspend fun replaceCompleteReservations(memberId: Long, reservations: List<Reservation>)
}

@Singleton
class RoomCurrentCirculationSnapshotStore @Inject constructor(
    private val database: AppDatabase,
) : CurrentCirculationSnapshotStore {
    override suspend fun replaceLoans(memberId: Long, loans: List<Loan>) {
        database.replaceCurrentLoans(memberId, loans.map { it.toEntity(memberId) })
    }

    override suspend fun replaceCompleteReservations(memberId: Long, reservations: List<Reservation>) {
        database.replaceCurrentReservations(memberId, excludeCancelledReservations(reservations).map { it.toEntity(memberId) })
    }
}
