package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.BookDetailParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.CalendarParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.LoanListParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ParseException
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ReservationListParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.SearchResultParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ShelfParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.SummaryParser
import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import okhttp3.FormBody

class LicsXpClient(
    private val session: LicsXpSession = LicsXpSession(),
) : LibraryGateway {
    private val json = Json { ignoreUnknownKeys = true }

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

    override suspend fun fetchUserData(cardNumber: String, password: String): UserData = mapErrors {
        // 認証Cookieを公開検索などのセッションと共有しない。
        val userSession = session.newIsolatedSession()
        val loginForm = userSession.get(
            path = "OpacInitLoginAction.do",
            query = mapOf("subSystemFlag" to "0"),
        )
        requireNotMaintenance(loginForm)

        val afterLogin = userSession.post(
            path = "j_security_check",
            query = mapOf("subSystemFlag" to "0"),
            form = FormBody.Builder()
                .add("j_username", "0".repeat(CARD_NUMBER_PREFIX_LENGTH) + cardNumber)
                .add("j_password", password)
                .build(),
        )
        classifyLoginResponse(afterLogin)

        val menu = userSession.get(
            path = "WOpacMnuTopInitAction.do",
            query = mapOf("WebLinkFlag" to "1"),
        )
        requireNotMaintenance(menu)
        userSession.updateTokens(menu)

        val loansHtml = openUserPage(userSession, "usrlend")
        val reservationsHtml = openUserPage(userSession, "usrrsv")
        val shelfHtml = openUserPage(userSession, "mybooklist")

        UserData(
            summary = SummaryParser.parse(loansHtml),
            loans = LoanListParser.parse(loansHtml),
            reservations = ReservationListParser.parse(reservationsHtml),
            shelf = ShelfParser.parse(shelfHtml),
        )
    }

    private suspend fun openUserPage(session: LicsXpSession, gamen: String): String {
        val tokens = session.requireTokens()
        val html = session.post(
            path = "WOpacMnuTopToPwdLibraryAction.do",
            query = mapOf("gamen" to gamen),
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

    private fun classifyLoginResponse(html: String) {
        val document = Jsoup.parse(html)
        val hasLogoutLink = document.select("a").any { anchor ->
            anchor.id().equals("logout", ignoreCase = true) ||
                anchor.text().contains("ログアウト") ||
                anchor.text().contains("logout", ignoreCase = true) ||
                anchor.attr("class").contains("logout", ignoreCase = true) ||
                anchor.attr("href").contains("logout", ignoreCase = true)
        }
        if (hasLogoutLink) return
        if (document.selectFirst("input[name=j_password]") != null) throw LibraryError.Auth(memberName = null)
        requireNotMaintenance(html)
        throw ParseException("login", "ログイン後画面にログアウトリンクが見つかりません")
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
        const val CARD_NUMBER_PREFIX_LENGTH = 16
        const val SEARCH_PAGE_SIZE = 20
        val MAINTENANCE_MARKERS = listOf("メンテナンス中", "メンテナンスのため", "システムメンテナンス", "ただいまメンテナンス")
    }
}
