package com.fallgist.nishinomiyalibrary.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import java.time.LocalDate

@Entity(tableName = "members")
data class MemberEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val colorHex: String,
    val cardNumber: String,
    val sortOrder: Int,
)

@Entity(tableName = "loans")
data class LoanEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val memberId: Long,
    val title: String,
    val materialType: String,
    val lendingLibrary: String,
    val loanDate: LocalDate,
    val dueDate: LocalDate,
    val status: String,
)

@Entity(
    tableName = "reservations",
    indices = [Index(value = ["memberId", "title", "materialType", "reservedDate"])],
)
data class ReservationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val memberId: Long,
    val title: String,
    val materialType: String,
    val pickupLibrary: String,
    val reservedDate: LocalDate,
    val queuePosition: Int?,
    val state: ReservationState,
    val holdExpiryDate: LocalDate?,
    val firstReadyNotifiedAt: Long?,
)

@Entity(
    tableName = "shelf_items",
    primaryKeys = ["memberId", "tilcod"],
)
data class ShelfItemEntity(
    val memberId: Long,
    val tilcod: String,
    val title: String,
    val memo: String,
    val registeredDate: LocalDate,
)

@Entity(
    tableName = "closed_days",
    primaryKeys = ["libraryCode", "date"],
)
data class ClosedDayEntity(
    val libraryCode: String,
    val date: LocalDate,
)

@Entity(tableName = "sync_logs")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long?,
    val trigger: String,
    val succeeded: Boolean?,
    val details: String,
)

@Entity(tableName = "user_summaries")
data class UserSummaryEntity(
    @PrimaryKey
    val memberId: Long,
    val shelfCount: Int,
    val loanCount: Int,
    val reservationCount: Int,
    val cartCount: Int,
)
