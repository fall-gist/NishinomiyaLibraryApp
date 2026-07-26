package com.fallgist.nishinomiyalibrary.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryGateway
import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.domain.model.Holding
import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecordKey
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** [NewArrivalRepositoryImpl.refresh]の最終取得時刻の保存/未更新を検証する。 */
@RunWith(RobolectricTestRunner::class)
class NewArrivalRepositoryImplTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private val dataStoreScopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        dataStoreScopes.forEach(CoroutineScope::cancel)
        database.close()
    }

    @Test
    fun `全置換に成功したときだけ最終取得時刻を更新する`() = runBlocking {
        val settingsStore = settingsStore()
        val clock = fixedClock("2030-05-03T12:00:00Z")
        val gateway = FakeNewArrivalGateway(listOf(newArrival("1")))
        val repository = NewArrivalRepositoryImpl(database, database.newArrivalDao(), gateway, settingsStore, clock)

        assertNull(repository.lastFetchedAtEpochMillis())
        assertTrue(!repository.hasCachedItems())

        repository.refresh()

        assertEquals(clock.millis(), repository.lastFetchedAtEpochMillis())
        assertTrue(repository.hasCachedItems())
        assertEquals(listOf("1"), repository.newArrivals().first().map { it.tilcod })

        // 失敗時は前回の最終取得時刻を維持する
        gateway.shouldFail = true
        val secondClock = fixedClock("2030-05-04T12:00:00Z")
        val repositoryWithSecondClock =
            NewArrivalRepositoryImpl(database, database.newArrivalDao(), gateway, settingsStore, secondClock)
        try {
            repositoryWithSecondClock.refresh()
            throw AssertionError("失敗するはず")
        } catch (_: IllegalStateException) {
            // 期待どおりの失敗
        }
        assertEquals(clock.millis(), repository.lastFetchedAtEpochMillis())
    }

    private fun settingsStore(): SettingsStore {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStoreScopes += scope
        return SettingsStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(context.filesDir, "newarrival-repo-${UUID.randomUUID()}.preferences_pb") },
            ),
        )
    }

    private fun fixedClock(instant: String): Clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)

    private fun newArrival(tilcod: String) = NewArrival(
        tilcod = tilcod,
        title = "書名$tilcod",
        volume = "",
        author = "",
        publisher = "",
        publishedYearMonth = "",
        classification = "",
        lendable = null,
    )

    private class FakeNewArrivalGateway(
        private val items: List<NewArrival>,
    ) : LibraryGateway {
        var shouldFail = false

        override suspend fun search(keyword: String, page: Int) = SearchPage(emptyList(), 0, false)

        override suspend fun autocomplete(keyword: String): List<String> = emptyList()

        override suspend fun isLendable(tilcod: String): Boolean? = null

        override suspend fun bookDetail(tilcod: String): BookDetail = BookDetail(
            tilcod,
            emptyMap(),
            null,
            emptyList<Holding>(),
            0,
            0,
            0,
        )

        override suspend fun closedDays(libraryCode: String): List<LocalDate> = emptyList()

        override suspend fun newArrivals(): List<NewArrival> {
            if (shouldFail) throw IllegalStateException("取得失敗")
            return items
        }

        override suspend fun fetchUserData(
            cardNumber: String,
            password: String,
            knownReadingRecordKeys: Set<ReadingRecordKey>,
        ) = throw UnsupportedOperationException("このテストでは使用しない")
    }
}
