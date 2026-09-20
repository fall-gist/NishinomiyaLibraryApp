package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.dao.ReadingRecordDao
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingHistoryCheckpointEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingRecordEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingInfoProjection
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingRecordKeyProjection
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ReadingRecordRepositoryImpl.search`の単語AND検索を検証する
 * (`docs/design/reading-records-search.md` §4のテスト計画8〜10)。
 */
class ReadingRecordRepositoryImplTest {
    private val loanDate = LocalDate.of(2026, 1, 1)

    @Test
    fun `検索語が空ならrecordsと同じ結果を返す`() = runTest {
        val entities = listOf(
            entity(memberId = 1L, tilcod = "1", title = "かいけつゾロリの大金もち", date = loanDate),
            entity(memberId = 1L, tilcod = "2", title = "忍たま乱太郎", date = loanDate.minusDays(1)),
        )
        val repository = ReadingRecordRepositoryImpl(FakeReadingRecordDao(entities))

        val recordsResult = repository.records(memberId = null).first().map { it.title }
        val searchResult = repository.search(query = "  ", memberId = null).first().map { it.title }

        assertEquals(recordsResult, searchResult)
    }

    @Test
    fun `2語のANDでメンバー絞り込みと併用しても正しく絞れる`() = runTest {
        val entities = listOf(
            entity(memberId = 1L, tilcod = "1", title = "かいけつゾロリの大金もち", date = loanDate),
            entity(memberId = 1L, tilcod = "2", title = "かいけつゾロリの初恋", date = loanDate.minusDays(1)),
            entity(memberId = 2L, tilcod = "3", title = "かいけつゾロリの大金もち", date = loanDate.minusDays(2)),
        )
        val repository = ReadingRecordRepositoryImpl(FakeReadingRecordDao(entities))

        val result = repository.search(query = "ゾロリ 大金", memberId = 1L).first()

        assertEquals(listOf("かいけつゾロリの大金もち"), result.map { it.title })
        assertEquals(listOf(1L), result.map { it.memberId })
    }

    @Test
    fun `並び順はloanDate降順 同日はtitle昇順のまま保たれる`() = runTest {
        val day1 = loanDate
        val day2 = loanDate.plusDays(1)
        val entities = listOf(
            // observeAllは`loanDate DESC, title ASC`で返す前提のDAO実装を模す
            entity(memberId = 1L, tilcod = "1", title = "ゾロリのAあ", date = day2),
            entity(memberId = 1L, tilcod = "2", title = "ゾロリのBい", date = day2),
            entity(memberId = 1L, tilcod = "3", title = "ゾロリのCう", date = day1),
        )
        val repository = ReadingRecordRepositoryImpl(FakeReadingRecordDao(entities))

        val result = repository.search(query = "ゾロリ", memberId = null).first()

        assertEquals(listOf("ゾロリのAあ", "ゾロリのBい", "ゾロリのCう"), result.map { it.title })
        assertEquals(listOf(day2, day2, day1), result.map { it.loanDate })
    }

    private fun entity(memberId: Long, tilcod: String, title: String, date: LocalDate) = ReadingRecordEntity(
        memberId = memberId,
        tilcod = tilcod,
        title = title,
        loanDate = date,
        library = "中央図書館",
        titleNormalized = title,
    )

    /** [ReadingRecordDao.search]は使わない前提のフェイク。呼ばれたら失敗させて回帰を検知する。 */
    private class FakeReadingRecordDao(private val entities: List<ReadingRecordEntity>) : ReadingRecordDao {
        override suspend fun getHistoryCheckpointKeys(memberId: Long): List<ReadingRecordKeyProjection> =
            throw UnsupportedOperationException("このテストでは使用しない")

        override suspend fun upsertAll(records: List<ReadingRecordEntity>) = Unit

        override suspend fun upsertHistoryCheckpoints(checkpoints: List<ReadingHistoryCheckpointEntity>) = Unit

        override fun observeAll(): Flow<List<ReadingRecordEntity>> = flowOf(entities)

        override suspend fun getAll(): List<ReadingRecordEntity> = entities

        override fun observeForMember(memberId: Long): Flow<List<ReadingRecordEntity>> =
            flowOf(entities.filter { it.memberId == memberId })

        override fun search(normalizedQuery: String, memberId: Long?): Flow<List<ReadingRecordEntity>> =
            throw UnsupportedOperationException("ReadingRecordRepositoryImpl.searchはDAOのsearchを使わない")

        override fun observeReadingInfo(tilcod: String): Flow<List<ReadingInfoProjection>> =
            throw UnsupportedOperationException("このテストでは使用しない")

        override suspend fun deleteForMember(memberId: Long) = Unit

        override suspend fun deleteHistoryCheckpointsForMember(memberId: Long) = Unit

        override suspend fun getAllHistoryCheckpoints(): List<ReadingHistoryCheckpointEntity> = emptyList()

        override suspend fun clearAllRecords() = Unit

        override suspend fun clearAllHistoryCheckpoints() = Unit
    }
}
