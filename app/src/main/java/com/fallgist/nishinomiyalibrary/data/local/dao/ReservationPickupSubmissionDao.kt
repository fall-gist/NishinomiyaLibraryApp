package com.fallgist.nishinomiyalibrary.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationPickupSubmissionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReservationPickupSubmissionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(submission: ReservationPickupSubmissionEntity)

    @Query("SELECT * FROM reservation_pickup_submissions WHERE memberId = :memberId AND tilcod = :tilcod")
    suspend fun get(memberId: Long, tilcod: String): ReservationPickupSubmissionEntity?

    /**
     * 予約中一覧が受取館「未定」の名前解決に使う観測用Flow(2026-08-05追加)。
     * 既存の`get`・書込みクエリは変更していない。
     */
    @Query("SELECT * FROM reservation_pickup_submissions")
    fun observeAll(): Flow<List<ReservationPickupSubmissionEntity>>

    @Query("DELETE FROM reservation_pickup_submissions WHERE memberId = :memberId AND tilcod NOT IN (:activeTilcods)")
    suspend fun deleteMissingFromCompleteSnapshot(memberId: Long, activeTilcods: List<String>): Int

    @Query("DELETE FROM reservation_pickup_submissions WHERE memberId = :memberId")
    suspend fun deleteForMember(memberId: Long)
}
