package com.fallgist.nishinomiyalibrary.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShelfItemDao {
    @Query("SELECT * FROM shelf_items WHERE memberId = :memberId ORDER BY registeredDate DESC")
    fun observeForMember(memberId: Long): Flow<List<ShelfItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ShelfItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ShelfItemEntity>)

    @Update
    suspend fun update(item: ShelfItemEntity)

    @Delete
    suspend fun delete(item: ShelfItemEntity)

    @Query("DELETE FROM shelf_items WHERE memberId = :memberId")
    suspend fun deleteForMember(memberId: Long)
}
