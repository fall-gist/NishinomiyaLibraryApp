package com.fallgist.nishinomiyalibrary.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationControlEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationLatestItemEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationLatestRunEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationRuleEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationTermEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.LATEST_AUTO_RESERVATION_RUN_ID
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationControlStatus
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoReservationDao {
    @Query("SELECT * FROM auto_reservation_rules ORDER BY sortOrder ASC, id ASC")
    suspend fun getRules(): List<AutoReservationRuleEntity>

    @Query("SELECT * FROM auto_reservation_terms WHERE ruleId IN (:ruleIds) ORDER BY ruleId ASC, kind ASC, sortOrder ASC")
    suspend fun getTerms(ruleIds: List<Long>): List<AutoReservationTermEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRule(rule: AutoReservationRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTerms(terms: List<AutoReservationTermEntity>)

    @Query("DELETE FROM auto_reservation_rules WHERE id = :ruleId")
    suspend fun deleteRule(ruleId: Long)

    @Query("DELETE FROM auto_reservation_rules")
    suspend fun clearRules()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertControl(control: AutoReservationControlEntity)

    @Query("SELECT * FROM auto_reservation_controls WHERE tilcod = :tilcod")
    suspend fun getControl(tilcod: String): AutoReservationControlEntity?

    @Query("DELETE FROM auto_reservation_controls WHERE expiresOn <= :today")
    suspend fun deleteExpiredControls(today: LocalDate): Int

    @Query("UPDATE auto_reservation_controls SET status = :unknownStatus, preparedMemberId = NULL WHERE status = :preparedStatus")
    suspend fun markPreparedControlsUnknown(
        preparedStatus: AutoReservationControlStatus = AutoReservationControlStatus.PREPARED,
        unknownStatus: AutoReservationControlStatus = AutoReservationControlStatus.UNKNOWN_AFTER_POST,
    ): Int

    @Query("SELECT * FROM auto_reservation_latest_run WHERE id = :id")
    fun observeLatestRun(id: Int = LATEST_AUTO_RESERVATION_RUN_ID): Flow<AutoReservationLatestRunEntity?>

    @Query("SELECT * FROM auto_reservation_latest_run WHERE id = :id")
    suspend fun getLatestRun(id: Int = LATEST_AUTO_RESERVATION_RUN_ID): AutoReservationLatestRunEntity?

    @Query("SELECT * FROM auto_reservation_latest_items WHERE runId = :runId ORDER BY tilcod ASC")
    suspend fun getLatestItems(runId: Long): List<AutoReservationLatestItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLatestRun(run: AutoReservationLatestRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLatestItems(items: List<AutoReservationLatestItemEntity>)

    @Query("DELETE FROM auto_reservation_latest_items")
    suspend fun clearLatestItems()

    @Query("UPDATE auto_reservation_latest_run SET acknowledged = 1 WHERE id = :id AND runId = :expectedRunId")
    suspend fun markLatestRunAcknowledged(
        expectedRunId: Long,
        id: Int = LATEST_AUTO_RESERVATION_RUN_ID,
    ): Int

    @Transaction
    suspend fun replaceLatestRun(run: AutoReservationLatestRunEntity, items: List<AutoReservationLatestItemEntity>) {
        require(items.all { it.runId == run.runId }) { "最新履歴項目のrunIdが一致しません" }
        require(items.map { it.tilcod }.distinct().size == items.size) { "最新履歴項目のtilcodが重複しています" }
        clearLatestItems()
        upsertLatestRun(run.copy(id = LATEST_AUTO_RESERVATION_RUN_ID))
        insertLatestItems(items)
    }
}
