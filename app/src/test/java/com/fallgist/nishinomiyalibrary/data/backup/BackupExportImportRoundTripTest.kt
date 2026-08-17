package com.fallgist.nishinomiyalibrary.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationControlEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationLatestItemEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationLatestRunEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationRuleEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationTermEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ClosedDayEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.LoanEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.NewArrivalEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingHistoryCheckpointEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingRecordEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationCartItemEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationPickupSubmissionEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfItemEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.SyncLogEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.UserSummaryEntity
import com.fallgist.nishinomiyalibrary.data.sync.SyncScheduleStarter
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationControlStatus
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationTermKind
import com.fallgist.nishinomiyalibrary.domain.model.ReservationPickupSubmissionOrigin
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import java.io.File
import java.io.IOException
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
import org.junit.Assert.assertNull
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
    private lateinit var credentialStore: CredentialStore
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
        credentialStore = CredentialStore(context)
        scheduleStarter = FakeScheduleStarter()
        exporter = BackupExporter(
            database = database,
            settingsStore = settingsStore,
            credentialStore = credentialStore,
            appVersion = "1.1",
            sourceDbVersion = 9,
        )
        importer = BackupImporter(database, settingsStore, scheduleStarter, credentialStore)
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

    @Test
    fun `インポート時にCredentialStoreが全消去される(エクスポートファイルに含まれない残留パスワードは消える)`() = runBlocking {
        // エクスポートファイルに含まれない、この端末だけの残留パスワード(id=99は移行対象に無い)。
        credentialStore.savePassword(99L, "無関係な残留パスワード")
        seedSourceData()
        val exported = exporter.export()

        val result = importer.import(exported)
        assertTrue("Successになるはずが $result でした", result is BackupImportResult.Success)

        assertNull(
            "インポート後もCredentialStoreに無関係な残留パスワードが残っています(id衝突で別人に使われる恐れ)",
            credentialStore.getPassword(99L),
        )
    }

    @Test
    fun `エクスポートしたパスワードはインポート後に同じ平文へ復元される(§4)`() = runBlocking {
        credentialStore.savePassword(1L, "いちろうのダミーパスワード")
        credentialStore.savePassword(2L, "じろうのダミーパスワード")
        seedSourceData()
        val exported = exporter.export()

        assertTrue(
            "エクスポートJSONにpasswordEncrypted=trueが含まれていません(平文のまま出力されている疑い)",
            exported.contains("\"passwordEncrypted\": true"),
        )
        assertFalse(
            "エクスポートJSONに平文パスワードが含まれています",
            exported.contains("いちろうのダミーパスワード") || exported.contains("じろうのダミーパスワード"),
        )

        val result = importer.import(exported)
        assertTrue("Successになるはずが $result でした", result is BackupImportResult.Success)
        result as BackupImportResult.Success
        assertEquals(0, result.passwordRestoreFailedCount)

        assertEquals("いちろうのダミーパスワード", credentialStore.getPassword(1L))
        assertEquals("じろうのダミーパスワード", credentialStore.getPassword(2L))
    }

    @Test
    fun `復号に失敗したパスワードはそのメンバーだけ未設定になりインポート全体は成功する(§4)`() = runBlocking {
        credentialStore.savePassword(1L, "いちろうのダミーパスワード")
        credentialStore.savePassword(2L, "じろうのダミーパスワード")
        seedSourceData()
        val exported = exporter.export()

        // id=1のpasswordだけ壊れたBase64へ差し替え、id=2は正常な値のまま残す。
        val corrupted = corruptFirstMemberPassword(exported)

        val result = importer.import(corrupted)
        assertTrue("Successになるはずが $result でした", result is BackupImportResult.Success)
        result as BackupImportResult.Success
        assertEquals(1, result.passwordRestoreFailedCount)

        assertNull("復号失敗のメンバーはパスワード未設定のはずです", credentialStore.getPassword(1L))
        assertEquals(
            "復号に成功した他メンバーのパスワードまで失われています",
            "じろうのダミーパスワード",
            credentialStore.getPassword(2L),
        )
    }

    @Test
    fun `CredentialStore全消去に失敗した場合はパスワード書き込みだけがスキップされる(§6_3)`() = runBlocking {
        // エクスポート元にはパスワードを設定しておく(正常なcredentialStoreを使って書き出す)。
        credentialStore.savePassword(1L, "いちろうのダミーパスワード")
        credentialStore.savePassword(2L, "じろうのダミーパスワード")
        seedSourceData()
        val exported = exporter.export()

        // インポート先のCredentialStoreは全消去を含む全操作が失敗する。§6.3の実装が正しければ
        // clearAll失敗を理由にパスワード書き込みの試行自体をスキップするため、savePasswordは
        // 一度も呼ばれず、passwordRestoreFailedCountは0のままになる(「試みて失敗した」件数では
        // ない)。もし誤って書き込みを試みれば、failingCredentialStoreへのsavePasswordが例外を
        // 投げ、2件とも復元失敗としてカウントされてしまうはずである。
        val failingCredentialStore = CredentialStore(FailingSharedPreferencesContext(context))
        val localScheduleStarter = FakeScheduleStarter()
        val warningImporter = BackupImporter(database, settingsStore, localScheduleStarter, failingCredentialStore)

        val result = warningImporter.import(exported)
        assertTrue("AppliedWithWarningになるはずが $result でした", result is BackupImportResult.AppliedWithWarning)
        result as BackupImportResult.AppliedWithWarning

        assertEquals(
            "clearAll失敗時にパスワード書き込みが試みられ、復元失敗としてカウントされています" +
                "(§6.3: 書き込み自体をスキップすべき)",
            0,
            result.passwordRestoreFailedCount,
        )

        // 設定書き込みと同期スケジュール再構成は、そのときも実行される(§6.3)。
        assertEquals(1, localScheduleStarter.calls)
        val settingsAfter = settingsStore.settings.first()
        assertEquals(18, settingsAfter.syncHour)

        val members = database.memberDao().getAll()
        assertEquals(listOf(1L, 2L), members.map { it.id })
    }

    /** [BackupPayload]のmembers配列先頭要素のpasswordだけを壊れたBase64へ差し替える(テスト専用)。 */
    private fun corruptFirstMemberPassword(json: String): String {
        val passwordMarker = "\"password\": \""
        val start = json.indexOf(passwordMarker)
        require(start >= 0) { "テスト前提が崩れています: passwordフィールドが見つかりません" }
        val valueStart = start + passwordMarker.length
        val valueEnd = json.indexOf('"', valueStart)
        return json.substring(0, valueStart) + "***not-valid-base64***" + json.substring(valueEnd)
    }

    @Test
    fun `メンバー依存キャッシュは削除されclosed_daysとnew_arrivalsは残る(§6_1、削除範囲が広がりすぎたら落ちる)`() = runBlocking {
        seedSourceData()
        val exported = exporter.export()

        // インポート前の"旧端末"の残骸として、メンバー依存キャッシュとメンバー非依存キャッシュを両方入れておく。
        database.loanDao().insert(
            LoanEntity(
                memberId = 1, title = "旧端末の貸出", materialType = "図書", lendingLibrary = "中央図書館",
                loanDate = LocalDate.of(2026, 1, 1), dueDate = LocalDate.of(2026, 1, 15), status = "貸出中",
            ),
        )
        database.reservationDao().insert(
            ReservationEntity(
                memberId = 1, title = "旧端末の予約", materialType = "図書", pickupLibrary = "中央図書館",
                reservedDate = LocalDate.of(2026, 1, 1), queuePosition = 1, state = ReservationState.WAITING,
                holdExpiryDate = null, firstReadyNotifiedAt = null,
            ),
        )
        database.shelfDao().insertAll(listOf(ShelfEntity(memberId = 1, shelfNo = 1, name = "旧端末の本棚")))
        database.shelfItemDao().insert(
            ShelfItemEntity(
                memberId = 1, shelfNo = 1, tilcod = "9000000000001", title = "旧端末の本棚項目",
                memo = "", registeredDate = LocalDate.of(2026, 1, 1),
            ),
        )
        database.userSummaryDao().insert(UserSummaryEntity(memberId = 1, shelfCount = 1, loanCount = 1, reservationCount = 1, cartCount = 1))
        database.syncLogDao().insert(SyncLogEntity(startedAtEpochMillis = 1L, finishedAtEpochMillis = 2L, trigger = "manual", succeeded = true, details = ""))
        database.autoReservationDao().upsertLatestRun(AutoReservationLatestRunEntity(runId = 1L, completedAtEpochMillis = 1L, summaryJson = "{}", acknowledged = false))
        database.autoReservationDao().insertLatestItems(
            listOf(AutoReservationLatestItemEntity(runId = 1L, tilcod = "9000000000001", title = "t", matchedRulesJson = "[]", attemptedMembersJson = "[]", outcome = "SUCCESS")),
        )
        database.closedDayDao().insert(ClosedDayEntity(libraryCode = "106", date = LocalDate.of(2026, 1, 1)))
        database.newArrivalDao().insertAll(
            listOf(NewArrivalEntity(tilcod = "9000000000001", title = "t", volume = "", author = "", publisher = "", publishedYearMonth = "2026-01", classification = "", lendable = null)),
        )

        val result = importer.import(exported)
        assertTrue("Successになるはずが $result でした", result is BackupImportResult.Success)

        assertTrue("loansが残っています", database.loanDao().getAll().isEmpty())
        assertTrue("reservationsが残っています", database.reservationDao().getAll().isEmpty())
        assertTrue("shelvesが残っています", database.shelfDao().observeForMember(1).first().isEmpty())
        assertTrue("shelf_itemsが残っています", database.shelfItemDao().observeForMember(1).first().isEmpty())
        assertTrue("user_summariesが残っています", database.userSummaryDao().observeAll().first().isEmpty())
        assertNull("sync_logsが残っています", database.syncLogDao().observeLatest().first())
        assertEquals("auto_reservation_latest_runが残っています", null, database.autoReservationDao().getLatestRun())
        assertTrue("auto_reservation_latest_itemsが残っています", database.autoReservationDao().getLatestItems(1L).isEmpty())

        // メンバー非依存のキャッシュは削除範囲外(§6.1)。ここが崩れたら削除範囲が広がりすぎている。
        assertEquals(1, database.closedDayDao().observeForLibrary("106").first().size)
        assertEquals(1, database.newArrivalDao().observeAll().first().size)
    }

    @Test
    fun `検証拒否は無変更のRejectedに、Room確定後の後処理失敗は変更済みのAppliedWithWarningになる(対になるケースを固定・進行指示15)`() = runBlocking {
        // ケース1: 検証で拒否される入力 → 既存データは一切変更されない。
        seedSourceData()
        val beforeRejected = snapshot()
        val exported = exporter.export()
        val corrupted = exported.replaceFirst("\"formatVersion\": 1", "\"formatVersion\": 999")

        val rejectedResult = importer.import(corrupted)
        assertTrue("拒否ケースがRejectedでない: $rejectedResult", rejectedResult is BackupImportResult.Rejected)
        assertEquals("拒否ケースで既存データが変更されました", beforeRejected, snapshot())

        // ケース2: 正当な入力でRoomトランザクションは確定するが、後処理(スケジュール再構成)が失敗する。
        // 「送っていないと確定した失敗」と「送った後の成否不明」を同じ型に丸めないことを固定する。
        val failingScheduleStarter = FailingScheduleStarter()
        val warningImporter = BackupImporter(database, settingsStore, failingScheduleStarter, credentialStore)

        val warningResult = warningImporter.import(exported)
        assertTrue(
            "後処理失敗ケースがAppliedWithWarningでない: $warningResult",
            warningResult is BackupImportResult.AppliedWithWarning,
        )
        // Roomは既に確定しているため、データは置き換わっているはず(Rejectedと違って無変更ではない)。
        val membersAfterWarning = database.memberDao().getAll()
        assertEquals("後処理失敗時にRoomのデータが置き換わっていません", listOf(1L, 2L), membersAfterWarning.map { it.id })
    }

    @Test
    fun `CredentialStore全消去に失敗しても後続の設定書き込みと同期スケジュール再構成は行われる(§6_3、第1_7段)`() = runBlocking {
        seedSourceData()
        val exported = exporter.export()

        val failingCredentialStore = CredentialStore(FailingSharedPreferencesContext(context))
        val localScheduleStarter = FakeScheduleStarter()
        val warningImporter = BackupImporter(database, settingsStore, localScheduleStarter, failingCredentialStore)

        val result = warningImporter.import(exported)
        assertTrue("AppliedWithWarningになるはずが $result でした", result is BackupImportResult.AppliedWithWarning)
        result as BackupImportResult.AppliedWithWarning

        assertTrue(
            "パスワード消去失敗の案内でない: ${result.message}",
            result.message.contains("パスワードが残っている可能性があります"),
        )
        assertFalse(
            "パスワード消去失敗が同期の再設定失敗として案内されています(利用者が誤った対処へ誘導される): ${result.message}",
            result.message.contains("自動同期の再設定"),
        )
        // §6.3・第1.7段: 全消去が失敗しても、独立した後続処理(設定書き込み・スケジュール再構成)は
        // スキップせず実行する。第1.6段はここで先頭の失敗を理由に以降をスキップしていた欠陥がある。
        assertEquals(
            "パスワード消去失敗後も後続の同期スケジュール再構成が呼ばれるはずです",
            1,
            localScheduleStarter.calls,
        )
        val settingsAfter = settingsStore.settings.first()
        assertEquals("パスワード消去失敗後も設定は書き込まれるはずです", 18, settingsAfter.syncHour)

        // Roomトランザクションは確定済みなので、データ自体は適用されているはず。
        val members = database.memberDao().getAll()
        assertEquals(listOf(1L, 2L), members.map { it.id })
    }

    @Test
    fun `設定の書き込みに失敗しても同期スケジュールは再構成される(§6_3、第1_7段)`() = runBlocking {
        seedSourceData()
        val exported = exporter.export()

        val brokenScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStoreScopes += brokenScope
        // produceFileがディレクトリを返すようにし、書き込み(FileOutputStream)を必ず失敗させる。
        // 存在しないパスはDataStoreが親ディレクトリを自動作成するため失敗させられない。
        val brokenTarget = File(context.filesDir, "broken-settings-${UUID.randomUUID()}").apply { mkdirs() }
        val brokenSettingsStore = SettingsStore(
            PreferenceDataStoreFactory.create(
                scope = brokenScope,
                produceFile = { brokenTarget },
            ),
        )
        val localScheduleStarter = FakeScheduleStarter()
        val warningImporter = BackupImporter(database, brokenSettingsStore, localScheduleStarter, credentialStore)

        val result = warningImporter.import(exported)
        assertTrue("AppliedWithWarningになるはずが $result でした", result is BackupImportResult.AppliedWithWarning)
        result as BackupImportResult.AppliedWithWarning

        assertTrue(
            "設定書き込み失敗の案内でない: ${result.message}",
            result.message.contains("設定の保存に失敗しました"),
        )
        // §6.3・第1.7段: 設定書き込みが失敗しても、独立した同期スケジュール再構成はスキップせず実行する。
        assertEquals(
            "設定書き込み失敗後も同期スケジュール再構成が呼ばれるはずです",
            1,
            localScheduleStarter.calls,
        )
    }

    @Test
    fun `複数の後処理が失敗したときはすべてが案内に含まれる(§6_3、第1_7段)`() = runBlocking {
        seedSourceData()
        val exported = exporter.export()

        // CredentialStoreの全消去と同期スケジュールの再構成の両方を失敗させる。
        // 設定書き込みは成功させ、成功した後処理の案内が紛れ込まないことも合わせて確認する。
        val failingCredentialStore = CredentialStore(FailingSharedPreferencesContext(context))
        val failingScheduleStarter = FailingScheduleStarter()
        val warningImporter = BackupImporter(database, settingsStore, failingScheduleStarter, failingCredentialStore)

        val result = warningImporter.import(exported)
        assertTrue("AppliedWithWarningになるはずが $result でした", result is BackupImportResult.AppliedWithWarning)
        result as BackupImportResult.AppliedWithWarning

        assertTrue(
            "パスワード消去失敗の案内が含まれていません: ${result.message}",
            result.message.contains("パスワードが残っている可能性があります"),
        )
        assertTrue(
            "スケジュール再構成失敗の案内が含まれていません: ${result.message}",
            result.message.contains("自動同期の再設定に失敗しました"),
        )
        assertFalse(
            "成功したはずの設定書き込みの失敗案内が紛れ込んでいます: ${result.message}",
            result.message.contains("設定の保存に失敗しました"),
        )
        // 設定書き込み自体は成功しているはず。
        val settingsAfter = settingsStore.settings.first()
        assertEquals("成功したはずの設定書き込みが行われていません", 18, settingsAfter.syncHour)
    }

    @Test
    fun `同期スケジュールの再構成に失敗した場合は同期時刻を開き直す案内になる(§6_3)`() = runBlocking {
        seedSourceData()
        val exported = exporter.export()

        val failingScheduleStarter = FailingScheduleStarter()
        val warningImporter = BackupImporter(database, settingsStore, failingScheduleStarter, credentialStore)

        val result = warningImporter.import(exported)
        assertTrue("AppliedWithWarningになるはずが $result でした", result is BackupImportResult.AppliedWithWarning)
        result as BackupImportResult.AppliedWithWarning

        assertTrue(
            "スケジュール再構成失敗の案内でない: ${result.message}",
            result.message.contains("自動同期の再設定に失敗しました"),
        )
    }

    @Test
    fun `reservation_pickup_submissionsは外部キーCASCADEで消える(§6_1、§9)`() = runBlocking {
        seedSourceData()
        val exported = exporter.export()

        database.reservationPickupSubmissionDao().upsert(
            ReservationPickupSubmissionEntity(
                memberId = 1,
                tilcod = "9000000000002",
                pickupLibraryCode = "106",
                origin = ReservationPickupSubmissionOrigin.CONFIRMED_SUBMISSION,
            ),
        )
        assertEquals(
            "テスト前提が崩れています(投入した行が見当たりません)",
            1,
            database.reservationPickupSubmissionDao().observeAll().first().size,
        )

        val result = importer.import(exported)
        assertTrue("Successになるはずが $result でした", result is BackupImportResult.Success)

        assertTrue(
            "reservation_pickup_submissionsがCASCADE削除されず残っています",
            database.reservationPickupSubmissionDao().observeAll().first().isEmpty(),
        )
    }

    @Test
    fun `主キー・unique制約の重複はREPLACE系テーブルも含め検証フェーズで拒否される`() = runBlocking {
        seedSourceData()
        val before = snapshot()
        val exported = exporter.export()

        // reading_records は (memberId, tilcod, loanDate) の複合キーで REPLACE 挿入のため、
        // 検証しないと制約違反にもならず黙って行が減る(§7)。JSON配列へ同じキーの行を複製する。
        val readingRecordsMarker = "\"readingRecords\": ["
        val insertAt = exported.indexOf(readingRecordsMarker) + readingRecordsMarker.length
        val duplicatedRecord = """

            {
                "memberId": 1,
                "tilcod": "1000000000002",
                "title": "火車(複製)",
                "loanDate": "2026-07-01",
                "library": "中央図書館",
                "titleNormalized": "火車"
            },
        """.trimIndent()
        val corrupted = exported.substring(0, insertAt) + duplicatedRecord + exported.substring(insertAt)

        val result = importer.import(corrupted)
        assertTrue("重複行が拒否されるはずが結果は $result でした", result is BackupImportResult.Rejected)
        assertEquals(before, snapshot())
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

    /** Room確定後の後処理失敗(§6.3)を再現するためのフェイク。必ず例外を投げる。 */
    private class FailingScheduleStarter : SyncScheduleStarter {
        override suspend fun scheduleFromSettings() {
            throw IOException("スケジュール再構成に失敗しました(テスト用)")
        }
    }

    /**
     * [CredentialStore.clearAll]失敗(§6.3)を再現するためのContext。`EncryptedSharedPreferences.create`は
     * 内部で`context.getSharedPreferences`を呼ぶため、ここで例外を投げれば`CredentialStore`本体には
     * 手を入れずにテストできる。MasterKeyの生成自体は素通しし、Keystoreまわりの正常経路は変えない。
     */
    private class FailingSharedPreferencesContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            throw RuntimeException("SharedPreferencesを開けませんでした(テスト用)")
        }
    }
}
