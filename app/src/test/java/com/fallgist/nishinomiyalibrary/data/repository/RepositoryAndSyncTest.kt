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
import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.domain.model.Holding
import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
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
        database.shelfItemDao().insert(shelfItem(first.id))
        database.userSummaryDao().insert(summary(first.id))
        repository.removeMember(first.id)

        assertNull(credentialStore.getPassword(first.id))
        assertEquals(listOf("二人目"), repository.members().first().map { it.name })
        assertTrue(database.loanDao().observeForMember(first.id).first().isEmpty())
        assertTrue(database.reservationDao().observeForMember(first.id).first().isEmpty())
        assertTrue(database.shelfItemDao().observeForMember(first.id).first().isEmpty())
        assertNull(database.userSummaryDao().observeForMember(first.id).first())
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
        assertEquals(1, sink.returnPlans.size)
        assertEquals(1, sink.pickupPlans.size)
        val storedLoan = database.loanDao().observeForMember(member.id).first().single()
        assertEquals(member.id, storedLoan.memberId)
        val storedReservation = database.reservationDao().observeForMember(member.id).first().single()
        assertEquals(member.id, storedReservation.memberId)
        assertEquals(clock.millis(), storedReservation.firstReadyNotifiedAt)

        val justBeforeCooldown = statusRepository(
            gateway,
            notificationService,
            Clock.offset(clock, Duration.ofMinutes(4).plusSeconds(59)),
        )
        assertTrue(justBeforeCooldown.syncAll(SyncTrigger.MANUAL) is SyncResult.SkippedCooldown)
        assertEquals(1, sink.pickupPlans.size)

        val atCooldownBoundary = statusRepository(
            gateway,
            notificationService,
            Clock.offset(clock, Duration.ofMinutes(5)),
        )
        assertEquals(SyncResult.Completed(1, 0), atCooldownBoundary.syncAll(SyncTrigger.MANUAL))
        assertEquals(SyncResult.Completed(1, 0), atCooldownBoundary.syncAll(SyncTrigger.SCHEDULED))
        assertEquals(1, sink.pickupPlans.size)
        assertTrue(atCooldownBoundary.lastSync().first()!!.succeeded == true)
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
        assertEquals(0, notifier.callCount)
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
        ).notifyAfterSuccessfulSync()
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
        ).notifyAfterSuccessfulSync()
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
    fun `並行manual同期は一回だけネットワークを実行し後続をクールダウンでスキップする`() = runBlocking {
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
        assertTrue(second.await() is SyncResult.SkippedCooldown)
        assertEquals(1, gateway.fetchedCardNumbers.size)
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
    ): UserData = UserData(
        summary = UserSummary(memberId, 1, 1, 1, 0),
        loans = listOf(
            Loan(memberId, "貸出資料", "図書", "中央図書館", dueDate.minusDays(7), dueDate, "貸出中"),
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
        shelf = listOf(ShelfItem(memberId, "TIL-1", "本棚資料", "メモ", dueDate.minusDays(10))),
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

        override suspend fun notifyAfterSuccessfulSync() {
            callCount += 1
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

        override suspend fun fetchUserData(cardNumber: String, password: String): UserData {
            fetchedCardNumbers += cardNumber
            return fetchUserDataBlock(cardNumber, password)
        }
    }
}
