package com.fallgist.nishinomiyalibrary.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationPickupSubmissionEntity

@Dao
interface ReservationPickupSubmissionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(submission: ReservationPickupSubmissionEntity)

    @Query("SELECT * FROM reservation_pickup_submissions WHERE memberId = :memberId AND tilcod = :tilcod")
    suspend fun get(memberId: Long, tilcod: String): ReservationPickupSubmissionEntity?

    @Query("DELETE FROM reservation_pickup_submissions WHERE memberId = :memberId AND tilcod NOT IN (:activeTilcods)")
    suspend fun deleteMissingFromCompleteSnapshot(memberId: Long, activeTilcods: List<String>): Int

    @Query("DELETE FROM reservation_pickup_submissions WHERE memberId = :memberId")
    suspend fun deleteForMember(memberId: Long)
}
