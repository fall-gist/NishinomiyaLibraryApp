package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.domain.model.MaterialKind
import com.fallgist.nishinomiyalibrary.domain.model.PublishedRange
import com.fallgist.nishinomiyalibrary.domain.model.SearchQuery
import com.fallgist.nishinomiyalibrary.domain.model.SearchSort
import com.fallgist.nishinomiyalibrary.domain.model.SearchSortKey
import com.fallgist.nishinomiyalibrary.domain.model.SortDirection
import com.fallgist.nishinomiyalibrary.domain.model.StockFilter
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 詳細検索経路(docs/design/search-sort-filter.md §3.2)の通信の組み立て・手順・順序。
 * サイトの応答は採取したHTML(`tif_*.html`)を MockWebServer で返す。実サイトへは通信しない。
 */
class LicsXpClientTifSearchTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val publishedAsc = SearchSort(SearchSortKey.PUBLISHED, SortDirection.ASCENDING)
    private val publishedDesc = SearchSort(SearchSortKey.PUBLISHED, SortDirection.DESCENDING)

    @Test
    fun `キーワード検索は詳細検索のフォームGETと実行POSTを順に行う`() = runBlocking {
        server.enqueue(html(fixture("tif_search_form.html"), setCookie = true))
        server.enqueue(html(fixture("tif_search_result.html")))

        val page = client().search(SearchQuery.keywordOnly("ドラゴンボール"))

        assertEquals(49, page.totalCount)
        assertEquals(20, page.hits.size)
        assertEquals(2, server.requestCount)

        val form = takeRequest()
        assertEquals("GET", form.method)
        assertEquals("/WOpacTifSchCmpdDispAction.do", form.requestUrl!!.encodedPath)
        assertEquals(LicsXpSession.USER_AGENT, form.getHeader("User-Agent"))

        val exec = takeRequest()
        assertEquals("POST", exec.method)
        assertEquals("/WOpacTifSchCmpdExecAction.do", exec.requestUrl!!.encodedPath)
        assertEquals("1", exec.requestUrl!!.queryParameter("tifschcmpd"))
        assertTrue(exec.getHeader("Cookie")!!.contains("JSESSIONID=fixture"))
        // 本文の全項目(§2.1)。語はキーワードだけなので全項目コード7が condition1 に入る。
        assertEquals(
            listOf(
                "chu_search_ini" to "1",
                "hash" to "",
                "jin" to "0",
                "returnid" to "",
                "gamenid" to "tiles.WTifSchCmpd",
                "chkflg" to "nocheck",
                "loccodschkflg" to "nocheck",
                "langcodschkflg" to "nocheck",
                "targetsChkflg" to "nocheck",
                "targetsAvChkflg" to "nocheck",
                "tifKanrabtn" to "",
                "tilkbncodschkflg" to "nocheck",
                "condition1" to "7", "condition1Text" to "ドラゴンボール", "range1" to "0", "mixing1" to "0",
                "condition2" to "", "condition2Text" to "", "range2" to "0", "mixing2" to "0",
                "condition4" to "", "condition4Text" to "", "range4" to "0", "mixing4" to "0",
                "condition5" to "", "condition5Text" to "", "range5" to "0", "mixing5" to "0",
                "condition3" to "5", "condition3Text" to "", "range3" to "0",
                "dispmaxnum" to "20",
                "disporder" to "0",
                "yearselect" to "0",
                "yearstart" to "", "monthstart" to "", "yearend" to "", "monthend" to "",
            ),
            formFields(exec),
        )
    }

    @Test
    fun `語の欄はキーワード書名著者出版者の順に指定分だけ詰めUTF8で送る`() = runBlocking {
        server.enqueue(html(fixture("tif_search_form.html"), setCookie = true))
        server.enqueue(html(fixture("tif_search_result.html")))

        // 書名は空なので飛ばし、著者が2番目の欄、出版者が3番目の欄(condition4)に入る。
        client().search(
            SearchQuery(keyword = "鳥山", title = "  ", author = "明", publisher = "集英社", classification = "726"),
        )

        takeRequest()
        val exec = takeRequest()
        assertEquals("7", formValue(exec, "condition1"))
        assertEquals("鳥山", formValue(exec, "condition1Text"))
        assertEquals("1", formValue(exec, "condition2"))
        assertEquals("明", formValue(exec, "condition2Text"))
        assertEquals("2", formValue(exec, "condition4"))
        assertEquals("集英社", formValue(exec, "condition4Text"))
        assertEquals("", formValue(exec, "condition5"))
        assertEquals("", formValue(exec, "condition5Text"))
        assertEquals("5", formValue(exec, "condition3"))
        assertEquals("726", formValue(exec, "condition3Text"))
        // 生の本文が UTF-8 のパーセント符号化であること(「鳥」= E9 B3 A5)
        assertTrue(exec.body.clone().readUtf8().contains("%E9%B3%A5%E5%B1%B1"))
    }

    @Test
    fun `書名だけなら書名コード0が先頭の欄に入る`() = runBlocking {
        server.enqueue(html(fixture("tif_search_form.html"), setCookie = true))
        server.enqueue(html(fixture("tif_search_result.html")))

        client().search(SearchQuery(title = "ドラゴンボール", publisher = "集英社"))

        takeRequest()
        val exec = takeRequest()
        assertEquals("0", formValue(exec, "condition1"))
        assertEquals("ドラゴンボール", formValue(exec, "condition1Text"))
        assertEquals("2", formValue(exec, "condition2"))
        assertEquals("集英社", formValue(exec, "condition2Text"))
        assertEquals("", formValue(exec, "condition4"))
    }

    @Test
    fun `資料の種類は選んだ数だけmngshusを送りchkflgをcheckにし年月も送る`() = runBlocking {
        server.enqueue(html(fixture("tif_search_form.html"), setCookie = true))
        server.enqueue(html(fixture("tif_search_result.html")))

        client().search(
            SearchQuery(
                materialKinds = setOf(MaterialKind.VIDEO_DVD, MaterialKind.GENERAL, MaterialKind.LARGE_PRINT_PICTURE_BOOK),
                published = PublishedRange(fromYear = 2010, fromMonth = 4, toYear = 2020, toMonth = 12),
            ),
        )

        takeRequest()
        val exec = takeRequest()
        assertEquals(listOf("1", "5", "8"), formFields(exec).filter { it.first == "mngshus" }.map { it.second })
        assertEquals("check", formValue(exec, "chkflg"))
        assertEquals("0", formValue(exec, "yearselect"))
        assertEquals("2010", formValue(exec, "yearstart"))
        assertEquals("4", formValue(exec, "monthstart"))
        assertEquals("2020", formValue(exec, "yearend"))
        assertEquals("12", formValue(exec, "monthend"))
        // 語が無いので語の欄は全て空
        assertEquals("", formValue(exec, "condition1"))
        assertEquals("", formValue(exec, "condition1Text"))
    }

    @Test
    fun `並べ替えが1回で望む向きになれば1回だけ送る`() = runBlocking {
        server.enqueue(html(fixture("tif_search_form.html"), setCookie = true))
        server.enqueue(html(withHash(fixture("tif_search_result.html"), "result-hash")))
        server.enqueue(html(fixture("tif_search_result_pubymd_asc.html")))

        val page = client().search(SearchQuery.keywordOnly("ドラゴンボール").copy(sort = publishedAsc))

        assertEquals(3, server.requestCount)
        assertEquals(publishedAsc, page.currentSort)
        assertEquals("1985/09", page.hits.first().publishedYearMonth)
        takeRequest()
        takeRequest()
        val sort = takeRequest()
        assertEquals("GET", sort.method)
        assertEquals("/WOpacTifTilListDispAction.do", sort.requestUrl!!.encodedPath)
        assertEquals("SLSTITL.PUBYMD_ST", sort.requestUrl!!.queryParameter("sortKey"))
        assertEquals("0", sort.requestUrl!!.queryParameter("startIndex"))
        assertEquals("1", sort.requestUrl!!.queryParameter("notPageSort"))
        assertEquals("result-hash", sort.requestUrl!!.queryParameter("hash"))
    }

    @Test
    fun `降順は1回目で昇順になるのでもう1回送って降順にする`() = runBlocking {
        server.enqueue(html(fixture("tif_search_form.html"), setCookie = true))
        server.enqueue(html(withHash(fixture("tif_search_result.html"), "result-hash")))
        server.enqueue(html(withHash(fixture("tif_search_result_pubymd_asc.html"), "asc-hash")))
        server.enqueue(html(fixture("tif_search_result_pubymd_desc.html")))

        val page = client().search(SearchQuery.keywordOnly("ドラゴンボール").copy(sort = publishedDesc))

        assertEquals(4, server.requestCount)
        assertEquals(publishedDesc, page.currentSort)
        assertEquals("2022/06", page.hits.first().publishedYearMonth)
        takeRequest()
        takeRequest()
        val first = takeRequest()
        val second = takeRequest()
        assertEquals("result-hash", first.requestUrl!!.queryParameter("hash"))
        // 2回目は直前の応答のトークンで送る
        assertEquals("asc-hash", second.requestUrl!!.queryParameter("hash"))
        assertEquals("SLSTITL.PUBYMD_ST", second.requestUrl!!.queryParameter("sortKey"))
        assertEquals("1", second.requestUrl!!.queryParameter("notPageSort"))
    }

    @Test
    fun `2回送っても望む向きにならなければ失敗し3回目は送らない`() = runBlocking {
        server.enqueue(html(fixture("tif_search_form.html"), setCookie = true))
        server.enqueue(html(fixture("tif_search_result.html")))
        server.enqueue(html(fixture("tif_search_result_pubymd_asc.html")))
        server.enqueue(html(fixture("tif_search_result_pubymd_asc.html")))

        val error = failure { client().search(SearchQuery.keywordOnly("a").copy(sort = publishedDesc)) }

        assertTrue(error is LibraryError.Parse)
        assertEquals("search_sort", (error as LibraryError.Parse).screen)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `既定の並びと同じ指定なら並べ替えを送らない`() = runBlocking {
        server.enqueue(html(fixture("tif_search_form.html"), setCookie = true))
        server.enqueue(html(fixture("tif_search_result.html")))

        // 既定の結果は見出しが「書名▲」。同じキーを送ると逆順になってしまうので送らない。
        val page = client().search(
            SearchQuery.keywordOnly("a").copy(sort = SearchSort(SearchSortKey.TITLE, SortDirection.ASCENDING)),
        )

        assertEquals(2, server.requestCount)
        assertEquals(SearchSortKey.TITLE, page.currentSort!!.key)
    }

    @Test
    fun `2ページ目は並べ替えを確立してからstartIndexでページを開き向きを確かめる`() = runBlocking {
        server.enqueue(html(fixture("tif_search_form.html"), setCookie = true))
        server.enqueue(html(fixture("tif_search_result.html")))
        server.enqueue(html(fixture("tif_search_result_pubymd_asc.html")))
        server.enqueue(html(withHash(fixture("tif_search_result_pubymd_desc.html"), "desc-hash")))
        server.enqueue(html(fixture("tif_search_result_pubymd_desc_page2.html")))

        val page = client().search(SearchQuery.keywordOnly("ドラゴンボール").copy(sort = publishedDesc), page = 2)

        assertEquals(5, server.requestCount)
        assertEquals("1992/06", page.hits.first().publishedYearMonth)
        assertEquals(publishedDesc, page.currentSort)
        val paths = (1..5).map { takeRequest() }
        assertEquals(
            listOf(
                "/WOpacTifSchCmpdDispAction.do",
                "/WOpacTifSchCmpdExecAction.do",
                "/WOpacTifTilListDispAction.do",
                "/WOpacTifTilListDispAction.do",
                "/WOpacTifTilListDispAction.do",
            ),
            paths.map { it.requestUrl!!.encodedPath },
        )
        val pageRequest = paths.last()
        assertEquals("GET", pageRequest.method)
        assertEquals("SLSTITL.PUBYMD_ST", pageRequest.requestUrl!!.queryParameter("sortKey"))
        assertEquals("20", pageRequest.requestUrl!!.queryParameter("startIndex"))
        assertEquals("desc-hash", pageRequest.requestUrl!!.queryParameter("hash"))
        assertNull(pageRequest.requestUrl!!.queryParameter("notPageSort"))
    }

    @Test
    fun `ページ送りで向きが保たれなければ失敗する`() = runBlocking {
        server.enqueue(html(fixture("tif_search_form.html"), setCookie = true))
        server.enqueue(html(fixture("tif_search_result.html")))
        server.enqueue(html(fixture("tif_search_result_pubymd_asc.html")))
        server.enqueue(html(fixture("tif_search_result_pubymd_desc.html")))
        // 2ページ目なのに昇順の表示が返る
        server.enqueue(html(fixture("tif_search_result_pubymd_asc.html")))

        val error = failure { client().search(SearchQuery.keywordOnly("a").copy(sort = publishedDesc), page = 2) }

        assertEquals("search_sort", (error as LibraryError.Parse).screen)
    }

    @Test
    fun `並べ替え未指定の2ページ目はsortKeyを空にして直近トークンでページを開く`() = runBlocking {
        server.enqueue(html(fixture("tif_search_form.html"), setCookie = true))
        server.enqueue(html(withHash(fixture("tif_search_result.html"), "result-hash")))
        server.enqueue(html(fixture("tif_search_result_pubymd_desc_page2.html")))

        val page = client().search(SearchQuery.keywordOnly("a"), page = 3)

        assertEquals(3, server.requestCount)
        assertEquals(20, page.hits.size)
        takeRequest()
        takeRequest()
        val pageRequest = takeRequest()
        assertEquals("GET", pageRequest.method)
        assertEquals("/WOpacTifTilListDispAction.do", pageRequest.requestUrl!!.encodedPath)
        assertEquals("", pageRequest.requestUrl!!.queryParameter("sortKey"))
        assertEquals("40", pageRequest.requestUrl!!.queryParameter("startIndex"))
        assertEquals("result-hash", pageRequest.requestUrl!!.queryParameter("hash"))
    }

    @Test
    fun `在庫状況は検索の後で結果画面のhiddenとselectをそのまま送りstockStateだけ差し替える`() = runBlocking {
        server.enqueue(html(fixture("tif_search_form.html"), setCookie = true))
        server.enqueue(html(withHash(fixture("tif_search_result.html"), "result-hash")))
        server.enqueue(html(fixture("tif_search_result_stock_lendable.html")))

        val page = client().search(SearchQuery.keywordOnly("ドラゴンボール").copy(stock = StockFilter.LENDABLE_ONLY))

        assertEquals(43, page.totalCount)
        assertTrue(page.hits.all { it.lendable == true })
        assertEquals(3, server.requestCount)
        takeRequest()
        takeRequest()
        val stock = takeRequest()
        assertEquals("POST", stock.method)
        assertEquals("/WOpacWebTifTilListStockStateSearchAction.do", stock.requestUrl!!.encodedPath)
        val fields = formFields(stock)
        assertEquals(listOf("2"), fields.filter { it.first == "stockState" }.map { it.second })
        assertEquals("result-hash", formValue(stock, "hash"))
        assertEquals("tiles.WTifTilList", formValue(stock, "gamenid"))
        assertEquals("tiles.WTifSchCmpd", formValue(stock, "returnid"))
        assertEquals("20", formValue(stock, "rowsPerPage"))
        // select は選択中の option の値で送る(表示件数20件・検索項目の書名=0)
        assertEquals("20", formValue(stock, "dispmaxnum"))
        assertEquals("0", formValue(stock, "searchkind_add"))
        // 結果画面にあった hidden は欠けない
        assertTrue(fields.any { it.first == "narrowSchCnt" })
        assertTrue(fields.any { it.first == "sortKey" })
    }

    @Test
    fun `在庫状況と並べ替えの順序は検索 在庫状況 並べ替え 2ページ目`() = runBlocking {
        server.enqueue(html(fixture("tif_search_form.html"), setCookie = true))
        server.enqueue(html(fixture("tif_search_result.html")))
        server.enqueue(html(fixture("tif_search_result_stock_lendable.html")))
        server.enqueue(html(fixture("tif_search_result_pubymd_asc.html")))
        server.enqueue(html(fixture("tif_search_result_pubymd_asc.html")))

        // 在庫状況の応答は降順表示なので、昇順を望めば並べ替えを1回送る。その後2ページ目。
        client().search(SearchQuery.keywordOnly("a").copy(stock = StockFilter.READING_ROOM_ONLY, sort = publishedAsc), page = 2)

        val requests = (1..5).map { takeRequest() }
        assertEquals(
            listOf(
                "GET /WOpacTifSchCmpdDispAction.do",
                "POST /WOpacTifSchCmpdExecAction.do",
                "POST /WOpacWebTifTilListStockStateSearchAction.do",
                "GET /WOpacTifTilListDispAction.do",
                "GET /WOpacTifTilListDispAction.do",
            ),
            requests.map { "${it.method} ${it.requestUrl!!.encodedPath}" },
        )
        assertEquals("3", formValue(requests[2], "stockState"))
        assertEquals("1", requests[3].requestUrl!!.queryParameter("notPageSort"))
        assertEquals("20", requests[4].requestUrl!!.queryParameter("startIndex"))
    }

    @Test
    fun `在庫状況が全てなら在庫状況のPOSTを送らない`() = runBlocking {
        server.enqueue(html(fixture("tif_search_form.html"), setCookie = true))
        server.enqueue(html(fixture("tif_search_result.html")))

        client().search(SearchQuery.keywordOnly("a").copy(stock = StockFilter.ALL))

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `条件が不正なら通信せずに拒否する`() = runBlocking {
        val gateway = client()
        try {
            gateway.search(SearchQuery(), page = 1)
            fail("IllegalArgumentException が送出されませんでした")
        } catch (expected: IllegalArgumentException) {
            // 期待どおり
        }
        try {
            gateway.search(SearchQuery(published = PublishedRange(fromYear = 2020, toYear = 2010)), page = 1)
            fail("IllegalArgumentException が送出されませんでした")
        } catch (expected: IllegalArgumentException) {
            // 期待どおり
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `メンテナンス表示はMaintenanceにする`() = runBlocking {
        server.enqueue(html(fixture("tif_search_form.html"), setCookie = true))
        server.enqueue(html("<html><body>ただいまメンテナンス中です</body></html>"))

        val error = failure { client().search(SearchQuery.keywordOnly("a")) }

        assertTrue(error is LibraryError.Maintenance)
        assertFalse(error is LibraryError.Parse)
    }

    private suspend fun failure(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("例外が送出されませんでした")
    } catch (error: LibraryError) {
        error
    }

    private fun withHash(html: String, hash: String): String =
        html.replace("""name="hash" value=""""", """name="hash" value="$hash"""")

    private fun client(): LicsXpClient = LicsXpClient(
        LicsXpSession(
            baseUrl = server.url("/"),
            client = OkHttpClient(),
            waitForRequestSlot = {},
        ),
    )

    private fun html(body: String, setCookie: Boolean = false): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .apply { if (setCookie) setHeader("Set-Cookie", "JSESSIONID=fixture; Path=/") }
        .setBody(body)

    private fun takeRequest(): RecordedRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))

    private fun formValue(request: RecordedRequest, name: String): String? =
        formFields(request).firstOrNull { (key, _) -> key == name }?.second

    private fun formFields(request: RecordedRequest): List<Pair<String, String>> =
        request.body.clone().readUtf8()
            .split('&')
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator < 0) return@mapNotNull null
                URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8) to
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8)
            }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader).getResource("fixtures/$name")!!.readText()
}
