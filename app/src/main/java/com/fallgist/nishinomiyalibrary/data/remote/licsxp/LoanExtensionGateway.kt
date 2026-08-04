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
            // 1段階目POST直後の全ての検証(メンテナンス検知・確認フォーム解析・送信先解決)は、
            // 「状態変更POST(=2段階目)をまだ送っていない」場合の3分岐(§5.3)へ一律で倒す。
            // ここで例外を外へ投げてFailure(SITE_MAINTENANCE)へ倒す(旧実装の欠陥)と、
            // 同じstage1応答に対する他の検証(確認フォーム解析失敗等)と非対称になる。
            if (isMaintenancePage(stage1Page.html)) {
                return@withExclusiveRequestSequence resolveExtensionBeforeStage2Sent(session, tilcod, beforeDueDate)
            }

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
                // 確認コード・OK_CODES_NAME・送信先を抽出できなければ2段階目を送らず送信前に停止する(§5.1)。
                // 1段階目は実測どおり延長を確定させない再描画であるため(§2・§5.2)、状態変更POST(2段階目)は
                // まだ行っていない。§5.3の3分岐で成否を確定させる。
                return@withExclusiveRequestSequence resolveExtensionBeforeStage2Sent(session, tilcod, beforeDueDate)
            }
            val actionUrl = session.baseUrl.resolve(confirmationForm.action)
                ?: return@withExclusiveRequestSequence resolveExtensionBeforeStage2Sent(session, tilcod, beforeDueDate)

            try {
                postLoanExtensionStage2ExactlyOnce(actionUrl, confirmationForm.buildForm(), stage1Page)
            } catch (_: LibraryError.Network) {
                return@withExclusiveRequestSequence LoanExtensionOutcome.Unknown
            }

            // 2段階目(状態変更POST)を送信済みのため、§5.2どおり進捗なしは常にUnknownとする
            // (サイトの拒否と通信の取りこぼしを区別できないため。§5.3の裁定で変わらない)。
            resolveExtensionAfterStage2Sent(session, tilcod, beforeDueDate)
        }
    }

    override fun close() = Unit
}

/**
 * 貸出延長POST後の一覧を再取得し、対象tilcodの返却期日の変化を判定する共通処理(§5.2・§5.3)。
 *
 * 対象行は必ずtilcodで探す(並び順が変わり対象行が末尾へ移動することを実測済み)。送信後の一覧は
 * 延長ボタンの有無に関わらず全行を保持して照合する([LoanExtensionListParser]は元々ボタンの無い行も
 * 含めて返すため、ここで絞り込みは行わない)。
 *
 * 「返却期日が進んでいない」ことと「進んだかどうか確定できない」ことを区別する
 * ([LoanExtensionRefetchResult]参照)。この区別を、2段階目(状態変更POST)を送ったかどうかで
 * 呼び出し側([resolveExtensionBeforeStage2Sent]・[resolveExtensionAfterStage2Sent])が
 * 別々の結果へ変換する。
 */
private suspend fun LicsXpSession.ExclusiveRequestSequence.refetchAndCompareDueDate(
    session: LicsXpSession,
    tilcod: String,
    beforeDueDate: LocalDate,
): LoanExtensionRefetchResult = try {
    val menu = get("WOpacMnuTopInitAction.do", mapOf("WebLinkFlag" to "1"))
    requireNotMaintenance(menu)
    if (isLoginForm(menu)) {
        LoanExtensionRefetchResult.Indeterminate
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
            LoanExtensionRefetchResult.Indeterminate
        } else {
            val afterRows = LoanExtensionListParser.parse(afterListPage.html)
            val afterMatches = afterRows.filter { it.tilcod == tilcod }
            val afterDueDate = afterMatches.singleOrNull()?.dueDate
            when {
                afterDueDate == null -> LoanExtensionRefetchResult.Indeterminate
                afterDueDate.isAfter(beforeDueDate) -> LoanExtensionRefetchResult.Progressed(afterDueDate)
                else -> LoanExtensionRefetchResult.NotProgressed
            }
        }
    }
} catch (exception: CancellationException) {
    throw exception
} catch (_: Exception) {
    LoanExtensionRefetchResult.Indeterminate
}

/** [refetchAndCompareDueDate]の結果。「進捗なしと確定できる」ことと「確定できない」ことを型で区別する。 */
private sealed interface LoanExtensionRefetchResult {
    data class Progressed(val newDueDate: LocalDate) : LoanExtensionRefetchResult
    data object NotProgressed : LoanExtensionRefetchResult
    data object Indeterminate : LoanExtensionRefetchResult
}

/**
 * 1段階目POST後、2段階目(状態変更POST)を送っていない状態からの成否解決(§5.3、2026-08-05所有者裁定)。
 *
 * 1. 返却期日が進んでいれば`Extended`(1段階目に副作用が無いことは未実測のため、進んでいたら正直に成功とする)
 * 2. 再取得・解析に成功し進んでいなければ`Failure(SITE_RESPONSE_CHANGED)`(2段階目を送っていないと確定できるため)
 * 3. 再取得できない・対象を一意特定できない・返却期日を解析できない場合は`Unknown`
 *    (メンテナンス画面を検知した場合は再取得も失敗するため、自動的にここへ倒れる)
 */
private suspend fun LicsXpSession.ExclusiveRequestSequence.resolveExtensionBeforeStage2Sent(
    session: LicsXpSession,
    tilcod: String,
    beforeDueDate: LocalDate,
): LoanExtensionOutcome = when (val result = refetchAndCompareDueDate(session, tilcod, beforeDueDate)) {
    is LoanExtensionRefetchResult.Progressed -> LoanExtensionOutcome.Extended(result.newDueDate)
    LoanExtensionRefetchResult.NotProgressed -> LoanExtensionOutcome.Failure(FailureReason.SITE_RESPONSE_CHANGED)
    LoanExtensionRefetchResult.Indeterminate -> LoanExtensionOutcome.Unknown
}

/**
 * 2段階目(状態変更POST)を送信済みの状態からの成否解決(§5.2)。
 * サイトの拒否と通信の取りこぼしを区別できないため、進捗なしは`NotProgressed`・`Indeterminate`の
 * いずれであっても一律`Unknown`とする(§5.3の裁定でもこの意味論は変わらない)。
 */
private suspend fun LicsXpSession.ExclusiveRequestSequence.resolveExtensionAfterStage2Sent(
    session: LicsXpSession,
    tilcod: String,
    beforeDueDate: LocalDate,
): LoanExtensionOutcome = when (val result = refetchAndCompareDueDate(session, tilcod, beforeDueDate)) {
    is LoanExtensionRefetchResult.Progressed -> LoanExtensionOutcome.Extended(result.newDueDate)
    LoanExtensionRefetchResult.NotProgressed, LoanExtensionRefetchResult.Indeterminate -> LoanExtensionOutcome.Unknown
}

private fun isLoginForm(html: String): Boolean =
    Jsoup.parse(html).selectFirst("input[name=j_password], input[name=j_username], form[action*=j_security_check]") != null

private fun requireNotMaintenance(html: String) {
    if (isMaintenancePage(html)) throw LibraryError.Maintenance()
}

private fun isMaintenancePage(html: String): Boolean = LOAN_EXTENSION_MAINTENANCE_MARKERS.any(html::contains)

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
