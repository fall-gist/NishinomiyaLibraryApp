package com.fallgist.nishinomiyalibrary.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fallgist.nishinomiyalibrary.data.local.entity.LoanEntity
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
}
