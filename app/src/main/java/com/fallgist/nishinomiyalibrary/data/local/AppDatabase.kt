package com.fallgist.nishinomiyalibrary.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverters
import androidx.room.withTransaction
import com.fallgist.nishinomiyalibrary.data.local.dao.ClosedDayDao
import com.fallgist.nishinomiyalibrary.data.local.dao.LoanDao
import com.fallgist.nishinomiyalibrary.data.local.dao.MemberDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReservationDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ShelfItemDao
import com.fallgist.nishinomiyalibrary.data.local.dao.SyncLogDao
import com.fallgist.nishinomiyalibrary.data.local.dao.UserSummaryDao
import com.fallgist.nishinomiyalibrary.data.local.entity.ClosedDayEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.LoanEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfItemEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.SyncLogEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.UserSummaryEntity
import java.time.LocalDate

@Database(
    entities = [
        MemberEntity::class,
        LoanEntity::class,
        ReservationEntity::class,
        ShelfItemEntity::class,
        ClosedDayEntity::class,
        SyncLogEntity::class,
        UserSummaryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(LocalDateConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memberDao(): MemberDao
    abstract fun loanDao(): LoanDao
    abstract fun reservationDao(): ReservationDao
    abstract fun shelfItemDao(): ShelfItemDao
    abstract fun closedDayDao(): ClosedDayDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun userSummaryDao(): UserSummaryDao

    /**
     * 同期結果をメンバー単位で置き換える。予約の通知済み時刻だけは、
     * 状態が変化しても同じ通知識別項目の行へ引き継ぐ。
     */
    @Transaction
    suspend fun replaceMemberSnapshot(
        memberId: Long,
        loans: List<LoanEntity>,
        reservations: List<ReservationEntity>,
        shelfItems: List<ShelfItemEntity>,
        summary: UserSummaryEntity,
    ) {
        require(loans.all { it.memberId == memberId }) { "貸出データのmemberIdが一致しません" }
        require(reservations.all { it.memberId == memberId }) { "予約データのmemberIdが一致しません" }
        require(shelfItems.all { it.memberId == memberId }) { "本棚データのmemberIdが一致しません" }
        require(summary.memberId == memberId) { "サマリのmemberIdが一致しません" }

        withTransaction {
            val previousNotificationTimes = reservationDao()
                .getForMember(memberId)
                .groupBy { it.notificationKey() }
                .mapValues { (_, matchingReservations) ->
                    matchingReservations.map { it.firstReadyNotifiedAt }
                }
            val previousNotificationIndexes = mutableMapOf<ReservationNotificationKey, Int>()
            val reservationsToInsert = reservations.map { reservation ->
                val key = reservation.notificationKey()
                val index = previousNotificationIndexes[key] ?: 0
                previousNotificationIndexes[key] = index + 1
                reservation.copy(
                    firstReadyNotifiedAt = previousNotificationTimes[key]
                        ?.getOrNull(index)
                        ?: reservation.firstReadyNotifiedAt,
                )
            }

            loanDao().deleteForMember(memberId)
            reservationDao().deleteForMember(memberId)
            shelfItemDao().deleteForMember(memberId)
            userSummaryDao().deleteForMember(memberId)

            loanDao().insertAll(loans)
            reservationDao().insertAll(reservationsToInsert)
            shelfItemDao().insertAll(shelfItems)
            userSummaryDao().insert(summary)
        }
    }

    /** 指定館の当日以降の休館日を、取得結果で置き換える。 */
    @Transaction
    suspend fun replaceFutureClosedDays(
        libraryCode: String,
        today: LocalDate,
        days: List<ClosedDayEntity>,
    ) {
        require(days.all { it.libraryCode == libraryCode }) { "休館日のlibraryCodeが一致しません" }

        withTransaction {
            closedDayDao().deleteFromToday(libraryCode, today)
            closedDayDao().insertAll(days.filter { !it.date.isBefore(today) })
        }
    }

    /** メンバー削除に伴い、そのメンバーに属するローカルデータも削除する。 */
    @Transaction
    suspend fun deleteMemberAndLocalData(member: MemberEntity) {
        withTransaction {
            loanDao().deleteForMember(member.id)
            reservationDao().deleteForMember(member.id)
            shelfItemDao().deleteForMember(member.id)
            userSummaryDao().deleteForMember(member.id)
            memberDao().delete(member)
        }
    }

    /** sortOrderを既存順序の末尾に安定して採番してメンバーを保存する。 */
    @Transaction
    suspend fun insertMemberAtEnd(member: MemberEntity): Long = withTransaction {
        memberDao().insert(member.copy(id = 0, sortOrder = memberDao().nextSortOrder()))
    }

    private fun ReservationEntity.notificationKey(): ReservationNotificationKey = ReservationNotificationKey(
        memberId = memberId,
        title = title,
        materialType = materialType,
        reservedDate = reservedDate,
    )

    private data class ReservationNotificationKey(
        val memberId: Long,
        val title: String,
        val materialType: String,
        val reservedDate: LocalDate,
    )
}
