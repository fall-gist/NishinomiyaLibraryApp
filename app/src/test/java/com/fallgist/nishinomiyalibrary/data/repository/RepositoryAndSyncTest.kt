package com.fallgist.nishinomiyalibrary.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.work.NetworkType
import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.AppSettings
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.local.entity.LoanEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingHistoryCheckpointEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingRecordEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfEntity
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.UserData
import com.fallgist.nishinomiyalibrary.data.remote.openbd.BookMetadataGateway
import com.fallgist.nishinomiyalibrary.data.sync.NotificationPlanner
import com.fallgist.nishinomiyalibrary.data.sync.NotificationService
import com.fallgist.nishinomiyalibrary.data.sync.NotificationSink
import com.fallgist.nishinomiyalibrary.data.sync.PickupReadyPlan
import com.fallgist.nishinomiyalibrary.data.sync.PostSyncNotifier
import com.fallgist.nishinomiyalibrary.data.sync.ReservationNotificationSource
import com.fallgist.nishinomiyalibrary.data.sync.ReturnReminderPlan
import com.fallgist.nishinomiyalibrary.data.sync.SyncWorkerDecision
import com.fallgist.nishinomiyalibrary.data.sync.createDailySyncWorkRequest
import com.fallgist.nishinomiyalibrary.data.sync.nextScheduleDelay
import com.fallgist.nishinomiyalibrary.data.sync.syncWorkerDecision
import com.fallgist.nishinomiyalibrary.data.sync.executeScheduledSync
import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.domain.model.Holding
import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecordKey
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecordTitleNormalizer
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.model.Shelf
import com.fallgist.nishinomiyalibrary.domain.model.UserSummary
import com.fallgist.nishinomiyalibrary.domain.repository.SyncResult
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
class RepositoryAndSyncTest {
    @Test
    fun `scheduled sync runs status before update only when auto is enabled`() = runBlocking {
        val enabledEvents = mutableListOf<String>()
        val enabled = executeScheduledSync(
            autoReservationEnabled = true,
            sync = { enabledEvents += "sync"; SyncResult.Completed(1, 0) },
            update = { enabledEvents += "update"; NewArrivalUpdateResult.Completed(AutomaticReservationRunResult.NoMatch) },
        )
        assertEquals(listOf("sync", "update"), enabledEvents)
        assertTrue(enabled.updateResult is NewArrivalUpdateResult.Completed)

        val disabledEvents = mutableListOf<String>()
        val disabled = executeScheduledSync(
            autoReservationEnabled = false,
            sync = { disabledEvents += "sync"; SyncResult.Completed(1, 0) },
            update = { disabledEvents += "update"; NewArrivalUpdateResult.RefreshFailed },
        )
        assertEquals(listOf("sync"), disabledEvents)
        assertNull(disabled.updateResult)
    }

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var credentialStore: CredentialStore
    private val dataStoreScopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        credentialStore = CredentialStore(context)
    }

    @After
    fun tearDown() {
        dataStoreScopes.forEach(CoroutineScope::cancel)
        database.close()
    }

    @Test
    fun `FamilyRepositoryは末尾採番し資格情報とメンバー関連データを削除する`() = runBlocking {
        val repository = FamilyRepositoryImpl(database, database.memberDao(), credentialStore)
        val firstPassword = generatedValue()
        val secondPassword = generatedValue()
        repository.addMember("一人目", "#111111", generatedValue(), firstPassword)
        repository.addMember("二人目", "#222222", generatedValue(), secondPassword)

        val members = repository.members().first()
        assertEquals(listOf(0, 1), members.map { it.sortOrder })
        assertEquals(firstPassword, credentialStore.getPassword(members.first().id))

        val first = members.first()
        repository.updateMember(first.copy(name = "更新後"), newPassword = generatedValue())
        assertEquals("更新後", repository.members().first().first().name)

        database.loanDao().insert(loanEntity(first.id, dueDate = LocalDate.of(2030, 5, 1)))
        database.reservationDao().insert(reservationEntity(first.id, ReservationState.WAITING))
        database.shelfDao().insertAll(listOf(ShelfEntity(first.id, 1, "削除対象本棚")))
        database.shelfItemDao().insert(shelfItem(first.id))
        database.userSummaryDao().insert(summary(first.id))
        database.readingRecordDao().upsertAll(
            listOf(readingRecordEntity(first.id, "1000000000001", "削除対象の読書記録", LocalDate.of(2030, 5, 1))),
        )
        database.readingRecordDao().upsertHistoryCheckpoints(
            listOf(ReadingHistoryCheckpointEntity(first.id, "1000000000001", LocalDate.of(2030, 5, 1))),
        )
        repository.removeMember(first.id)

        assertNull(credentialStore.getPassword(first.id))
        assertEquals(listOf("二人目"), repository.members().first().map { it.name })
        assertTrue(database.loanDao().observeForMember(first.id).first().isEmpty())
        assertTrue(database.reservationDao().observeForMember(first.id).first().isEmpty())
        assertTrue(database.shelfItemDao().observeForMember(first.id).first().isEmpty())
        assertTrue(database.shelfDao().observeForMember(first.id).first().isEmpty())
        assertNull(database.userSummaryDao().observeForMember(first.id).first())
        assertTrue(database.readingRecordDao().observeForMember(first.id).first().isEmpty())
        assertTrue(database.readingRecordDao().getHistoryCheckpointKeys(first.id).isEmpty())
    }

    @Test
    fun `読書記録Repositoryは正規化検索とメンバー絞り込みと既読情報を公開する`() = runBlocking {
        val repository = ReadingRecordRepositoryImpl(database.readingRecordDao())
        database.readingRecordDao().upsertAll(
            listOf(
                readingRecordEntity(1L, "1000000000101", "ＡＢＣ　著者", LocalDate.of(2030, 3, 2)),
                readingRecordEntity(2L, "1000000000101", "ABC 著者", LocalDate.of(2030, 3, 3), "北口図書館"),
                readingRecordEntity(1L, "1000000000102", "別資料", LocalDate.of(2030, 3, 1)),
            ),
        )

        assertEquals(
            listOf("1000000000101"),
            repository.search("ａｂｃ 著者", memberId = 1L).first().map { it.tilcod },
        )
        assertEquals(listOf(2L, 1L), repository.records().first().map { it.memberId }.take(2))
        assertEquals(
            listOf("北口図書館", "中央図書館"),
            repository.hasRead("1000000000101").first().map { it.library },
        )
    }

    @Test
    fun `現在貸出だけでは履歴チェックポイントを作らずサイト履歴だけを次回既知キーにする`() = runBlocking {
        val clock = fixedClock("2030-05-03T00:00:00Z")
        val member = insertMember("読書記録対象", 0)
        credentialStore.savePassword(member.id, generatedValue())
        var responseIndex = 0
        val historyRecord = ReadingRecord(
            memberId = -1,
            tilcod = "1000000000200",
            title = "後から取得した履歴",
            loanDate = LocalDate.of(2030, 4, 1),
            library = "中央図書館",
        )
        val gateway = FakeGateway { _, _ ->
            when (responseIndex++) {
                0 -> userData(
                    memberId = -1,
                    dueDate = LocalDate.of(2030, 5, 10),
                    loanTilcod = "1000000000201",
                )

                1 -> userData(memberId = -1, readingRecords = listOf(historyRecord))
                else -> userData(memberId = -1)
            }
        }
        val repository = statusRepository(gateway, CountingNotifier(), clock)

        assertEquals(SyncResult.Completed(1, 0), repository.syncAll(SyncTrigger.MANUAL))
        assertEquals(emptySet<ReadingRecordKey>(), gateway.knownReadingRecordKeys[0])
        assertTrue(database.readingRecordDao().getHistoryCheckpointKeys(member.id).isEmpty())
        assertEquals(
            listOf("1000000000201"),
            database.readingRecordDao().observeForMember(member.id).first().map { it.tilcod },
        )

        assertEquals(SyncResult.Completed(1, 0), repository.syncAll(SyncTrigger.SCHEDULED))
        assertEquals(emptySet<ReadingRecordKey>(), gateway.knownReadingRecordKeys[1])
        assertEquals(
            setOf(ReadingRecordKey("1000000000200", LocalDate.of(2030, 4, 1))),
            database.readingRecordDao().getHistoryCheckpointKeys(member.id)
                .map { ReadingRecordKey(it.tilcod, it.loanDate) }
                .toSet(),
        )

        assertEquals(SyncResult.Completed(1, 0), repository.syncAll(SyncTrigger.SCHEDULED))
        assertEquals(
            setOf(ReadingRecordKey("1000000000200", LocalDate.of(2030, 4, 1))),
            gateway.knownReadingRecordKeys[2],
        )
        assertEquals(
            listOf("1000000000201", "1000000000200"),
            database.readingRecordDao().observeForMember(member.id).first().map { it.tilcod },
        )
    }

    @Test
    fun `同期はDB置換後に返却とREADY通知を一度だけ記録する`() = runBlocking {
        val clock = fixedClock("2030-04-10T18:00:00Z")
        val member = insertMember("同期対象", 0)
        credentialStore.savePassword(member.id, generatedValue())
        val sink = RecordingNotificationSink()
        val notificationService = NotificationService(
            settingsStore(),
            database.memberDao(),
            database.loanDao(),
            database.reservationDao(),
            clock,
            sink,
        )
        val gateway = FakeGateway { _, _ ->
            userData(
                memberId = -1,
                dueDate = LocalDate.now(clock).plusDays(1),
                reservationState = ReservationState.READY,
            )
        }
        val repository = statusRepository(gateway, notificationService, clock)

        val result = repository.syncAll(SyncTrigger.MANUAL)
        assertEquals(SyncResult.Completed(1, 0), result)
        assertEquals("本棚", repository.shelf(member.id).first().single().shelfName)
        assertEquals(1, sink.returnPlans.size)
        assertEquals(1, sink.pickupPlans.size)
        val storedLoan = database.loanDao().observeForMember(member.id).first().single()
        assertEquals(member.id, storedLoan.memberId)
        val storedReservation = database.reservationDao().observeForMember(member.id).first().single()
        assertEquals(member.id, storedReservation.memberId)
        assertEquals(clock.millis(), storedReservation.firstReadyNotifiedAt)

        // クールダウンは撤廃済み(所有者判断、家庭内利用のため)。手動同期は直後でも即座に実行できる。
        val immediatelyAfter = statusRepository(gateway, notificationService, clock)
        assertEquals(SyncResult.Completed(1, 0), immediatelyAfter.syncAll(SyncTrigger.MANUAL))
        assertEquals(1, sink.pickupPlans.size)

        assertEquals(SyncResult.Completed(1, 0), immediatelyAfter.syncAll(SyncTrigger.SCHEDULED))
        assertEquals(1, sink.pickupPlans.size)
        assertTrue(immediatelyAfter.lastSync().first()!!.succeeded == true)
    }

    @Test
    fun `同期は順次継続し失敗詳細に例外メッセージや資格情報を含めない`() = runBlocking {
        val clock = fixedClock("2030-05-01T18:00:00Z")
        val first = insertMember("先", 1)
        val second = insertMember("後", 2)
        val thirdWithoutPassword = insertMember("資格情報なし", 3)
        val firstPassword = generatedValue()
        val secondPassword = generatedValue()
        credentialStore.savePassword(first.id, firstPassword)
        credentialStore.savePassword(second.id, secondPassword)
        val privateText = generatedValue()
        val gateway = FakeGateway { cardNumber, _ ->
            if (cardNumber == second.cardNumber) throw IllegalStateException(privateText)
            userData(memberId = -99, dueDate = LocalDate.now(clock).plusDays(1))
        }
        val notifier = CountingNotifier()
        val repository = statusRepository(gateway, notifier, clock)

        assertEquals(SyncResult.Completed(1, 2), repository.syncAll(SyncTrigger.MANUAL))
        assertEquals(listOf(first.cardNumber, second.cardNumber), gateway.fetchedCardNumbers)
        assertEquals(1, notifier.callCount)
        assertEquals(listOf(setOf(first.id)), notifier.successfulMemberIdSets)
        val latestLog = repository.lastSync().first()!!
        assertFalse(latestLog.succeeded == true)
        assertTrue(latestLog.details.contains("不明なエラー"))
        assertTrue(latestLog.details.contains("資格情報未設定"))
        assertFalse(latestLog.details.contains(privateText))
        assertFalse(latestLog.details.contains(first.cardNumber))
        assertFalse(latestLog.details.contains(second.cardNumber))
        assertFalse(latestLog.details.contains(thirdWithoutPassword.cardNumber))
        assertFalse(latestLog.details.contains(firstPassword))
        assertFalse(latestLog.details.contains(secondPassword))
        assertTrue(database.loanDao().observeForMember(first.id).first().isNotEmpty())
        assertTrue(database.loanDao().observeForMember(second.id).first().isEmpty())
    }

    @Test
    fun `部分成功では成功メンバーだけ通知し失敗メンバーのキャッシュを残す`() = runBlocking {
        val clock = fixedClock("2030-05-02T00:00:00Z")
        val successfulMember = insertMember("成功", 0)
        val failedMember = insertMember("失敗", 1)
        val successfulPassword = generatedValue()
        credentialStore.savePassword(successfulMember.id, successfulPassword)
        val failedPassword = generatedValue()
        credentialStore.savePassword(failedMember.id, failedPassword)

        database.loanDao().insert(
            loanEntity(failedMember.id, LocalDate.now(clock).plusDays(1)).copy(title = "失敗メンバーの貸出"),
        )
        database.reservationDao().insert(
            reservationEntity(failedMember.id, ReservationState.READY).copy(
                title = "失敗メンバーの予約",
            ),
        )

        val sink = RecordingNotificationSink()
        val notifier = NotificationService(
            settingsStore(),
            database.memberDao(),
            database.loanDao(),
            database.reservationDao(),
            clock,
            sink,
        )
        val gateway = FakeGateway { cardNumber, _ ->
            if (cardNumber == failedMember.cardNumber) throw LibraryError.Auth(memberName = null)
            userData(
                memberId = -1,
                dueDate = LocalDate.now(clock).plusDays(1),
                reservationState = ReservationState.READY,
            )
        }
        val repository = statusRepository(gateway, notifier, clock)

        assertEquals(SyncResult.Completed(1, 1), repository.syncAll(SyncTrigger.MANUAL))
        assertEquals(listOf(successfulMember.id), sink.returnPlans.single().itemsByMember.map { it.memberId })
        assertEquals(1, sink.pickupPlans.single().items.size)
        assertFalse(sink.pickupPlans.single().items.any { it.title == "失敗メンバーの予約" })

        assertEquals(
            "失敗メンバーの貸出",
            database.loanDao().observeForMember(failedMember.id).first().single().title,
        )
        val failedReservation = database.reservationDao().observeForMember(failedMember.id).first().single()
        assertEquals("失敗メンバーの予約", failedReservation.title)
        assertNull(failedReservation.firstReadyNotifiedAt)
        assertEquals(
            clock.millis(),
            database.reservationDao().observeForMember(successfulMember.id).first().single().firstReadyNotifiedAt,
        )

        val latestLog = repository.lastSync().first()!!
        assertFalse(latestLog.succeeded == true)
        assertTrue(latestLog.details.contains("認証エラー"))
        assertFalse(latestLog.details.contains(successfulMember.cardNumber))
        assertFalse(latestLog.details.contains(successfulPassword))
        assertFalse(latestLog.details.contains(failedMember.cardNumber))
        assertFalse(latestLog.details.contains(failedPassword))
    }

    @Test
    fun `全メンバー失敗時は同期後通知を呼ばない`() = runBlocking {
        val member = insertMember("認証失敗", 0)
        credentialStore.savePassword(member.id, generatedValue())
        val notifier = CountingNotifier()
        val repository = statusRepository(
            gateway = FakeGateway { _, _ -> throw LibraryError.Auth(memberName = null) },
            notifier = notifier,
            clock = fixedClock("2030-05-03T00:00:00Z"),
        )

        assertEquals(SyncResult.Completed(0, 1), repository.syncAll(SyncTrigger.MANUAL))
        assertEquals(0, notifier.callCount)
    }

    @Test
    fun `空の成功メンバー集合では通知候補もREADY通知時刻も変更しない`() = runBlocking {
        val today = LocalDate.of(2030, 5, 4)
        val member = insertMember("通知対象外", 0)
        database.loanDao().insert(loanEntity(member.id, today.plusDays(1)))
        database.reservationDao().insert(reservationEntity(member.id, ReservationState.READY))
        val sink = RecordingNotificationSink()

        NotificationService(
            settingsStore(),
            database.memberDao(),
            database.loanDao(),
            database.reservationDao(),
            Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
            sink,
        ).notifyAfterSuccessfulSync(emptySet())

        assertTrue(sink.returnPlans.isEmpty())
        assertTrue(sink.pickupPlans.isEmpty())
        assertNull(database.reservationDao().observeForMember(member.id).first().single().firstReadyNotifiedAt)
    }

    @Test
    fun `SearchとCalendarRepositoryは委譲し12施設の休館日を館別に置換する`() = runBlocking {
        val clock = fixedClock("2030-06-10T00:00:00Z")
        val gateway = FakeGateway { _, _ -> userData() }
        gateway.searchResult = SearchPage(emptyList(), totalCount = 0, hasNext = false)
        gateway.closedDaysResult = listOf(LocalDate.of(2030, 6, 12))
        val metadataGateway = object : BookMetadataGateway {
            override suspend fun coverUrl(isbn: String): String? = "https://example.invalid/cover"
        }
        val searchRepository = SearchRepositoryImpl(gateway, metadataGateway)
        val calendarRepository = CalendarRepositoryImpl(database, database.closedDayDao(), gateway, clock)

        assertEquals(12, calendarRepository.libraries.size)
        assertEquals(
            listOf("001", "002", "003", "004", "101", "102", "103", "104", "105", "106", "107", "109"),
            calendarRepository.libraries.map { it.code },
        )
        assertEquals(0, searchRepository.search("任意", 1).totalCount)
        assertEquals("https://example.invalid/cover", searchRepository.coverUrl("9780000000000"))
        calendarRepository.refreshClosedDays("106")
        assertEquals(
            listOf(LocalDate.of(2030, 6, 12)),
            calendarRepository.closedDays("106").first().map { it.date },
        )
        assertEquals(listOf("106"), gateway.closedDayRequests)
    }

    @Test
    fun `NotificationPlannerと設定OFFは前日当日超過READY重複防止を扱う`() = runBlocking {
        val today = LocalDate.of(2030, 7, 10)
        val returnPlan = NotificationPlanner.returnReminder(
            today,
            listOf(
                com.fallgist.nishinomiyalibrary.data.sync.LoanNotificationSource(1, "親", "明日期限", today.plusDays(1)),
                com.fallgist.nishinomiyalibrary.data.sync.LoanNotificationSource(1, "親", "当日期限", today),
                com.fallgist.nishinomiyalibrary.data.sync.LoanNotificationSource(2, "子", "超過", today.minusDays(1)),
                com.fallgist.nishinomiyalibrary.data.sync.LoanNotificationSource(2, "子", "対象外", today.plusDays(2)),
            ),
        )
        requireNotNull(returnPlan)
        assertTrue(returnPlan.hasOverdue)
        assertEquals(3, returnPlan.itemsByMember.sumOf { it.titles.size })
        val readyPlan = NotificationPlanner.pickupReady(
            listOf(
                ReservationNotificationSource(1, "親", "未通知", "館", today.plusDays(7), ReservationState.READY, null),
                ReservationNotificationSource(2, "親", "既通知", "館", null, ReservationState.READY, 1L),
                ReservationNotificationSource(3, "親", "待機", "館", null, ReservationState.WAITING, null),
            ),
        )
        assertEquals(listOf(1L), requireNotNull(readyPlan).items.map { it.reservationId })

        val member = insertMember("通知OFF", 0)
        database.loanDao().insert(loanEntity(member.id, today.plusDays(1)))
        database.reservationDao().insert(reservationEntity(member.id, ReservationState.READY))
        val sink = RecordingNotificationSink()
        val settings = settingsStore().also {
            it.update(AppSettings(notifyReturnReminder = false, notifyPickupReady = false))
        }
        NotificationService(
            settings,
            database.memberDao(),
            database.loanDao(),
            database.reservationDao(),
            Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
            sink,
        ).notifyAfterSuccessfulSync(setOf(member.id))
        assertTrue(sink.returnPlans.isEmpty())
        assertTrue(sink.pickupPlans.isEmpty())
        assertNull(database.reservationDao().observeForMember(member.id).first().single().firstReadyNotifiedAt)

        settings.update(AppSettings())
        val rejectingSink = RecordingNotificationSink(acceptNotifications = false)
        NotificationService(
            settings,
            database.memberDao(),
            database.loanDao(),
            database.reservationDao(),
            Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
            rejectingSink,
        ).notifyAfterSuccessfulSync(setOf(member.id))
        assertTrue(rejectingSink.pickupPlans.isNotEmpty())
        assertNull(database.reservationDao().observeForMember(member.id).first().single().firstReadyNotifiedAt)
    }

    @Test
    fun `SchedulerとWorker判定は時刻境界ネットワーク制約と最大二回再試行を守る`() {
        val clock = fixedClock("2030-08-10T17:30:00Z")
        assertEquals(Duration.ofMinutes(30), nextScheduleDelay(clock, 18, 0))
        assertEquals(Duration.ofDays(1), nextScheduleDelay(fixedClock("2030-08-10T18:00:00Z"), 18, 0))
        val request = createDailySyncWorkRequest(clock, AppSettings(syncHour = 18, syncMinute = 0))
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(Duration.ofMinutes(30).toMillis(), request.workSpec.initialDelay)
        val failure = SyncResult.Completed(syncedMemberCount = 0, failedMemberCount = 1)
        assertEquals(SyncWorkerDecision.RETRY, syncWorkerDecision(failure, 0))
        assertEquals(SyncWorkerDecision.RETRY, syncWorkerDecision(failure, 1))
        assertEquals(SyncWorkerDecision.FAILURE, syncWorkerDecision(failure, 2))
        assertEquals(
            SyncWorkerDecision.SUCCESS,
            syncWorkerDecision(SyncResult.Completed(1, 0), 0),
        )
        assertEquals(SyncWorkerDecision.RETRY, syncWorkerDecision(failure, NewArrivalUpdateResult.RefreshFailed, 0))
        assertEquals(SyncWorkerDecision.RETRY, syncWorkerDecision(failure, NewArrivalUpdateResult.RefreshFailed, 1))
        assertEquals(SyncWorkerDecision.FAILURE, syncWorkerDecision(failure, NewArrivalUpdateResult.RefreshFailed, 2))
        assertEquals(
            SyncWorkerDecision.SUCCESS,
            syncWorkerDecision(failure, NewArrivalUpdateResult.Completed(AutomaticReservationRunResult.Completed(emptyList(), preparedReached = true)), 0),
        )
        assertEquals(
            SyncWorkerDecision.FAILURE,
            syncWorkerDecision(failure, NewArrivalUpdateResult.AutomaticFailed(preparedReached = true), 0),
        )
        assertEquals(SyncWorkerDecision.FAILURE, syncWorkerDecision(failure, NewArrivalUpdateResult.AutomaticFailed(preparedReached = false), 0))
        assertEquals(SyncWorkerDecision.SUCCESS, syncWorkerDecision(failure, NewArrivalUpdateResult.AlreadyRunning, 0))
        assertEquals(SyncWorkerDecision.SUCCESS, syncWorkerDecision(failure, NewArrivalUpdateResult.FreshnessSkipped, 0))
        assertEquals(
            SyncWorkerDecision.SUCCESS,
            syncWorkerDecision(failure, NewArrivalUpdateResult.Completed(AutomaticReservationRunResult.NoMatch), 0),
        )

        val jst = ZoneId.of("Asia/Tokyo")
        assertEquals(
            Duration.ofSeconds(1),
            nextScheduleDelay(Clock.fixed(Instant.parse("2030-08-10T08:59:59Z"), jst), 18, 0),
        )
        assertEquals(
            Duration.ofDays(1),
            nextScheduleDelay(Clock.fixed(Instant.parse("2030-08-10T09:00:00Z"), jst), 18, 0),
        )
    }

    @Test
    fun `並行manual同期はmutexで直列化されクールダウンなしで両方とも実行される`() = runBlocking {
        // クールダウン撤廃(所有者判断、家庭内利用のため)後も、syncMutexによる直列化(同時ネットワーク
        // 実行の防止)は維持する。後続は待たされるだけで、スキップされずに実行される。
        val clock = fixedClock("2030-09-01T09:00:00Z")
        val member = insertMember("並行同期", 0)
        credentialStore.savePassword(member.id, generatedValue())
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val gateway = FakeGateway { _, _ ->
            fetchStarted.complete(Unit)
            releaseFetch.await()
            userData(memberId = -1, dueDate = LocalDate.now(clock).plusDays(1))
        }
        val repository = statusRepository(gateway, CountingNotifier(), clock)

        val first = async(Dispatchers.Default) { repository.syncAll(SyncTrigger.MANUAL) }
        fetchStarted.await()
        val second = async(Dispatchers.Default) {
            secondStarted.complete(Unit)
            repository.syncAll(SyncTrigger.MANUAL)
        }
        secondStarted.await()
        releaseFetch.complete(Unit)

        assertEquals(SyncResult.Completed(1, 0), first.await())
        assertEquals(SyncResult.Completed(1, 0), second.await())
        assertEquals(2, gateway.fetchedCardNumbers.size)
    }

    @Test
    fun `手動同期はクールダウンなしで連続実行できる`() = runBlocking {
        // 所有者判断でクールダウンを撤廃した(家庭内利用が前提、利用者の良心に委ねる、実機検証の支障)。
        // 直後に連続して手動同期しても、両方ともCompletedで実行されることを確認する。
        val clock = fixedClock("2030-09-02T09:00:00Z")
        val member = insertMember("連続同期", 0)
        credentialStore.savePassword(member.id, generatedValue())
        val gateway = FakeGateway { _, _ -> userData(memberId = -1, dueDate = LocalDate.now(clock).plusDays(1)) }
        val repository = statusRepository(gateway, CountingNotifier(), clock)

        assertEquals(SyncResult.Completed(1, 0), repository.syncAll(SyncTrigger.MANUAL))
        assertEquals(SyncResult.Completed(1, 0), repository.syncAll(SyncTrigger.MANUAL))
        assertEquals(2, gateway.fetchedCardNumbers.size)
    }

    private fun statusRepository(
        gateway: LibraryGateway,
        notifier: PostSyncNotifier,
        clock: Clock,
    ): StatusRepositoryImpl = StatusRepositoryImpl(
        database,
        database.memberDao(),
        database.loanDao(),
        database.reservationDao(),
        database.shelfItemDao(),
        database.userSummaryDao(),
        database.syncLogDao(),
        credentialStore,
        gateway,
        notifier,
        clock,
    )

    private suspend fun insertMember(name: String, sortOrder: Int): MemberEntity {
        val id = database.memberDao().insert(
            MemberEntity(
                name = name,
                colorHex = "#123456",
                cardNumber = generatedValue(),
                sortOrder = sortOrder,
            ),
        )
        return requireNotNull(database.memberDao().getById(id))
    }

    private fun settingsStore(): SettingsStore {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStoreScopes += scope
        return SettingsStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(context.filesDir, "repo-${UUID.randomUUID()}.preferences_pb") },
            ),
        )
    }

    private fun userData(
        memberId: Long = -1,
        dueDate: LocalDate = LocalDate.of(2030, 1, 2),
        reservationState: ReservationState = ReservationState.WAITING,
        loanTilcod: String = "",
        readingRecords: List<ReadingRecord> = emptyList(),
    ): UserData = UserData(
        summary = UserSummary(memberId, 1, 1, 1, 0),
        loans = listOf(
            Loan(memberId, "貸出資料", "図書", "中央図書館", dueDate.minusDays(7), dueDate, "貸出中", loanTilcod),
        ),
        reservations = listOf(
            Reservation(
                memberId,
                "予約資料",
                "図書",
                "高須分室",
                dueDate.minusDays(3),
                1,
                reservationState,
                dueDate.plusDays(7),
            ),
        ),
        shelves = listOf(Shelf(1, "本棚")),
        shelfItems = listOf(
            ShelfItem(memberId, "TIL-1", "本棚資料", "メモ", dueDate.minusDays(10), 1, "本棚"),
        ),
        readingRecords = readingRecords,
    )

    private fun loanEntity(memberId: Long, dueDate: LocalDate): LoanEntity = LoanEntity(
        memberId = memberId,
        title = "資料",
        materialType = "図書",
        lendingLibrary = "中央図書館",
        loanDate = dueDate.minusDays(7),
        dueDate = dueDate,
        status = "貸出中",
    )

    private fun readingRecordEntity(
        memberId: Long,
        tilcod: String,
        title: String,
        loanDate: LocalDate,
        library: String = "中央図書館",
    ): ReadingRecordEntity = ReadingRecordEntity(
        memberId = memberId,
        tilcod = tilcod,
        title = title,
        loanDate = loanDate,
        library = library,
        titleNormalized = ReadingRecordTitleNormalizer.normalize(title),
    )

    private fun reservationEntity(memberId: Long, state: ReservationState): ReservationEntity = ReservationEntity(
        memberId = memberId,
        title = "予約資料",
        materialType = "図書",
        pickupLibrary = "高須分室",
        reservedDate = LocalDate.of(2030, 1, 1),
        queuePosition = 1,
        state = state,
        holdExpiryDate = LocalDate.of(2030, 1, 10),
        firstReadyNotifiedAt = null,
    )

    private fun shelfItem(memberId: Long) = com.fallgist.nishinomiyalibrary.data.local.entity.ShelfItemEntity(
        memberId,
        1,
        "TIL-2",
        "本棚資料",
        "メモ",
        LocalDate.of(2030, 1, 1),
    )

    private fun summary(memberId: Long) = com.fallgist.nishinomiyalibrary.data.local.entity.UserSummaryEntity(
        memberId,
        shelfCount = 1,
        loanCount = 1,
        reservationCount = 1,
        cartCount = 0,
    )

    private fun fixedClock(instant: String): Clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)

    private fun generatedValue(): String = UUID.randomUUID().toString()

    private class CountingNotifier : PostSyncNotifier {
        var callCount = 0
        val successfulMemberIdSets = mutableListOf<Set<Long>>()

        override suspend fun notifyAfterSuccessfulSync(successfulMemberIds: Set<Long>) {
            callCount += 1
            successfulMemberIdSets += successfulMemberIds
        }
    }

    private class RecordingNotificationSink(
        private val acceptNotifications: Boolean = true,
    ) : NotificationSink {
        val returnPlans = mutableListOf<ReturnReminderPlan>()
        val pickupPlans = mutableListOf<PickupReadyPlan>()

        override suspend fun postReturnReminder(plan: ReturnReminderPlan): Boolean {
            returnPlans += plan
            return acceptNotifications
        }

        override suspend fun postPickupReady(plan: PickupReadyPlan): Boolean {
            pickupPlans += plan
            return acceptNotifications
        }
    }

    private class FakeGateway(
        private val fetchUserDataBlock: suspend (String, String) -> UserData,
    ) : LibraryGateway {
        val fetchedCardNumbers = mutableListOf<String>()
        val knownReadingRecordKeys = mutableListOf<Set<ReadingRecordKey>>()
        val closedDayRequests = mutableListOf<String>()
        var closedDaysResult: List<LocalDate> = emptyList()
        var searchResult: SearchPage = SearchPage(emptyList(), 0, false)

        override suspend fun search(keyword: String, page: Int): SearchPage = searchResult

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

        override suspend fun closedDays(libraryCode: String): List<LocalDate> {
            closedDayRequests += libraryCode
            return closedDaysResult
        }

        override suspend fun newArrivals(): List<com.fallgist.nishinomiyalibrary.domain.model.NewArrival> = emptyList()

        override suspend fun fetchUserData(
            cardNumber: String,
            password: String,
            knownReadingRecordKeys: Set<ReadingRecordKey>,
        ): UserData {
            fetchedCardNumbers += cardNumber
            this.knownReadingRecordKeys += knownReadingRecordKeys
            return fetchUserDataBlock(cardNumber, password)
        }
    }
}
