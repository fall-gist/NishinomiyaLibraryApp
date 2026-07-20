package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.dao.NewArrivalDao
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryGateway
import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import com.fallgist.nishinomiyalibrary.domain.repository.NewArrivalRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class NewArrivalRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val newArrivalDao: NewArrivalDao,
    private val gateway: LibraryGateway,
) : NewArrivalRepository {
    override fun newArrivals(): Flow<List<NewArrival>> =
        newArrivalDao.observeAll().map { items -> items.map { it.toDomain() } }

    override suspend fun refresh() {
        database.replaceNewArrivals(gateway.newArrivals().map { it.toEntity() })
    }
}
