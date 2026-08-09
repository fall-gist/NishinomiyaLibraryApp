package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.dao.ShelfDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ShelfItemDao
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfItemEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfItemWithShelfName
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BookshelfRepositoryImplTest {
    @Test
    fun observeShelves_keepsEmptyShelvesAndRegisteredDateOrder() = runBlocking {
        val shelves = MutableStateFlow(listOf(ShelfEntity(1, 2, "空棚"), ShelfEntity(1, 4, "資料あり")))
        val items = MutableStateFlow(
            listOf(
                item(shelfNo = 4, tilcod = "new", date = LocalDate.of(2030, 2, 1)),
                item(shelfNo = 4, tilcod = "old", date = LocalDate.of(2030, 1, 1)),
            ),
        )
        val repository = BookshelfRepositoryImpl(FakeShelfDao(shelves), FakeShelfItemDao(items))

        val result = repository.observeShelves(1).first()

        assertEquals(listOf(2, 4), result.map { it.shelfNo })
        assertEquals(emptyList<String>(), result.first().items.map { it.tilcod })
        assertEquals(listOf("new", "old"), result.last().items.map { it.tilcod })
        assertEquals("資料あり", result.last().items.first().shelfName)
    }

    private class FakeShelfDao(private val shelves: Flow<List<ShelfEntity>>) : ShelfDao {
        override fun observeForMember(memberId: Long): Flow<List<ShelfEntity>> = shelves
        override suspend fun insertAll(shelves: List<ShelfEntity>) = Unit
        override suspend fun deleteForMember(memberId: Long) = Unit
    }

    private class FakeShelfItemDao(private val items: Flow<List<ShelfItemWithShelfName>>) : ShelfItemDao {
        override fun observeForMember(memberId: Long): Flow<List<ShelfItemWithShelfName>> = items
        override suspend fun insert(item: ShelfItemEntity) = Unit
        override suspend fun insertAll(items: List<ShelfItemEntity>) = Unit
        override suspend fun update(item: ShelfItemEntity) = Unit
        override suspend fun delete(item: ShelfItemEntity) = Unit
        override suspend fun deleteForMember(memberId: Long) = Unit
    }

    private fun item(shelfNo: Int, tilcod: String, date: LocalDate) = ShelfItemWithShelfName(
        memberId = 1,
        shelfNo = shelfNo,
        shelfName = "古い名称",
        tilcod = tilcod,
        title = tilcod,
        memo = "",
        registeredDate = date,
    )
}
