package com.fallgist.nishinomiyalibrary.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
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

@Entity(
    tableName = "reservation_cart_items",
    foreignKeys = [
        ForeignKey(
            entity = MemberEntity::class,
            parentColumns = ["id"],
            childColumns = ["memberId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["memberId"]), Index(value = ["memberId", "tilcod"], unique = true)],
)
data class ReservationCartItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val memberId: Long,
    val tilcod: String,
    val title: String,
    val writerLine: String?,
    val addedAtEpochMillis: Long,
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
    /** 貸出一覧の書誌詳細リンクから取得するタイトルコード。旧データは空文字列。 */
    val tilcod: String = "",
)

@Entity(
    tableName = "reading_records",
    primaryKeys = ["memberId", "tilcod", "loanDate"],
    indices = [Index(value = ["memberId"]), Index(value = ["tilcod"]), Index(value = ["titleNormalized"])],
)
data class ReadingRecordEntity(
    val memberId: Long,
    val tilcod: String,
    val title: String,
    val loanDate: LocalDate,
    val library: String,
    val titleNormalized: String,
)

/** サイト読書履歴で確認済みの行だけを記録する、差分同期用チェックポイント。 */
@Entity(
    tableName = "reading_history_checkpoints",
    primaryKeys = ["memberId", "tilcod", "loanDate"],
    indices = [Index(value = ["memberId"])],
)
data class ReadingHistoryCheckpointEntity(
    val memberId: Long,
    val tilcod: String,
    val loanDate: LocalDate,
)

data class ReadingRecordKeyProjection(
    val tilcod: String,
    val loanDate: LocalDate,
)

data class ReadingInfoProjection(
    val memberId: Long,
    val loanDate: LocalDate,
    val library: String,
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
    /** 予約一覧の書誌詳細リンク(hTilcod)由来のタイトルコード。旧データは空文字列。 */
    val tilcod: String = "",
    /** 取消ボタン(yoykCancel)由来の予約コード。取消ボタンが無い行では空文字列。旧データも空文字列。 */
    val cancelCode: String = "",
)

@Entity(
    tableName = "shelf_items",
    primaryKeys = ["memberId", "shelfNo", "tilcod"],
)
data class ShelfItemEntity(
    val memberId: Long,
    val shelfNo: Int,
    val tilcod: String,
    val title: String,
    val memo: String,
    val registeredDate: LocalDate,
)

@Entity(
    tableName = "shelves",
    primaryKeys = ["memberId", "shelfNo"],
)
data class ShelfEntity(
    val memberId: Long,
    val shelfNo: Int,
    val name: String,
)

data class ShelfItemWithShelfName(
    val memberId: Long,
    val shelfNo: Int,
    val shelfName: String,
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

/** 全ジャンルを統合した新着資料。tilcodを主キーに名寄せ保持する。 */
@Entity(tableName = "new_arrivals")
data class NewArrivalEntity(
    @PrimaryKey
    val tilcod: String,
    val title: String,
    val volume: String,
    val author: String,
    val publisher: String,
    val publishedYearMonth: String,
    val classification: String,
    val lendable: Boolean?,
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
