package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.dao.ClosedDayDao
import com.fallgist.nishinomiyalibrary.data.local.entity.ClosedDayEntity
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryGateway
import com.fallgist.nishinomiyalibrary.domain.model.ClosedDay
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.repository.CalendarRepository
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val closedDayDao: ClosedDayDao,
    private val gateway: LibraryGateway,
    private val clock: Clock,
) : CalendarRepository {
    override val libraries: List<Library> = LIBRARIES

    override fun closedDays(libraryCode: String): Flow<List<ClosedDay>> =
        closedDayDao.observeForLibrary(libraryCode).map { days -> days.map { it.toDomain() } }

    override suspend fun refreshClosedDays(libraryCode: String) {
        require(libraries.any { it.code == libraryCode }) { "未対応の図書館です" }
        database.replaceFutureClosedDays(
            libraryCode = libraryCode,
            today = LocalDate.now(clock),
            days = gateway.closedDays(libraryCode).map { ClosedDayEntity(libraryCode, it) },
        )
    }

    private companion object {
        val LIBRARIES = listOf(
            Library("001", "中央図書館"),
            Library("002", "北口図書館"),
            Library("003", "鳴尾図書館"),
            Library("004", "北部図書館"),
            Library("101", "越木岩分室"),
            Library("102", "若竹分室"),
            Library("103", "段上分室"),
            Library("104", "上ケ原分室"),
            Library("105", "甲東園分室"),
            Library("106", "高須分室"),
            Library("107", "山口分室"),
            Library("109", "義務教育学校"),
        )
    }
}
