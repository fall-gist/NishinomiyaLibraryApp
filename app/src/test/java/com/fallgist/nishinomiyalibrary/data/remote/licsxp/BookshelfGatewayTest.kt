package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.domain.model.Shelf
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfExpectedShelf
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutationExpectation
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 実サイトへ接続しない合成fixtureで、段階2の送信列と照合条件を検証する。 */
class BookshelfGatewayTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun `本棚作成は二段階POSTで確認後URLのqueryを除去する`() = runBlocking {
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "作成前")))
        server.enqueue(page(createPage()))
        server.enqueue(page(confirmPage(createFields("新しい棚"), "OPACSDI017")))
        server.enqueue(page("<html>完了</html>"))
        server.enqueue(page(shelfPage(2, "新しい棚", shelves = listOf(1 to "作成前", 2 to "新しい棚"))))
        server.enqueue(page(shelfPage(1, "作成前", shelves = listOf(1 to "作成前", 2 to "新しい棚"))))

        val outcome = session().mutate(RemoteBookshelfMutation.CreateShelf("新しい棚", expected()))

        assertEquals(RemoteBookshelfOutcome.Applied(listOf(Shelf(1, "作成前"), Shelf(2, "新しい棚")), emptyList()), outcome)
        val requests = requests(10)
        assertEquals("/WOpacSdiBookListExecAction.do", requests[6].path)
        assertEquals("/WOpacSdiBookListExecAction.do", requests[7].path)
        assertEquals("hash=masked&returnid=tiles.WSdiBookList&gamenid=tiles.WSdiBookListNew&listname=%E6%96%B0%E3%81%97%E3%81%84%E6%A3%9A&commnt=", requests[6].body.readUtf8())
        assertEquals("okCodes=OPACSDI017", requests[7].body.readUtf8().substringAfterLast('&'))
    }

    @Test
    fun `資料追加は既存資料を保持して一回だけPOSTする`() = runBlocking {
        val old = FixtureItem("1000000000001", "既存", "既存メモ")
        val added = FixtureItem("1000000000002", "追加", "新規メモ")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "棚", items = listOf(old))))
        server.enqueue(page(detailAddPage(added.code)))
        server.enqueue(page("<html>完了</html>"))
        server.enqueue(page(shelfPage(1, "棚", items = listOf(old, added))))

        val outcome = session().mutate(RemoteBookshelfMutation.AddItem(1, added.code, added.memo, expected()))

        assertTrue(outcome is RemoteBookshelfOutcome.Applied)
        assertEquals(8, server.requestCount)
        val requests = requests(8)
        assertEquals("/WOpacTifDetailAddBookListAction.do", requests[6].path)
        assertEquals(1, requests.count { it.path == "/WOpacTifDetailAddBookListAction.do" })
    }

    @Test
    fun `資料追加は状態変更POST応答のトークンで再取得する`() = runBlocking {
        val added = FixtureItem("1000000000002", "追加資料", "新規メモ")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "棚")))
        server.enqueue(page(detailAddPage(added.code)))
        server.enqueue(page(tokenPage("masked-next", "tiles.AfterAdd")))
        server.enqueue(page(shelfPage(1, "棚", items = listOf(added))))

        val outcome = session().mutate(RemoteBookshelfMutation.AddItem(1, added.code, added.memo, expected()))

        assertTrue(outcome is RemoteBookshelfOutcome.Applied)
        val requests = requests(8)
        assertEquals("gamenid=tiles.AfterAdd", requests[7].body.readUtf8().substringAfter('&'))
        assertEquals(1, requests.count { it.path == "/WOpacTifDetailAddBookListAction.do" })
    }

    @Test
    fun `資料追加のPOST応答が空hashなら従来トークンで再取得する`() = runBlocking {
        val added = FixtureItem("1000000000002", "追加資料", "新規メモ")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "棚")))
        server.enqueue(page(detailAddPage(added.code)))
        server.enqueue(page(tokenPage("", "tiles.AfterAdd")))
        server.enqueue(page(shelfPage(1, "棚", items = listOf(added))))

        val outcome = session().mutate(RemoteBookshelfMutation.AddItem(1, added.code, added.memo, expected()))

        assertTrue(outcome is RemoteBookshelfOutcome.Applied)
        val requests = requests(8)
        assertEquals("gamenid=tiles.WSdiBookList", requests[7].body.readUtf8().substringAfter('&'))
        assertEquals(1, requests.count { it.path == "/WOpacTifDetailAddBookListAction.do" })
    }

    @Test
    fun `stage2確定POST応答のトークンで再取得してAppliedにする`() = runBlocking {
        val shelves = listOf(1 to "作成前", 2 to "新しい棚")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "作成前")))
        server.enqueue(page(createPage()))
        server.enqueue(page(confirmPage(createFields("新しい棚"), "OPACSDI017")))
        server.enqueue(page(tokenPage("masked-next", "tiles.AfterCreate")))
        server.enqueue(page(shelfPage(2, "新しい棚", shelves = shelves)))
        server.enqueue(page(shelfPage(1, "作成前", shelves = shelves)))

        val outcome = session().mutate(RemoteBookshelfMutation.CreateShelf("新しい棚", expected()))

        assertTrue(outcome is RemoteBookshelfOutcome.Applied)
        val requests = requests(10)
        assertEquals("gamenid=tiles.AfterCreate", requests[8].body.readUtf8().substringAfter('&'))
        assertEquals(1, requests.count { it.path == "/WOpacSdiBookListExecAction.do" && it.body.readUtf8().contains("okCodes") })
    }

    @Test
    fun `追加済み資料は詳細画面も状態変更POSTも行わずAlreadyRegisteredにする`() = runBlocking {
        val existing = FixtureItem("1000000000001", "既存", "メモ")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "棚", items = listOf(existing))))

        val outcome = session().mutate(RemoteBookshelfMutation.AddItem(1, existing.code, "別メモ", expected()))

        assertTrue(outcome is RemoteBookshelfOutcome.AlreadyRegistered)
        assertEquals(5, server.requestCount)
        assertEquals(0, requests(5).count { it.path == "/WOpacTifDetailAddBookListAction.do" })
    }

    @Test
    fun `確認時の棚名または件数が変化した場合は状態変更POSTを送らない`() = runBlocking {
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "変更後")))

        val outcome = session().mutate(
            RemoteBookshelfMutation.AddItem(
                shelfNo = 1,
                tilcod = "1000000000002",
                memo = "",
                expected = BookshelfMutationExpectation(
                    memberName = "利用者",
                    shelfCount = 1,
                    shelf = BookshelfExpectedShelf(1, "確認時の棚名", 0),
                ),
            ),
        )

        assertEquals(RemoteBookshelfOutcome.Failure(com.fallgist.nishinomiyalibrary.domain.model.FailureReason.SITE_RESPONSE_CHANGED), outcome)
        assertEquals(5, server.requestCount)
        assertEquals(0, requests(5).count { it.path == "/WOpacTifDetailAddBookListAction.do" })
    }

    @Test
    fun `資料追加は既存資料が変化した応答を成功扱いしない`() = runBlocking {
        val old = FixtureItem("1000000000001", "既存", "既存メモ")
        val changed = old.copy(memo = "改変")
        val added = FixtureItem("1000000000002", "追加", "新規メモ")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "棚", items = listOf(old))))
        server.enqueue(page(detailAddPage(added.code)))
        server.enqueue(page("<html>完了</html>"))
        server.enqueue(page(shelfPage(1, "棚", items = listOf(changed, added))))

        val outcome = session().mutate(RemoteBookshelfMutation.AddItem(1, added.code, added.memo, expected()))

        assertEquals(RemoteBookshelfOutcome.Unknown, outcome)
    }

    @Test
    fun `資料削除は対象行だけを除いた資料列との完全一致で成功する`() = runBlocking {
        val first = FixtureItem("1000000000001", "一冊目", "メモ1")
        val target = FixtureItem("1000000000002", "削除対象", "メモ2")
        val last = FixtureItem("1000000000003", "三冊目", "メモ3")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "棚", items = listOf(first, target, last))))
        server.enqueue(page(editPage(1, "棚", listOf(first, target, last))))
        server.enqueue(page(confirmPage(deleteItemFields(1, "棚", listOf(first, target, last), target.code), "OPACSDI033")))
        server.enqueue(page("<html>完了</html>"))
        server.enqueue(page(shelfPage(1, "棚", items = listOf(first, last))))

        val outcome = session().mutate(RemoteBookshelfMutation.DeleteItem(1, target.code, expected()))

        assertTrue(outcome is RemoteBookshelfOutcome.Applied)
        assertEquals(9, server.requestCount)
        assertEquals("/WOpacSdiBookDelAction.do?flg=1", requests(9)[6].path)
    }

    @Test
    fun `編集フォームの行メモ不一致では状態変更POSTを送らない`() = runBlocking {
        val item = FixtureItem("1000000000001", "資料", "表示メモ")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "棚", items = listOf(item))))
        server.enqueue(page(editPage(1, "棚", listOf(item)).replace("<textarea name='eachcmnt'>表示メモ</textarea>", "<textarea name='eachcmnt'>改変</textarea>")))

        val outcome = session().mutate(RemoteBookshelfMutation.RenameShelf(1, "変更後", expected()))

        assertTrue(outcome is RemoteBookshelfOutcome.Failure)
        assertEquals(6, server.requestCount)
        assertEquals(0, requests(6).count { it.path == "/WOpacSdiBookListUpdateAction.do" })
    }

    @Test
    fun `資料削除は残存行の改変を成功扱いしない`() = runBlocking {
        val first = FixtureItem("1000000000001", "一冊目", "メモ1")
        val target = FixtureItem("1000000000002", "削除対象", "メモ2")
        val last = FixtureItem("1000000000003", "三冊目", "メモ3")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "棚", items = listOf(first, target, last))))
        server.enqueue(page(editPage(1, "棚", listOf(first, target, last))))
        server.enqueue(page(confirmPage(deleteItemFields(1, "棚", listOf(first, target, last), target.code), "OPACSDI033")))
        server.enqueue(page("<html>完了</html>"))
        server.enqueue(page(shelfPage(1, "棚", items = listOf(last, first))))

        val outcome = session().mutate(RemoteBookshelfMutation.DeleteItem(1, target.code, expected()))

        assertEquals(RemoteBookshelfOutcome.Unknown, outcome)
    }

    @Test
    fun `本棚名変更は二段階POSTで成功する`() = runBlocking {
        val item = FixtureItem("1000000000001", "資料", "変更前")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "変更前", items = listOf(item))))
        server.enqueue(page(editPage(1, "変更前", listOf(item))))
        server.enqueue(page(inlineUpdateConfirmPage(editFields(1, "変更後", listOf(item)))))
        server.enqueue(page(completionPage(editFields(1, "変更後", listOf(item)))))
        server.enqueue(page("<html>完了</html>"))
        server.enqueue(page(shelfPage(1, "変更後", items = listOf(item))))
        val renamed = session().mutate(RemoteBookshelfMutation.RenameShelf(1, "変更後", expected()))
        assertTrue(renamed is RemoteBookshelfOutcome.Applied)
        assertEquals(10, server.requestCount)
        val requests = requests(10)
        assertEquals("/WOpacSdiBookListDispAction.do", requests[8].path)
        assertEquals("hash=masked&returnid=tiles.WSdiBookList&gamenid=tiles.WSdiBookList&tilcod=&dispflg=&otherbook=1&listname=%E5%A4%89%E6%9B%B4%E5%BE%8C&commnt=&bookcmnt=%E5%A4%89%E6%9B%B4%E5%89%8D&eachcmnt=%E5%A4%89%E6%9B%B4%E5%89%8D&sortno=0&eachsortno=0&okCodes=OPACSDI011", requests[8].body.readUtf8())
    }

    @Test
    fun `資料メモ更新は二段階POSTで成功する`() = runBlocking {
        val original = FixtureItem("1000000000001", "資料", "変更前")
        val updated = original.copy(memo = "変更後")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "棚", items = listOf(original))))
        server.enqueue(page(editPage(1, "棚", listOf(original))))
        server.enqueue(page(inlineUpdateConfirmPage(updateMemoFields(1, "棚", listOf(original), updated.memo))))
        server.enqueue(page(completionPage(updateMemoFields(1, "棚", listOf(original), updated.memo))))
        server.enqueue(page("<html>完了</html>"))
        server.enqueue(page(shelfPage(1, "棚", items = listOf(updated))))

        val outcome = session().mutate(RemoteBookshelfMutation.UpdateItemMemo(1, original.code, updated.memo, expected()))

        assertTrue(outcome is RemoteBookshelfOutcome.Applied)
        assertEquals(10, server.requestCount)
        val requests = requests(10)
        assertEquals("/WOpacSdiBookListDispAction.do", requests[8].path)
        assertTrue(requests[8].body.readUtf8().contains("eachcmnt=%E5%A4%89%E6%9B%B4%E5%BE%8C"))
    }

    @Test
    fun `資料メモ更新中に対象棚のメタデータが改変された応答を成功扱いしない`() = runBlocking {
        val original = FixtureItem("1000000000001", "資料", "変更前")
        val updated = original.copy(memo = "変更後")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "棚", items = listOf(original))))
        server.enqueue(page(editPage(1, "棚", listOf(original))))
        server.enqueue(page(confirmPage(updateMemoFields(1, "棚", listOf(original), updated.memo), "OPACSDI011")))
        server.enqueue(page(completionPage(updateMemoFields(1, "棚", listOf(original), updated.memo))))
        server.enqueue(page("<html>完了</html>"))
        server.enqueue(page(shelfPage(1, "改変された棚", items = listOf(updated))))

        val outcome = session().mutate(RemoteBookshelfMutation.UpdateItemMemo(1, original.code, updated.memo, expected()))

        assertEquals(RemoteBookshelfOutcome.Unknown, outcome)
    }

    @Test
    fun `本棚削除は対象外の棚と資料を保持して成功する`() = runBlocking {
        val kept = FixtureItem("1000000000001", "残す資料", "残すメモ")
        val removed = FixtureItem("1000000000002", "消す資料", "消すメモ")
        val shelves = listOf(1 to "残す棚", 2 to "消す棚")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "残す棚", shelves, listOf(kept))))
        server.enqueue(page(shelfPage(2, "消す棚", shelves, listOf(removed))))
        server.enqueue(page(confirmPage(deleteShelfFields(2), "OPACSDI010")))
        server.enqueue(page("<html>完了</html>"))
        server.enqueue(page(shelfPage(1, "残す棚", items = listOf(kept))))

        val outcome = session().mutate(RemoteBookshelfMutation.DeleteShelf(2, expected(2)))

        assertTrue(outcome is RemoteBookshelfOutcome.Applied)
        assertEquals(9, server.requestCount)
        assertEquals("/WOpacSdiBookListDelAction.do?delflg=1", requests(9)[6].path)
    }

    @Test
    fun `stage1確認不成立ではstage2を送らず操作前と同一ならFailureにする`() = runBlocking {
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "作成前")))
        server.enqueue(page(createPage()))
        server.enqueue(page(confirmPage(createFields("異なる棚"), "OPACSDI017")))
        server.enqueue(page(shelfPage(1, "作成前")))

        val outcome = session().mutate(RemoteBookshelfMutation.CreateShelf("新しい棚", expected()))

        assertTrue(outcome is RemoteBookshelfOutcome.Failure)
        assertEquals(8, server.requestCount)
        assertEquals(1, requests(8).count { it.path == "/WOpacSdiBookListExecAction.do" })
    }

    @Test
    fun `stage1確認不成立でも照合済みの作成結果ならAppliedにする`() = runBlocking {
        val shelves = listOf(1 to "作成前", 2 to "新しい棚")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "作成前")))
        server.enqueue(page(createPage()))
        server.enqueue(page(confirmPage(createFields("異なる棚"), "OPACSDI017")))
        server.enqueue(page(shelfPage(2, "新しい棚", shelves = shelves)))
        server.enqueue(page(shelfPage(1, "作成前", shelves = shelves)))

        val outcome = session().mutate(RemoteBookshelfMutation.CreateShelf("新しい棚", expected()))

        assertTrue(outcome is RemoteBookshelfOutcome.Applied)
        assertEquals(9, server.requestCount)
        assertEquals(1, requests(9).count { it.path == "/WOpacSdiBookListExecAction.do" })
    }

    @Test
    fun `stage1確認不成立で異なる状態ならUnknownにする`() = runBlocking {
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "作成前")))
        server.enqueue(page(createPage()))
        server.enqueue(page(confirmPage(createFields("異なる棚"), "OPACSDI017")))
        server.enqueue(page(shelfPage(1, "他の変更")))

        val outcome = session().mutate(RemoteBookshelfMutation.CreateShelf("新しい棚", expected()))

        assertEquals(RemoteBookshelfOutcome.Unknown, outcome)
        assertEquals(8, server.requestCount)
        assertEquals(1, requests(8).count { it.path == "/WOpacSdiBookListExecAction.do" })
    }

    @Test
    fun `確定POST後の通信断は再送せず照合を一回だけ行う`() = runBlocking {
        val shelves = listOf(1 to "作成前", 2 to "新しい棚")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "作成前")))
        server.enqueue(page(createPage()))
        server.enqueue(page(confirmPage(createFields("新しい棚"), "OPACSDI017")))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(page(shelfPage(2, "新しい棚", shelves = shelves)))
        server.enqueue(page(shelfPage(1, "作成前", shelves = shelves)))

        val outcome = session().mutate(RemoteBookshelfMutation.CreateShelf("新しい棚", expected()))

        assertTrue(outcome is RemoteBookshelfOutcome.Applied)
        assertEquals(10, server.requestCount)
        assertEquals(2, requests(10).count { it.path == "/WOpacSdiBookListExecAction.do" })
    }

    @Test
    fun `stage1応答中の取消は確定POSTを送らず再throwする`() = runBlocking {
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "作成前")))
        server.enqueue(page(createPage()))
        server.enqueue(page(confirmPage(createFields("新しい棚"), "OPACSDI017")).setBodyDelay(100, TimeUnit.MILLISECONDS))

        val deferred = async(Dispatchers.Default) { session().mutate(RemoteBookshelfMutation.CreateShelf("新しい棚", expected())) }
        val requests = requests(7)
        deferred.cancel()
        try {
            deferred.await()
            throw AssertionError("CancellationExceptionが必要です")
        } catch (_: CancellationException) { }

        assertEquals(0, requests.drop(6).count { it.path == "/WOpacSdiBookListExecAction.do" && it.body.readUtf8().contains("okCodes") })
    }

    @Test
    fun `資料追加確定POST応答中の取消はUnknownで再送しない`() = runBlocking {
        val added = FixtureItem("1000000000002", "追加", "新規メモ")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "棚")))
        server.enqueue(page(detailAddPage(added.code)))
        server.enqueue(page("<html>完了</html>").setBodyDelay(100, TimeUnit.MILLISECONDS))

        var observed: RemoteBookshelfOutcome? = null
        val job = launch(Dispatchers.Default) {
            observed = session().mutate(RemoteBookshelfMutation.AddItem(1, added.code, added.memo, expected()))
        }
        val requests = requests(7)
        job.cancel()
        job.join()

        assertEquals(RemoteBookshelfOutcome.Unknown, observed)
        assertTrue(job.isCancelled)
        assertEquals(1, requests.count { it.path == "/WOpacTifDetailAddBookListAction.do" })
    }

    @Test
    fun `stage2確定POST応答中の取消はUnknownで再送しない`() = runBlocking {
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "作成前")))
        server.enqueue(page(createPage()))
        server.enqueue(page(confirmPage(createFields("新しい棚"), "OPACSDI017")))
        server.enqueue(page("<html>完了</html>").setBodyDelay(100, TimeUnit.MILLISECONDS))

        var observed: RemoteBookshelfOutcome? = null
        val job = launch(Dispatchers.Default) {
            observed = session().mutate(RemoteBookshelfMutation.CreateShelf("新しい棚", expected()))
        }
        val requests = requests(8)
        job.cancel()
        job.join()

        assertEquals(RemoteBookshelfOutcome.Unknown, observed)
        assertTrue(job.isCancelled)
        assertEquals(1, requests.count { it.path == "/WOpacSdiBookListExecAction.do" && it.body.readUtf8().contains("okCodes") })
    }

    @Test
    fun `確定POST成功後の再取得応答中取消はUnknownで確定POSTを一回だけ送る`() = runBlocking {
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "作成前")))
        server.enqueue(page(createPage()))
        server.enqueue(page(confirmPage(createFields("新しい棚"), "OPACSDI017")))
        server.enqueue(page("<html>完了</html>"))
        server.enqueue(page(shelfPage(1, "作成前")).setBodyDelay(100, TimeUnit.MILLISECONDS))

        var observed: RemoteBookshelfOutcome? = null
        val job = launch(Dispatchers.Default) {
            observed = session().mutate(RemoteBookshelfMutation.CreateShelf("新しい棚", expected()))
        }
        val requests = requests(9)
        job.cancel()
        job.join()

        assertEquals(RemoteBookshelfOutcome.Unknown, observed)
        assertTrue(job.isCancelled)
        assertEquals(1, requests.count { it.path == "/WOpacSdiBookListExecAction.do" && it.body.readUtf8().contains("okCodes") })
    }

    private suspend fun session() = LicsXpBookshelfGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
        .openAuthenticatedSession("1234", "test-password")

    private fun requests(count: Int) = List(count) { server.takeRequest() }

    private fun expected(shelfCount: Int = 1) = BookshelfMutationExpectation("利用者", shelfCount)

    private fun enqueueLogin() {
        server.enqueue(page("<html>初期化</html>"))
        server.enqueue(page("<form action='j_security_check'><input type='text' name='username'><input type='hidden' name='j_username'><input type='password' name='j_password'></form>"))
        server.enqueue(page("<html>中継</html>"))
        server.enqueue(page("<div id='stat-login'></div><form name='LBForm'><input name='hash' value='masked'><input name='gamenid' value='tiles.Menu'></form>"))
    }

    private fun shelfPage(no: Int, name: String, shelves: List<Pair<Int, String>> = listOf(no to name), items: List<FixtureItem> = emptyList()) = """
        <h1>マイ本棚</h1>
        <form name='LBForm'>
          <input type='hidden' name='hash' value='masked'><input type='hidden' name='returnid' value='tiles.WSdiBookList'>
          <input type='hidden' name='gamenid' value='tiles.WSdiBookList'><input type='hidden' name='tilcod' value=''>
          <input type='hidden' name='btnflg' value=''><input type='hidden' name='otherbook' value='$no'>
        </form>
        <select name='otherbook'>${shelves.joinToString("") { (number, label) -> "<option value='$number'${if (number == no) " selected" else ""}>$label</option>" }}</select>
        <table summary='本棚属性'><tr><td><em class='huge'>$name</em></td></tr></table>
        <table summary='リスト詳細'><tbody>${items.joinToString("") { itemRow(it) }}</tbody></table>
    """.trimIndent()

    private fun itemRow(item: FixtureItem) = "<tr><td class='title'>${item.code} ${item.title}</td><td class='memo'>${item.memo}</td><td>登録日: ${item.date}</td></tr>"

    private fun detailAddPage(code: String): String {
        val names = listOf("islogin", "gamentilcod", "prevORnext", "preNextTilcod", "hash", "syurui", "syuruivalue", "returnid", "diccod", "syuruiName", "btnflg", "execflg", "amazonUrl", "aWSAccessKeyId", "secretAccessKey", "associateTag", "version", "responseGroup", "amazonIsbn", "storeId", "amazonDispFlag", "kensakuFlg", "kensaku", "yoy_directtilcod", "tilcod", "refCode", "gamenid", "booklist", "commnt")
        return "<form name='LBForm'>${names.joinToString("") { name -> if (name == "commnt") "<textarea name='commnt'></textarea>" else "<input name='$name' value='${if (name == "tilcod") code else name}'>" }}</form>"
    }

    private fun createPage() = "<form name='LBForm'><input name='hash' value='masked'><input name='returnid' value='tiles.WSdiBookList'><input name='gamenid' value='tiles.WSdiBookListNew'><input name='listname' value=''><textarea name='commnt'></textarea></form>"
    private fun tokenPage(hash: String, gamenId: String) = "<form name='LBForm'><input name='hash' value='$hash'><input name='gamenid' value='$gamenId'></form>"
    private fun createFields(name: String) = listOf("hash" to "masked", "returnid" to "tiles.WSdiBookList", "gamenid" to "tiles.WSdiBookListNew", "listname" to name, "commnt" to "")

    private fun editPage(no: Int, name: String, items: List<FixtureItem>) = fieldsForm(editFields(no, name, items))
    private fun editFields(no: Int, name: String, items: List<FixtureItem>) = buildList {
        add("hash" to "masked"); add("returnid" to "tiles.WSdiBookList"); add("gamenid" to "tiles.WSdiBookList")
        add("tilcod" to ""); add("dispflg" to ""); add("otherbook" to no.toString()); add("listname" to name); add("commnt" to "")
        items.forEachIndexed { index, item -> add("bookcmnt" to item.memo); add("eachcmnt" to item.memo); add("sortno" to index.toString()); add("eachsortno" to index.toString()) }
    }
    private fun deleteItemFields(no: Int, name: String, items: List<FixtureItem>, code: String) = editFields(no, name, items).map { if (it.first == "tilcod") "tilcod" to code else it }.let { listOf("flg" to "1") + it }
    @Test
    fun `資料メモ更新は実測順に並べ替えられた確認フォームを受理する`() = runBlocking {
        val original = FixtureItem("1000000000001", "資料一件目", "変更前")
        val unchanged = FixtureItem("1000000000002", "資料二件目", "そのまま")
        val updated = original.copy(memo = "変更後")
        val items = listOf(original, unchanged)
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "棚", items = items)))
        server.enqueue(page(editPage(1, "棚", items)))
        server.enqueue(page(inlineUpdateConfirmPage(groupUpdateFields(updateMemoFields(1, "棚", items, updated.memo)))))
        server.enqueue(page(completionPage(groupUpdateFields(updateMemoFields(1, "棚", items, updated.memo)))))
        server.enqueue(page("<html>完了</html>"))
        server.enqueue(page(shelfPage(1, "棚", items = listOf(updated, unchanged))))

        val outcome = session().mutate(RemoteBookshelfMutation.UpdateItemMemo(1, original.code, updated.memo, expected()))

        assertTrue(outcome is RemoteBookshelfOutcome.Applied)
        assertEquals(10, server.requestCount)
        val requests = requests(10)
        assertEquals("/WOpacSdiBookListDispAction.do", requests[8].path)
        assertTrue(requests[8].body.readUtf8().contains("bookcmnt=%E5%A4%89%E6%9B%B4%E5%89%8D&bookcmnt=%E3%81%9D%E3%81%AE%E3%81%BE%E3%81%BE&eachcmnt=%E5%A4%89%E6%9B%B4%E5%BE%8C&eachcmnt=%E3%81%9D%E3%81%AE%E3%81%BE%E3%81%BE"))
    }

    @Test
    fun `UPDATEの第3フォームが不正なら表示POSTを送らず送信後照合だけを行う`() = runBlocking {
        val item = FixtureItem("1000000000001", "資料", "変更前")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "変更前", items = listOf(item))))
        server.enqueue(page(editPage(1, "変更前", listOf(item))))
        server.enqueue(page(inlineUpdateConfirmPage(editFields(1, "変更後", listOf(item)))))
        server.enqueue(page(completionPage(editFields(1, "変更後", listOf(item))).replace("WOpacSdiBookListDispAction.do", "WOpacSdiBookListDispAction.do?next=1")))
        server.enqueue(page(shelfPage(1, "変更後", items = listOf(item))))

        val outcome = session().mutate(RemoteBookshelfMutation.RenameShelf(1, "変更後", expected()))

        assertTrue(outcome is RemoteBookshelfOutcome.Applied)
        assertEquals(9, server.requestCount)
        assertEquals(0, requests(9).count { it.path == "/WOpacSdiBookListDispAction.do" })
    }

    @Test
    fun `UPDATEの第3POST通信断では再送せず送信後照合だけを行う`() = runBlocking {
        val item = FixtureItem("1000000000001", "資料", "変更前")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "変更前", items = listOf(item))))
        server.enqueue(page(editPage(1, "変更前", listOf(item))))
        server.enqueue(page(inlineUpdateConfirmPage(editFields(1, "変更後", listOf(item)))))
        server.enqueue(page(completionPage(editFields(1, "変更後", listOf(item)))))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(page(shelfPage(1, "変更後", items = listOf(item))))

        val outcome = session().mutate(RemoteBookshelfMutation.RenameShelf(1, "変更後", expected()))

        assertTrue(outcome is RemoteBookshelfOutcome.Applied)
        assertEquals(10, server.requestCount)
        assertEquals(1, requests(10).count { it.path == "/WOpacSdiBookListDispAction.do" })
    }

    @Test
    fun `UPDATEの第3POST応答中取消はUnknownで再送しない`() = runBlocking {
        val item = FixtureItem("1000000000001", "資料", "変更前")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "変更前", items = listOf(item))))
        server.enqueue(page(editPage(1, "変更前", listOf(item))))
        server.enqueue(page(inlineUpdateConfirmPage(editFields(1, "変更後", listOf(item)))))
        server.enqueue(page(completionPage(editFields(1, "変更後", listOf(item)))))
        server.enqueue(page("<html>完了</html>").setBodyDelay(100, TimeUnit.MILLISECONDS))

        var observed: RemoteBookshelfOutcome? = null
        val job = launch(Dispatchers.Default) {
            observed = session().mutate(RemoteBookshelfMutation.RenameShelf(1, "変更後", expected()))
        }
        val requests = requests(9)
        job.cancel()
        job.join()

        assertEquals(RemoteBookshelfOutcome.Unknown, observed)
        assertTrue(job.isCancelled)
        assertEquals(1, requests.count { it.path == "/WOpacSdiBookListDispAction.do" })
    }

    private fun updateMemoFields(no: Int, name: String, items: List<FixtureItem>, memo: String): List<Pair<String, String>> {
        var index = -1
        return editFields(no, name, items).map { field ->
            if (field.first == "eachcmnt") index++
            if (field.first == "eachcmnt" && index == 0) "eachcmnt" to memo else field
        }
    }

    /** UPDATE確認画面の実測順は、prefix後に項目名ごとの全資料分を配置する。 */
    private fun groupUpdateFields(fields: List<Pair<String, String>>): List<Pair<String, String>> =
        fields.take(8) + listOf("bookcmnt", "eachcmnt", "sortno", "eachsortno").flatMap { name ->
            fields.drop(8).filter { it.first == name }
        }
    private fun deleteShelfFields(no: Int) = listOf("delflg" to "1", "hash" to "masked", "returnid" to "tiles.WSdiBookList", "gamenid" to "tiles.WSdiBookList", "tilcod" to "", "btnflg" to "", "otherbook" to no.toString())

    /** 実測済み共通構造を縮約した合成fixture。 */
    private fun confirmPage(fields: List<Pair<String, String>>, code: String) = "<form name='prevRequestForm'>${fields.joinToString("") { (name, value) -> if (name == "commnt" || name == "eachcmnt") "<textarea name='$name'>$value</textarea>" else "<input name='$name' value='$value'>" }}</form><script>var OK_CODES_NAME = 'okCodes'; function createConfirmDialog() { var okArray = new Array(); if (rest) { okArray[okArray.length] = '$code'; } for (var i = 0; i < okArray.length; i++) { var newHidden = document.createElement('input'); newHidden.type = 'hidden'; newHidden.name = OK_CODES_NAME; newHidden.value = okArray[i]; document.prevRequestForm.appendChild(newHidden); } document.prevRequestForm.action = '${actionFor(code)}'; document.prevRequestForm.submit(); } window.onload = createConfirmDialog;</script>"
    private fun actionFor(code: String) = when (code) { "OPACSDI017" -> "WOpacSdiBookListExecAction.do"; "OPACSDI011" -> "WOpacSdiBookListUpdateAction.do"; "OPACSDI033" -> "WOpacSdiBookDelAction.do"; else -> "WOpacSdiBookListDelAction.do" }
    private fun inlineUpdateConfirmPage(fields: List<Pair<String, String>>) = "<form name='prevRequestForm'>${fields.joinToString("") { (name, value) -> if (name == "commnt" || name == "eachcmnt") "<textarea name='$name'>$value</textarea>" else "<input name='$name' value='$value'>" }}</form><script>var OK_CODES_NAME = 'okCodes'; var CANCEL_CODES_NAME = 'cancelCodes'; var okArray = new Array(); var cancelArray = new Array(); if (rest) { okArray[okArray.length] = 'OPACSDI011'; submitFlg = false; } else { return cancelDialog(); } for (var i = 0; i < okArray.length; i++) { var newHidden = document.createElement('input'); newHidden.type = 'hidden'; newHidden.name = OK_CODES_NAME; newHidden.value = okArray[i]; document.prevRequestForm.appendChild(newHidden); } for (var c = 0; c < cancelArray.length; c++) { var cancelHidden = document.createElement('input'); cancelHidden.type = 'hidden'; cancelHidden.name = CANCEL_CODES_NAME; cancelHidden.value = cancelArray[c]; document.prevRequestForm.appendChild(cancelHidden); } document.prevRequestForm.action = '/licsxp-opac/WOpacSdiBookListUpdateAction.do'; document.prevRequestForm.submit();</script>"
    private fun completionPage(fields: List<Pair<String, String>>) = "<form name='prevRequestForm'>${(fields + ("okCodes" to "OPACSDI011")).joinToString("") { (name, value) -> if (name == "commnt" || name == "eachcmnt") "<textarea name='$name'>$value</textarea>" else "<input name='$name' value='$value'>" }}</form><script>function createConfirmDialog() { document.prevRequestForm.action = '/licsxp-opac/WOpacSdiBookListDispAction.do'; document.prevRequestForm.submit(); } window.onload = createConfirmDialog;</script>"
    private fun fieldsForm(fields: List<Pair<String, String>>) = "<form name='LBForm'>${fields.joinToString("") { (name, value) -> if (name == "commnt" || name == "eachcmnt") "<textarea name='$name'>$value</textarea>" else "<input name='$name' value='$value'>" }}</form>"
    private fun page(body: String) = MockResponse().setBody(body)

    private data class FixtureItem(val code: String, val title: String, val memo: String, val date: String = "2026/08/01")
}
