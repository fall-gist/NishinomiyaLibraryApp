package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryGateway
import com.fallgist.nishinomiyalibrary.data.remote.openbd.BookMetadataGateway
import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import com.fallgist.nishinomiyalibrary.domain.repository.SearchRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepositoryImpl @Inject constructor(
    private val gateway: LibraryGateway,
    private val bookMetadataGateway: BookMetadataGateway,
) : SearchRepository {
    override suspend fun search(keyword: String, page: Int): SearchPage = gateway.search(keyword, page)

    override suspend fun autocomplete(keyword: String): List<String> = gateway.autocomplete(keyword)

    override suspend fun isLendable(tilcod: String): Boolean? = gateway.isLendable(tilcod)

    override suspend fun bookDetail(tilcod: String): BookDetail = gateway.bookDetail(tilcod)

    override suspend fun coverUrl(isbn: String): String? = bookMetadataGateway.coverUrl(isbn)
}
