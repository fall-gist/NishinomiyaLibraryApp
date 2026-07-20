package com.fallgist.nishinomiyalibrary.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fallgist.nishinomiyalibrary.data.local.entity.NewArrivalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NewArrivalDao {
    /** 出版年月の新しい順(同月内は書名順)で一覧を購読する。 */
    @Query("SELECT * FROM new_arrivals ORDER BY publishedYearMonth DESC, title ASC")
    fun observeAll(): Flow<List<NewArrivalEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<NewArrivalEntity>)

    @Query("DELETE FROM new_arrivals")
    suspend fun clear()
}
