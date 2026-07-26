package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LicsXpSession
import com.fallgist.nishinomiyalibrary.data.remote.openbd.BookMetadataGateway
import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import com.fallgist.nishinomiyalibrary.domain.repository.SearchRepository
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrl

@Singleton
class SearchRepositoryImpl @Inject constructor(
    private val gateway: LibraryGateway,
    private val bookMetadataGateway: BookMetadataGateway,
) : SearchRepository {
    override suspend fun search(keyword: String, page: Int): SearchPage = gateway.search(keyword, page)

    override suspend fun autocomplete(keyword: String): List<String> = gateway.autocomplete(keyword)

    override suspend fun isLendable(tilcod: String): Boolean? = gateway.isLendable(tilcod)

    override suspend fun bookDetail(tilcod: String): BookDetail = gateway.bookDetail(tilcod)

    /**
     * openBDが表紙を持たない(蔵書によっては `summary.cover` が空文字で返る)場合、
     * 図書館サイト自身が配信する表紙にフォールバックする。フォールバックURLは組み立てるだけで、
     * ここでは事前確認の通信は行わない(実際の画像取得はCoilが行う)。
     *
     * 既知の制約: 図書館サイトの `book/cover?isbn=` は、該当する表紙が無いISBNでも200で
     * 「画像なし」のプレースホルダ画像を返す(実測: 存在しないISBN `9999999999999` でも
     * 200・69043バイトのPNGが返る)。そのためアプリ側では「表紙なし」を判別できない。
     * 詳細は docs/site-research.md を参照。
     */
    override suspend fun coverUrl(isbn: String): String? =
        bookMetadataGateway.coverUrl(isbn) ?: libraryCoverUrl(isbn)

    private fun libraryCoverUrl(isbn: String): String =
        LicsXpSession.DEFAULT_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("book")
            .addPathSegment("cover")
            .addQueryParameter("isbn", isbn.filter { it.isDigit() })
            .build()
            .toString()
}
