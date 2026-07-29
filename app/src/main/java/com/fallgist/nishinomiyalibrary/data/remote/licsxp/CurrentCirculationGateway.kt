package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Reservation

/** 自動予約専用の、ログインから予約一覧までで終了する読み取り境界。 */
interface CurrentCirculationGateway {
    suspend fun fetchCurrentCirculation(cardNumber: String, password: String): CurrentCirculationSnapshot
}

data class CurrentCirculationSnapshot(
    val loans: List<Loan>,
    val reservations: List<Reservation>,
    val reservationListComplete: Boolean,
)
