package com.fallgist.nishinomiyalibrary.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemberDao {
    @Query("SELECT * FROM members ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<MemberEntity>>

    @Query("SELECT * FROM members WHERE id = :memberId")
    fun observeById(memberId: Long): Flow<MemberEntity?>

    @Query("SELECT * FROM members WHERE id = :memberId")
    suspend fun getById(memberId: Long): MemberEntity?

    @Query("SELECT * FROM members ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<MemberEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM members")
    suspend fun nextSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(member: MemberEntity): Long

    @Update
    suspend fun update(member: MemberEntity)

    @Delete
    suspend fun delete(member: MemberEntity)
}
