package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import com.fallgist.nishinomiyalibrary.domain.model.Shelf
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.model.UserSummary
import java.time.LocalDate

interface LibraryGateway {
    suspend fun search(keyword: String, page: Int = 1): SearchPage
    suspend fun autocomplete(keyword: String): List<String>
    suspend fun isLendable(tilcod: String): Boolean?
    suspend fun bookDetail(tilcod: String): BookDetail
    suspend fun closedDays(libraryCode: String): List<LocalDate>
    suspend fun fetchUserData(cardNumber: String, password: String): UserData
}

data class UserData(
    val summary: UserSummary,
    val loans: List<Loan>,
    val reservations: List<Reservation>,
    val shelves: List<Shelf>,
    val shelfItems: List<ShelfItem>,
)
