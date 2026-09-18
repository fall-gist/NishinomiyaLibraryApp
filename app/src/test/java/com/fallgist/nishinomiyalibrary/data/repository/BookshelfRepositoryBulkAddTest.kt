package com.fallgist.nishinomiyalibrary.data.repository

import android.content.Context
import androidx.room.Room
import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.BookshelfGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.BookshelfSession
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.RemoteBookshelfMutation
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.RemoteBookshelfOutcome
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddItem
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddItemOutcome
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddRequest
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfExpectedShelf
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutationExpectation
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.Shelf
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `BookshelfRepositoryImpl.addItems`のテスト(段階1、`docs/design/bulk-bookshelf-add.md` §6.1)。
 * Gateway・セッションはフェイクで置き換え、フェイクが受け取った期待値を検査する。
 * 実サイトへの通信は一切行わない。
 */
@RunWith(RobolectricTestRunner::class)
class BookshelfRepositoryBulkAddTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var credentials: CredentialStore
    private lateinit var member: MemberEntity

    private fun confirmed(itemCount: Int = 0) = BookshelfMutationExpectation(
        memberName = "利用者",
        shelfCount = 1,
        shelf = BookshelfExpectedShelf(shelfNo = 1, name = "読みたい", itemCount = itemCount),
    )

    private fun items(count: Int) = (1..count).map { BookshelfBulkAddItem(tilcod = "tilcod$it", title = "タイトル$it") }

    private fun request(itemCount: Int = 3, confirmedExpectation: BookshelfMutationExpectation = confirmed()) =
        BookshelfBulkAddRequest(memberId = member.id, shelfNo = 1, items = items(itemCount), confirmed = confirmedExpectation)

    /** 対象棚(shelfNo=1)にcountItems件、名前nameの本棚状態を持つAppliedを返す。 */
    private fun applied(countItems: Int, name: String = "読みたい"): RemoteBookshelfOutcome.Applied {
        val shelfItems = (1..countItems).map { shelfItem(shelfNo = 1, tilcod = "existing$it") }
        return RemoteBookshelfOutcome.Applied(listOf(Shelf(1, name)), shelfItems)
    }

    private fun alreadyRegistered(countItems: Int, name: String = "読みたい"): RemoteBookshelfOutcome.AlreadyRegistered {
        val shelfItems = (1..countItems).map { shelfItem(shelfNo = 1, tilcod = "existing$it") }
        return RemoteBookshelfOutcome.AlreadyRegistered(listOf(Shelf(1, name)), shelfItems)
    }

    private fun shelfItem(shelfNo: Int, tilcod: String = "book"): ShelfItem = ShelfItem(
        memberId = -1,
        tilcod = tilcod,
        title = "資料",
        memo = "",
        registeredDate = LocalDate.of(2030, 1, 1),
        shelfNo = shelfNo,
    )

    @Before
    fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        credentials = CredentialStore(context)
        val id = database.memberDao().insert(MemberEntity(name = "利用者", colorHex = "#123456", cardNumber = "card", sortOrder = 0))
        member = requireNotNull(database.memberDao().getById(id))
        credentials.savePassword(member.id, "password")
        database.shelfDao().insertAll(
            listOf(com.fallgist.nishinomiyalibrary.data.local.entity.ShelfEntity(member.id, 1, "読みたい")),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // 1・2: 全件Appliedのとき、2件目以降の期待値の資料数が直前の結果の実数になり、
    // それ以外(メンバー名・本棚の総数・本棚番号・本棚名)は確認時のまま。
    @Test
    fun `全件Appliedのとき2件目以降の期待値の資料数だけが直前の実数で更新される`() = runBlocking {
        val receivedExpected = mutableListOf<BookshelfMutationExpectation>()
        var callCount = 0
        val repository = repository { mutation ->
            val add = mutation as RemoteBookshelfMutation.AddItem
            receivedExpected += add.expected
            callCount++
            applied(countItems = callCount) // 1件目呼び出し後は1件、2件目呼び出し後は2件…
        }

        val result = repository.addItems(request(itemCount = 3))

        assertEquals(3, result.items.size)
        assertTrue(result.items.all { it.outcome == BookshelfBulkAddItemOutcome.Added })
        // 1件目は確認時の値そのまま(資料数0)
        assertEquals(confirmed(itemCount = 0), receivedExpected[0])
        // 2件目は1件目の結果の実数(1件)
        assertEquals(confirmed(itemCount = 1), receivedExpected[1])
        // 3件目は2件目の結果の実数(2件)
        assertEquals(confirmed(itemCount = 2), receivedExpected[2])
        // メンバー名・本棚の総数・本棚番号・本棚名は全件で確認時のまま
        receivedExpected.forEach {
            assertEquals("利用者", it.memberName)
            assertEquals(1, it.shelfCount)
            assertEquals(1, it.shelf?.shelfNo)
            assertEquals("読みたい", it.shelf?.name)
        }
    }

    // 3: 直前の結果で対象本棚の名前が変わっていても、次の期待値の名前は確認時のまま(追随しない)。
    @Test
    fun `直前の結果で本棚名が変わっても次の期待値の名前は確認時のまま`() = runBlocking {
        val receivedExpected = mutableListOf<BookshelfMutationExpectation>()
        var callCount = 0
        val repository = repository { mutation ->
            val add = mutation as RemoteBookshelfMutation.AddItem
            receivedExpected += add.expected
            callCount++
            // 1件目の結果は「改名後」という別名を返すが、名前は追随してはならない。
            applied(countItems = callCount, name = "改名後")
        }

        repository.addItems(request(itemCount = 2))

        assertEquals("読みたい", receivedExpected[1].shelf?.name)
    }

    // 4: AlreadyRegisteredのとき次へ進み、資料数は直前の結果の実数を使う。
    @Test
    fun `AlreadyRegisteredのとき次へ進み資料数は直前の実数を使う`() = runBlocking {
        val receivedExpected = mutableListOf<BookshelfMutationExpectation>()
        var callCount = 0
        val repository = repository { mutation ->
            val add = mutation as RemoteBookshelfMutation.AddItem
            receivedExpected += add.expected
            callCount++
            alreadyRegistered(countItems = callCount)
        }

        val result = repository.addItems(request(itemCount = 2))

        assertTrue(result.items.all { it.outcome == BookshelfBulkAddItemOutcome.AlreadyRegistered })
        assertEquals(confirmed(itemCount = 0), receivedExpected[0])
        assertEquals(confirmed(itemCount = 1), receivedExpected[1])
    }

    // 5: Unknownで打ち切り、残りがNotAttemptedになる。
    @Test
    fun `Unknownで打ち切り残りがNotAttemptedになる`() = runBlocking {
        var callCount = 0
        val repository = repository { _ ->
            callCount++
            if (callCount == 1) RemoteBookshelfOutcome.Unknown else error("2件目以降は送信されてはいけません")
        }

        val result = repository.addItems(request(itemCount = 3))

        assertEquals(
            listOf(BookshelfBulkAddItemOutcome.Unknown, BookshelfBulkAddItemOutcome.NotAttempted, BookshelfBulkAddItemOutcome.NotAttempted),
            result.items.map { it.outcome },
        )
    }

    // 6: Failureで打ち切り、残りがNotAttemptedになる。
    @Test
    fun `Failureで打ち切り残りがNotAttemptedになる`() = runBlocking {
        var callCount = 0
        val repository = repository { _ ->
            callCount++
            if (callCount == 1) RemoteBookshelfOutcome.Failure(FailureReason.SITE_MAINTENANCE) else error("2件目以降は送信されてはいけません")
        }

        val result = repository.addItems(request(itemCount = 3))

        assertEquals(
            listOf(
                BookshelfBulkAddItemOutcome.Failed(FailureReason.SITE_MAINTENANCE),
                BookshelfBulkAddItemOutcome.NotAttempted,
                BookshelfBulkAddItemOutcome.NotAttempted,
            ),
            result.items.map { it.outcome },
        )
    }

    // 7: Room置換の失敗は打ち切らず、結果のlocalRefreshRequiredが真になる。
    @Test
    fun `Room置換の失敗は打ち切らずlocalRefreshRequiredが真になる`() = runBlocking {
        var callCount = 0
        val repository = repository { _ ->
            callCount++
            // shelves=空なのにitemsがshelfNo=1を持つため、replaceShelfSnapshotの整合性チェックで例外になる。
            RemoteBookshelfOutcome.Applied(emptyList(), listOf(shelfItem(shelfNo = 1)))
        }

        val result = repository.addItems(request(itemCount = 2))

        assertTrue(result.localRefreshRequired)
        assertEquals(
            listOf(BookshelfBulkAddItemOutcome.Added, BookshelfBulkAddItemOutcome.Added),
            result.items.map { it.outcome },
        )
    }

    // 8: ログインが一括処理全体で1回、closeも1回(全件成功時・途中打ち切り時・例外時のいずれも)。
    @Test
    fun `ログインとcloseは一括処理全体で1回`() = runBlocking {
        var openCalls = 0
        var closeCalls = 0
        val gateway = object : BookshelfGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): BookshelfSession {
                openCalls++
                return object : BookshelfSession {
                    override suspend fun mutate(mutation: RemoteBookshelfMutation): RemoteBookshelfOutcome = applied(1)
                    override fun close() {
                        closeCalls++
                    }
                }
            }
        }
        repository(gateway).addItems(request(itemCount = 3))
        assertEquals(1, openCalls)
        assertEquals(1, closeCalls)
    }

    @Test
    fun `途中で打ち切ってもログインとcloseは1回のまま`() = runBlocking {
        var openCalls = 0
        var closeCalls = 0
        val gateway = object : BookshelfGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): BookshelfSession {
                openCalls++
                return object : BookshelfSession {
                    override suspend fun mutate(mutation: RemoteBookshelfMutation): RemoteBookshelfOutcome = RemoteBookshelfOutcome.Unknown
                    override fun close() {
                        closeCalls++
                    }
                }
            }
        }
        repository(gateway).addItems(request(itemCount = 3))
        assertEquals(1, openCalls)
        assertEquals(1, closeCalls)
    }

    @Test
    fun `ログイン例外でもopenは1回呼ばれ残り全件がFailedになる`() = runBlocking {
        var openCalls = 0
        val gateway = object : BookshelfGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): BookshelfSession {
                openCalls++
                throw com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError.Network(IllegalStateException())
            }
        }
        val result = repository(gateway).addItems(request(itemCount = 2))
        assertEquals(1, openCalls)
        assertEquals(
            listOf(BookshelfBulkAddItemOutcome.Failed(FailureReason.NETWORK), BookshelfBulkAddItemOutcome.Failed(FailureReason.NETWORK)),
            result.items.map { it.outcome },
        )
    }

    // 9: メモが常に空文字で送られる。
    @Test
    fun `メモは常に空文字で送られる`() = runBlocking {
        val receivedMemos = mutableListOf<String>()
        val repository = repository { mutation ->
            val add = mutation as RemoteBookshelfMutation.AddItem
            receivedMemos += add.memo
            applied(1)
        }
        repository.addItems(request(itemCount = 2))
        assertTrue(receivedMemos.all { it == "" })
    }

    // 10: onProgressが件ごとに呼ばれ、打ち切った件でも呼ばれ、NotAttemptedでは呼ばれない。
    @Test
    fun `onProgressは件ごとに呼ばれ打ち切った件でも呼ばれNotAttemptedでは呼ばれない`() = runBlocking {
        val progressCalls = mutableListOf<Pair<Int, Int>>()
        var callCount = 0
        val repository = repository { _ ->
            callCount++
            if (callCount == 1) RemoteBookshelfOutcome.Unknown else error("2件目以降は送信されてはいけません")
        }
        repository.addItems(request(itemCount = 3)) { completed, total -> progressCalls += completed to total }
        assertEquals(listOf(1 to 3), progressCalls)
    }

    @Test
    fun `全件成功時はonProgressが件数分呼ばれる`() = runBlocking {
        val progressCalls = mutableListOf<Pair<Int, Int>>()
        var callCount = 0
        val repository = repository { _ ->
            callCount++
            applied(callCount)
        }
        repository.addItems(request(itemCount = 3)) { completed, total -> progressCalls += completed to total }
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progressCalls)
    }

    // 11: 空リストでは通信せず(ログインもしない)空の結果。
    @Test
    fun `空リストでは通信せず空の結果を返す`() = runBlocking {
        var openCalls = 0
        val gateway = object : BookshelfGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): BookshelfSession {
                openCalls++
                error("空リストではGatewayを呼んではいけません")
            }
        }
        val result = repository(gateway).addItems(
            BookshelfBulkAddRequest(memberId = member.id, shelfNo = 1, items = emptyList(), confirmed = confirmed()),
        )
        assertEquals(0, openCalls)
        assertEquals(com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddResult(emptyList(), localRefreshRequired = false), result)
    }

    // 12(§9で確定): 開始時のメンバー名が確認時と異なれば、1件も送らず全件Failedにする。
    @Test
    fun `開始時のメンバー名不一致では1件も送らず全件Failedになる`() = runBlocking {
        var openCalls = 0
        val gateway = object : BookshelfGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): BookshelfSession {
                openCalls++
                error("メンバー名不一致ではGatewayを呼んではいけません")
            }
        }
        val progressCalls = mutableListOf<Pair<Int, Int>>()
        val mismatched = confirmed().copy(memberName = "確認時の別名")
        val result = repository(gateway).addItems(request(itemCount = 3, confirmedExpectation = mismatched)) { completed, total ->
            progressCalls += completed to total
        }

        assertEquals(0, openCalls)
        assertEquals(
            listOf(
                BookshelfBulkAddItemOutcome.Failed(FailureReason.SITE_RESPONSE_CHANGED),
                BookshelfBulkAddItemOutcome.Failed(FailureReason.SITE_RESPONSE_CHANGED),
                BookshelfBulkAddItemOutcome.Failed(FailureReason.SITE_RESPONSE_CHANGED),
            ),
            result.items.map { it.outcome },
        )
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progressCalls)
    }

    // 回帰: Room置換が成功する通常経路でも本棚がRoomへ反映されることを確認する。
    @Test
    fun `成功のたびにRoomの本棚が置換される`() = runBlocking {
        var callCount = 0
        val repository = repository { _ ->
            callCount++
            applied(callCount, name = "更新後")
        }
        repository.addItems(request(itemCount = 1))
        assertEquals(listOf("更新後"), database.shelfDao().observeForMember(member.id).first().map { it.name })
    }

    private fun repository(
        mutate: suspend (RemoteBookshelfMutation) -> RemoteBookshelfOutcome,
    ): BookshelfRepositoryImpl = repository(
        gateway = object : BookshelfGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): BookshelfSession =
                object : BookshelfSession {
                    override suspend fun mutate(mutation: RemoteBookshelfMutation): RemoteBookshelfOutcome = mutate(mutation)
                    override fun close() = Unit
                }
        },
    )

    private fun repository(gateway: BookshelfGateway): BookshelfRepositoryImpl = BookshelfRepositoryImpl(
        shelfDao = database.shelfDao(),
        shelfItemDao = database.shelfItemDao(),
        database = database,
        memberDao = database.memberDao(),
        credentialStore = credentials,
        gateway = gateway,
        bookshelfStateGate = BookshelfStateGate(),
    )
}
