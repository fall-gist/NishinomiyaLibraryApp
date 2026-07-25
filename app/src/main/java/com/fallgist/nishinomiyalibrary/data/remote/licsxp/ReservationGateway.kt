package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.DirectReservationConfirmParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.DirectReservationConfirmationPage
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.DirectReservationResponseParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.BookDetailReservationFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.LoginFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ParseException
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ReservationListParser
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import org.jsoup.Jsoup
import okhttp3.FormBody

/** 読み取り用 LibraryGateway と切り離した、予約確定だけの通信境界。 */
interface ReservationGateway {
    suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession
}

interface ReservationSession {
    suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt

    suspend fun fetchReservations(): List<Reservation>

    fun close()
}

sealed interface DirectReservationAttempt {
    data object Submitted : DirectReservationAttempt
    data object DuplicateDetected : DirectReservationAttempt
    data object SessionExpiredBeforeSubmit : DirectReservationAttempt
    data object RejectedBeforeSubmit : DirectReservationAttempt
    data object IndeterminateAfterPost : DirectReservationAttempt
}

/** POST前に確定した館不一致。ネットワーク境界内だけで利用する。 */
internal class InvalidPickupLibraryException : Exception()
/** 確定POST後のHTMLが既知構造ではない。再送せず照合に委ねる。 */

/** 確定POSTを行わず、確認画面までの遷移と解析結果だけを調べる診断専用の口。 */
internal interface ReservationConfirmationInspector {
    suspend fun inspectDirectReservationConfirmation(
        tilcod: String,
        pickupLibraryCode: String,
        /** nullなら従来どおり確認画面の解析だけを行う。非nullならメール選択の再表示POSTも1回だけ行う。 */
        contactDirectWebValue: String? = null,
    ): ConfirmationInspection
}

/** メール選択の再表示POST後に、確認画面がどう変わったか。 */
internal data class ContactSelectionRetry(
    val requestedValue: String,
    val parsed: Boolean,
    val fieldNames: List<String>,
    val explicitPickupLibraryCode: String?,
    val explicitContactCode: String?,
    val failureReason: String?,
)

internal sealed interface ConfirmationInspection {
    /** fieldNames は確定POSTで送る successful controls の名前をDOM順で並べたもの。値は含めない。 */
    data class Parsed(
        val fieldNames: List<String>,
        val pickupLibraryCodes: Set<String>,
        val requestedPickupAvailable: Boolean,
        /** 確認画面がselected属性で明示している受取館コード。明示が無ければ null。 */
        val explicitPickupLibraryCode: String?,
        /** 確認画面がselected属性で明示している連絡方法コード。明示が無ければ null。 */
        val explicitContactCode: String?,
        /** contactDirectWebValueが指定された場合だけの、再表示POST後の解析結果。 */
        val contactSelectionRetry: ContactSelectionRetry? = null,
    ) : ConfirmationInspection
    data class ParseFailed(val screen: String, val reason: String) : ConfirmationInspection
    data object SessionExpiredBeforeConfirm : ConfirmationInspection
}

/** root session のスロットリングだけを共有し、Cookieはメンバーごとに隔離する。 */
class LicsXpReservationGateway private constructor(
    private val rootSession: LicsXpSession,
    private val sequenceHooks: ReservationSequenceHooks,
) : ReservationGateway {
    constructor(rootSession: LicsXpSession) : this(rootSession, ReservationSequenceHooks())

    companion object {
        internal fun forTesting(
            rootSession: LicsXpSession,
            sequenceHooks: ReservationSequenceHooks,
        ): LicsXpReservationGateway = LicsXpReservationGateway(rootSession, sequenceHooks)
    }

    override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession {
        require(cardNumber.isNotBlank()) { "カード番号が空です" }
        require(password.isNotBlank()) { "パスワードが空です" }
        val session = rootSession.newIsolatedSession()
        try {
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
            return LicsXpReservationSession(session, sequenceHooks)
        } catch (exception: ParseException) {
            throw LibraryError.Parse(exception.screen, exception.reason)
        }
    }

    private fun classifyLoginMenu(html: String) {
        val document = Jsoup.parse(html)
        if (document.selectFirst("#stat-login") != null || document.select("a[href*=logout], [id*=logout], [class*=logout]").isNotEmpty()) return
        if (isLoginForm(document)) throw LibraryError.Auth(null)
        requireNotMaintenance(html)
        throw ParseException("login", "ログイン後メニューを判定できません")
    }
}

internal class LicsXpReservationSession(
    private val session: LicsXpSession,
    private val sequenceHooks: ReservationSequenceHooks,
) : ReservationSession, ReservationConfirmationInspector {
    override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt {
        require(tilcod.isNotBlank()) { "tilcodが空です" }
        require(pickupLibraryCode in PICKUP_LIBRARY_CODES) { "受取館コードが不正です" }
        return session.withExclusiveRequestSequence {
            val detailPage = getReservationDetail(
                "WOpacTifTilListToTifTilDetailAction.do",
                mapOf("urlNotFlag" to "1", "tilcod" to tilcod),
            )
            val detailHtml = detailPage.html
            requireNotMaintenance(detailHtml)
            if (isLoginForm(Jsoup.parse(detailHtml))) {
                return@withExclusiveRequestSequence DirectReservationAttempt.SessionExpiredBeforeSubmit
            }
            val detailForm = try {
                BookDetailReservationFormParser.parse(detailHtml, tilcod)
            } catch (exception: ParseException) {
                throw LibraryError.Parse(exception.screen, exception.reason)
            }
            val confirmationPage = postReservationConfirmation(
                "WOpacTifDirectYoyDispAction.do",
                mapOf("tilcod" to tilcod),
                detailForm.buildForm(),
                detailPage,
            )
            val confirmHtml = confirmationPage.html
            sequenceHooks.afterConfirmFetched()
            requireNotMaintenance(confirmHtml)
            if (isLoginForm(Jsoup.parse(confirmHtml))) return@withExclusiveRequestSequence DirectReservationAttempt.SessionExpiredBeforeSubmit
            var confirmation = try {
                DirectReservationConfirmParser.parse(confirmHtml, tilcod)
            } catch (exception: ParseException) {
                throw LibraryError.Parse(exception.screen, exception.reason)
            }
            var effectiveConfirmationPage = confirmationPage
            if (confirmation.contactDirectWebValue != CONTACT_DIRECT_WEB_EMAIL) {
                // ブラウザ実測: 確認画面の「予約確認メールを送信する」ボタンは selectyoyrak(returnValue) を呼び、
                // contactdirectweb へ returnValue を設定して webrak=1 で確認画面自身へ再POSTする。
                // dry-run診断で、contactdirectwebだけをDOM順のまま4へ上書きして再POSTすると
                // 確認画面が正常に再表示されることを確認済み。
                // 「4」はcontactのEmailコードとして実測済みだが、contactdirectwebが同じコード体系かは未検証で、
                // 確認できているのは再表示POSTが受理されるという事実だけである。
                val retryForm = confirmation.buildFormWithContactDirectWeb(CONTACT_DIRECT_WEB_EMAIL)
                val retryPage = postReservationConfirmation(
                    "WOpacTifDirectYoyDispAction.do",
                    mapOf("webrak" to "1"),
                    retryForm,
                    confirmationPage,
                )
                val retryHtml = retryPage.html
                requireNotMaintenance(retryHtml)
                if (isLoginForm(Jsoup.parse(retryHtml))) {
                    return@withExclusiveRequestSequence DirectReservationAttempt.SessionExpiredBeforeSubmit
                }
                confirmation = try {
                    DirectReservationConfirmParser.parse(retryHtml, tilcod)
                } catch (exception: ParseException) {
                    throw LibraryError.Parse(exception.screen, exception.reason)
                }
                effectiveConfirmationPage = retryPage
            }
            if (pickupLibraryCode !in confirmation.pickupLibraryCodes) throw InvalidPickupLibraryException()
            val form = confirmation.buildForm(pickupLibraryCode)
            val response = try {
                postReservationExactlyOnce(
                    "WOpacTifDirectYoyExecAction.do",
                    mapOf("tilcod" to tilcod),
                    form,
                    effectiveConfirmationPage,
                )
            } catch (_: LibraryError.Network) {
                return@withExclusiveRequestSequence DirectReservationAttempt.IndeterminateAfterPost
            }
            when (DirectReservationResponseParser.parse(response)) {
                DirectReservationResponseParser.Result.LoginAfterPost -> DirectReservationAttempt.IndeterminateAfterPost
                DirectReservationResponseParser.Result.DuplicateDetected -> DirectReservationAttempt.DuplicateDetected
                DirectReservationResponseParser.Result.IndeterminateAfterPost -> DirectReservationAttempt.IndeterminateAfterPost
            }
        }
    }

    override suspend fun fetchReservations(): List<Reservation> {
        val menu = session.get("WOpacMnuTopInitAction.do", mapOf("WebLinkFlag" to "1"))
        requireNotMaintenance(menu)
        if (isLoginForm(Jsoup.parse(menu))) throw LibraryError.Auth(null)
        session.updateTokens(menu)
        val tokens = session.requireTokens()
        val html = session.post(
            "WOpacMnuTopToPwdLibraryAction.do",
            mapOf("gamen" to "usrrsv"),
            FormBody.Builder().add("hash", tokens.hash).add("gamenid", tokens.gamenId).build(),
        )
        requireNotMaintenance(html)
        try {
            return ReservationListParser.parse(html)
        } catch (exception: ParseException) {
            throw LibraryError.Parse(exception.screen, exception.reason)
        }
    }

    override fun close() = Unit

    /** 確定POSTを行わず、確認画面までの遷移と解析結果だけを調べる。directReserveは呼ばない。 */
    override suspend fun inspectDirectReservationConfirmation(
        tilcod: String,
        pickupLibraryCode: String,
        contactDirectWebValue: String?,
    ): ConfirmationInspection {
        require(tilcod.isNotBlank()) { "tilcodが空です" }
        require(pickupLibraryCode in PICKUP_LIBRARY_CODES) { "受取館コードが不正です" }
        return session.withExclusiveRequestSequence {
            val detailPage = getReservationDetail(
                "WOpacTifTilListToTifTilDetailAction.do",
                mapOf("urlNotFlag" to "1", "tilcod" to tilcod),
            )
            val detailHtml = detailPage.html
            requireNotMaintenance(detailHtml)
            if (isLoginForm(Jsoup.parse(detailHtml))) {
                return@withExclusiveRequestSequence ConfirmationInspection.SessionExpiredBeforeConfirm
            }
            val detailForm = try {
                BookDetailReservationFormParser.parse(detailHtml, tilcod)
            } catch (exception: ParseException) {
                throw LibraryError.Parse(exception.screen, exception.reason)
            }
            val confirmationPage = postReservationConfirmation(
                "WOpacTifDirectYoyDispAction.do",
                mapOf("tilcod" to tilcod),
                detailForm.buildForm(),
                detailPage,
            )
            val confirmHtml = confirmationPage.html
            requireNotMaintenance(confirmHtml)
            if (isLoginForm(Jsoup.parse(confirmHtml))) {
                return@withExclusiveRequestSequence ConfirmationInspection.SessionExpiredBeforeConfirm
            }
            val confirmation = try {
                DirectReservationConfirmParser.parse(confirmHtml, tilcod)
            } catch (exception: ParseException) {
                return@withExclusiveRequestSequence ConfirmationInspection.ParseFailed(exception.screen, exception.reason)
            }
            val contactSelectionRetry = contactDirectWebValue?.let { value ->
                performContactSelectionRetry(value, confirmation, confirmationPage, tilcod)
            }
            ConfirmationInspection.Parsed(
                fieldNames = confirmation.fieldNames,
                pickupLibraryCodes = confirmation.pickupLibraryCodes,
                requestedPickupAvailable = pickupLibraryCode in confirmation.pickupLibraryCodes,
                explicitPickupLibraryCode = confirmation.explicitPickupLibraryCode,
                explicitContactCode = confirmation.explicitContactCode,
                contactSelectionRetry = contactSelectionRetry,
            )
        }
    }
}

/**
 * メール選択の再表示POST（WOpacTifDirectYoyDispAction.do?webrak=1）を排他区間の中で1回だけ行い、
 * 確認画面としての再解析を試みる。receivenameとcontactはサイト発行値のまま送り、
 * contactdirectwebだけを上書きする。再解析に失敗しても例外を投げず、失敗理由を結果へ残す。
 */
private suspend fun LicsXpSession.ExclusiveRequestSequence.performContactSelectionRetry(
    value: String,
    confirmation: DirectReservationConfirmationPage,
    confirmationPage: LicsXpReservationConfirmationPage,
    tilcod: String,
): ContactSelectionRetry {
    val retryForm = confirmation.buildFormWithContactDirectWeb(value)
    return try {
        val retryPage = postReservationConfirmation(
            "WOpacTifDirectYoyDispAction.do",
            mapOf("webrak" to "1"),
            retryForm,
            confirmationPage,
        )
        val retryHtml = retryPage.html
        requireNotMaintenance(retryHtml)
        if (isLoginForm(Jsoup.parse(retryHtml))) {
            failedContactSelectionRetry(value, "再表示POST後にログインフォームへ遷移しました")
        } else {
            val reparsed = DirectReservationConfirmParser.parse(retryHtml, tilcod)
            ContactSelectionRetry(
                requestedValue = value,
                parsed = true,
                fieldNames = reparsed.fieldNames,
                explicitPickupLibraryCode = reparsed.explicitPickupLibraryCode,
                explicitContactCode = reparsed.explicitContactCode,
                failureReason = null,
            )
        }
    } catch (exception: ParseException) {
        failedContactSelectionRetry(value, "${exception.screen}: ${exception.reason}")
    } catch (exception: LibraryError) {
        failedContactSelectionRetry(value, exception.message ?: "再表示POSTに失敗しました")
    }
}

private fun failedContactSelectionRetry(value: String, reason: String): ContactSelectionRetry =
    ContactSelectionRetry(
        requestedValue = value,
        parsed = false,
        fieldNames = emptyList(),
        explicitPickupLibraryCode = null,
        explicitContactCode = null,
        failureReason = reason,
    )

/** 競合試験で確認GET直後の状態を再現するための内部フック。 */
internal data class ReservationSequenceHooks(
    val afterConfirmFetched: suspend () -> Unit = {},
)

private fun isLoginForm(document: org.jsoup.nodes.Document): Boolean =
    document.selectFirst("input[name=j_password], input[name=j_username], form[action*=j_security_check]") != null

private fun requireNotMaintenance(html: String) {
    if (MAINTENANCE_MARKERS.any(html::contains)) throw LibraryError.Maintenance()
}

private val MAINTENANCE_MARKERS = listOf("メンテナンス中", "メンテナンスのため", "システムメンテナンス", "ただいまメンテナンス")
/** 設定とライブ確認で確定している12館。 */
internal val PICKUP_LIBRARY_CODES = setOf("001", "002", "003", "004", "101", "102", "103", "104", "105", "106", "107", "109")

/**
 * 連絡方法Emailのコード。アプリのEmail固定仕様に対応する。
 * `contact` のEmailコードが `4` であることは実測済みだが、`contactdirectweb` が同じコード体系であることは
 * 未検証で、確認できているのは `contactdirectweb=4` での再表示POSTが受理される（確認画面が正常に
 * 再表示される）という事実だけである。
 */
internal const val CONTACT_DIRECT_WEB_EMAIL = "4"
