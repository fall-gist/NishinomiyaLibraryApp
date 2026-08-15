package com.fallgist.nishinomiyalibrary.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfItemEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfItemWithShelfName
import kotlinx.coroutines.flow.Flow

@Dao
interface ShelfItemDao {
    @Query(
        "SELECT shelf_items.memberId, shelf_items.shelfNo, shelves.name AS shelfName, " +
            "shelf_items.tilcod, shelf_items.title, shelf_items.memo, shelf_items.registeredDate " +
            "FROM shelf_items INNER JOIN shelves " +
            "ON shelf_items.memberId = shelves.memberId AND shelf_items.shelfNo = shelves.shelfNo " +
            "WHERE shelf_items.memberId = :memberId " +
            "ORDER BY shelf_items.shelfNo, shelf_items.registeredDate DESC",
    )
    fun observeForMember(memberId: Long): Flow<List<ShelfItemWithShelfName>>

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

    /** 設定インポート(全置換)専用(docs/design/settings-export-import.md §6.1)。 */
    @Query("DELETE FROM shelf_items")
    suspend fun clearAll()
}
