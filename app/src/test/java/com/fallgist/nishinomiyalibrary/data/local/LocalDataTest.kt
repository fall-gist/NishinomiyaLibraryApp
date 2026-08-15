package com.fallgist.nishinomiyalibrary.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.fallgist.nishinomiyalibrary.data.local.entity.ClosedDayEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.LoanEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.NewArrivalEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationCartItemEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingHistoryCheckpointEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingRecordEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfItemEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.SyncLogEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.UserSummaryEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationRuleEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationTermEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationControlEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationLatestItemEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationLatestRunEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationPickupSubmissionEntity
import com.fallgist.nishinomiyalibrary.data.repository.AutoReservationRepositoryImpl
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationTermKind
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationControlStatus
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationControl
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationLatestItem
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationLatestRun
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationRule
import com.fallgist.nishinomiyalibrary.domain.model.ReservationPickupSubmissionOrigin
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
    /**
     * v9で`extendable`列を追加する前のloansテーブル形状。
     * 本物のLoanEntity(main)はv9で`extendable`を持つため、v7/v8を模したRoom DBでは
     * この凍結済みの形状を使わないと、移行前から列が存在してしまいMIGRATION_8_9のテストが成立しない。
     */
    @Entity(tableName = "loans")
    data class LegacyLoanEntity(
        @PrimaryKey(autoGenerate = true)
        val id: Long = 0,
        val memberId: Long,
        val title: String,
        val materialType: String,
        val lendingLibrary: String,
        val loanDate: LocalDate,
        val dueDate: LocalDate,
        val status: String,
        val tilcod: String = "",
    )

    @Dao
    interface LegacyLoanDao {
        @Insert
        suspend fun insert(loan: LegacyLoanEntity)

        @Query("SELECT * FROM loans")
        suspend fun getAll(): List<LegacyLoanEntity>
    }

    @Database(
        entities = [
            MemberEntity::class,
            LegacyLoanEntity::class,
            ReservationEntity::class,
            ShelfItemEntity::class,
            ShelfEntity::class,
            ClosedDayEntity::class,
            SyncLogEntity::class,
            UserSummaryEntity::class,
            ReadingRecordEntity::class,
            ReadingHistoryCheckpointEntity::class,
            NewArrivalEntity::class,
            ReservationCartItemEntity::class,
        ],
        version = 7,
        exportSchema = false,
    )
    @TypeConverters(LocalDateConverters::class)
    abstract class V7Database : RoomDatabase() {
        abstract fun memberDao(): com.fallgist.nishinomiyalibrary.data.local.dao.MemberDao
        abstract fun reservationDao(): com.fallgist.nishinomiyalibrary.data.local.dao.ReservationDao
    }

    /** v9で`extendable`が追加される直前(v8)のフルスキーマ。MIGRATION_8_9単独のRoom検証に使う。 */
    @Database(
        entities = [
            MemberEntity::class,
            LegacyLoanEntity::class,
            ReservationEntity::class,
            ShelfItemEntity::class,
            ShelfEntity::class,
            ClosedDayEntity::class,
            SyncLogEntity::class,
            UserSummaryEntity::class,
            ReadingRecordEntity::class,
            ReadingHistoryCheckpointEntity::class,
            NewArrivalEntity::class,
            ReservationCartItemEntity::class,
            AutoReservationRuleEntity::class,
            AutoReservationTermEntity::class,
            AutoReservationControlEntity::class,
            AutoReservationLatestRunEntity::class,
            AutoReservationLatestItemEntity::class,
            ReservationPickupSubmissionEntity::class,
        ],
        version = 8,
        exportSchema = false,
    )
    @TypeConverters(LocalDateConverters::class)
    abstract class V8Database : RoomDatabase() {
        abstract fun memberDao(): com.fallgist.nishinomiyalibrary.data.local.dao.MemberDao
        abstract fun legacyLoanDao(): LegacyLoanDao
    }

    @Test
    fun v7DatabaseMigratesToV8WithRoomValidationAndPreservesExistingRows() = runBlocking {
        val databaseName = "migration-room-${UUID.randomUUID()}.db"
        try {
            // schema export は無効で MigrationTestHelper を利用できないため、コミット済みv7と
            // 同じエンティティ集合のRoom DBを作成してから、実際のv8 Room DBで移行・検証する。
            val v7Database = Room.databaseBuilder(context, V7Database::class.java, databaseName)
                .allowMainThreadQueries()
                .build()
            val memberId = try {
                val id = v7Database.memberDao().insert(
                    MemberEntity(name = "v7利用者", colorHex = "#000000", cardNumber = "v7-card", sortOrder = 0),
                )
                v7Database.reservationDao().insert(
                    ReservationEntity(
                        memberId = id,
                        title = "v7予約資料",
                        materialType = "図書",
                        pickupLibrary = "中央図書館",
                        reservedDate = LocalDate.of(2030, 1, 2),
                        queuePosition = 3,
                        state = ReservationState.WAITING,
                        holdExpiryDate = null,
                        firstReadyNotifiedAt = 123L,
                        tilcod = "v7-tilcod",
                        cancelCode = "v7-cancel-code",
                    ),
                )
                id
            } finally {
                v7Database.close()
            }

            val v8Database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                .addMigrations(DatabaseMigrations.MIGRATION_7_8, DatabaseMigrations.MIGRATION_8_9)
                .allowMainThreadQueries()
                .build()
            try {
                // writableDatabase を開く時点でMigration実行後のRoomスキーマ検証が行われる。
                v8Database.openHelper.writableDatabase
                assertEquals("v7利用者", v8Database.memberDao().getById(memberId)?.name)
                val reservation = v8Database.reservationDao().getForMember(memberId).single()
                assertEquals("v7予約資料", reservation.title)
                assertEquals("v7-tilcod", reservation.tilcod)
                assertEquals("v7-cancel-code", reservation.cancelCode)
            } finally {
                v8Database.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun `v7からv8移行は自動予約と送信館記録の全表を作成する`() {
        val databaseName = "migration-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                  .name(databaseName)
                  .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // v7の既存データは今回の追加テーブルで失ってはならない。
                        database.execSQL("CREATE TABLE members (id INTEGER PRIMARY KEY NOT NULL, name TEXT NOT NULL)")
                        database.execSQL("CREATE TABLE reservations (id INTEGER PRIMARY KEY NOT NULL, title TEXT NOT NULL)")
                        database.execSQL("INSERT INTO members(id, name) VALUES (1, 'v7利用者')")
                        database.execSQL("INSERT INTO reservations(id, title) VALUES (1, 'v7予約')")
                    }
                    override fun onUpgrade(database: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        try {
            val sqlite = helper.writableDatabase
            DatabaseMigrations.MIGRATION_7_8.migrate(sqlite)
            val tables = sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertTrue(
                tables.containsAll(
                    setOf(
                        "auto_reservation_rules", "auto_reservation_terms", "auto_reservation_controls",
                        "auto_reservation_latest_run", "auto_reservation_latest_items", "reservation_pickup_submissions",
                    ),
                ),
            )
            sqlite.query("SELECT name FROM members WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("v7利用者", cursor.getString(0))
            }
            sqlite.query("SELECT title FROM reservations WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("v7予約", cursor.getString(0))
            }
            val indexes = sqlite.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertTrue(indexes.contains("index_auto_reservation_rules_sortOrder"))
            assertTrue(indexes.contains("index_auto_reservation_terms_ruleId"))
            assertTrue(indexes.contains("index_reservation_pickup_submissions_memberId"))
            sqlite.execSQL("PRAGMA foreign_keys=ON")
            sqlite.execSQL("INSERT INTO auto_reservation_rules(id, enabled, sortOrder) VALUES (1, 1, 0)")
            sqlite.execSQL("INSERT INTO auto_reservation_terms(ruleId, kind, sortOrder, original, normalized) VALUES (1, 'INCLUDE', 0, 'AI', 'ai')")
            sqlite.execSQL("DELETE FROM auto_reservation_rules WHERE id = 1")
            sqlite.query("SELECT COUNT(*) FROM auto_reservation_terms").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            sqlite.execSQL("INSERT INTO reservation_pickup_submissions(memberId, tilcod, pickupLibraryCode, origin) VALUES (1, 't', '106', 'CONFIRMED_SUBMISSION')")
            sqlite.execSQL("DELETE FROM members WHERE id = 1")
            sqlite.query("SELECT COUNT(*) FROM reservation_pickup_submissions").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun `v8からv9移行は延長可否列を既定falseで追加し既存の貸出行を保持する`() = runBlocking {
        val databaseName = "migration-loan-extendable-${UUID.randomUUID()}.db"
        try {
            // schema export は無効で MigrationTestHelper を利用できないため、コミット済みv8と
            // 同じエンティティ集合のRoom DBを作成してから、実際のv9 Room DBで移行・検証する。
            val v8Database = Room.databaseBuilder(context, V8Database::class.java, databaseName)
                .allowMainThreadQueries()
                .build()
            val memberId = try {
                val id = v8Database.memberDao().insert(
                    MemberEntity(name = "v8利用者", colorHex = "#000000", cardNumber = "v8-card", sortOrder = 0),
                )
                v8Database.legacyLoanDao().insert(
                    LegacyLoanEntity(
                        memberId = id,
                        title = "v8貸出資料",
                        materialType = "図書",
                        lendingLibrary = "本館",
                        loanDate = LocalDate.of(2026, 7, 1),
                        dueDate = LocalDate.of(2026, 7, 15),
                        status = "貸出中",
                        tilcod = "v8-tilcod",
                    ),
                )
                id
            } finally {
                v8Database.close()
            }

            // Room自身にv8→v9のMIGRATION_8_9を適用させ、ALTER後のスキーマが現行LoanEntityと
            // 完全一致することを実際のRoom検証(openHelper.writableDatabase)で確かめる。
            val v9Database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                .addMigrations(DatabaseMigrations.MIGRATION_8_9)
                .allowMainThreadQueries()
                .build()
            try {
                v9Database.openHelper.writableDatabase
                val loan = v9Database.loanDao().getAll().single()
                assertEquals(memberId, loan.memberId)
                assertEquals("v8貸出資料", loan.title)
                assertEquals("v8-tilcod", loan.tilcod)
                assertFalse(loan.extendable)
            } finally {
                v9Database.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun `自動予約ルールは削除時に語をCASCADEし完全な予約一覧だけが送信館記録を掃除する`() = runBlocking {
        val ruleId = database.autoReservationDao().insertRule(AutoReservationRuleEntity(enabled = true, sortOrder = 0))
        database.autoReservationDao().insertTerms(
            listOf(AutoReservationTermEntity(ruleId, AutoReservationTermKind.INCLUDE, 0, "AI", "ai")),
        )
        database.autoReservationDao().deleteRule(ruleId)
        assertTrue(database.autoReservationDao().getTerms(listOf(ruleId)).isEmpty())

        val memberId = database.memberDao().insert(member("送信館", 0))
        val submissions = database.reservationPickupSubmissionDao()
        submissions.upsert(
            ReservationPickupSubmissionEntity(memberId, "target", "106", ReservationPickupSubmissionOrigin.CONFIRMED_SUBMISSION),
        )
        database.replaceMemberSnapshot(memberId, emptyList(), emptyList(), emptyList(), emptyList(), summary(memberId, 0).copy(reservationCount = 0))
        assertNull(submissions.get(memberId, "target"))

        submissions.upsert(
            ReservationPickupSubmissionEntity(memberId, "incomplete", "106", ReservationPickupSubmissionOrigin.UNVERIFIED_SUBMISSION),
        )
        database.replaceMemberSnapshot(memberId, emptyList(), emptyList(), emptyList(), emptyList(), summary(memberId, 0).copy(reservationCount = 1))
        assertEquals("106", submissions.get(memberId, "incomplete")?.pickupLibraryCode)
    }

    @Test
    fun `取消済みだけの完全な予約一覧は送信館記録を削除する`() = runBlocking {
        val memberId = database.memberDao().insert(member("取消", 0))
        database.reservationPickupSubmissionDao().upsert(
            ReservationPickupSubmissionEntity(memberId, "cancelled", "106", ReservationPickupSubmissionOrigin.CONFIRMED_SUBMISSION),
        )

        database.replaceMemberSnapshot(
            memberId, emptyList(),
            listOf(reservation(memberId, "取消済み", ReservationState.CANCELLED).copy(tilcod = "cancelled")),
            emptyList(), emptyList(), summary(memberId, 0).copy(reservationCount = 0),
        )

        assertNull(database.reservationPickupSubmissionDao().get(memberId, "cancelled"))
    }

    @Test
    fun `自動予約の制御記録と直近履歴は期限遷移全置換確認を守る`() = runBlocking {
        val dao = database.autoReservationDao()
        val today = LocalDate.of(2030, 3, 1)
        dao.upsertControl(AutoReservationControlEntity(
            "prepared", today.minusMonths(1), today.plusMonths(1), AutoReservationControlStatus.PREPARED, 999,
        ))
        dao.upsertControl(AutoReservationControlEntity(
            "fallback", today.minusMonths(1), today.plusMonths(1), AutoReservationControlStatus.MEMBER_FALLBACK_PENDING, 1,
        ))
        dao.upsertControl(AutoReservationControlEntity(
            "expired", today.minusMonths(2), today, AutoReservationControlStatus.SUCCESS, null,
        ))

        assertEquals(1, dao.markPreparedControlsUnknown())
        assertEquals(AutoReservationControlStatus.UNKNOWN_AFTER_POST, dao.getControl("prepared")!!.status)
        assertNull(dao.getControl("prepared")!!.preparedMemberId)
        assertEquals(AutoReservationControlStatus.MEMBER_FALLBACK_PENDING, dao.getControl("fallback")!!.status)
        assertEquals(1, dao.deleteExpiredControls(today))
        assertNull(dao.getControl("expired"))

        val first = AutoReservationLatestRunEntity(runId = 1, completedAtEpochMillis = 1, summaryJson = "first", acknowledged = false)
        dao.replaceLatestRun(first, listOf(latestItem(1, "one")))
        assertEquals(1, dao.markLatestRunAcknowledged(expectedRunId = 1))
        assertTrue(dao.getLatestRun()!!.acknowledged)
        dao.replaceLatestRun(
            AutoReservationLatestRunEntity(runId = 2, completedAtEpochMillis = 2, summaryJson = "second", acknowledged = false),
            listOf(latestItem(2, "two")),
        )
        assertEquals(0, dao.markLatestRunAcknowledged(expectedRunId = 1))
        assertFalse(dao.getLatestRun()!!.acknowledged)
        assertEquals(1, dao.markLatestRunAcknowledged(expectedRunId = 2))
        assertTrue(dao.getLatestRun()!!.acknowledged)
        assertEquals(listOf("two"), dao.getLatestItems(2).map { it.tilcod })
        assertTrue(dao.getLatestItems(1).isEmpty())
    }

    @Test
    fun `直近履歴の置換はrunId一致とtilcod一意を要求する`() = runBlocking {
        val dao = database.autoReservationDao()
        val run = AutoReservationLatestRunEntity(runId = 7, completedAtEpochMillis = 7, summaryJson = "{}", acknowledged = false)
        assertTrue(runCatching { dao.replaceLatestRun(run, listOf(latestItem(8, "x"))) }.isFailure)
        assertTrue(runCatching { dao.replaceLatestRun(run, listOf(latestItem(7, "x"), latestItem(7, "x"))) }.isFailure)

        val repository = AutoReservationRepositoryImpl(database, dao)
        assertTrue(runCatching {
            repository.replaceLatestRun(
                AutoReservationLatestRun(7, 7, "{}", false, listOf(AutoReservationLatestItem(8, "x", "資料", "[]", "[]", "SUCCESS"))),
            )
        }.isFailure)
        assertTrue(runCatching {
            repository.replaceLatestRun(
                AutoReservationLatestRun(
                    7, 7, "{}", false,
                    listOf(
                        AutoReservationLatestItem(7, "x", "資料", "[]", "[]", "SUCCESS"),
                        AutoReservationLatestItem(7, "x", "資料", "[]", "[]", "SUCCESS"),
                    ),
                ),
            )
        }.isFailure)
    }

    @Test
    fun `Repositoryは制御記録の月跨ぎ期限と履歴のルールメンバー削除独立性を守る`() = runBlocking {
        val repository = AutoReservationRepositoryImpl(database, database.autoReservationDao())
        assertTrue(runCatching {
            repository.replaceRules(
                listOf(
                    AutoReservationRule(enabled = true, sortOrder = 0, includeTerms = listOf("AI")),
                    AutoReservationRule(enabled = true, sortOrder = 0, includeTerms = listOf("ML")),
                ),
            )
        }.isFailure)
        val firstCandidateDate = LocalDate.of(2032, 1, 31)
        repository.saveControl(
            AutoReservationControl(
                "month-end", firstCandidateDate, LocalDate.of(2032, 3, 31),
                AutoReservationControlStatus.MEMBER_FALLBACK_PENDING, 123,
            ),
        )
        // 再試行はfirstCandidateDate/expiresOnを変えられず、期限日に削除対象となる。
        assertTrue(runCatching {
            repository.saveControl(
                AutoReservationControl(
                    "month-end", firstCandidateDate, LocalDate.of(2032, 4, 1),
                    AutoReservationControlStatus.MEMBER_FALLBACK_PENDING, 456,
                ),
            )
        }.isFailure)
        assertEquals(1, repository.removeExpiredControls(LocalDate.of(2032, 3, 31)))

        val memberId = database.memberDao().insert(member("履歴の元メンバー", 0))
        repository.replaceRules(listOf(AutoReservationRule(enabled = true, sortOrder = 0, includeTerms = listOf("AI"))))
        repository.replaceLatestRun(
            AutoReservationLatestRun(
                runId = 10, completedAtEpochMillis = 10, summaryJson = "{}",
                acknowledged = false,
                items = listOf(AutoReservationLatestItem(10, "history", "資料", "[1]", "[$memberId]", "SUCCESS")),
            ),
        )
        repository.replaceRules(emptyList())
        database.deleteMemberAndLocalData(requireNotNull(database.memberDao().getById(memberId)))

        val latest = repository.latestRun().first()!!
        assertEquals(listOf("history"), latest.items.map { it.tilcod })
        assertEquals("[1]", latest.items.single().matchedRulesJson)
        assertEquals("[$memberId]", latest.items.single().attemptedMembersJson)
    }

    @Test
    fun `予約カートは重複を無視し追加順で公開しメンバー削除でCASCADEする`() = runBlocking {
        val memberId = database.memberDao().insert(member(name = "カート利用者", sortOrder = 0))
        val dao = database.reservationCartDao()
        val second = dao.insertIgnoreDuplicate(ReservationCartItemEntity(memberId = memberId, tilcod = "2", title = "後", writerLine = "著者", addedAtEpochMillis = 20))
        dao.insertIgnoreDuplicate(ReservationCartItemEntity(memberId = memberId, tilcod = "1", title = "先", writerLine = null, addedAtEpochMillis = 10))
        assertEquals(-1L, dao.insertIgnoreDuplicate(ReservationCartItemEntity(memberId = memberId, tilcod = "2", title = "重複", writerLine = null, addedAtEpochMillis = 30)))
        assertEquals(listOf("先", "後"), dao.observeAll().first().map { it.title })
        database.deleteReservationCartItems(listOf(second))
        assertEquals(listOf("先"), dao.observeAll().first().map { it.title })
        database.deleteMemberAndLocalData(requireNotNull(database.memberDao().getById(memberId)))
        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test
    fun `v5からv6移行は予約カートと外部キー索引を作成する`() {
        val databaseName = "migration-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                        database.execSQL("CREATE TABLE members (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
                        database.execSQL("INSERT INTO members(id, name) VALUES (1, '利用者')")
                    }
                    override fun onUpgrade(database: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val sqlite = helper.writableDatabase
            sqlite.execSQL("PRAGMA foreign_keys=ON")
            DatabaseMigrations.MIGRATION_5_6.migrate(sqlite)
            sqlite.execSQL("INSERT INTO reservation_cart_items(memberId, tilcod, title, writerLine, addedAtEpochMillis) VALUES (1, 'x', '本', NULL, 1)")
            sqlite.query("SELECT tilcod FROM reservation_cart_items").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("x", cursor.getString(0))
            }
            sqlite.execSQL("DELETE FROM members WHERE id = 1")
            sqlite.query("SELECT COUNT(*) FROM reservation_cart_items").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }
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
    fun `current circulation replacement replaces rows and preserves ready notification`() = runBlocking {
        val memberId = 41L
        database.loanDao().insert(loan(memberId, "old loan", LocalDate.of(2030, 1, 1)))
        database.reservationDao().insert(reservation(memberId, "same", firstReadyNotifiedAt = 456L))
        database.reservationDao().insert(reservation(memberId, "old reservation").copy(reservedDate = LocalDate.of(2030, 1, 2)))

        database.replaceCurrentLoans(memberId, listOf(loan(memberId, "new loan", LocalDate.of(2030, 2, 1))))
        database.replaceCurrentReservations(memberId, listOf(reservation(memberId, "same", firstReadyNotifiedAt = null)))

        assertEquals(listOf("new loan"), database.loanDao().getAll().filter { it.memberId == memberId }.map { it.title })
        val reservations = database.reservationDao().getForMember(memberId)
        assertEquals(listOf("same"), reservations.map { it.title })
        assertEquals(456L, reservations.single().firstReadyNotifiedAt)
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
    fun `v6からv7移行は予約にcancelCode列を空文字列で追加する`() {
        val databaseName = "migration-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
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
            DatabaseMigrations.MIGRATION_6_7.migrate(sqlite)
            sqlite.query("SELECT title, cancelCode FROM reservations").use { cursor ->
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
    fun `replaceMemberSnapshotはupdateShelvesがfalseなら本棚テーブルに触れず貸出等だけ置換する`() = runBlocking {
        // 本棚取得をスキップした同期(otherbookのselect無し等)で、誤って0件と断定しローカルの
        // 本棚を消さないための引数(docs/design/account-and-bookshelf-fixes.md §2)。
        val memberId = 21L
        database.loanDao().insert(loan(memberId, "旧貸出", LocalDate.of(2030, 5, 1)))
        database.shelfDao().insertAll(listOf(ShelfEntity(memberId, 1, "保持される棚")))
        database.shelfItemDao().insert(shelf(memberId, "keep-item"))
        database.userSummaryDao().insert(summary(memberId, loanCount = 1))

        database.replaceMemberSnapshot(
            memberId = memberId,
            loans = listOf(loan(memberId, "新貸出", LocalDate.of(2030, 6, 10))),
            reservations = emptyList(),
            shelves = emptyList(),
            shelfItems = emptyList(),
            summary = summary(memberId, loanCount = 1),
            updateShelves = false,
        )

        assertEquals(listOf("新貸出"), database.loanDao().observeForMember(memberId).first().map { it.title })
        assertEquals(listOf("保持される棚"), database.shelfDao().observeForMember(memberId).first().map { it.name })
        assertEquals(listOf("keep-item"), database.shelfItemDao().observeForMember(memberId).first().map { it.tilcod })
    }

    @Test
    fun `replaceShelfSnapshotは空棚を含めて置換し既存サマリの棚数だけを更新する`() = runBlocking {
        val memberId = 60L
        val otherMemberId = 61L
        database.shelfDao().insertAll(listOf(ShelfEntity(memberId, 1, "旧本棚"), ShelfEntity(otherMemberId, 1, "他人の棚")))
        database.shelfItemDao().insert(shelf(memberId, "old"))
        database.shelfItemDao().insert(shelf(otherMemberId, "keep"))
        database.userSummaryDao().insert(summary(memberId, loanCount = 7).copy(shelfCount = 1, reservationCount = 8, cartCount = 9))

        database.replaceShelfSnapshot(
            memberId = memberId,
            shelves = listOf(ShelfEntity(memberId, 2, "空棚"), ShelfEntity(memberId, 4, "資料あり")),
            shelfItems = listOf(ShelfItemEntity(memberId, 4, "new", "新資料", "新メモ", LocalDate.of(2030, 4, 1))),
        )

        assertEquals(listOf(2 to "空棚", 4 to "資料あり"), database.shelfDao().observeForMember(memberId).first().map { it.shelfNo to it.name })
        assertEquals(listOf("new"), database.shelfItemDao().observeForMember(memberId).first().map { it.tilcod })
        assertEquals(listOf("keep"), database.shelfItemDao().observeForMember(otherMemberId).first().map { it.tilcod })
        val summary = database.userSummaryDao().observeForMember(memberId).first()!!
        assertEquals(2, summary.shelfCount)
        assertEquals(7, summary.loanCount)
        assertEquals(8, summary.reservationCount)
        assertEquals(9, summary.cartCount)
    }

    @Test
    fun `replaceShelfSnapshotは不正な親棚参照で既存データを変更しない`() = runBlocking {
        val memberId = 62L
        database.shelfDao().insertAll(listOf(ShelfEntity(memberId, 1, "保持棚")))
        database.shelfItemDao().insert(shelf(memberId, "keep"))

        var rejected = false
        try {
            database.replaceShelfSnapshot(
                memberId = memberId,
                shelves = listOf(ShelfEntity(memberId, 2, "新棚")),
                shelfItems = listOf(ShelfItemEntity(memberId, 3, "invalid", "不正", "", LocalDate.of(2030, 5, 1))),
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals(listOf("保持棚"), database.shelfDao().observeForMember(memberId).first().map { it.name })
        assertEquals(listOf("keep"), database.shelfItemDao().observeForMember(memberId).first().map { it.tilcod })
        assertNull(database.userSummaryDao().observeForMember(memberId).first())
    }

    @Test
    fun `replaceShelfSnapshotは未作成のサマリを新規作成しない`() = runBlocking {
        val memberId = 63L

        database.replaceShelfSnapshot(
            memberId = memberId,
            shelves = listOf(ShelfEntity(memberId, 1, "サマリなし棚")),
            shelfItems = emptyList(),
        )

        assertEquals(listOf("サマリなし棚"), database.shelfDao().observeForMember(memberId).first().map { it.name })
        assertNull(database.userSummaryDao().observeForMember(memberId).first())
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

        settingsStore.update(AppSettings(7, 15, false, false, "107", 3, true))
        assertEquals(AppSettings(7, 15, false, false, "107", 3, true), settingsStore.settings.first())
        settingsStore.updateReturnReminderDaysBefore(7)
        assertEquals(7, settingsStore.settings.first().returnReminderDaysBefore)
        assertTrue(runCatching { settingsStore.updateReturnReminderDaysBefore(0) }.isFailure)
        assertTrue(runCatching { settingsStore.updateReturnReminderDaysBefore(8) }.isFailure)
        assertTrue(settingsStore.settings.first().diagnosticLogEnabled)
        settingsStore.updateDiagnosticLogEnabled(false)
        assertFalse(settingsStore.settings.first().diagnosticLogEnabled)
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

    private fun latestItem(runId: Long, tilcod: String) = AutoReservationLatestItemEntity(
        runId = runId,
        tilcod = tilcod,
        title = "資料",
        matchedRulesJson = "[]",
        attemptedMembersJson = "[]",
        outcome = "SUCCESS",
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
