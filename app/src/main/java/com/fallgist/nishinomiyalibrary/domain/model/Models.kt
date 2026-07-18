package com.fallgist.nishinomiyalibrary.domain.model

import java.time.LocalDate

data class Member(
    val id: Long,
    val name: String,
    val colorHex: String,
    val cardNumber: String,
    val sortOrder: Int,
)

data class Loan(
    val memberId: Long,
    val title: String,
    val materialType: String,
    val lendingLibrary: String,
    val loanDate: LocalDate,
    val dueDate: LocalDate,
    val status: String,
)

data class Reservation(
    val memberId: Long,
    val title: String,
    val materialType: String,
    val pickupLibrary: String,
    val reservedDate: LocalDate,
    val queuePosition: Int?,
    val state: ReservationState,
    val holdExpiryDate: LocalDate?,
)

enum class ReservationState { WAITING, READY, UNKNOWN }

data class ShelfItem(
    val memberId: Long,
    val tilcod: String,
    val title: String,
    val memo: String,
    val registeredDate: LocalDate,
)

data class UserSummary(
    val memberId: Long,
    val shelfCount: Int,
    val loanCount: Int,
    val reservationCount: Int,
    val cartCount: Int,
)

data class SearchHit(
    val tilcod: String,
    val title: String,
    val writerLine: String,
    val materialType: String,
)

data class SearchPage(
    val hits: List<SearchHit>,
    val totalCount: Int,
    val hasNext: Boolean,
)

data class BookDetail(
    val tilcod: String,
    val fields: Map<String, String>,
    val isbn: String?,
    val holdings: List<Holding>,
    val holdingCount: Int,
    val availableCount: Int,
    val reservationCount: Int,
)

data class Holding(
    val library: String,
    val materialType: String,
    val callNumber: String,
    val location: String,
    val lendable: String,
    val status: String,
)

data class Library(val code: String, val name: String)

data class ClosedDay(val libraryCode: String, val date: LocalDate)
