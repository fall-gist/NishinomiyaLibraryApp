package com.fallgist.nishinomiyalibrary.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.fallgist.nishinomiyalibrary.data.local.entity.LoanEntity
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface LoanDao {
    @Query("SELECT * FROM loans ORDER BY dueDate ASC")
    fun observeAll(): Flow<List<LoanEntity>>

    @Query("SELECT * FROM loans WHERE memberId = :memberId ORDER BY dueDate ASC")
    fun observeForMember(memberId: Long): Flow<List<LoanEntity>>

    @Query("SELECT * FROM loans ORDER BY dueDate ASC, id ASC")
    suspend fun getAll(): List<LoanEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(loan: LoanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(loans: List<LoanEntity>)

    @Update
    suspend fun update(loan: LoanEntity)

    @Delete
    suspend fun delete(loan: LoanEntity)

    @Query("DELETE FROM loans WHERE memberId = :memberId")
    suspend fun deleteForMember(memberId: Long)

    /**
     * 設定インポート(全置換)専用。`loans`は`MemberEntity`への外部キーを持たないため
     * カスケード削除されず、消さないと旧メンバーの貸出が同じidの新メンバーのものとして残る
     * (docs/design/settings-export-import.md §6.1)。
     */
    @Query("DELETE FROM loans")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM loans WHERE memberId = :memberId AND tilcod = :tilcod")
    suspend fun countByMemberAndTilcod(memberId: Long, tilcod: String): Int

    @Query("UPDATE loans SET dueDate = :dueDate, extendable = 0 WHERE memberId = :memberId AND tilcod = :tilcod")
    suspend fun updateAfterExtension(memberId: Long, tilcod: String, dueDate: LocalDate): Int

    /**
     * 貸出延長成功後のローカル反映(`docs/design/loan-extension.md` §6.1)。
     * 対象(memberId+tilcod)の行が1件でなければ更新しない(フェイルクローズ)。
     * 更新できたときだけtrueを返す。
     */
    @Transaction
    suspend fun applyExtensionResult(memberId: Long, tilcod: String, dueDate: LocalDate): Boolean {
        if (countByMemberAndTilcod(memberId, tilcod) != 1) return false
        return updateAfterExtension(memberId, tilcod, dueDate) == 1
    }
}
