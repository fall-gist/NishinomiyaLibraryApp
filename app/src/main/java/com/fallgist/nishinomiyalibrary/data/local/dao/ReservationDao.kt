package com.fallgist.nishinomiyalibrary.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReservationDao {
    @Query("SELECT * FROM reservations ORDER BY reservedDate ASC")
    fun observeAll(): Flow<List<ReservationEntity>>

    @Query("SELECT * FROM reservations WHERE memberId = :memberId ORDER BY reservedDate ASC")
    fun observeForMember(memberId: Long): Flow<List<ReservationEntity>>

    @Query("SELECT * FROM reservations WHERE memberId = :memberId ORDER BY id ASC")
    suspend fun getForMember(memberId: Long): List<ReservationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reservation: ReservationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reservations: List<ReservationEntity>)

    @Update
    suspend fun update(reservation: ReservationEntity)

    @Delete
    suspend fun delete(reservation: ReservationEntity)

    @Query("DELETE FROM reservations WHERE memberId = :memberId")
    suspend fun deleteForMember(memberId: Long)
}
