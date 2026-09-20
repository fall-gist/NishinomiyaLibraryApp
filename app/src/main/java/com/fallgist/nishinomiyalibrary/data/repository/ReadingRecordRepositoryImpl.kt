package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.dao.ReadingRecordDao
import com.fallgist.nishinomiyalibrary.domain.model.KeywordQuery
import com.fallgist.nishinomiyalibrary.domain.model.ReadingInfo
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord
import com.fallgist.nishinomiyalibrary.domain.repository.ReadingRecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadingRecordRepositoryImpl @Inject constructor(
    private val readingRecordDao: ReadingRecordDao,
) : ReadingRecordRepository {
    override fun records(memberId: Long?): Flow<List<ReadingRecord>> = when (memberId) {
        null -> readingRecordDao.observeAll()
        else -> readingRecordDao.observeForMember(memberId)
    }.map { records -> records.map { it.toDomain() } }

    /**
     * 単語AND検索(`docs/design/reading-records-search.md` §2・§3.1)。可変個のAND条件はRoomの
     * `@Query`では組めないため、[records]を購読してメモリ上で[KeywordQuery]により絞り込む。
     * 並び順は[records]（DAOが保証する`loanDate DESC, title ASC`）のまま保つ。
     */
    override fun search(query: String, memberId: Long?): Flow<List<ReadingRecord>> {
        val terms = KeywordQuery.terms(query)
        return records(memberId).map { records ->
            records.filter { record -> KeywordQuery.matches(record.title, terms) }
        }
    }

    override fun hasRead(tilcod: String): Flow<List<ReadingInfo>> =
        readingRecordDao.observeReadingInfo(tilcod).map { infos -> infos.map { it.toDomain() } }
}
