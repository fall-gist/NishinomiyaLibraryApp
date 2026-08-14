package com.fallgist.nishinomiyalibrary.data.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationControlEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationRuleEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationTermEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingHistoryCheckpointEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingRecordEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationCartItemEntity
import com.fallgist.nishinomiyalibrary.data.sync.SyncScheduleStarter
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationControlStatus
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationTermKind
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [BackupExporter]/[BackupImporter]の往復と、docs/design/settings-export-import.md §6・§9で
 * 要求される不変条件(id保持、自動採番の非衝突、拒否時の無変更、スケジュール再構成)を確認する。
 */
@RunWith(RobolectricTestRunner::class)
class BackupExportImportRoundTripTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var scheduleStarter: FakeScheduleStarter
    private lateinit var exporter: BackupExporter
    private lateinit var importer: BackupImporter
    private val dataStoreScopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStoreScopes += scope
        settingsStore = SettingsStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(context.filesDir, "backup-roundtrip-${UUID.randomUUID()}.preferences_pb") },
            ),
        )
        scheduleStarter = FakeScheduleStarter()
        exporter = BackupExporter(
            memberDao = database.memberDao(),
            settingsStore = settingsStore,
            autoReservationDao = database.autoReservationDao(),
            readingRecordDao = database.readingRecordDao(),
            reservationCartDao = database.reservationCartDao(),
            appVersion = "1.1",
            sourceDbVersion = 9,
        )
        importer = BackupImporter(database, settingsStore, scheduleStarter)
    }

    @After
    fun tearDown() {
        dataStoreScopes.forEach(CoroutineScope::cancel)
        database.close()
    }

    @Test
    fun `エクスポートしたJSONをインポートすると全テーブルが元の値のまま復元されidも保持される`() = runBlocking {
        seedSourceData()
        val exported = exporter.export()

        // 別端末を模して、インポート前の状態を変えておく(既存データは破棄されて置き換わるはず)。
        database.memberDao().insert(MemberEntity(id = 0, name = "移行先の既存メンバー", colorHex = "#000000", cardNumber = "9999", sortOrder = 0))
        settingsStore.updateSyncTime(3, 30)

        val result = importer.import(exported)
        assertTrue(result is BackupImportResult.Success)
        result as BackupImportResult.Success
        assertEquals(2, result.importedMemberCount)
        assertTrue(result.requestNotificationPermission)

        val members = database.memberDao().getAll()
        assertEquals(listOf(1L, 2L), members.map { it.id })
        assertEquals(listOf("一郎", "二郎"), members.map { it.name })

        val rules = database.autoReservationDao().getRules()
        assertEquals(listOf(5L), rules.map { it.id })
        val terms = database.autoReservationDao().getTerms(listOf(5L))
        assertEquals(1, terms.size)
        assertEquals("宮部みゆき", terms.first().original)

        val controls = database.autoReservationDao().getAllControls()
        assertEquals(1, controls.size)
        assertEquals("1000000000001", controls.first().tilcod)

        val readingRecords = database.readingRecordDao().observeAll().first()
        assertEquals(1, readingRecords.size)
        assertEquals(1L, readingRecords.first().memberId)

        val checkpoints = database.readingRecordDao().getAllHistoryCheckpoints()
        assertEquals(1, checkpoints.size)

        val cartItems = database.reservationCartDao().getAll()
        assertEquals(1, cartItems.size)
        assertEquals(2L, cartItems.first().memberId)

        val settings = settingsStore.settings.first()
        assertEquals(18, settings.syncHour)
        assertEquals(0, settings.syncMinute)

        assertEquals(1, scheduleStarter.calls)
    }

    @Test
    fun `インポート後に新規メンバーを追加してもid衝突は起きない(推定の確認)`() = runBlocking {
        seedSourceData()
        val exported = exporter.export()
        importer.import(exported)

        val newId = database.memberDao().insert(
            MemberEntity(id = 0, name = "新規メンバー", colorHex = "#123456", cardNumber = "5555", sortOrder = 99),
        )
        val existingIds = listOf(1L, 2L)
        assertFalse("新規採番idが既存の輸入済みidと衝突しました: $newId", newId in existingIds)

        // ルール側も同じ機構(autoGenerate=trueへの明示id挿入)に依存するため、同様に確認する。
        val newRuleId = database.autoReservationDao().insertRule(
            AutoReservationRuleEntity(id = 0, enabled = true, sortOrder = 99),
        )
        assertNotEquals(5L, newRuleId)
    }

    @Test
    fun `formatVersionが未対応の場合は拒否され既存データは一切変更されない`() = runBlocking {
        seedSourceData()
        val before = snapshot()

        val exported = exporter.export()
        val corrupted = exported.replaceFirst("\"formatVersion\": 1", "\"formatVersion\": 999")
        val result = importer.import(corrupted)

        assertTrue(result is BackupImportResult.Rejected)
        assertEquals(before, snapshot())
        assertEquals(0, scheduleStarter.calls)
    }

    @Test
    fun `値域違反の場合は拒否され既存データは一切変更されない`() = runBlocking {
        seedSourceData()
        val before = snapshot()

        val exported = exporter.export()
        val corrupted = exported.replaceFirst("\"syncHour\": 18", "\"syncHour\": 24")
        val result = importer.import(corrupted)

        assertTrue(result is BackupImportResult.Rejected)
        assertEquals(before, snapshot())
        assertEquals(0, scheduleStarter.calls)
    }

    @Test
    fun `参照整合性違反の場合は拒否され既存データは一切変更されない`() = runBlocking {
        seedSourceData()
        val before = snapshot()

        val exported = exporter.export()
        // カート項目のmemberIdを存在しないメンバーへ差し替える。
        val corrupted = exported.replaceFirst("\"memberId\": 2,\n            \"tilcod\": \"1000000000003\"", "\"memberId\": 999,\n            \"tilcod\": \"1000000000003\"")
        val result = importer.import(corrupted)

        assertTrue("拒否されるはずが結果は $result でした", result is BackupImportResult.Rejected)
        assertEquals(before, snapshot())
        assertEquals(0, scheduleStarter.calls)
    }

    private suspend fun seedSourceData() {
        database.memberDao().insert(MemberEntity(id = 1, name = "一郎", colorHex = "#3D6DB5", cardNumber = "1111", sortOrder = 0))
        database.memberDao().insert(MemberEntity(id = 2, name = "二郎", colorHex = "#C25278", cardNumber = "2222", sortOrder = 1))

        database.autoReservationDao().insertRule(AutoReservationRuleEntity(id = 5, enabled = true, sortOrder = 0))
        database.autoReservationDao().insertTerms(
            listOf(
                AutoReservationTermEntity(
                    ruleId = 5,
                    kind = AutoReservationTermKind.INCLUDE,
                    sortOrder = 0,
                    original = "宮部みゆき",
                    normalized = "宮部みゆき",
                ),
            ),
        )
        database.autoReservationDao().upsertControl(
            AutoReservationControlEntity(
                tilcod = "1000000000001",
                firstCandidateDate = LocalDate.of(2026, 8, 1),
                expiresOn = LocalDate.of(2026, 9, 1),
                status = AutoReservationControlStatus.SUCCESS,
                preparedMemberId = null,
            ),
        )
        database.readingRecordDao().upsertAll(
            listOf(
                ReadingRecordEntity(
                    memberId = 1,
                    tilcod = "1000000000002",
                    title = "火車",
                    loanDate = LocalDate.of(2026, 7, 1),
                    library = "中央図書館",
                    titleNormalized = "火車",
                ),
            ),
        )
        database.readingRecordDao().upsertHistoryCheckpoints(
            listOf(ReadingHistoryCheckpointEntity(memberId = 1, tilcod = "1000000000002", loanDate = LocalDate.of(2026, 7, 1))),
        )
        database.reservationCartDao().insertIgnoreDuplicate(
            ReservationCartItemEntity(
                memberId = 2,
                tilcod = "1000000000003",
                title = "模倣犯",
                writerLine = "宮部みゆき/著",
                addedAtEpochMillis = 1_700_000_000_000L,
            ),
        )
        settingsStore.updateSyncTime(18, 0)
        settingsStore.updateNotifyReturnReminder(true)
        settingsStore.updateNotifyPickupReady(true)
    }

    /** 拒否テストで「既存データが変更されていないこと」を比較するための簡易スナップショット。 */
    private suspend fun snapshot(): String = listOf(
        database.memberDao().getAll().toString(),
        database.autoReservationDao().getRules().toString(),
        database.autoReservationDao().getAllControls().toString(),
        database.readingRecordDao().observeAll().first().toString(),
        database.readingRecordDao().getAllHistoryCheckpoints().toString(),
        database.reservationCartDao().getAll().toString(),
        settingsStore.settings.first().toString(),
    ).joinToString("|")

    private class FakeScheduleStarter : SyncScheduleStarter {
        var calls = 0
            private set

        override suspend fun scheduleFromSettings() {
            calls += 1
        }
    }
}
