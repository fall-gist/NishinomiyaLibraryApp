package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.UserData
import com.fallgist.nishinomiyalibrary.data.remote.openbd.BookMetadataGateway
import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecordKey
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** テストで使わないメソッドは呼ばれた場合に気付けるよう例外にする。 */
private class UnusedLibraryGateway : LibraryGateway {
    override suspend fun search(keyword: String, page: Int): SearchPage = error("未使用")
    override suspend fun autocomplete(keyword: String): List<String> = error("未使用")
    override suspend fun isLendable(tilcod: String): Boolean? = error("未使用")
    override suspend fun bookDetail(tilcod: String): BookDetail = error("未使用")
    override suspend fun closedDays(libraryCode: String): List<LocalDate> = error("未使用")
    override suspend fun newArrivals(): List<NewArrival> = error("未使用")
    override suspend fun fetchUserData(
        cardNumber: String,
        password: String,
        knownReadingRecordKeys: Set<ReadingRecordKey>,
    ): UserData = error("未使用")
}

class SearchRepositoryImplTest {
    private val gateway = UnusedLibraryGateway()

    @Test
    fun `openBDが表紙URLを返すときはそのまま使う`() = runBlocking {
        val metadataGateway = object : BookMetadataGateway {
            override suspend fun coverUrl(isbn: String): String? = "https://cover.openbd.example/1234567890123"
        }
        val repository = SearchRepositoryImpl(gateway, metadataGateway)

        assertEquals("https://cover.openbd.example/1234567890123", repository.coverUrl("1234567890123"))
    }

    @Test
    fun `openBDがnullを返すときは図書館サイトの表紙URLへフォールバックする`() = runBlocking {
        val metadataGateway = object : BookMetadataGateway {
            override suspend fun coverUrl(isbn: String): String? = null
        }
        val repository = SearchRepositoryImpl(gateway, metadataGateway)

        assertEquals(
            "https://tosho.nishi.or.jp/licsxp-opac/book/cover?isbn=1234567890123",
            repository.coverUrl("1234567890123"),
        )
    }

    @Test
    fun `フォールバックURLの組み立てはISBNの数字以外を除去する`() = runBlocking {
        val metadataGateway = object : BookMetadataGateway {
            override suspend fun coverUrl(isbn: String): String? = null
        }
        val repository = SearchRepositoryImpl(gateway, metadataGateway)

        assertEquals(
            "https://tosho.nishi.or.jp/licsxp-opac/book/cover?isbn=9784000000000",
            repository.coverUrl("978-4-00-000000-0"),
        )
    }
}
