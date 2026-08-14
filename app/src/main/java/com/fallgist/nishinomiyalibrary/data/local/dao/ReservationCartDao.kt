package com.fallgist.nishinomiyalibrary.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationCartItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReservationCartDao {
    @Query("SELECT * FROM reservation_cart_items ORDER BY addedAtEpochMillis ASC, id ASC")
    fun observeAll(): Flow<List<ReservationCartItemEntity>>

    @Query("SELECT * FROM reservation_cart_items ORDER BY addedAtEpochMillis ASC, id ASC")
    suspend fun getAll(): List<ReservationCartItemEntity>

    @Query("SELECT * FROM reservation_cart_items WHERE id IN (:ids) ORDER BY addedAtEpochMillis ASC, id ASC")
    suspend fun getByIds(ids: List<Long>): List<ReservationCartItemEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreDuplicate(item: ReservationCartItemEntity): Long

    /** 設定インポート(全置換)専用。検証済みの投入なので衝突は失敗として扱う。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<ReservationCartItemEntity>): List<Long>

    @Delete
    suspend fun delete(item: ReservationCartItemEntity)

    @Query("DELETE FROM reservation_cart_items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM reservation_cart_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 設定インポート(全置換)専用。 */
    @Query("DELETE FROM reservation_cart_items")
    suspend fun clearAll()
}
