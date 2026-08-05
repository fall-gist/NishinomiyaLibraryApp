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
    // 全12館の対応表は Library.ALL_LIBRARIES が唯一の正本(2026-08-05に移動。値は変更していない)。
    override val libraries: List<Library> = Library.ALL_LIBRARIES

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
}
