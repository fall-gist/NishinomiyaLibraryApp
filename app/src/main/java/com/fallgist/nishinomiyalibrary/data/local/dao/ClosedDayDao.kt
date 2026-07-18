package com.fallgist.nishinomiyalibrary.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fallgist.nishinomiyalibrary.data.local.entity.ClosedDayEntity
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface ClosedDayDao {
    @Query("SELECT * FROM closed_days WHERE libraryCode = :libraryCode ORDER BY date ASC")
    fun observeForLibrary(libraryCode: String): Flow<List<ClosedDayEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(day: ClosedDayEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(days: List<ClosedDayEntity>)

    @Update
    suspend fun update(day: ClosedDayEntity)

    @Delete
    suspend fun delete(day: ClosedDayEntity)

    @Query("DELETE FROM closed_days WHERE libraryCode = :libraryCode AND date >= :today")
    suspend fun deleteFromToday(libraryCode: String, today: LocalDate)
}
