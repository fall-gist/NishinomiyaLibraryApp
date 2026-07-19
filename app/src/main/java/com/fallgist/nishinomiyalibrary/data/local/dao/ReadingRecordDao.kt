package com.fallgist.nishinomiyalibrary.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingInfoProjection
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingHistoryCheckpointEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingRecordEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingRecordKeyProjection
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingRecordDao {
    /** 現在貸出由来の記録を除外した、サイト履歴だけの既知キー。 */
    @Query("SELECT tilcod, loanDate FROM reading_history_checkpoints WHERE memberId = :memberId")
    suspend fun getHistoryCheckpointKeys(memberId: Long): List<ReadingRecordKeyProjection>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<ReadingRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistoryCheckpoints(checkpoints: List<ReadingHistoryCheckpointEntity>)

    @Query("SELECT * FROM reading_records ORDER BY loanDate DESC, title ASC")
    fun observeAll(): Flow<List<ReadingRecordEntity>>

    @Query("SELECT * FROM reading_records WHERE memberId = :memberId ORDER BY loanDate DESC, title ASC")
    fun observeForMember(memberId: Long): Flow<List<ReadingRecordEntity>>

    @Query(
        "SELECT * FROM reading_records " +
            "WHERE (:memberId IS NULL OR memberId = :memberId) " +
            "AND titleNormalized LIKE '%' || :normalizedQuery || '%' " +
            "ORDER BY loanDate DESC, title ASC",
    )
    fun search(normalizedQuery: String, memberId: Long?): Flow<List<ReadingRecordEntity>>

    @Query(
        "SELECT memberId, loanDate, library FROM reading_records " +
            "WHERE tilcod = :tilcod ORDER BY loanDate DESC",
    )
    fun observeReadingInfo(tilcod: String): Flow<List<ReadingInfoProjection>>

    @Query("DELETE FROM reading_records WHERE memberId = :memberId")
    suspend fun deleteForMember(memberId: Long)

    @Query("DELETE FROM reading_history_checkpoints WHERE memberId = :memberId")
    suspend fun deleteHistoryCheckpointsForMember(memberId: Long)
}
