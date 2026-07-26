package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.local.dao.NewArrivalDao
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryGateway
import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import com.fallgist.nishinomiyalibrary.domain.repository.NewArrivalRepository
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class NewArrivalRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val newArrivalDao: NewArrivalDao,
    private val gateway: LibraryGateway,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
) : NewArrivalRepository {
    override fun newArrivals(): Flow<List<NewArrival>> =
        newArrivalDao.observeAll().map { items -> items.map { it.toDomain() } }

    override suspend fun refresh() {
        database.replaceNewArrivals(gateway.newArrivals().map { it.toEntity() })
        // 全置換に成功したときだけ最終取得時刻を更新する(失敗時は前回の時刻を維持する)
        settingsStore.setLastNewArrivalFetchedAt(clock.millis())
    }

    override suspend fun lastFetchedAtEpochMillis(): Long? = settingsStore.getLastNewArrivalFetchedAt()

    override suspend fun hasCachedItems(): Boolean = newArrivalDao.observeAll().first().isNotEmpty()
}
