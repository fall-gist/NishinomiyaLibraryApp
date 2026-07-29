package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.BookDetailParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.CalendarParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.LoanListParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.LoginFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.NewArrivalListParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.NewArrivalMenuParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ParseException
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ReservationListParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.SearchResultParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ShelfParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ShelfListParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.SummaryParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.UsrReadListParser
import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecordKey
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import okhttp3.FormBody

class LicsXpClient(
    private val session: LicsXpSession = LicsXpSession(),
) : LibraryGateway, CurrentCirculationGateway {
    private val json = Json { ignoreUnknownKeys = true }

    /** 既存の具体クライアント呼出しとの互換用。 */
    suspend fun fetchUserData(cardNumber: String, password: String): UserData =
        fetchUserData(cardNumber, password, emptySet())

    override suspend fun search(keyword: String, page: Int): SearchPage = mapErrors {
        require(page >= 1) { "page は1以上で指定してください" }

        val searchForm = session.get("WOpacEsSchCmpdDispAction.do")
        requireNotMaintenance(searchForm)
        session.updateTokens(searchForm)

        val firstPageHtml = session.post(
            path = "WOpacEsSchCmpdExecAction.do",
            form = FormBody.Builder()
                .add("condition1Text", keyword)
                .add("gamenid", "tiles.WEsSchCmpd")
                .add("tifKanrabtn", "1")
                .add("loccodschkflg", "nocheck")
                .add("returnid", "")
                .add("hash", "")
                .add("chkflg", "")
                .build(),
        )
        requireNotMaintenance(firstPageHtml)
        session.updateTokens(firstPageHtml)

        val pageHtml = if (page == 1) {
            firstPageHtml
        } else {
            val tokens = session.requireTokens()
            session.post(
                path = "WOpacWebEsTilSubListAction.do",
                query = mapOf(
                    "sortKey" to "",
                    "startIndex" to ((page - 1) * SEARCH_PAGE_SIZE).toString(),
                    "hash" to tokens.hash,
                ),
                form = tokenForm(tokens),
            ).also {
                requireNotMaintenance(it)
                session.updateTokens(it)
            }
        }
        SearchResultParser.parse(pageHtml)
    }

    override suspend fun autocomplete(keyword: String): List<String> = mapErrors {
        val body = session.get(
            path = "WOpacEsApiAutoCompleteAction.do",
            query = mapOf("keyword" to keyword),
        )
        requireNotMaintenance(body)
        parseStringArray(body, "autocomplete")
    }

    override suspend fun isLendable(tilcod: String): Boolean? = mapErrors {
        val body = session.post(
            path = "getIsLend.do",
            form = FormBody.Builder().add("tilcod", tilcod).build(),
        )
        requireNotMaintenance(body)
        val value = parseObject(body, "is_lend")["isLend"] as? JsonPrimitive
        when (value?.content) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    override suspend fun bookDetail(tilcod: String): BookDetail = mapErrors {
        val html = session.get(
            path = "WOpacTifTilListToTifTilDetailAction.do",
            query = mapOf("urlNotFlag" to "1", "tilcod" to tilcod),
        )
        requireNotMaintenance(html)
        BookDetailParser.parse(html)
    }

    override suspend fun closedDays(libraryCode: String): List<LocalDate> = mapErrors {
        val html = session.get(
            path = "WOpacMnuTopInitAction.do",
            query = mapOf(
                "WebLinkFlag" to "1",
                "moveToGamenId" to "msgcld",
                "loccod" to libraryCode,
            ),
        )
        requireNotMaintenance(html)
        CalendarParser.parse(html)
    }

    override suspend fun newArrivals(): List<NewArrival> = mapErrors {
        // 認証不要の公開ページ。ジャンル一覧→各ジャンルの順にGETし、tilcodで名寄せする。
        val menuHtml = session.get(
            path = "WOpacMsgNewMenuDispAction.do",
            query = mapOf("moveToGamenId" to "msgnewmenu"),
        )
        requireNotMaintenance(menuHtml)
        val genreCodes = NewArrivalMenuParser.parseGenreCodes(menuHtml)

        // 先勝ちで重複を除く。複数ジャンルに現れる資料は最初に見つけたジャンルの行を採用。
        val byTilcod = LinkedHashMap<String, NewArrival>()
        for (code in genreCodes) {
            val listHtml = session.get(
                path = "WOpacMsgNewMenuToMsgNewListAction.do",
                query = mapOf("newMenuCode" to code),
            )
            requireNotMaintenance(listHtml)
            for (arrival in NewArrivalListParser.parse(listHtml)) {
                byTilcod.putIfAbsent(arrival.tilcod, arrival)
            }
        }
        byTilcod.values.toList()
    }

    override suspend fun fetchUserData(
        cardNumber: String,
        password: String,
        knownReadingRecordKeys: Set<ReadingRecordKey>,
    ): UserData = mapErrors {
        val prefix = openCurrentCirculationPrefix(cardNumber, password)
        val userSession = prefix.session
        val shelfHtml = openUserPage(userSession, "mybooklist")
        val listedShelves = ShelfListParser.parse(shelfHtml)
        val currentShelf = ShelfParser.parse(shelfHtml)
        val listedCurrentShelf = listedShelves.firstOrNull { it.no == currentShelf.shelf.no }
            ?: throw ParseException("shelf", "現在の本棚が一覧にありません")
        if (listedCurrentShelf.name != currentShelf.shelf.name) {
            throw ParseException("shelf", "現在の本棚名が一覧と一致しません")
        }
        val parsedShelves = mutableMapOf(currentShelf.shelf.no to currentShelf)
        for (shelf in listedShelves) {
            if (shelf.no == currentShelf.shelf.no) continue
            val tokens = userSession.requireTokens()
            val otherShelfHtml = userSession.post(
                path = "WOpacSdiBookListToOtherBookDispAction.do",
                query = mapOf("flg" to "1"),
                form = FormBody.Builder()
                    .add("hash", tokens.hash)
                    .add("gamenid", "tiles.WSdiBookList")
                    .add("otherbook", shelf.no.toString())
                    .add("tilcod", "")
                    .add("btnflg", "")
                    .build(),
            )
            requireNotMaintenance(otherShelfHtml)
            userSession.updateTokens(otherShelfHtml)
            val parsedShelf = ShelfParser.parse(otherShelfHtml)
            if (parsedShelf.shelf != shelf) {
                throw ParseException("shelf", "切替後の本棚が要求した本棚と一致しません")
            }
            parsedShelves[shelf.no] = parsedShelf
        }
        val readingRecords = fetchReadingRecords(userSession, knownReadingRecordKeys)

        UserData(
            summary = SummaryParser.parse(prefix.loansHtml),
            loans = prefix.loans,
            reservations = prefix.reservations,
            shelves = listedShelves,
            shelfItems = listedShelves.flatMap { shelf ->
                requireNotNull(parsedShelves[shelf.no]).items
            },
            readingRecords = readingRecords,
        )
    }

    /**
     * 通常同期の要求列の厳密な接頭辞だけを実行する。ここで止める呼出しは本棚・読書履歴に触れない。
     */
    override suspend fun fetchCurrentCirculation(cardNumber: String, password: String): CurrentCirculationSnapshot = mapErrors {
        val prefix = openCurrentCirculationPrefix(cardNumber, password)
        CurrentCirculationSnapshot(
            loans = prefix.loans,
            reservations = prefix.reservations,
            reservationListComplete = prefix.reservationListComplete,
        )
    }

    private suspend fun openCurrentCirculationPrefix(cardNumber: String, password: String): AuthenticatedUserPrefix {
        // 認証Cookieを公開検索などのセッションと共有しない。
        val userSession = session.newIsolatedSession()
        // 通常ページへのアクセスで、分離セッションのJSESSIONIDを有効化する。
        userSession.get("WOpacEsSchCmpdDispAction.do")
        val loginForm = userSession.get(
            path = "OpacInitLoginAction.do",
            query = mapOf("subSystemFlag" to "0"),
        )
        requireNotMaintenance(loginForm)
        userSession.post(
            path = "j_security_check",
            query = mapOf("subSystemFlag" to "0"),
            form = LoginFormParser.parse(loginForm).buildForm(cardNumber, password),
        )
        val menu = userSession.get(
            path = "WOpacMnuTopInitAction.do",
            query = mapOf("WebLinkFlag" to "1"),
        )
        classifyLoginMenu(menu)
        userSession.updateTokens(menu)
        val loansHtml = openUserPage(userSession, "usrlend")
        val reservationsHtml = openUserPage(userSession, "usrrsv")
        val reservations = ReservationListParser.parse(reservationsHtml)
        // 予約数はメニューではなく、通常同期でUserSummaryにも使う貸出ページのサマリを正本にする。
        val reservationCount = runCatching { SummaryParser.parse(loansHtml).reservationCount }.getOrNull()
        return AuthenticatedUserPrefix(
            session = userSession,
            loansHtml = loansHtml,
            loans = LoanListParser.parse(loansHtml),
            reservations = reservations,
            reservationListComplete = reservationCount != null &&
                reservationCount == reservations.count { it.state != ReservationState.CANCELLED },
        )
    }

    private data class AuthenticatedUserPrefix(
        val session: LicsXpSession,
        val loansHtml: String,
        val loans: List<com.fallgist.nishinomiyalibrary.domain.model.Loan>,
        val reservations: List<com.fallgist.nishinomiyalibrary.domain.model.Reservation>,
        val reservationListComplete: Boolean,
    )

    /**
     * 新しい履歴だけを返す。既知キーなしの初回は最終ページまで取得し、
     * 既知キーありの差分同期では先頭から既知行を検出した時点で停止する。
     */
    private suspend fun fetchReadingRecords(
        session: LicsXpSession,
        knownKeys: Set<ReadingRecordKey>,
    ): List<ReadingRecord> {
        var currentStartIndex = 0
        val visitedStartIndexes = mutableSetOf(currentStartIndex)
        openUserPage(session, "usrread", mapOf("initFlag" to "0"))
        // 表示件数はフォームの rowsPerPage 送信でしか変わらない(クエリの pagingMax は無視される)
        val listTokens = session.requireTokens()
        var pageHtml = session.post(
            path = "WOpacUsrReadListAction.do",
            form = FormBody.Builder()
                .add("hash", listTokens.hash)
                .add("gamenid", listTokens.gamenId)
                .add("rowsPerPage", USR_READ_PAGE_SIZE.toString())
                .build(),
        )
        requireNotMaintenance(pageHtml)
        session.updateTokens(pageHtml)
        val fetched = mutableListOf<ReadingRecord>()

        historyPages@ while (true) {
            val page = UsrReadListParser.parse(pageHtml, currentStartIndex)
            for (record in page.records) {
                if (knownKeys.isNotEmpty() && ReadingRecordKey(record.tilcod, record.loanDate) in knownKeys) {
                    // 新しい順の一覧では、ここより後ろは既知領域として扱う。
                    break@historyPages
                }
                fetched += record
            }
            val nextStartIndex = page.nextStartIndex ?: break
            // サイトの異常なページリンクで同じページを周回しない。
            if (!visitedStartIndexes.add(nextStartIndex)) break

            val tokens = session.requireTokens()
            pageHtml = session.get(
                path = "WOpacUsrReadListAction.do",
                query = mapOf(
                    "sortKey" to "KASYMD",
                    "isAsc" to "false",
                    "startIndex" to nextStartIndex.toString(),
                    "hash" to tokens.hash,
                ),
            )
            requireNotMaintenance(pageHtml)
            session.updateTokens(pageHtml)
            currentStartIndex = nextStartIndex
        }
        return fetched
            .asSequence()
            .distinctBy { record -> ReadingRecordKey(record.tilcod, record.loanDate) }
            .toList()
    }

    private suspend fun openUserPage(
        session: LicsXpSession,
        gamen: String,
        additionalQuery: Map<String, String> = emptyMap(),
    ): String {
        val tokens = session.requireTokens()
        val html = session.post(
            path = "WOpacMnuTopToPwdLibraryAction.do",
            query = mapOf("gamen" to gamen) + additionalQuery,
            form = tokenForm(tokens),
        )
        requireNotMaintenance(html)
        session.updateTokens(html)
        return html
    }

    private fun tokenForm(tokens: com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.PageTokens): FormBody =
        FormBody.Builder()
            .add("hash", tokens.hash)
            .add("gamenid", tokens.gamenId)
            .build()

    /**
     * j_security_check は画面遷移用の中継HTMLを返すため、本文では認証状態を判定しない。
     * 続くメニュー画面だけを認証結果の根拠にする。
     */
    private fun classifyLoginMenu(html: String) {
        val document = Jsoup.parse(html)
        if (document.selectFirst("#stat-login") != null) return
        val hasLogoutElement = document.select("a, [id], [class]").any { element ->
            element.id().contains("logout", ignoreCase = true) ||
                element.attr("class").contains("logout", ignoreCase = true) ||
                (element.tagName() == "a" && (
                    element.text().contains("ログアウト") ||
                        element.text().contains("logout", ignoreCase = true) ||
                        element.attr("href").contains("logout", ignoreCase = true)
                    ))
        }
        if (hasLogoutElement) {
            return
        }
        if (document.selectFirst("input[name=j_password], input[name=j_username], form[action*=j_security_check]") != null) {
            throw LibraryError.Auth(memberName = null)
        }
        requireNotMaintenance(html)
        throw ParseException("login", "ログイン後メニューを判定できません")
    }

    private fun requireNotMaintenance(html: String) {
        if (MAINTENANCE_MARKERS.any(html::contains)) throw LibraryError.Maintenance()
    }

    private fun parseStringArray(body: String, screen: String): List<String> {
        val array = try {
            json.parseToJsonElement(body) as? JsonArray
                ?: throw LibraryError.Parse(screen, "JSON配列ではありません")
        } catch (exception: LibraryError.Parse) {
            throw exception
        } catch (exception: Exception) {
            throw LibraryError.Parse(screen, "JSONの解析に失敗しました")
        }
        return array.map { value ->
            val primitive = value as? JsonPrimitive
                ?: throw LibraryError.Parse(screen, "配列要素が文字列ではありません")
            primitive.content
        }
    }

    private fun parseObject(body: String, screen: String): JsonObject = try {
        json.parseToJsonElement(body) as? JsonObject
            ?: throw LibraryError.Parse(screen, "JSONオブジェクトではありません")
    } catch (exception: LibraryError.Parse) {
        throw exception
    } catch (exception: Exception) {
        throw LibraryError.Parse(screen, "JSONの解析に失敗しました")
    }

    private suspend fun <T> mapErrors(block: suspend () -> T): T = try {
        block()
    } catch (exception: LibraryError) {
        throw exception
    } catch (exception: ParseException) {
        throw LibraryError.Parse(exception.screen, exception.reason)
    }

    private companion object {
        const val SEARCH_PAGE_SIZE = 20
        const val USR_READ_PAGE_SIZE = 100
        val MAINTENANCE_MARKERS = listOf("メンテナンス中", "メンテナンスのため", "システムメンテナンス", "ただいまメンテナンス")
    }
}
