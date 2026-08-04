package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.LoanExtensionConfirmationField
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.LoanExtensionConfirmationFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.LoanExtensionListParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.LoanExtensionRequestFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.LoginFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ParseException
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionOutcome
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import okhttp3.FormBody
import org.jsoup.Jsoup

/**
 * 貸出延長だけの通信境界。既存の`ReservationGateway`とは独立させる
 * (`docs/design/loan-extension.md` §3: 貸出と予約はサイト側の別画面・別アクションであり、
 * 認証セッションの使い回し以外に共有する状態がない)。
 *
 * 呼出し元が貸出中一覧であることを前提にしない(§9.1)。引数は延長対象1件を特定する情報
 * (tilcod。memberIdは`openAuthenticatedSession`のカード番号で既に固定される)だけを受け取り、
 * 画面種別に依存する引数・分岐を持たせない。
 */
interface LoanExtensionGateway {
    suspend fun openAuthenticatedSession(cardNumber: String, password: String): LoanExtensionSession
}

interface LoanExtensionSession {
    /**
     * 指定tilcodの貸出資料を延長する。
     *
     * `renewalCode`はこのメソッドの内部でのみ、延長実行のたびに取得し直した貸出状況一覧から得る。
     * Roomにも保持せず、呼出し元(UI層・Repository層)へは一切渡さない(§9.1・§4.1)。
     */
    suspend fun extendLoan(tilcod: String): LoanExtensionOutcome

    fun close()
}

/** root session のスロットリングだけを共有し、Cookieはメンバーごとに隔離する。ReservationGatewayと同型。 */
class LicsXpLoanExtensionGateway(
    private val rootSession: LicsXpSession,
) : LoanExtensionGateway {
    override suspend fun openAuthenticatedSession(cardNumber: String, password: String): LoanExtensionSession {
        require(cardNumber.isNotBlank()) { "カード番号が空です" }
        require(password.isNotBlank()) { "パスワードが空です" }
        val session = rootSession.newIsolatedSession()
        try {
            // 貸出延長のHARは既にログイン済みの状態から始まっており、ログイン入口を実測できていない。
            // 通常の貸出状況一覧取得(LicsXpClient.openCurrentCirculationPrefix)と同じ入口を使う。
            session.get("WOpacEsSchCmpdDispAction.do")
            val loginForm = session.get("OpacInitLoginAction.do", mapOf("subSystemFlag" to "0"))
            requireNotMaintenance(loginForm)
            session.post(
                path = "j_security_check",
                query = mapOf("subSystemFlag" to "0"),
                form = LoginFormParser.parse(loginForm).buildForm(cardNumber, password),
            )
            val menu = session.get("WOpacMnuTopInitAction.do", mapOf("WebLinkFlag" to "1"))
            classifyLoginMenu(menu)
            session.updateTokens(menu)
            return LicsXpLoanExtensionSession(session)
        } catch (exception: ParseException) {
            throw LibraryError.Parse(exception.screen, exception.reason)
        }
    }
}

internal class LicsXpLoanExtensionSession(
    private val session: LicsXpSession,
) : LoanExtensionSession {
    override suspend fun extendLoan(tilcod: String): LoanExtensionOutcome {
        require(tilcod.isNotBlank()) { "tilcodが空です" }
        return session.withExclusiveRequestSequence {
            val menu = get("WOpacMnuTopInitAction.do", mapOf("WebLinkFlag" to "1"))
            requireNotMaintenance(menu)
            if (isLoginForm(menu)) {
                return@withExclusiveRequestSequence LoanExtensionOutcome.Failure(FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT)
            }
            session.updateTokens(menu)
            val tokens = session.requireTokens()
            val listPage = postLoanList(
                "WOpacMnuTopToPwdLibraryAction.do",
                mapOf("gamen" to "usrlend"),
                FormBody.Builder().add("hash", tokens.hash).add("gamenid", tokens.gamenId).build(),
            )
            requireNotMaintenance(listPage.html)
            if (isLoginForm(listPage.html)) {
                return@withExclusiveRequestSequence LoanExtensionOutcome.Failure(FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT)
            }

            // 送信直前に取得した一覧で対象tilcodの行を一意特定する(§4.3・§8: 送信前に停止するフェイルクローズ)。
            val rows = try {
                LoanExtensionListParser.parse(listPage.html)
            } catch (exception: ParseException) {
                throw LibraryError.Parse(exception.screen, exception.reason)
            }
            val matches = rows.filter { it.tilcod == tilcod }
            if (matches.size != 1) {
                return@withExclusiveRequestSequence LoanExtensionOutcome.Failure(FailureReason.SITE_RESPONSE_CHANGED)
            }
            val target = matches.single()
            // extendableでも実行時にrenewalCodeが取得できなければ送信前に停止する(§4.1)。
            val renewalCode = target.renewalCode
                ?: return@withExclusiveRequestSequence LoanExtensionOutcome.Failure(FailureReason.SITE_RESPONSE_CHANGED)
            val beforeDueDate = target.dueDate

            val requestForm = try {
                LoanExtensionRequestFormParser.parse(listPage.html)
            } catch (exception: ParseException) {
                throw LibraryError.Parse(exception.screen, exception.reason)
            }
            // paraだけを対象renewalCodeへ上書きし、他はDOM順・重複込みでそのまま送る(§5.1)。
            val stage1Form = try {
                requestForm.buildForm(renewalCode)
            } catch (exception: ParseException) {
                throw LibraryError.Parse(exception.screen, exception.reason)
            }

            val stage1Page = try {
                postLoanExtensionStage1ExactlyOnce(
                    "WOpacUsrLendListExtendAction.do",
                    mapOf("mngFlg1_handan" to "1"),
                    stage1Form,
                    listPage,
                )
            } catch (_: LibraryError.Network) {
                // 送信開始後の通信断は再送しない。
                return@withExclusiveRequestSequence LoanExtensionOutcome.Unknown
            }
            requireNotMaintenance(stage1Page.html)

            // prevRequestFormの多重集合は1段階目の「query + body」全体と一致する
            // (mngFlg1_handanはqueryだがprevRequestFormには含まれる。`docs/site-research.md` §10)。
            val expectedStage1Fields = buildList {
                add(LoanExtensionConfirmationField("mngFlg1_handan", "1"))
                for (index in 0 until stage1Form.size) {
                    add(LoanExtensionConfirmationField(stage1Form.name(index), stage1Form.value(index)))
                }
            }
            val confirmationForm = try {
                LoanExtensionConfirmationFormParser.parse(stage1Page.html, expectedStage1Fields)
            } catch (_: ParseException) {
                // OK_CODES_NAME・送信先を抽出できなければ2段階目を送らず送信前に停止する(§5.1)。
                // 1段階目は実測どおり延長を確定させない再描画であるため(§2・§5.2)、状態変更POSTは
                // まだ行っていないが、既にネットワークへ1回送っているため成否は一覧照合で確定させる。
                return@withExclusiveRequestSequence resolveExtension(session, tilcod, beforeDueDate)
            }
            val actionUrl = session.baseUrl.resolve(confirmationForm.action)
                ?: return@withExclusiveRequestSequence resolveExtension(session, tilcod, beforeDueDate)

            try {
                postLoanExtensionStage2ExactlyOnce(actionUrl, confirmationForm.buildForm(), stage1Page)
            } catch (_: LibraryError.Network) {
                return@withExclusiveRequestSequence LoanExtensionOutcome.Unknown
            }

            resolveExtension(session, tilcod, beforeDueDate)
        }
    }

    override fun close() = Unit
}

/**
 * 貸出延長POST後の一覧を再取得し、対象tilcodの返却期日の変化で成否を判定する(§5.2)。
 *
 * 対象行は必ずtilcodで探す(並び順が変わり対象行が末尾へ移動することを実測済み)。送信後の一覧は
 * 延長ボタンの有無に関わらず全行を保持して照合する([LoanExtensionListParser]は元々ボタンの無い行も
 * 含めて返すため、ここで絞り込みは行わない)。POST後の照合に失敗する経路(対象消失・複数化・
 * 返却期限が解析できない・通信断・メンテナンス・ログインフォーム)は、既に状態変更POSTを送信済み
 * であるため、Failureにはせず全てUnknownへ倒す。
 */
private suspend fun LicsXpSession.ExclusiveRequestSequence.resolveExtension(
    session: LicsXpSession,
    tilcod: String,
    beforeDueDate: LocalDate,
): LoanExtensionOutcome = try {
    val menu = get("WOpacMnuTopInitAction.do", mapOf("WebLinkFlag" to "1"))
    requireNotMaintenance(menu)
    if (isLoginForm(menu)) {
        LoanExtensionOutcome.Unknown
    } else {
        session.updateTokens(menu)
        val tokens = session.requireTokens()
        val afterListPage = postLoanList(
            "WOpacMnuTopToPwdLibraryAction.do",
            mapOf("gamen" to "usrlend"),
            FormBody.Builder().add("hash", tokens.hash).add("gamenid", tokens.gamenId).build(),
        )
        requireNotMaintenance(afterListPage.html)
        if (isLoginForm(afterListPage.html)) {
            LoanExtensionOutcome.Unknown
        } else {
            val afterRows = LoanExtensionListParser.parse(afterListPage.html)
            val afterMatches = afterRows.filter { it.tilcod == tilcod }
            val afterDueDate = afterMatches.singleOrNull()?.dueDate
            if (afterDueDate != null && afterDueDate.isAfter(beforeDueDate)) {
                LoanExtensionOutcome.Extended(afterDueDate)
            } else {
                LoanExtensionOutcome.Unknown
            }
        }
    }
} catch (exception: CancellationException) {
    throw exception
} catch (_: Exception) {
    LoanExtensionOutcome.Unknown
}

private fun isLoginForm(html: String): Boolean =
    Jsoup.parse(html).selectFirst("input[name=j_password], input[name=j_username], form[action*=j_security_check]") != null

private fun requireNotMaintenance(html: String) {
    if (LOAN_EXTENSION_MAINTENANCE_MARKERS.any(html::contains)) throw LibraryError.Maintenance()
}

private val LOAN_EXTENSION_MAINTENANCE_MARKERS =
    listOf("メンテナンス中", "メンテナンスのため", "システムメンテナンス", "ただいまメンテナンス")

/**
 * j_security_check は画面遷移用の中継HTMLを返すため、本文では認証状態を判定しない。
 * 続くメニュー画面だけを認証結果の根拠にする(LicsXpClient.classifyLoginMenuと同型)。
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
    if (hasLogoutElement) return
    if (isLoginForm(html)) throw LibraryError.Auth(memberName = null)
    requireNotMaintenance(html)
    throw ParseException("login", "ログイン後メニューを判定できません")
}
