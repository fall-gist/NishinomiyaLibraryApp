package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.DirectReservationConfirmParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.DirectReservationConfirmationPage
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.DirectReservationResponseParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.BookDetailReservationFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.LoginFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ParseException
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ReservationCancelFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ReservationListParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.SummaryParser
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import okhttp3.FormBody

/** 読み取り用 LibraryGateway と切り離した、予約確定だけの通信境界。 */
interface ReservationGateway {
    suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession
}

interface ReservationSession {
    suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt

    suspend fun fetchReservations(): List<Reservation>

    /**
     * 指定コードの予約を取り消す。利用者の明示操作からのみ呼ぶこと。
     * 自動処理・バックグラウンド同期からは絶対に呼んではならない（副作用があるサイト操作のため）。
     * 既定実装は未対応として例外を投げる。予約取消に関わらない既存のテスト用フェイクを
     * 壊さないための既定値であり、本番実装(LicsXpReservationSession)は必ずoverrideする。
     */
    suspend fun cancelReservation(cancelCode: String): ReservationCancelAttempt =
        throw UnsupportedOperationException("このセッションは予約取消に対応していません")

    fun close()
}

/** 予約一覧と、その一覧が完全だと確認できたかどうか。照合の断定はcompleteのときだけ行う。 */
internal data class ReservationListSnapshot(
    val reservations: List<Reservation>,
    val complete: Boolean,
)

/**
 * 予約一覧の完全性判定を必要とする呼出し元向けの内部限定API。
 * ReservationSessionは公開インターフェースであり、既存実装（テストのフェイクを含む）を壊さずに
 * メソッドを追加することはできないため、この能力だけを別の internal インターフェースへ切り出す。
 * 呼出し側は `as?` で任意に取得し、実装していないセッションに対しては「完全性は確認できない」
 * ものとして安全側（成否不明）に倒す。
 */
internal interface ReservationSnapshotSource {
    suspend fun fetchReservationSnapshot(): ReservationListSnapshot
}

sealed interface DirectReservationAttempt {
    data object Submitted : DirectReservationAttempt
    data object DuplicateDetected : DirectReservationAttempt
    data object SessionExpiredBeforeSubmit : DirectReservationAttempt
    data object RejectedBeforeSubmit : DirectReservationAttempt
    data object IndeterminateAfterPost : DirectReservationAttempt
    /** 確定POST後も確認画面のまま。業務的拒否（上限超過など）の可能性があるが、この時点では断定できない。 */
    data object StayedOnConfirmation : DirectReservationAttempt
    /** 実測(2026-07-27)の予約制限超過ダイアログ文言。message はサイトが返した文言そのもの。 */
    data class LimitExceeded(val message: String) : DirectReservationAttempt
    /** 実測(2026-07-27)の成功ダイアログ文言。ただし成否の最終判断は従来どおり予約一覧照合で行う。 */
    data object Registered : DirectReservationAttempt
}

/**
 * 予約取消の1回の試みの結果。
 *
 * 実測(2026-07-27)で判明したとおり、予約取消は2段階になっている。
 * `WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1` への1回目のPOSTは、
 * ダイアログ文言 `予約の取消を行います。よろしいですか？` を含む予約状況一覧の画面を返すだけであり、
 * この時点では取り消されていない（取消後の一覧に対象が残っていることを実測で確認済み）。
 * ページ側のスクリプトは、このダイアログのOK後に `document.prevRequestForm`（1段階目に送った
 * フォーム）へ `okCodes` のhiddenを追加してそのまま再送信する仕組みであることが実測(2026-07-27)で
 * 判明したため、実装は1段階目に確認ダイアログ文言を検出した場合、`okCodes=OPACUSR001` を付けた
 * 2段階目を `WOpacUsrRsvCancelAction.do`（クエリ無し）へ自動的に送る。
 * ただし、この2段階目のPOSTによって実サイト上で実際に予約が取り消されることは、本実装時点では
 * まだ確認できていない。
 *
 * [Rejected] はかつて「できません」「越えています」等の一般語で判定していたが、これらは
 * 全ページに埋め込まれた共通JSの定数（`仮パスワードでは利用できません。パスワード変更を行なって
 * ください。` 等）にも一致してしまい誤検出することが実測で判明したため、現在は使用していない
 * （型としては残すが、生成箇所は無い）。
 */
sealed interface ReservationCancelAttempt {
    /** 取消POST後に一覧を再取得し、対象コードが消えていたことを確認できた。 */
    data object Cancelled : ReservationCancelAttempt
    data object SessionExpiredBeforeSubmit : ReservationCancelAttempt
    data object IndeterminateAfterPost : ReservationCancelAttempt
    /**
     * 応答が「予約の取消を行います。よろしいですか？」という確認ダイアログ文言を含んでいた。
     * 実装は1段階目でこの文言を検出すると、`okCodes=OPACUSR001` を付けた2段階目を自動的に送るため、
     * この結果になるのは2段階目の応答にも同じ確認ダイアログ文言が含まれていた場合だけである。
     * message はサイトが返した確認ダイアログ文言そのもの（1段階目・2段階目いずれかの応答由来）。
     */
    data class ConfirmationRequired(val message: String) : ReservationCancelAttempt
    /**
     * 現在は生成されない。一般語による拒否判定が誤検出を招くことが実測で判明したため。
     * 実測で拒否文言そのものが判明した場合は、[ConfirmationRequired] のように専用の結果を
     * 追加すること。
     */
    data class Rejected(val message: String) : ReservationCancelAttempt
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
    /** 再表示後の確認画面のhidden hashが空でないか。再表示自体に失敗した場合は false。 */
    val hashPresent: Boolean,
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
        /** 確認フォームのhidden hashが空でないか。値そのものは保持しない。 */
        val confirmHashPresent: Boolean = false,
        /** 書誌詳細ページ(LBForm)のhidden hashが空でないか。値そのものは保持しない。 */
        val detailHashPresent: Boolean = false,
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
            // ブラウザ実測(2026-07-26)の予約成立時の遷移列では、ログインフォームの入口が
            // WOpacInitLoginActiontemp.do であり、j_security_check?subSystemFlag=0 への送信先は
            // 従来と同一。認証後の戻り先はこの入口によって決まるため、読み取り用(LicsXpClient)の
            // OpacInitLoginAction.do とは意図的に分けている。
            val loginForm = session.get("WOpacInitLoginActiontemp.do")
            requireNotMaintenance(loginForm)
            session.post(
                path = "j_security_check",
                query = mapOf("subSystemFlag" to "0"),
                form = LoginFormParser.parse(loginForm).buildForm(cardNumber, password),
            )
            // ブラウザは認証直後にWPwdLoginCheckAction.doへ遷移してからメニューに入る。
            // 未認証時は200・空ボディを返すため、内容は解析せずメンテナンス判定のみ行う。
            // この1回のGETが予約成立に必要かは未検証。
            val loginCheck = session.get("WPwdLoginCheckAction.do")
            requireNotMaintenance(loginCheck)
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
) : ReservationSession, ReservationConfirmationInspector, ReservationSnapshotSource {
    override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt {
        require(tilcod.isNotBlank()) { "tilcodが空です" }
        require(pickupLibraryCode in PICKUP_LIBRARY_CODES) { "受取館コードが不正です" }
        return session.withExclusiveRequestSequence {
            // ブラウザ実測: 入口アクションによって描画されるgamenidとhashの有無が変わる。
            // WOpacTifTilListToTifTilDetailAction.do は tiles.WTifTilDetail をhash空で描画し、
            // 確定POSTが詳細検索画面へ差し戻される。WOpacMsgNewListToTifTilDetailAction.do は
            // tiles.WTifTilDetail2 をhash非空で描画し、ブラウザの予約成立と同じ経路になる。
            val detailPage = getReservationDetail(
                "WOpacMsgNewListToTifTilDetailAction.do",
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
                session.noteDiagnostic("direct-reserve", "${exception.screen}: ${exception.reason}")
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
            val confirmation = try {
                DirectReservationConfirmParser.parse(confirmHtml, tilcod)
            } catch (exception: ParseException) {
                session.noteDiagnostic("direct-reserve", "${exception.screen}: ${exception.reason}")
                throw LibraryError.Parse(exception.screen, exception.reason)
            }
            if (pickupLibraryCode !in confirmation.pickupLibraryCodes) throw InvalidPickupLibraryException()
            val form = confirmation.buildForm(pickupLibraryCode)
            val response = try {
                postReservationExactlyOnce(
                    "WOpacTifDirectYoyExecAction.do",
                    mapOf("tilcod" to tilcod),
                    form,
                    confirmationPage,
                )
            } catch (_: LibraryError.Network) {
                return@withExclusiveRequestSequence DirectReservationAttempt.IndeterminateAfterPost
            }
            when (val parsed = DirectReservationResponseParser.parse(response)) {
                DirectReservationResponseParser.Result.LoginAfterPost -> DirectReservationAttempt.IndeterminateAfterPost
                DirectReservationResponseParser.Result.DuplicateDetected -> DirectReservationAttempt.DuplicateDetected
                DirectReservationResponseParser.Result.IndeterminateAfterPost -> DirectReservationAttempt.IndeterminateAfterPost
                DirectReservationResponseParser.Result.StayedOnConfirmation -> DirectReservationAttempt.StayedOnConfirmation
                is DirectReservationResponseParser.Result.LimitExceeded -> DirectReservationAttempt.LimitExceeded(parsed.message)
                DirectReservationResponseParser.Result.Registered -> DirectReservationAttempt.Registered
            }
        }
    }

    override suspend fun fetchReservations(): List<Reservation> = fetchReservationSnapshot().reservations

    /**
     * 予約一覧と、その一覧が完全だと確認できたかどうかを返す。
     * メニューHTML（WOpacMnuTopInitAction.do）には既にログイン後の利用状況サマリが含まれているため、
     * 新たなリクエストを発生させずにサマリの予約中件数を取り出せる。この件数と一覧の解析行数が
     * 一致した場合だけ「一覧は完全」と判定する。サマリが解析できない、または件数が一致しない場合は
     * ページングの有無などを確認できないため、完全とは断定しない。
     */
    override suspend fun fetchReservationSnapshot(): ReservationListSnapshot {
        val menu = session.get("WOpacMnuTopInitAction.do", mapOf("WebLinkFlag" to "1"))
        requireNotMaintenance(menu) { session.noteDiagnostic("fetch-reservations", "メンテナンス") }
        if (isLoginForm(Jsoup.parse(menu))) {
            session.noteDiagnostic("fetch-reservations", "ログインフォームが返った")
            throw LibraryError.Auth(null)
        }
        session.updateTokens(menu)
        // サマリの解析失敗は致命的にせず、完全性を確認できないものとして扱う。
        val summaryReservationCount = runCatching { SummaryParser.parse(menu).reservationCount }.getOrNull()
        val tokens = session.requireTokens()
        val html = session.post(
            "WOpacMnuTopToPwdLibraryAction.do",
            mapOf("gamen" to "usrrsv"),
            FormBody.Builder().add("hash", tokens.hash).add("gamenid", tokens.gamenId).build(),
        )
        requireNotMaintenance(html) { session.noteDiagnostic("fetch-reservations", "メンテナンス") }
        val reservations = try {
            ReservationListParser.parse(html)
        } catch (exception: ParseException) {
            session.noteDiagnostic("fetch-reservations", "${exception.screen}: ${exception.reason}")
            throw LibraryError.Parse(exception.screen, exception.reason)
        }
        val complete = summaryReservationCount != null && summaryReservationCount == reservations.size
        if (!complete) {
            session.noteDiagnostic(
                "fetch-reservations",
                "サマリ件数と解析行数が不一致 (summary=${summaryReservationCount?.toString() ?: "取得不可"}, parsed=${reservations.size})",
            )
        }
        return ReservationListSnapshot(reservations, complete)
    }

    /**
     * 予約を取り消す。予約確定(directReserve)と同じ安全策を守る:
     * 排他区間の中で行い、各POSTはexactly-once・自動リトライ無効で送る。
     * 実測(2026-07-27)のとおり取消は2段階であり、1段階目の応答に確認ダイアログ文言
     * （`取消を行います`）を検出した場合だけ、`okCodes=OPACUSR001` を付けた2段階目を自動的に送る。
     * いずれの段階でも確認ダイアログ文言が返らなければ、取消後の一覧照合で成否を判定する。
     */
    override suspend fun cancelReservation(cancelCode: String): ReservationCancelAttempt {
        require(cancelCode.isNotBlank()) { "cancelCodeが空です" }
        return session.withExclusiveRequestSequence {
            val menu = get("WOpacMnuTopInitAction.do", mapOf("WebLinkFlag" to "1"))
            requireNotMaintenance(menu) { session.noteDiagnostic("cancel-reservation", "メンテナンス") }
            if (isLoginForm(Jsoup.parse(menu))) {
                session.noteDiagnostic("cancel-reservation", "ログインフォームが返った")
                return@withExclusiveRequestSequence ReservationCancelAttempt.SessionExpiredBeforeSubmit
            }
            session.updateTokens(menu)
            val tokens = session.requireTokens()
            fun listForm() = FormBody.Builder().add("hash", tokens.hash).add("gamenid", tokens.gamenId).build()

            val listPage = postReservationList("WOpacMnuTopToPwdLibraryAction.do", mapOf("gamen" to "usrrsv"), listForm())
            val listHtml = listPage.html
            requireNotMaintenance(listHtml) { session.noteDiagnostic("cancel-reservation", "メンテナンス") }
            if (isLoginForm(Jsoup.parse(listHtml))) {
                session.noteDiagnostic("cancel-reservation", "一覧取得でログインフォームが返った")
                return@withExclusiveRequestSequence ReservationCancelAttempt.SessionExpiredBeforeSubmit
            }
            val cancelForm = try {
                ReservationCancelFormParser.parse(listHtml).buildForm(cancelCode)
            } catch (exception: ParseException) {
                session.noteDiagnostic("cancel-reservation", "${exception.screen}: ${exception.reason}")
                throw LibraryError.Parse(exception.screen, exception.reason)
            }
            val stage1Page = try {
                postReservationExactlyOnce(
                    "WOpacUsrRsvCancelAction.do",
                    mapOf("mngFlg2_handan" to "1", "kbnchgflag" to "1"),
                    cancelForm,
                    listPage,
                )
            } catch (_: LibraryError.Network) {
                return@withExclusiveRequestSequence ReservationCancelAttempt.IndeterminateAfterPost
            }
            val stage1Html = stage1Page.html
            requireNotMaintenance(stage1Html) { session.noteDiagnostic("cancel-reservation", "メンテナンス") }
            // 実測(2026-07-27): 1回目のPOST応答は「予約の取消を行います。よろしいですか？」という
            // 確認ダイアログ文言を含む一覧画面であり、この時点ではまだ取り消されていない。
            // 一般語（「できません」等）による判定は、全ページ共通のJS定数に誤反応するため行わない。
            val stage1ConfirmationMessage = extractSiteMessages(Jsoup.parse(stage1Html))
                .firstOrNull { message -> message.contains(CANCEL_CONFIRMATION_MARKER) }
            if (stage1ConfirmationMessage == null) {
                return@withExclusiveRequestSequence resolveCancelByListDiff(session, cancelCode, ::listForm)
            }
            session.noteDiagnostic("cancel-reservation", "確認ダイアログを検出し2段階目を送信")
            // 実測(2026-07-27): ページ側スクリプトは確認ダイアログのOK後、document.prevRequestForm
            // （1段階目に送ったフォーム）へ okCodes のhiddenを追加してそのまま再送信するだけである。
            // そのため2段階目の本文は「1段階目のクエリパラメータ→1段階目と同じ本文(同じ順序・
            // 同名重複のまま)→okCodes」の順に組み立てる。
            // 注意: okCodes=OPACUSR001 の値、および mngFlg2_handan/kbnchgflag を本文へ入れることは
            // 予約取消の確認ダイアログに固有の実測値であり、他の操作では異なるメッセージコードになる。
            val stage2Form = FormBody.Builder().apply {
                add("mngFlg2_handan", "1")
                add("kbnchgflag", "1")
                for (index in 0 until cancelForm.size) {
                    add(cancelForm.name(index), cancelForm.value(index))
                }
                add("okCodes", "OPACUSR001")
            }.build()
            val stage2Response = try {
                postReservationExactlyOnce(
                    "WOpacUsrRsvCancelAction.do",
                    emptyMap(),
                    stage2Form,
                    stage1Page,
                )
            } catch (_: LibraryError.Network) {
                return@withExclusiveRequestSequence ReservationCancelAttempt.IndeterminateAfterPost
            }
            requireNotMaintenance(stage2Response) { session.noteDiagnostic("cancel-reservation", "メンテナンス") }
            val stage2ConfirmationMessage = extractSiteMessages(Jsoup.parse(stage2Response))
                .firstOrNull { message -> message.contains(CANCEL_CONFIRMATION_MARKER) }
            if (stage2ConfirmationMessage != null) {
                session.noteDiagnostic("cancel-reservation", "2段階目後も確認ダイアログが返り取消は未完了")
                return@withExclusiveRequestSequence ReservationCancelAttempt.ConfirmationRequired(stage2ConfirmationMessage)
            }
            resolveCancelByListDiff(session, cancelCode, ::listForm)
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
            // 本番のdirectReserveと同じ入口アクション（詳細はdirectReserve側のコメント参照）。
            val detailPage = getReservationDetail(
                "WOpacMsgNewListToTifTilDetailAction.do",
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
                confirmHashPresent = confirmation.hasNonEmptyHash,
                detailHashPresent = hasNonEmptyDetailHash(detailHtml),
            )
        }
    }
}

/**
 * 取消後の一覧を1回だけ再取得し、対象コードが消えたかどうかで成否を判定する。
 * 再取得できない、または解析できない場合は成否不明として扱い、POSTは再送しない。
 * 1段階目のみで確認ダイアログが出なかった場合と、2段階目送信後に確認ダイアログが出なかった場合の
 * 両方から呼ばれる共通ロジックであり、cancelReservationから重複を避けるために切り出した。
 */
private suspend fun LicsXpSession.ExclusiveRequestSequence.resolveCancelByListDiff(
    session: LicsXpSession,
    cancelCode: String,
    listForm: () -> FormBody,
): ReservationCancelAttempt {
    val stillPresent = try {
        val refetched = postReservationList("WOpacMnuTopToPwdLibraryAction.do", mapOf("gamen" to "usrrsv"), listForm())
        if (isLoginForm(Jsoup.parse(refetched.html))) {
            session.noteDiagnostic("cancel-reservation", "取消後の一覧取得でログインフォームが返った")
            null
        } else {
            requireNotMaintenance(refetched.html) { session.noteDiagnostic("cancel-reservation", "メンテナンス") }
            ReservationListParser.parse(refetched.html).any { it.cancelCode == cancelCode }
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        session.noteDiagnostic("cancel-reservation", "取消後の一覧を解析できなかった")
        null
    }
    return when (stillPresent) {
        false -> ReservationCancelAttempt.Cancelled
        else -> ReservationCancelAttempt.IndeterminateAfterPost
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
                hashPresent = reparsed.hasNonEmptyHash,
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
        hashPresent = false,
    )

/** 書誌詳細ページ(LBForm)のhidden hashが空でないかどうかを判定する。値は保持しない。 */
private fun hasNonEmptyDetailHash(html: String): Boolean {
    val hash = Jsoup.parse(html).selectFirst("form#LBForm input[type=hidden][name=hash]")?.attr("value")
    return !hash.isNullOrEmpty()
}

/** 競合試験で確認GET直後の状態を再現するための内部フック。 */
internal data class ReservationSequenceHooks(
    val afterConfirmFetched: suspend () -> Unit = {},
)

private fun isLoginForm(document: org.jsoup.nodes.Document): Boolean =
    document.selectFirst("input[name=j_password], input[name=j_username], form[action*=j_security_check]") != null

/** [onMaintenance] は診断ログへの注記だけに使う。呼出し側の判定・例外送出は一切変えない。 */
private fun requireNotMaintenance(html: String, onMaintenance: (() -> Unit)? = null) {
    if (MAINTENANCE_MARKERS.any(html::contains)) {
        onMaintenance?.invoke()
        throw LibraryError.Maintenance()
    }
}

private val MAINTENANCE_MARKERS = listOf("メンテナンス中", "メンテナンスのため", "システムメンテナンス", "ただいまメンテナンス")
/**
 * 取消の1回目のPOST応答が返す確認ダイアログ文言の一部（実測(2026-07-27):
 * 「予約の取消を行います。よろしいですか？」）。この文言が含まれる場合は、まだ取り消されておらず
 * 確認画面が返っただけと判定する。
 */
private const val CANCEL_CONFIRMATION_MARKER = "取消を行います"
/** 設定とライブ確認で確定している12館。 */
internal val PICKUP_LIBRARY_CODES = setOf("001", "002", "003", "004", "101", "102", "103", "104", "105", "106", "107", "109")
