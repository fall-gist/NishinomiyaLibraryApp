package com.fallgist.nishinomiyalibrary.data.local

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.fallgist.nishinomiyalibrary.data.local.entity.ClosedDayEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.LoanEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.NewArrivalEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingHistoryCheckpointEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingRecordEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfItemEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.SyncLogEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.UserSummaryEntity
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecordTitleNormalizer
import java.io.File
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LocalDataTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `Member DAOはCRUDを提供しFlowをsortOrder順に公開する`() = runBlocking {
        val laterId = database.memberDao().insert(member(name = "後", sortOrder = 2))
        val earlierId = database.memberDao().insert(member(name = "先", sortOrder = 1))

        assertEquals(listOf(earlierId, laterId), database.memberDao().observeAll().first().map { it.id })

        val updated = requireNotNull(database.memberDao().getById(laterId)).copy(sortOrder = 0)
        database.memberDao().update(updated)
        assertEquals(listOf(laterId, earlierId), database.memberDao().observeAll().first().map { it.id })

        database.memberDao().delete(updated)
        assertNull(database.memberDao().getById(laterId))
    }

    @Test
    fun `Loan DAOは期限順に公開し同名同期限の複数行を保持する`() = runBlocking {
        val memberId = 10L
        val sameDueDate = LocalDate.of(2030, 6, 10)
        database.loanDao().insert(loan(memberId, "同名資料", sameDueDate, lendingLibrary = "館A"))
        database.loanDao().insert(loan(memberId, "同名資料", sameDueDate, lendingLibrary = "館B"))
        database.loanDao().insert(loan(memberId, "後の期限", LocalDate.of(2030, 6, 20)))

        val loans = database.loanDao().observeForMember(memberId).first()
        assertEquals(3, loans.size)
        assertEquals(listOf(sameDueDate, sameDueDate, LocalDate.of(2030, 6, 20)), loans.map { it.dueDate })
        assertEquals(setOf("館A", "館B"), loans.filter { it.title == "同名資料" }.map { it.lendingLibrary }.toSet())

        val updated = loans.first().copy(status = "更新済み")
        database.loanDao().update(updated)
        assertEquals("更新済み", database.loanDao().observeForMember(memberId).first().first().status)
        database.loanDao().delete(updated)
        assertEquals(2, database.loanDao().observeForMember(memberId).first().size)
    }

    @Test
    fun `読書記録DAOは主キーupsertと正規化検索と既読情報をメンバー別に永続化する`() = runBlocking {
        val dao = database.readingRecordDao()
        val firstDate = LocalDate.of(2030, 6, 10)
        val secondDate = LocalDate.of(2030, 6, 11)
        dao.upsertAll(
            listOf(
                readingRecord(10L, "1000000000001", "ＡＢＣ　著者", firstDate, "中央図書館"),
                readingRecord(10L, "1000000000002", "別の記録", secondDate, "北口図書館"),
                readingRecord(20L, "1000000000001", "ABC 著者", secondDate, "鳴尾図書館"),
            ),
        )
        dao.upsertAll(listOf(readingRecord(10L, "1000000000001", "ＡＢＣ　著者（更新）", firstDate, "更新館")))
        dao.upsertHistoryCheckpoints(
            listOf(
                ReadingHistoryCheckpointEntity(10L, "1000000000001", firstDate),
                ReadingHistoryCheckpointEntity(10L, "1000000000002", secondDate),
            ),
        )

        assertEquals(3, dao.observeAll().first().size)
        assertEquals(listOf(secondDate, secondDate, firstDate), dao.observeAll().first().map { it.loanDate })
        assertEquals(
            listOf("ＡＢＣ　著者（更新）"),
            dao.search(ReadingRecordTitleNormalizer.normalize("ａｂｃ 著者"), memberId = 10L).first().map { it.title },
        )
        assertEquals(
            2,
            dao.search(ReadingRecordTitleNormalizer.normalize("ABC"), memberId = null).first().size,
        )
        assertEquals(
            listOf("北口図書館", "更新館"),
            dao.observeForMember(10L).first().map { it.library },
        )
        assertEquals(
            listOf("鳴尾図書館", "更新館"),
            dao.observeReadingInfo("1000000000001").first().map { it.library },
        )
        assertEquals(
            setOf("1000000000001" to firstDate, "1000000000002" to secondDate),
            dao.getHistoryCheckpointKeys(10L).map { it.tilcod to it.loanDate }.toSet(),
        )
    }

    @Test
    fun `同期スナップショットは既存の読書記録を削除しない`() = runBlocking {
        val memberId = 50L
        val retained = readingRecord(memberId, "1000000000050", "保持する記録", LocalDate.of(2030, 1, 1), "中央図書館")
        database.readingRecordDao().upsertAll(listOf(retained))

        database.replaceMemberSnapshot(
            memberId = memberId,
            loans = emptyList(),
            reservations = emptyList(),
            shelves = emptyList(),
            shelfItems = emptyList(),
            summary = summary(memberId, loanCount = 0),
            readingRecords = listOf(readingRecord(memberId, "1000000000051", "同期した記録", LocalDate.of(2030, 1, 2), "北口図書館")),
            readingHistoryCheckpoints = listOf(
                ReadingHistoryCheckpointEntity(memberId, "1000000000051", LocalDate.of(2030, 1, 2)),
            ),
        )

        assertEquals(
            listOf("1000000000051", "1000000000050"),
            database.readingRecordDao().observeForMember(memberId).first().map { it.tilcod },
        )
        assertEquals(
            listOf("1000000000051"),
            database.readingRecordDao().getHistoryCheckpointKeys(memberId).map { it.tilcod },
        )
    }

    @Test
    fun `新着資料DAOはtilcod主キーで全置換し出版年月の新しい順に公開する`() = runBlocking {
        database.replaceNewArrivals(
            listOf(
                newArrival("100", "古い本", published = "2026/04"),
                newArrival("200", "新しい本", published = "2026/06"),
            ),
        )
        assertEquals(
            listOf("新しい本", "古い本"),
            database.newArrivalDao().observeAll().first().map { it.title },
        )

        // 全置換: 前回分は消え、tilcod重複は最後の値で上書きされる
        database.replaceNewArrivals(
            listOf(
                newArrival("300", "別ジャンルの本", published = "2026/05"),
                newArrival("300", "同一tilcodの改題", published = "2026/05"),
            ),
        )
        val rows = database.newArrivalDao().observeAll().first()
        assertEquals(1, rows.size)
        assertEquals("同一tilcodの改題", rows.single().title)
    }

    @Test
    fun `v3からv4移行は新着資料テーブルを作成する`() {
        val databaseName = "migration-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(database: androidx.sqlite.db.SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(
                        database: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )

        try {
            val sqlite = helper.writableDatabase
            DatabaseMigrations.MIGRATION_3_4.migrate(sqlite)
            sqlite.execSQL(
                "INSERT INTO new_arrivals(tilcod, title, volume, author, publisher, " +
                    "publishedYearMonth, classification, lendable) " +
                    "VALUES ('1', '本', '', '著', '版元', '2026/06', 'F', 1)",
            )
            sqlite.query("SELECT tilcod, lendable FROM new_arrivals").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("1", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun `v4からv5移行は予約にtilcod列を空文字列で追加する`() {
        val databaseName = "migration-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                        database.execSQL(
                            "CREATE TABLE reservations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "title TEXT NOT NULL)",
                        )
                        database.execSQL("INSERT INTO reservations(title) VALUES ('移行前の予約')")
                    }

                    override fun onUpgrade(
                        database: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )

        try {
            val sqlite = helper.writableDatabase
            DatabaseMigrations.MIGRATION_4_5.migrate(sqlite)
            sqlite.query("SELECT title, tilcod FROM reservations").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("移行前の予約", cursor.getString(0))
                assertEquals("", cursor.getString(1))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun `v2からv3移行は既存貸出を保持し読書記録と履歴チェックポイントを作成する`() {
        val databaseName = "migration-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                        database.execSQL(
                            "CREATE TABLE loans (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL)",
                        )
                        database.execSQL("INSERT INTO loans(title) VALUES ('移行前の貸出')")
                    }

                    override fun onUpgrade(
                        database: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )

        try {
            val sqlite = helper.writableDatabase
            DatabaseMigrations.MIGRATION_2_3.migrate(sqlite)

            sqlite.query("SELECT title, tilcod FROM loans").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("移行前の貸出", cursor.getString(0))
                assertEquals("", cursor.getString(1))
            }
            val tables = sqlite.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN " +
                    "('reading_records', 'reading_history_checkpoints')",
            ).use { cursor ->
                generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toSet()
            }
            assertEquals(setOf("reading_records", "reading_history_checkpoints"), tables)
            val indexes = sqlite.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_reading_%'",
            ).use { cursor ->
                generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toSet()
            }
            assertTrue(indexes.contains("index_reading_records_titleNormalized"))
            assertTrue(indexes.contains("index_reading_history_checkpoints_memberId"))
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun `replaceMemberSnapshotは対象メンバーだけを全置換し予約通知時刻を順に保持する`() = runBlocking {
        val memberId = 20L
        database.loanDao().insert(loan(memberId, "削除対象", LocalDate.of(2030, 5, 1)))
        database.shelfDao().insertAll(listOf(ShelfEntity(memberId, 1, "旧本棚")))
        database.shelfItemDao().insert(shelf(memberId, "old-item"))
        database.userSummaryDao().insert(summary(memberId, loanCount = 1))
        database.reservationDao().insert(reservation(memberId, "同一予約", firstReadyNotifiedAt = 101L))
        database.reservationDao().insert(reservation(memberId, "同一予約", firstReadyNotifiedAt = 202L))

        database.replaceMemberSnapshot(
            memberId = memberId,
            loans = listOf(
                loan(memberId, "同名資料", LocalDate.of(2030, 6, 10), lendingLibrary = "館A"),
                loan(memberId, "同名資料", LocalDate.of(2030, 6, 10), lendingLibrary = "館B"),
            ),
            reservations = listOf(
                reservation(memberId, "同一予約", state = ReservationState.WAITING),
                reservation(memberId, "同一予約", state = ReservationState.READY),
            ),
            shelves = listOf(ShelfEntity(memberId, 1, "新本棚")),
            shelfItems = listOf(shelf(memberId, "new-item")),
            summary = summary(memberId, loanCount = 2),
        )

        val loans = database.loanDao().observeForMember(memberId).first()
        assertEquals(2, loans.size)
        assertEquals(setOf("館A", "館B"), loans.map { it.lendingLibrary }.toSet())
        assertEquals(
            listOf(101L, 202L),
            database.reservationDao().observeForMember(memberId).first().map { it.firstReadyNotifiedAt },
        )
        assertEquals(listOf("new-item"), database.shelfItemDao().observeForMember(memberId).first().map { it.tilcod })
        assertEquals(2, database.userSummaryDao().observeForMember(memberId).first()!!.loanCount)
    }

    @Test
    fun `replaceMemberSnapshotは異なるmemberIdの同期データを拒否する`() = runBlocking {
        var rejected = false

        try {
            database.replaceMemberSnapshot(
                memberId = 40L,
                loans = listOf(loan(41L, "不一致", LocalDate.of(2030, 9, 1))),
                reservations = emptyList(),
                shelves = emptyList(),
                shelfItems = emptyList(),
                summary = summary(40L, loanCount = 0),
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun `各DAOはメンバー単位の削除と残りエンティティのCRUDを提供する`() = runBlocking {
        val memberId = 30L
        val reservation = reservation(memberId, "予約")
        database.reservationDao().insert(reservation)
        val storedReservation = database.reservationDao().observeForMember(memberId).first().single()
        database.reservationDao().update(storedReservation.copy(pickupLibrary = "更新館"))
        assertEquals("更新館", database.reservationDao().observeForMember(memberId).first().single().pickupLibrary)
        database.reservationDao().deleteForMember(memberId)
        assertTrue(database.reservationDao().observeForMember(memberId).first().isEmpty())

        val item = shelf(memberId, "shelf-item")
        database.shelfDao().insertAll(listOf(ShelfEntity(memberId, 1, "本棚")))
        database.shelfItemDao().insert(item)
        database.shelfItemDao().update(item.copy(memo = "更新メモ"))
        assertEquals("更新メモ", database.shelfItemDao().observeForMember(memberId).first().single().memo)
        database.shelfItemDao().delete(item)
        assertTrue(database.shelfItemDao().observeForMember(memberId).first().isEmpty())

        val closedDay = ClosedDayEntity("106", LocalDate.of(2030, 7, 1))
        database.closedDayDao().insert(closedDay)
        assertEquals(closedDay, database.closedDayDao().observeForLibrary("106").first().single())
        database.closedDayDao().delete(closedDay)
        assertTrue(database.closedDayDao().observeForLibrary("106").first().isEmpty())

        val userSummary = summary(memberId, loanCount = 3)
        database.userSummaryDao().insert(userSummary)
        database.userSummaryDao().update(userSummary.copy(loanCount = 4))
        assertEquals(4, database.userSummaryDao().observeForMember(memberId).first()!!.loanCount)
        database.userSummaryDao().deleteForMember(memberId)
        assertNull(database.userSummaryDao().observeForMember(memberId).first())
    }

    @Test
    fun `未来の休館日は館ごとにトランザクションで置換する`() = runBlocking {
        val libraryCode = "106"
        val today = LocalDate.of(2030, 8, 10)
        database.closedDayDao().insert(ClosedDayEntity(libraryCode, today.minusDays(1)))
        database.closedDayDao().insert(ClosedDayEntity(libraryCode, today.plusDays(1)))

        database.replaceFutureClosedDays(
            libraryCode = libraryCode,
            today = today,
            days = listOf(
                ClosedDayEntity(libraryCode, today.minusDays(2)),
                ClosedDayEntity(libraryCode, today.plusDays(3)),
            ),
        )

        assertEquals(
            listOf(today.minusDays(1), today.plusDays(3)),
            database.closedDayDao().observeForLibrary(libraryCode).first().map { it.date },
        )
    }

    @Test
    fun `SyncLog DAOは最新の記録をFlowで公開する`() = runBlocking {
        val olderId = database.syncLogDao().insert(syncLog(startedAt = 10L))
        val latestId = database.syncLogDao().insert(syncLog(startedAt = 20L))

        assertEquals(latestId, database.syncLogDao().observeLatest().first()!!.id)

        val older = syncLog(startedAt = 10L).copy(id = olderId, succeeded = true)
        database.syncLogDao().update(older)
        database.syncLogDao().delete(older)
        assertEquals(latestId, database.syncLogDao().observeLatest().first()!!.id)
    }

    @Test
    fun `CredentialStoreは暗号化Prefsだけに保存しSettingsStoreは既定値と更新を公開する`() = runBlocking {
        val credentialValue = UUID.randomUUID().toString()
        val credentialStore = CredentialStore(context)
        credentialStore.savePassword(memberId = 77L, password = credentialValue)
        assertEquals(credentialValue, credentialStore.getPassword(77L))

        val encryptedBackingPreferences = context.getSharedPreferences(
            CredentialStore.PREFERENCES_FILE_NAME,
            Context.MODE_PRIVATE,
        )
        assertFalse(encryptedBackingPreferences.all.toString().contains(credentialValue))
        val encryptedBackingFile = File(
            context.applicationInfo.dataDir,
            "shared_prefs/${CredentialStore.PREFERENCES_FILE_NAME}.xml",
        )
        if (encryptedBackingFile.isFile) {
            assertFalse(encryptedBackingFile.readText().contains(credentialValue))
        }
        assertFalse(allRoomColumns().contains("password"))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settingsFile = File(context.filesDir, "settings-${UUID.randomUUID()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { settingsFile },
        )
        val settingsStore = SettingsStore(dataStore)
        assertEquals(AppSettings(), settingsStore.settings.first())

        settingsStore.update(AppSettings(7, 15, false, false, "107", 3))
        assertEquals(AppSettings(7, 15, false, false, "107", 3), settingsStore.settings.first())
        settingsStore.updateReturnReminderDaysBefore(7)
        assertEquals(7, settingsStore.settings.first().returnReminderDaysBefore)
        assertTrue(runCatching { settingsStore.updateReturnReminderDaysBefore(0) }.isFailure)
        assertTrue(runCatching { settingsStore.updateReturnReminderDaysBefore(8) }.isFailure)
        assertFalse(dataStore.data.first().asMap().values.toString().contains(credentialValue))
        assertFalse(
            context.getSharedPreferences("ordinary_settings", Context.MODE_PRIVATE)
                .all.toString().contains(credentialValue),
        )

        credentialStore.delete(77L)
        assertNull(credentialStore.getPassword(77L))
        scope.cancel()
    }

    @Test
    fun `本棚は同一資料を複数本棚に保持しJOIN名で読み出して同期時に全置換する`() = runBlocking {
        val targetId = database.memberDao().insert(member("対象", 0))
        val otherId = database.memberDao().insert(member("別メンバー", 1))
        database.shelfDao().insertAll(
            listOf(
                ShelfEntity(targetId, 1, "旧本棚"),
                ShelfEntity(otherId, 1, "別メンバー本棚"),
            ),
        )
        database.shelfItemDao().insertAll(
            listOf(
                ShelfItemEntity(targetId, 1, "OLD", "旧資料", "", LocalDate.of(2030, 1, 1)),
                ShelfItemEntity(otherId, 1, "KEEP", "別資料", "", LocalDate.of(2030, 1, 1)),
            ),
        )

        database.replaceMemberSnapshot(
            memberId = targetId,
            loans = emptyList(),
            reservations = emptyList(),
            shelves = listOf(
                ShelfEntity(targetId, 1, "一段目"),
                ShelfEntity(targetId, 2, "二段目"),
            ),
            shelfItems = listOf(
                ShelfItemEntity(targetId, 1, "SAME", "同じ資料", "", LocalDate.of(2030, 2, 1)),
                ShelfItemEntity(targetId, 2, "SAME", "同じ資料", "", LocalDate.of(2030, 2, 1)),
            ),
            summary = summary(targetId, loanCount = 0).copy(shelfCount = 2),
        )

        val targetItems = database.shelfItemDao().observeForMember(targetId).first()
        assertEquals(
            listOf(1 to "一段目", 2 to "二段目"),
            targetItems.map { it.shelfNo to it.shelfName },
        )
        assertEquals(listOf("SAME", "SAME"), targetItems.map { it.tilcod })
        assertEquals(listOf("一段目", "二段目"), database.shelfDao().observeForMember(targetId).first().map { it.name })
        assertEquals(listOf("KEEP"), database.shelfItemDao().observeForMember(otherId).first().map { it.tilcod })

        database.deleteMemberAndLocalData(requireNotNull(database.memberDao().getById(targetId)))
        assertTrue(database.shelfDao().observeForMember(targetId).first().isEmpty())
        assertTrue(database.shelfItemDao().observeForMember(targetId).first().isEmpty())
        assertEquals(listOf("KEEP"), database.shelfItemDao().observeForMember(otherId).first().map { it.tilcod })
    }

    private fun member(name: String, sortOrder: Int): MemberEntity = MemberEntity(
        name = name,
        colorHex = "#123456",
        cardNumber = "generated-${UUID.randomUUID()}",
        sortOrder = sortOrder,
    )

    private fun newArrival(
        tilcod: String,
        title: String,
        published: String,
    ): NewArrivalEntity = NewArrivalEntity(
        tilcod = tilcod,
        title = title,
        volume = "",
        author = "著者",
        publisher = "出版者",
        publishedYearMonth = published,
        classification = "F",
        lendable = null,
    )

    private fun loan(
        memberId: Long,
        title: String,
        dueDate: LocalDate,
        lendingLibrary: String = "図書館",
    ): LoanEntity = LoanEntity(
        memberId = memberId,
        title = title,
        materialType = "図書",
        lendingLibrary = lendingLibrary,
        loanDate = dueDate.minusDays(14),
        dueDate = dueDate,
        status = "貸出中",
    )

    private fun readingRecord(
        memberId: Long,
        tilcod: String,
        title: String,
        loanDate: LocalDate,
        library: String,
    ): ReadingRecordEntity = ReadingRecordEntity(
        memberId = memberId,
        tilcod = tilcod,
        title = title,
        loanDate = loanDate,
        library = library,
        titleNormalized = ReadingRecordTitleNormalizer.normalize(title),
    )

    private fun reservation(
        memberId: Long,
        title: String,
        state: ReservationState = ReservationState.READY,
        firstReadyNotifiedAt: Long? = null,
    ): ReservationEntity = ReservationEntity(
        memberId = memberId,
        title = title,
        materialType = "図書",
        pickupLibrary = "受取館",
        reservedDate = LocalDate.of(2030, 4, 1),
        queuePosition = 1,
        state = state,
        holdExpiryDate = LocalDate.of(2030, 4, 15),
        firstReadyNotifiedAt = firstReadyNotifiedAt,
    )

    private fun shelf(memberId: Long, tilcod: String): ShelfItemEntity = ShelfItemEntity(
        memberId = memberId,
        shelfNo = 1,
        tilcod = tilcod,
        title = "本棚資料",
        memo = "メモ",
        registeredDate = LocalDate.of(2030, 3, 1),
    )

    private fun summary(memberId: Long, loanCount: Int): UserSummaryEntity = UserSummaryEntity(
        memberId = memberId,
        shelfCount = 1,
        loanCount = loanCount,
        reservationCount = 1,
        cartCount = 0,
    )

    private fun syncLog(startedAt: Long): SyncLogEntity = SyncLogEntity(
        startedAtEpochMillis = startedAt,
        finishedAtEpochMillis = null,
        trigger = "MANUAL",
        succeeded = null,
        details = "結果",
    )

    private fun allRoomColumns(): Set<String> {
        val sqliteDatabase = database.openHelper.writableDatabase
        val tableNames = sqliteDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
        ).use { cursor ->
            buildList {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    add(cursor.getString(nameIndex))
                }
            }
        }

        return tableNames.flatMapTo(mutableSetOf()) { tableName ->
            sqliteDatabase.query("PRAGMA table_info($tableName)").use { cursor ->
                buildList {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) {
                        add(cursor.getString(nameIndex))
                    }
                }
            }
        }
    }
}
