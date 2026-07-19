package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.dao.ReadingRecordDao
import com.fallgist.nishinomiyalibrary.domain.model.ReadingInfo
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecordTitleNormalizer
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

    override fun search(query: String, memberId: Long?): Flow<List<ReadingRecord>> =
        readingRecordDao.search(ReadingRecordTitleNormalizer.normalize(query), memberId)
            .map { records -> records.map { it.toDomain() } }

    override fun hasRead(tilcod: String): Flow<List<ReadingInfo>> =
        readingRecordDao.observeReadingInfo(tilcod).map { infos -> infos.map { it.toDomain() } }
}
