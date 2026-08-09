package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.domain.model.Shelf
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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

        val outcome = session().mutate(RemoteBookshelfMutation.CreateShelf("新しい棚"))

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

        val outcome = session().mutate(RemoteBookshelfMutation.AddItem(1, added.code, added.memo))

        assertTrue(outcome is RemoteBookshelfOutcome.Applied)
        assertEquals(8, server.requestCount)
        val requests = requests(8)
        assertEquals("/WOpacTifDetailAddBookListAction.do", requests[6].path)
        assertEquals(1, requests.count { it.path == "/WOpacTifDetailAddBookListAction.do" })
    }

    @Test
    fun `追加済み資料は詳細画面も状態変更POSTも行わずAlreadyRegisteredにする`() = runBlocking {
        val existing = FixtureItem("1000000000001", "既存", "メモ")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "棚", items = listOf(existing))))

        val outcome = session().mutate(RemoteBookshelfMutation.AddItem(1, existing.code, "別メモ"))

        assertTrue(outcome is RemoteBookshelfOutcome.AlreadyRegistered)
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

        val outcome = session().mutate(RemoteBookshelfMutation.AddItem(1, added.code, added.memo))

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

        val outcome = session().mutate(RemoteBookshelfMutation.DeleteItem(1, target.code))

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

        val outcome = session().mutate(RemoteBookshelfMutation.RenameShelf(1, "変更後"))

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

        val outcome = session().mutate(RemoteBookshelfMutation.DeleteItem(1, target.code))

        assertEquals(RemoteBookshelfOutcome.Unknown, outcome)
    }

    @Test
    fun `本棚名変更は二段階POSTで成功する`() = runBlocking {
        val item = FixtureItem("1000000000001", "資料", "変更前")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "変更前", items = listOf(item))))
        server.enqueue(page(editPage(1, "変更前", listOf(item))))
        server.enqueue(page(confirmPage(editFields(1, "変更後", listOf(item)), "OPACSDI011")))
        server.enqueue(page("<html>完了</html>"))
        server.enqueue(page(shelfPage(1, "変更後", items = listOf(item))))
        val renamed = session().mutate(RemoteBookshelfMutation.RenameShelf(1, "変更後"))
        assertTrue(renamed is RemoteBookshelfOutcome.Applied)
        assertEquals(9, server.requestCount)
    }

    @Test
    fun `資料メモ更新は二段階POSTで成功する`() = runBlocking {
        val original = FixtureItem("1000000000001", "資料", "変更前")
        val updated = original.copy(memo = "変更後")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "棚", items = listOf(original))))
        server.enqueue(page(editPage(1, "棚", listOf(original))))
        server.enqueue(page(confirmPage(updateMemoFields(1, "棚", listOf(original), updated.memo), "OPACSDI011")))
        server.enqueue(page("<html>完了</html>"))
        server.enqueue(page(shelfPage(1, "棚", items = listOf(updated))))

        val outcome = session().mutate(RemoteBookshelfMutation.UpdateItemMemo(1, original.code, updated.memo))

        assertTrue(outcome is RemoteBookshelfOutcome.Applied)
        assertEquals(9, server.requestCount)
    }

    @Test
    fun `資料メモ更新中に対象棚のメタデータが改変された応答を成功扱いしない`() = runBlocking {
        val original = FixtureItem("1000000000001", "資料", "変更前")
        val updated = original.copy(memo = "変更後")
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "棚", items = listOf(original))))
        server.enqueue(page(editPage(1, "棚", listOf(original))))
        server.enqueue(page(confirmPage(updateMemoFields(1, "棚", listOf(original), updated.memo), "OPACSDI011")))
        server.enqueue(page("<html>完了</html>"))
        server.enqueue(page(shelfPage(1, "改変された棚", items = listOf(updated))))

        val outcome = session().mutate(RemoteBookshelfMutation.UpdateItemMemo(1, original.code, updated.memo))

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

        val outcome = session().mutate(RemoteBookshelfMutation.DeleteShelf(2))

        assertTrue(outcome is RemoteBookshelfOutcome.Applied)
        assertEquals(9, server.requestCount)
        assertEquals("/WOpacSdiBookListDelAction.do?delflg=1", requests(9)[6].path)
    }

    @Test
    fun `stage1確認不成立ではstage2を送らず操作前と同一ならFailureにする`() = runBlocking {
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "作成前")))
        server.enqueue(page(createPage()))
        server.enqueue(page(confirmPage(createFields("新しい棚").reversed(), "OPACSDI017")))
        server.enqueue(page(shelfPage(1, "作成前")))

        val outcome = session().mutate(RemoteBookshelfMutation.CreateShelf("新しい棚"))

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
        server.enqueue(page(confirmPage(createFields("新しい棚").reversed(), "OPACSDI017")))
        server.enqueue(page(shelfPage(2, "新しい棚", shelves = shelves)))
        server.enqueue(page(shelfPage(1, "作成前", shelves = shelves)))

        val outcome = session().mutate(RemoteBookshelfMutation.CreateShelf("新しい棚"))

        assertTrue(outcome is RemoteBookshelfOutcome.Applied)
        assertEquals(9, server.requestCount)
        assertEquals(1, requests(9).count { it.path == "/WOpacSdiBookListExecAction.do" })
    }

    @Test
    fun `stage1確認不成立で異なる状態ならUnknownにする`() = runBlocking {
        enqueueLogin()
        server.enqueue(page(shelfPage(1, "作成前")))
        server.enqueue(page(createPage()))
        server.enqueue(page(confirmPage(createFields("新しい棚").reversed(), "OPACSDI017")))
        server.enqueue(page(shelfPage(1, "他の変更")))

        val outcome = session().mutate(RemoteBookshelfMutation.CreateShelf("新しい棚"))

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

        val outcome = session().mutate(RemoteBookshelfMutation.CreateShelf("新しい棚"))

        assertTrue(outcome is RemoteBookshelfOutcome.Applied)
        assertEquals(10, server.requestCount)
        assertEquals(2, requests(10).count { it.path == "/WOpacSdiBookListExecAction.do" })
    }

    private suspend fun session() = LicsXpBookshelfGateway(LicsXpSession(server.url("/"), waitForRequestSlot = {}))
        .openAuthenticatedSession("1234", "test-password")

    private fun requests(count: Int) = List(count) { server.takeRequest() }

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
    private fun createFields(name: String) = listOf("hash" to "masked", "returnid" to "tiles.WSdiBookList", "gamenid" to "tiles.WSdiBookListNew", "listname" to name, "commnt" to "")

    private fun editPage(no: Int, name: String, items: List<FixtureItem>) = fieldsForm(editFields(no, name, items))
    private fun editFields(no: Int, name: String, items: List<FixtureItem>) = buildList {
        add("hash" to "masked"); add("returnid" to "tiles.WSdiBookList"); add("gamenid" to "tiles.WSdiBookList")
        add("tilcod" to ""); add("dispflg" to ""); add("otherbook" to no.toString()); add("listname" to name); add("commnt" to "")
        items.forEachIndexed { index, item -> add("bookcmnt" to item.memo); add("eachcmnt" to item.memo); add("sortno" to index.toString()); add("eachsortno" to index.toString()) }
    }
    private fun deleteItemFields(no: Int, name: String, items: List<FixtureItem>, code: String) = editFields(no, name, items).map { if (it.first == "tilcod") "tilcod" to code else it }.let { listOf("flg" to "1") + it }
    private fun updateMemoFields(no: Int, name: String, items: List<FixtureItem>, memo: String) = editFields(no, name, items).map { if (it.first == "eachcmnt") "eachcmnt" to memo else it }
    private fun deleteShelfFields(no: Int) = listOf("delflg" to "1", "hash" to "masked", "returnid" to "tiles.WSdiBookList", "gamenid" to "tiles.WSdiBookList", "tilcod" to "", "btnflg" to "", "otherbook" to no.toString())

    /** 実測済み共通構造を縮約した合成fixture。 */
    private fun confirmPage(fields: List<Pair<String, String>>, code: String) = "<form name='prevRequestForm'>${fields.joinToString("") { (name, value) -> if (name == "commnt" || name == "eachcmnt") "<textarea name='$name'>$value</textarea>" else "<input name='$name' value='$value'>" }}</form><script>var OK_CODES_NAME = 'okCodes'; function createConfirmDialog() { var okArray = new Array(); if (rest) { okArray[okArray.length] = '$code'; } for (var i = 0; i < okArray.length; i++) { var newHidden = document.createElement('input'); newHidden.type = 'hidden'; newHidden.name = OK_CODES_NAME; newHidden.value = okArray[i]; document.prevRequestForm.appendChild(newHidden); } document.prevRequestForm.action = '${actionFor(code)}'; document.prevRequestForm.submit(); } window.onload = createConfirmDialog;</script>"
    private fun actionFor(code: String) = when (code) { "OPACSDI017" -> "WOpacSdiBookListExecAction.do"; "OPACSDI011" -> "WOpacSdiBookListUpdateAction.do"; "OPACSDI033" -> "WOpacSdiBookDelAction.do"; else -> "WOpacSdiBookListDelAction.do" }
    private fun fieldsForm(fields: List<Pair<String, String>>) = "<form name='LBForm'>${fields.joinToString("") { (name, value) -> if (name == "commnt" || name == "eachcmnt") "<textarea name='$name'>$value</textarea>" else "<input name='$name' value='$value'>" }}</form>"
    private fun page(body: String) = MockResponse().setBody(body)

    private data class FixtureItem(val code: String, val title: String, val memo: String, val date: String = "2026/08/01")
}
