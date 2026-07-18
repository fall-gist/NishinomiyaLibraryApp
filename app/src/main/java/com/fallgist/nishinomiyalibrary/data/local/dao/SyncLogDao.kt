package com.fallgist.nishinomiyalibrary.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fallgist.nishinomiyalibrary.data.local.entity.SyncLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncLogDao {
    @Query("SELECT * FROM sync_logs ORDER BY startedAtEpochMillis DESC, id DESC LIMIT 1")
    fun observeLatest(): Flow<SyncLogEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(log: SyncLogEntity): Long

    @Update
    suspend fun update(log: SyncLogEntity)

    @Delete
    suspend fun delete(log: SyncLogEntity)
}
