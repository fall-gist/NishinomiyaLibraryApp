package com.fallgist.nishinomiyalibrary.data.repository

import android.content.Context
import androidx.room.Room
import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfEntity
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.BookshelfGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.BookshelfSession
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.RemoteBookshelfMutation
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.RemoteBookshelfOutcome
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.UserData
import com.fallgist.nishinomiyalibrary.data.sync.PostSyncNotifier
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutation
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutationExpectation
import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecordKey
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import com.fallgist.nishinomiyalibrary.domain.model.Shelf
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.model.UserSummary
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BookshelfRepositoryMutationTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var credentials: CredentialStore
    private lateinit var member: MemberEntity

    private fun expected() = BookshelfMutationExpectation("利用者", 0)

    @Before
    fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        credentials = CredentialStore(context)
        val id = database.memberDao().insert(MemberEntity(name = "利用者", colorHex = "#123456", cardNumber = "card", sortOrder = 0))
        member = requireNotNull(database.memberDao().getById(id))
        credentials.savePassword(member.id, "password")
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `6種類のdomain操作をremote操作へ変換する`() = runBlocking {
        val received = mutableListOf<RemoteBookshelfMutation>()
        val repository = repository { mutation -> received += mutation; RemoteBookshelfOutcome.Unknown }

        listOf(
            BookshelfMutation.AddItem(member.id, 1, "a", "memo", expected()),
            BookshelfMutation.DeleteItem(member.id, 1, "b", expected()),
            BookshelfMutation.UpdateItemMemo(member.id, 1, "c", "updated", expected()),
            BookshelfMutation.CreateShelf(member.id, "new", expected()),
            BookshelfMutation.RenameShelf(member.id, 2, "renamed", expected()),
            BookshelfMutation.DeleteShelf(member.id, 3, expected()),
        ).forEach { mutation -> assertEquals(BookshelfMutationOutcome.Unknown, repository.mutate(mutation)) }

        assertEquals(
            listOf(
                RemoteBookshelfMutation.AddItem(1, "a", "memo", expected()),
                RemoteBookshelfMutation.DeleteItem(1, "b", expected()),
                RemoteBookshelfMutation.UpdateItemMemo(1, "c", "updated", expected()),
                RemoteBookshelfMutation.CreateShelf("new", expected()),
                RemoteBookshelfMutation.RenameShelf(2, "renamed", expected()),
                RemoteBookshelfMutation.DeleteShelf(3, expected()),
            ),
            received,
        )
    }

    @Test
    fun `AppliedとAlreadyRegisteredだけが完全スナップショットをRoomへ反映する`() = runBlocking {
        val snapshot = RemoteBookshelfOutcome.Applied(listOf(Shelf(4, "更新後")), listOf(item(shelfNo = 4)))
        val applied = repository { snapshot }.mutate(BookshelfMutation.CreateShelf(member.id, "更新後", expected()))
        assertEquals(BookshelfMutationOutcome.Applied(), applied)
        assertEquals(listOf("更新後"), database.shelfDao().observeForMember(member.id).first().map { it.name })

        val already = repository {
            RemoteBookshelfOutcome.AlreadyRegistered(listOf(Shelf(5, "既存")), listOf(item(shelfNo = 5)))
        }.mutate(BookshelfMutation.AddItem(member.id, 5, "book", "", expected()))
        assertEquals(BookshelfMutationOutcome.AlreadyRegistered(), already)
        assertEquals(listOf(5), database.shelfDao().observeForMember(member.id).first().map { it.shelfNo })
    }

    @Test
    fun `UnknownとFailureはRoomを変更しない`() = runBlocking {
        database.shelfDao().insertAll(listOf(ShelfEntity(member.id, 1, "保持")))

        assertEquals(BookshelfMutationOutcome.Unknown, repository { RemoteBookshelfOutcome.Unknown }
            .mutate(BookshelfMutation.DeleteShelf(member.id, 1, expected())))
        assertEquals(
            BookshelfMutationOutcome.Failure(FailureReason.NETWORK),
            repository { RemoteBookshelfOutcome.Failure(FailureReason.NETWORK) }
                .mutate(BookshelfMutation.DeleteShelf(member.id, 1, expected())),
        )
        assertEquals(listOf("保持"), database.shelfDao().observeForMember(member.id).first().map { it.name })
    }

    @Test
    fun `Room反映失敗はサイト成功を失敗へ変換せず再取得を要求する`() = runBlocking {
        val result = repository {
            RemoteBookshelfOutcome.Applied(emptyList(), listOf(item(shelfNo = 99)))
        }.mutate(BookshelfMutation.CreateShelf(member.id, "new", expected()))

        assertEquals(BookshelfMutationOutcome.Applied(localRefreshRequired = true), result)
        assertTrue(database.shelfDao().observeForMember(member.id).first().isEmpty())
    }

    @Test
    fun `認証情報不足とmember不在はAuth失敗にする`() = runBlocking {
        assertEquals(
            BookshelfMutationOutcome.Failure(FailureReason.AUTH),
            repository { RemoteBookshelfOutcome.Unknown }.mutate(BookshelfMutation.CreateShelf(member.id + 1, "new", expected())),
        )
        credentials.delete(member.id)
        assertEquals(
            BookshelfMutationOutcome.Failure(FailureReason.AUTH),
            repository { RemoteBookshelfOutcome.Unknown }.mutate(BookshelfMutation.CreateShelf(member.id, "new", expected())),
        )
    }

    @Test
    fun `確認時からメンバー名が変化した場合はGatewayを呼ばない`() = runBlocking {
        var gatewayCalls = 0
        val gateway = object : BookshelfGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): BookshelfSession {
                gatewayCalls++
                error("Gatewayは呼ばれてはいけません")
            }
        }

        val result = repository(gateway).mutate(
            BookshelfMutation.CreateShelf(
                member.id,
                "new",
                BookshelfMutationExpectation("確認時の名前", 0),
            ),
        )

        assertEquals(BookshelfMutationOutcome.Failure(FailureReason.SITE_RESPONSE_CHANGED), result)
        assertEquals(0, gatewayCalls)
    }

    @Test
    fun `LibraryErrorを安全なFailureReasonへ写像する`() = runBlocking {
        val cases = listOf(
            LibraryError.Auth(null) to FailureReason.AUTH,
            LibraryError.Parse("screen", "detail") to FailureReason.SITE_RESPONSE_CHANGED,
            LibraryError.Maintenance() to FailureReason.SITE_MAINTENANCE,
            LibraryError.Network(IllegalStateException()) to FailureReason.NETWORK,
        )

        cases.forEach { (error, reason) ->
            assertEquals(
                BookshelfMutationOutcome.Failure(reason),
                repository { throw error }.mutate(BookshelfMutation.CreateShelf(member.id, "new", expected())),
            )
        }
    }

    @Test
    fun `sessionは正常終了と例外とキャンセルのいずれでもcloseする`() = runBlocking {
        val normal = CloseTrackingSession { RemoteBookshelfOutcome.Unknown }
        assertEquals(
            BookshelfMutationOutcome.Unknown,
            repository(gatewayFor(normal)).mutate(BookshelfMutation.CreateShelf(member.id, "new", expected())),
        )
        assertTrue(normal.closed)

        val exceptional = CloseTrackingSession { throw LibraryError.Network(IllegalStateException()) }
        assertEquals(
            BookshelfMutationOutcome.Failure(FailureReason.NETWORK),
            repository(gatewayFor(exceptional)).mutate(BookshelfMutation.CreateShelf(member.id, "new", expected())),
        )
        assertTrue(exceptional.closed)

        val cancelled = CloseTrackingSession { throw CancellationException() }
        try {
            repository(gatewayFor(cancelled)).mutate(BookshelfMutation.CreateShelf(member.id, "new", expected()))
            fail("CancellationExceptionが再throwされていません")
        } catch (_: CancellationException) {
            assertTrue(cancelled.closed)
        }
    }

    @Test
    fun `同一Gateでは待機した編集が古い同期snapshotを最後に上書きする`() = runBlocking {
        val gate = BookshelfStateGate()
        val syncFetched = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()
        val editOpened = CompletableDeferred<Unit>()
        val status = statusRepository(gate, ControlledLibraryGateway {
            syncFetched.complete(Unit)
            releaseSync.await()
            userData("同期", 1)
        })
        val bookshelf = repository(gate) {
            editOpened.complete(Unit)
            RemoteBookshelfOutcome.Applied(listOf(Shelf(2, "編集")), listOf(item(2)))
        }

        val sync = async { status.syncAll(SyncTrigger.MANUAL) }
        syncFetched.await()
        val edit = async { bookshelf.mutate(BookshelfMutation.CreateShelf(member.id, "編集", expected())) }
        assertTrue(!editOpened.isCompleted)
        releaseSync.complete(Unit)

        sync.await()
        assertEquals(BookshelfMutationOutcome.Applied(), edit.await())
        assertEquals(listOf("編集"), database.shelfDao().observeForMember(member.id).first().map { it.name })
    }

    @Test
    fun `同一Gateでは待機した同期が編集snapshotを最後に上書きする`() = runBlocking {
        val gate = BookshelfStateGate()
        val editOpened = CompletableDeferred<Unit>()
        val releaseEdit = CompletableDeferred<Unit>()
        val syncFetched = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()
        val status = statusRepository(gate, ControlledLibraryGateway {
            syncFetched.complete(Unit)
            releaseSync.await()
            userData("後続同期", 3)
        })
        val bookshelf = repository(gate) {
            editOpened.complete(Unit)
            releaseEdit.await()
            RemoteBookshelfOutcome.Applied(listOf(Shelf(2, "編集")), listOf(item(2)))
        }

        val edit = async { bookshelf.mutate(BookshelfMutation.CreateShelf(member.id, "編集", expected())) }
        editOpened.await()
        val sync = async { status.syncAll(SyncTrigger.MANUAL) }
        assertTrue(!syncFetched.isCompleted)
        releaseEdit.complete(Unit)

        assertEquals(BookshelfMutationOutcome.Applied(), edit.await())
        syncFetched.await()
        releaseSync.complete(Unit)
        sync.await()
        assertEquals(listOf("後続同期"), database.shelfDao().observeForMember(member.id).first().map { it.name })
    }

    private fun repository(
        gate: BookshelfStateGate = BookshelfStateGate(),
        mutate: suspend (RemoteBookshelfMutation) -> RemoteBookshelfOutcome,
    ): BookshelfRepositoryImpl = repository(
        gateway = object : BookshelfGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): BookshelfSession =
                object : BookshelfSession {
                    override suspend fun mutate(mutation: RemoteBookshelfMutation): RemoteBookshelfOutcome = mutate(mutation)
                    override fun close() = Unit
                }
        },
        gate = gate,
    )

    private fun repository(
        gateway: BookshelfGateway,
        gate: BookshelfStateGate = BookshelfStateGate(),
    ): BookshelfRepositoryImpl =
        BookshelfRepositoryImpl(
            shelfDao = database.shelfDao(),
            shelfItemDao = database.shelfItemDao(),
            database = database,
            memberDao = database.memberDao(),
            credentialStore = credentials,
            gateway = gateway,
            bookshelfStateGate = gate,
        )

    private fun statusRepository(gate: BookshelfStateGate, gateway: LibraryGateway): StatusRepositoryImpl = StatusRepositoryImpl(
        database = database,
        memberDao = database.memberDao(),
        loanDao = database.loanDao(),
        reservationDao = database.reservationDao(),
        shelfItemDao = database.shelfItemDao(),
        userSummaryDao = database.userSummaryDao(),
        syncLogDao = database.syncLogDao(),
        credentialStore = credentials,
        gateway = gateway,
        postSyncNotifier = object : PostSyncNotifier {
            override suspend fun notifyAfterSuccessfulSync(successfulMemberIds: Set<Long>) = Unit
        },
        clock = Clock.systemUTC(),
        bookshelfStateGate = gate,
    )

    private fun userData(shelfName: String, shelfNo: Int): UserData = UserData(
        summary = UserSummary(-1, 1, 0, 0, 0),
        loans = emptyList(),
        reservations = emptyList(),
        shelves = listOf(Shelf(shelfNo, shelfName)),
        shelfItems = listOf(item(shelfNo)),
    )

    private fun item(shelfNo: Int): ShelfItem = ShelfItem(
        memberId = -1,
        tilcod = "book",
        title = "資料",
        memo = "",
        registeredDate = LocalDate.of(2030, 1, 1),
        shelfNo = shelfNo,
    )

    private class ControlledLibraryGateway(
        private val fetch: suspend () -> UserData,
    ) : LibraryGateway {
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
        ): UserData = fetch()
    }

    private fun gatewayFor(session: BookshelfSession): BookshelfGateway = object : BookshelfGateway {
        override suspend fun openAuthenticatedSession(cardNumber: String, password: String): BookshelfSession = session
    }

    private class CloseTrackingSession(
        private val result: suspend () -> RemoteBookshelfOutcome,
    ) : BookshelfSession {
        var closed = false
            private set

        override suspend fun mutate(mutation: RemoteBookshelfMutation): RemoteBookshelfOutcome = result()

        override fun close() {
            closed = true
        }
    }
}
