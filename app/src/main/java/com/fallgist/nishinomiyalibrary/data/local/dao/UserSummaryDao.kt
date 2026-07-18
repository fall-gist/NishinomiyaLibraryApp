package com.fallgist.nishinomiyalibrary.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fallgist.nishinomiyalibrary.data.local.entity.UserSummaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserSummaryDao {
    @Query("SELECT * FROM user_summaries ORDER BY memberId ASC")
    fun observeAll(): Flow<List<UserSummaryEntity>>

    @Query("SELECT * FROM user_summaries WHERE memberId = :memberId")
    fun observeForMember(memberId: Long): Flow<UserSummaryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(summary: UserSummaryEntity)

    @Update
    suspend fun update(summary: UserSummaryEntity)

    @Delete
    suspend fun delete(summary: UserSummaryEntity)

    @Query("DELETE FROM user_summaries WHERE memberId = :memberId")
    suspend fun deleteForMember(memberId: Long)
}
