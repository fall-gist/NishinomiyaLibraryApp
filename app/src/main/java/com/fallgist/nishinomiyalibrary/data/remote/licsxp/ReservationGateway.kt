package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.DirectReservationConfirmParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.DirectReservationConfirmationPage
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.DirectReservationResponseParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.BookDetailReservationFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.LoginFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ParseException
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ReservationCancelFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ReservationCancelConfirmationField
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ReservationCancelConfirmationFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ReservationListParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.SummaryParser
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
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
     * 既定実装は未対応として例外を投げる。未対応実装を明示的に失敗させる既定値であり、
     * 本番実装(LicsXpReservationSession)は必ずoverrideする。
     */
    suspend fun cancelReservation(cancelCode: String, expectedTilcod: String): ReservationCancelAttempt =
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
 * [Cancelled] は、取消後に取得した完全な予約一覧から、送信前に固定した対象`tilcod`の行が
 * 消えた場合だけ返す。`cancelCode`の消失だけでは、予約状態の変化に伴って取消ボタンが消えた
 * 場合を成功と誤判定し得るため使わない。
 *
 * [Rejected] はかつて「できません」「越えています」等の一般語で判定していたが、これらは
 * 全ページに埋め込まれた共通JSの定数（`仮パスワードでは利用できません。パスワード変更を行なって
 * ください。` 等）にも一致してしまい誤検出することが実測で判明したため、現在は使用していない
 * （型としては残すが、生成箇所は無い）。
 */
sealed interface ReservationCancelAttempt {
    /** 取消POST後に完全な一覧を再取得し、送信前に固定した対象行が消えていたことを確認できた。 */
    data object Cancelled : ReservationCancelAttempt
    data object SessionExpiredBeforeSubmit : ReservationCancelAttempt
    data object IndeterminateAfterPost : ReservationCancelAttempt
    /**
     * 互換性のため残している結果型。現行の [LicsXpReservationSession.cancelReservation] は生成しない。
     * 1段階目・2段階目の成否は、確認文言の有無ではなく取消後の完全な予約一覧照合で判定する。
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
     * 排他区間の中で行い、状態変更POSTは自動リトライしない1回限りの試行として送る。
     * これはネットワーク上の厳密なexactly-once保証ではなく、通信断時には成否不明が残る。
     * 実測(2026-07-27)のとおり取消は2段階である。1段階目の応答が、同じ取消対象を残す予約一覧であり、
     * かつ既知の取消確認プロトコル署名を持つ場合だけ、`okCodes=OPACUSR001` を付けた2段階目を送る。
     * それ以外は取消後の一覧照合で成否を判定する。
     */
    override suspend fun cancelReservation(cancelCode: String, expectedTilcod: String): ReservationCancelAttempt {
        require(cancelCode.isNotBlank()) { "cancelCodeが空です" }
        require(expectedTilcod.isNotBlank()) { "expectedTilcodが空です" }
        return session.withExclusiveRequestSequence {
            val menu = get("WOpacMnuTopInitAction.do", mapOf("WebLinkFlag" to "1"))
            requireNotMaintenance(menu) { session.noteDiagnostic("cancel-reservation", "メンテナンス") }
            if (isLoginForm(Jsoup.parse(menu))) {
                session.noteDiagnostic("cancel-reservation", "ログインフォームが返った")
                return@withExclusiveRequestSequence ReservationCancelAttempt.SessionExpiredBeforeSubmit
            }
            session.updateTokens(menu)
            fun listForm(): FormBody {
                val tokens = session.requireTokens()
                return FormBody.Builder().add("hash", tokens.hash).add("gamenid", tokens.gamenId).build()
            }

            val listPage = postReservationList("WOpacMnuTopToPwdLibraryAction.do", mapOf("gamen" to "usrrsv"), listForm())
            val listHtml = listPage.html
            requireNotMaintenance(listHtml) { session.noteDiagnostic("cancel-reservation", "メンテナンス") }
            if (isLoginForm(Jsoup.parse(listHtml))) {
                session.noteDiagnostic("cancel-reservation", "一覧取得でログインフォームが返った")
                return@withExclusiveRequestSequence ReservationCancelAttempt.SessionExpiredBeforeSubmit
            }
            // cancelCodeは操作対象を選ぶための画面上の一時コードであり、成功照合には使わない。
            // 送信前一覧から対象行を一意に特定して、安定IDであるtilcodを固定する。
            val targetTilcod = try {
                val reservations = ReservationListParser.parse(listHtml)
                val target = reservations.filter { it.cancelCode == cancelCode }.singleOrNull()
                    ?: throw ParseException("reservation-cancel", "取消コードに対応する予約行を一意に特定できません")
                if (target.tilcod.isBlank()) {
                    throw ParseException("reservation-cancel", "取消対象の資料コードを取得できません")
                }
                if (target.tilcod != expectedTilcod) {
                    throw ParseException("reservation-cancel", "取消対象の資料コードが依頼時の値と一致しません")
                }
                if (reservations.count { it.tilcod == target.tilcod } != 1) {
                    throw ParseException("reservation-cancel", "取消対象の資料コードを一意に特定できません")
                }
                target.tilcod
            } catch (exception: ParseException) {
                session.noteDiagnostic("cancel-reservation", "${exception.screen}: ${exception.reason}")
                throw LibraryError.Parse(exception.screen, exception.reason)
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
            val signatureInspection = inspectKnownCancelConfirmationProtocolSignature(stage1Html)
            session.noteDiagnostic("cancel-reservation-signature", signatureInspection.diagnosticSummary())
            val stageInspection = inspectCancelConfirmationStage(
                html = stage1Html,
                cancelCode = cancelCode,
                targetTilcod = targetTilcod,
                signatureInspection = signatureInspection,
            )
            session.noteDiagnostic("cancel-reservation-stage", stageInspection.diagnosticSummary())
            // 2段階目は状態変更POSTである。JSの一般的な到達可能性は推定せず、直前に利用者が指定した
            // cancelCode/tilcodを含む一覧と、実測済みの取消確認プロトコル署名が両方そろう場合だけ送る。
            if (!stageInspection.matched) {
                return@withExclusiveRequestSequence resolveCancelByListDiff(session, targetTilcod, ::listForm)
            }
            val confirmationForm = try {
                ReservationCancelConfirmationFormParser.parse(
                    html = stage1Html,
                    expectedStage1Fields = stage1ExpectedFields(cancelForm),
                )
            } catch (exception: ParseException) {
                session.noteDiagnostic("cancel-reservation", "${exception.screen}: ${exception.reason}")
                return@withExclusiveRequestSequence resolveCancelByListDiff(session, targetTilcod, ::listForm)
            }
            val actionUrl = try {
                session.resolveReservationCancelAction(confirmationForm.action)
            } catch (exception: ParseException) {
                session.noteDiagnostic("cancel-reservation", "${exception.screen}: ${exception.reason}")
                return@withExclusiveRequestSequence resolveCancelByListDiff(session, targetTilcod, ::listForm)
            }
            session.noteDiagnostic("cancel-reservation", "対象一致の取消確認プロトコル署名と再送フォームを検出し2段階目を送信")
            val stage2Response = try {
                postReservationExactlyOnce(
                    actionUrl,
                    confirmationForm.buildForm(),
                    stage1Page,
                )
            } catch (_: LibraryError.Network) {
                return@withExclusiveRequestSequence ReservationCancelAttempt.IndeterminateAfterPost
            }
            requireNotMaintenance(stage2Response) { session.noteDiagnostic("cancel-reservation", "メンテナンス") }
            // 2段階目の応答に確認文言が残っていても、文字列だけで未完了とは断定しない。
            // 成否は完全な取消後一覧で固定済みtilcodを照合して決める。
            resolveCancelByListDiff(session, targetTilcod, ::listForm)
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
 * 取消後のメニューと一覧を各1回だけ再取得し、対象の安定ID(tilcod)が消えたかどうかで成否を判定する。
 * 最新メニューの予約件数サマリと一覧解析行数が一致しない、再取得・解析できない場合は成否不明として
 * 扱い、POSTは再送しない。
 * 1段階目のみで確認ダイアログが出なかった場合と、2段階目送信後に確認ダイアログが出なかった場合の
 * 両方から呼ばれる共通ロジックであり、cancelReservationから重複を避けるために切り出した。
 */
private suspend fun LicsXpSession.ExclusiveRequestSequence.resolveCancelByListDiff(
    session: LicsXpSession,
    targetTilcod: String,
    listForm: () -> FormBody,
): ReservationCancelAttempt {
    val targetRemoved = try {
        val menu = get("WOpacMnuTopInitAction.do", mapOf("WebLinkFlag" to "1"))
        requireNotMaintenance(menu) { session.noteDiagnostic("cancel-reservation", "メンテナンス") }
        if (isLoginForm(Jsoup.parse(menu))) {
            session.noteDiagnostic("cancel-reservation", "取消後のメニュー取得でログインフォームが返った")
            return ReservationCancelAttempt.IndeterminateAfterPost
        }
        session.updateTokens(menu)
        val summaryReservationCount = runCatching { SummaryParser.parse(menu).reservationCount }.getOrNull()
        val refetched = postReservationList("WOpacMnuTopToPwdLibraryAction.do", mapOf("gamen" to "usrrsv"), listForm())
        if (isLoginForm(Jsoup.parse(refetched.html))) {
            session.noteDiagnostic("cancel-reservation", "取消後の一覧取得でログインフォームが返った")
            null
        } else {
            requireNotMaintenance(refetched.html) { session.noteDiagnostic("cancel-reservation", "メンテナンス") }
            val reservations = ReservationListParser.parse(refetched.html)
            if (summaryReservationCount == null || summaryReservationCount != reservations.size) {
                session.noteDiagnostic(
                    "cancel-reservation",
                    "取消後一覧の完全性を確認できない (summary=${summaryReservationCount?.toString() ?: "取得不可"}, parsed=${reservations.size})",
                )
                null
            } else {
                reservations.none { it.tilcod == targetTilcod }
            }
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        session.noteDiagnostic("cancel-reservation", "取消後の一覧を解析できなかった")
        null
    }
    return when (targetRemoved) {
        true -> ReservationCancelAttempt.Cancelled
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
 * 取消の1回目のPOST応答が返す確認ダイアログ文言（実測(2026-07-27):
 * 「予約の取消を行います。よろしいですか？」）。プロトコル署名では全文一致だけを許す。
 */
private const val CANCEL_CONFIRMATION_MESSAGE = "予約の取消を行います。よろしいですか？"

/**
 * 2段階目POSTのための複合ガード。
 *
 * これはJavaScriptが実行されることを証明しない。直前の取消POSTの応答が、同じ`cancelCode`と`tilcod`を
 * 残した予約一覧であり、既知の確認プロトコル署名を同時に持つことを照合する。2段階目の本文は
 * 送信前に一意に解析済みの取消フォームを再利用するため、stage1の複数formを再解析しない。
 */
private data class CancelConfirmationStageInspection(
    val targetStillPresent: Boolean,
    val signatureMatched: Boolean,
) {
    val matched: Boolean = targetStillPresent && signatureMatched

    /** 個人情報・HTML・フォーム値を含めない複合ガード診断。 */
    fun diagnosticSummary(): String =
        "targetStillPresent=$targetStillPresent signatureMatched=$signatureMatched matched=$matched"
}

private fun inspectCancelConfirmationStage(
    html: String,
    cancelCode: String,
    targetTilcod: String,
    signatureInspection: CancelConfirmationSignatureInspection = inspectKnownCancelConfirmationProtocolSignature(html),
): CancelConfirmationStageInspection {
    val targetStillPresent = runCatching {
        val reservations = ReservationListParser.parse(html)
        reservations.filter { it.cancelCode == cancelCode }.singleOrNull()?.tilcod == targetTilcod &&
            reservations.count { it.tilcod == targetTilcod } == 1
    }.getOrDefault(false)
    return CancelConfirmationStageInspection(
        targetStillPresent = targetStillPresent,
        signatureMatched = signatureInspection.matched,
    )
}

/** stage1 POSTのURLクエリを、ブラウザがprevRequestFormへ保持する先頭hiddenと同じ順で比較する。 */
private fun stage1ExpectedFields(cancelForm: FormBody): List<ReservationCancelConfirmationField> = buildList {
    add(ReservationCancelConfirmationField("mngFlg2_handan", "1"))
    add(ReservationCancelConfirmationField("kbnchgflag", "1"))
    for (index in 0 until cancelForm.size) {
        add(ReservationCancelConfirmationField(cancelForm.name(index), cancelForm.value(index)))
    }
}

/**
 * 取消確認を、2026-07-27に採取したlegacy scriptの確認済み構造だけに限定して検出する。
 *
 * これはJavaScriptの実行可能性を一般に推定するものではない。ブラウザ判別分岐
 * (`document.all || IS_EXPLORER_11 || isEdge`)の内側で `lbConfirm`/`lbConfirm1`/`window.confirm` を呼び、
 * `rest` が真のとき `OPACUSR001` を `okArray` へ追加し、実測済みの`for`ループで`newHidden`へ
 * 同値を設定して`document.prevRequestForm.appendChild(newHidden)`を呼ぶ並びだけを許す。
 * `src`付き、非JavaScript type、コメント・文字列・template literal・正規表現・関数/class/arrow関数・
 * 不整合構文はプロトコル署名として扱わない。
 */
private data class CancelConfirmationSignatureInspection(
    val matched: Boolean,
    val inlineScriptCount: Int,
    val exactMessageScriptCount: Int,
    val outerIfScriptCount: Int,
    val scanOutcomeCounts: Map<CandidateExtractionOutcome, Int>,
    val extractedCandidateCount: Int,
    val sanitizeSucceededCount: Int,
    val fixedRegexMatchCount: Int,
) {
    /** 個人情報・HTML・スクリプト本文を含めない集計診断。 */
    fun diagnosticSummary(): String = buildString {
        append("matched=").append(matched)
        append(" inlineScripts=").append(inlineScriptCount)
        append(" exactMessageScripts=").append(exactMessageScriptCount)
        append(" outerIfScripts=").append(outerIfScriptCount)
        append(" scan=")
        append(CandidateExtractionOutcome.entries.joinToString(",") { outcome ->
            "${outcome.diagnosticName}:${scanOutcomeCounts[outcome] ?: 0}"
        })
        append(" candidates=").append(extractedCandidateCount)
        append(" sanitizeSucceeded=").append(sanitizeSucceededCount)
        append(" fixedRegexMatches=").append(fixedRegexMatchCount)
    }
}

private fun inspectKnownCancelConfirmationProtocolSignature(html: String): CancelConfirmationSignatureInspection {
    var inlineScriptCount = 0
    var exactMessageScriptCount = 0
    var outerIfScriptCount = 0
    var extractedCandidateCount = 0
    var sanitizeSucceededCount = 0
    var fixedRegexMatchCount = 0
    val scanOutcomeCounts = mutableMapOf<CandidateExtractionOutcome, Int>()
    Jsoup.parse(html).select("script").forEach { script ->
        if (!script.hasAttr("src")) inlineScriptCount += 1
        if (!isInlineJavaScript(script)) return@forEach
        val scriptSource = script.data()
        if (scriptSource.contains(CANCEL_CONFIRMATION_MESSAGE)) exactMessageScriptCount += 1
        if (OBSERVED_LEGACY_CANCEL_OUTER_IF.find(scriptSource) != null) outerIfScriptCount += 1
        val extraction = inspectObservedCancelConfirmationCandidates(scriptSource)
        scanOutcomeCounts[extraction.outcome] = (scanOutcomeCounts[extraction.outcome] ?: 0) + 1
        extractedCandidateCount += extraction.candidates.size
        extraction.candidates.forEach candidateLoop@ { candidate ->
            val source = sanitizeLegacyCancelScript(candidate) ?: return@candidateLoop
            sanitizeSucceededCount += 1
            if (OBSERVED_LEGACY_CANCEL_CONFIRMATION.matches(source)) fixedRegexMatchCount += 1
        }
    }
    return CancelConfirmationSignatureInspection(
        matched = fixedRegexMatchCount > 0,
        inlineScriptCount = inlineScriptCount,
        exactMessageScriptCount = exactMessageScriptCount,
        outerIfScriptCount = outerIfScriptCount,
        scanOutcomeCounts = scanOutcomeCounts,
        extractedCandidateCount = extractedCandidateCount,
        sanitizeSucceededCount = sanitizeSucceededCount,
        fixedRegexMatchCount = fixedRegexMatchCount,
    )
}

/**
 * 巨大なlegacy script全体を緩和して解釈しない。
 *
 * コメント・文字列等を飛ばしながら、丸括弧・角括弧の外かつ同一blockの文頭にある実測済み外側ifを探す。見つけた位置から連続する
 * 「外側if/else」「if(rest)/else」「for(okArray)」の3文だけを括弧対応で取り出す。候補外にある
 * function/class/arrow関数/正規表現などは無関係として許容するが、候補内部は従来どおり
 * [sanitizeLegacyCancelScript]と固定正規表現でフェイルクローズする。
 */
private enum class CandidateExtractionOutcome(val diagnosticName: String) {
    COMPLETED("completed"),
    TEMPLATE_LITERAL("template"),
    UNTERMINATED_COMMENT("unterminated-comment"),
    UNTERMINATED_STRING("unterminated-string"),
    UNTERMINATED_REGEX("unterminated-regex"),
    UNBALANCED_DELIMITER("unbalanced"),
}

private data class CandidateExtractionInspection(
    val candidates: List<String>,
    val outcome: CandidateExtractionOutcome,
)

private fun inspectObservedCancelConfirmationCandidates(script: String): CandidateExtractionInspection {
    val candidates = mutableListOf<String>()
    val parentheses = ArrayDeque<Boolean>()
    var bracketsDepth = 0
    var bracesDepth = 0
    var previousCodeSignificant: Char? = null
    var controlConditionAwaitingParenthesis = false
    var canStartRegexLiteral = true
    var index = 0
    while (index < script.length) {
        val char = script[index]
        if (char.isWhitespace()) {
            index += 1
            continue
        }
        if (char == '/' && script.getOrNull(index + 1) == '/') {
            index = script.indexOf('\n', index).let { if (it == -1) script.length else it + 1 }
            continue
        }
        if (char == '/' && script.getOrNull(index + 1) == '*') {
            val close = script.indexOf("*/", index + 2)
            if (close == -1) return CandidateExtractionInspection(emptyList(), CandidateExtractionOutcome.UNTERMINATED_COMMENT)
            index = close + 2
            continue
        }
        if (char == '`') return CandidateExtractionInspection(emptyList(), CandidateExtractionOutcome.TEMPLATE_LITERAL)
        if (char == '\'' || char == '"') {
            val end = skipLegacyJavaScriptString(script, index)
                ?: return CandidateExtractionInspection(emptyList(), CandidateExtractionOutcome.UNTERMINATED_STRING)
            if (parentheses.isEmpty() && bracketsDepth == 0) previousCodeSignificant = char
            canStartRegexLiteral = false
            controlConditionAwaitingParenthesis = false
            index = end
            continue
        }
        if (char == '/') {
            if (canStartRegexLiteral) {
                val end = skipJavaScriptRegexLiteral(script, index)
                    ?: return CandidateExtractionInspection(emptyList(), CandidateExtractionOutcome.UNTERMINATED_REGEX)
                if (parentheses.isEmpty() && bracketsDepth == 0) previousCodeSignificant = '/'
                canStartRegexLiteral = false
                controlConditionAwaitingParenthesis = false
                index = end
                continue
            }
            canStartRegexLiteral = true
            controlConditionAwaitingParenthesis = false
            if (parentheses.isEmpty() && bracketsDepth == 0) previousCodeSignificant = '/'
            index += 1
            continue
        }
        if (script.startsWith("=>", index)) {
            canStartRegexLiteral = true
            controlConditionAwaitingParenthesis = false
            if (parentheses.isEmpty() && bracketsDepth == 0) previousCodeSignificant = '>'
            index += 2
            continue
        }
        if (char.isLegacyJavaScriptIdentifierPart()) {
            var end = index + 1
            while (script.getOrNull(end)?.isLegacyJavaScriptIdentifierPart() == true) end += 1
            val token = script.substring(index, end)
            if (parentheses.isEmpty() && bracketsDepth == 0) {
                if ((previousCodeSignificant == null || previousCodeSignificant == '{' || previousCodeSignificant == ';' || previousCodeSignificant == '}') &&
                    token == "if" &&
                    OBSERVED_LEGACY_CANCEL_OUTER_IF.matchAt(script, index) != null
                ) {
                    val candidateEnd = extractObservedCancelConfirmationCandidate(script, index)
                    if (candidateEnd != null) {
                        candidates += script.substring(index, candidateEnd)
                        index = candidateEnd
                        previousCodeSignificant = '}'
                        canStartRegexLiteral = true
                        controlConditionAwaitingParenthesis = false
                        continue
                    }
                }
            }
            controlConditionAwaitingParenthesis = token in JAVASCRIPT_CONTROL_CONDITION_KEYWORDS
            canStartRegexLiteral = token in JAVASCRIPT_EXPRESSION_PREFIX_KEYWORDS
            if (parentheses.isEmpty() && bracketsDepth == 0) previousCodeSignificant = token.last()
            index = end
            continue
        }
        when (char) {
            '(' -> {
                parentheses.addLast(controlConditionAwaitingParenthesis)
                controlConditionAwaitingParenthesis = false
                canStartRegexLiteral = true
            }
            ')' -> {
                val wasControlCondition = parentheses.removeLastOrNull()
                    ?: return CandidateExtractionInspection(emptyList(), CandidateExtractionOutcome.UNBALANCED_DELIMITER)
                canStartRegexLiteral = wasControlCondition
            }
            '[' -> {
                bracketsDepth += 1
                canStartRegexLiteral = true
            }
            ']' -> {
                if (bracketsDepth-- <= 0) return CandidateExtractionInspection(emptyList(), CandidateExtractionOutcome.UNBALANCED_DELIMITER)
                canStartRegexLiteral = false
            }
            '{' -> {
                bracesDepth += 1
                canStartRegexLiteral = true
            }
            '}' -> {
                if (bracesDepth-- <= 0) return CandidateExtractionInspection(emptyList(), CandidateExtractionOutcome.UNBALANCED_DELIMITER)
                // ブロック末尾かobject literal末尾かを一般には判別しない。偽陽性を避けるため正規表現側へ倒す。
                canStartRegexLiteral = true
            }
            ';', ',', ':', '?', '=', '!', '&', '|', '+', '-', '*', '%', '~' -> {
                canStartRegexLiteral = true
                controlConditionAwaitingParenthesis = false
            }
            '.' -> {
                canStartRegexLiteral = false
                controlConditionAwaitingParenthesis = false
            }
            else -> {
                canStartRegexLiteral = false
                controlConditionAwaitingParenthesis = false
            }
        }
        if (parentheses.isEmpty() && bracketsDepth == 0 && !char.isWhitespace()) {
            previousCodeSignificant = char
        }
        index += 1
    }
    return if (parentheses.isEmpty() && bracketsDepth == 0 && bracesDepth == 0) {
        CandidateExtractionInspection(candidates, CandidateExtractionOutcome.COMPLETED)
    } else {
        CandidateExtractionInspection(emptyList(), CandidateExtractionOutcome.UNBALANCED_DELIMITER)
    }
}

private val JAVASCRIPT_CONTROL_CONDITION_KEYWORDS = setOf("if", "while", "for", "with", "switch", "catch")
private val JAVASCRIPT_EXPRESSION_PREFIX_KEYWORDS = setOf(
    "return", "throw", "case", "delete", "typeof", "void", "new", "in", "of", "yield", "await", "else", "do", "try", "finally",
    "break", "continue", "debugger",
)

private fun extractObservedCancelConfirmationCandidate(source: String, start: Int): Int? {
    val outerIfEnd = extractBracedIfElseStatement(source, start) ?: return null
    val restIfStart = skipJavaScriptTrivia(source, outerIfEnd) ?: return null
    if (!isLegacyKeywordAt(source, restIfStart, "if")) return null
    val restIfEnd = extractBracedIfElseStatement(source, restIfStart) ?: return null
    val forStart = skipJavaScriptTrivia(source, restIfEnd) ?: return null
    if (!isLegacyKeywordAt(source, forStart, "for")) return null
    return extractBracedForStatement(source, forStart)
}

private fun extractBracedIfElseStatement(source: String, start: Int): Int? {
    if (!isLegacyKeywordAt(source, start, "if")) return null
    val conditionStart = skipJavaScriptTrivia(source, start + "if".length) ?: return null
    if (source.getOrNull(conditionStart) != '(') return null
    val conditionEnd = findMatchingJavaScriptDelimiter(source, conditionStart) ?: return null
    val consequentStart = skipJavaScriptTrivia(source, conditionEnd) ?: return null
    if (source.getOrNull(consequentStart) != '{') return null
    val consequentEnd = findMatchingJavaScriptDelimiter(source, consequentStart) ?: return null
    val elseStart = skipJavaScriptTrivia(source, consequentEnd) ?: return null
    if (!isLegacyKeywordAt(source, elseStart, "else")) return null
    val alternateStart = skipJavaScriptTrivia(source, elseStart + "else".length) ?: return null
    if (source.getOrNull(alternateStart) != '{') return null
    return findMatchingJavaScriptDelimiter(source, alternateStart)
}

private fun extractBracedForStatement(source: String, start: Int): Int? {
    val conditionStart = skipJavaScriptTrivia(source, start + "for".length) ?: return null
    if (source.getOrNull(conditionStart) != '(') return null
    val conditionEnd = findMatchingJavaScriptDelimiter(source, conditionStart) ?: return null
    val bodyStart = skipJavaScriptTrivia(source, conditionEnd) ?: return null
    if (source.getOrNull(bodyStart) != '{') return null
    return findMatchingJavaScriptDelimiter(source, bodyStart)
}

/** 指定された開始括弧から対応する閉じ括弧の直後を返す。候補内で理解できない字句は拒否する。 */
private fun findMatchingJavaScriptDelimiter(source: String, start: Int): Int? {
    val delimiters = ArrayDeque<Char>()
    delimiters.addLast(source[start])
    var index = start + 1
    while (index < source.length) {
        val skipped = skipJavaScriptTriviaOrLiteral(source, index)
        if (skipped != null) {
            if (skipped <= index) return null
            index = skipped
            continue
        }
        when (val char = source[index]) {
            '(', '[', '{' -> delimiters.addLast(char)
            ')', ']', '}' -> {
                val expected = when (char) {
                    ')' -> '('
                    ']' -> '['
                    else -> '{'
                }
                if (delimiters.removeLastOrNull() != expected) return null
                if (delimiters.isEmpty()) return index + 1
            }
        }
        index += 1
    }
    return null
}

/** 空白・コメント・文字列・template literal・正規表現を飛ばす。通常コードならnullを返す。 */
private fun skipJavaScriptTriviaOrLiteral(source: String, start: Int): Int? {
    val char = source[start]
    if (char.isWhitespace()) return start + 1
    if (char == '/' && source.getOrNull(start + 1) == '/') {
        return source.indexOf('\n', start).let { if (it == -1) source.length else it + 1 }
    }
    if (char == '/' && source.getOrNull(start + 1) == '*') {
        val close = source.indexOf("*/", start + 2)
        return if (close == -1) start else close + 2
    }
    if (char == '\'' || char == '"') return skipLegacyJavaScriptString(source, start) ?: start
    if (char == '`') return skipJavaScriptTemplateLiteral(source, start) ?: start
    if (char == '/' && isLikelyJavaScriptRegexLiteral(source, start)) return skipJavaScriptRegexLiteral(source, start) ?: start
    return null
}

private fun skipJavaScriptTrivia(source: String, start: Int): Int? {
    var index = start
    while (index < source.length) {
        when {
            source[index].isWhitespace() -> index += 1
            source[index] == '/' && source.getOrNull(index + 1) == '/' -> {
                index = source.indexOf('\n', index).let { if (it == -1) source.length else it + 1 }
            }
            source[index] == '/' && source.getOrNull(index + 1) == '*' -> {
                val close = source.indexOf("*/", index + 2)
                if (close == -1) return null
                index = close + 2
            }
            else -> return index
        }
    }
    return null
}

private fun skipJavaScriptTemplateLiteral(source: String, start: Int): Int? {
    var index = start + 1
    while (index < source.length) {
        when (source[index]) {
            '\\' -> index += 2
            '`' -> return index + 1
            else -> index += 1
        }
    }
    return null
}

private fun isLikelyJavaScriptRegexLiteral(source: String, slashIndex: Int): Boolean {
    var index = slashIndex - 1
    while (index >= 0 && source[index].isWhitespace()) index -= 1
    if (index < 0) return true
    if (source[index] in "=(:,[!&|?;{}") return true
    if (!source[index].isLegacyJavaScriptIdentifierPart()) return false
    val end = index + 1
    while (index >= 0 && source[index].isLegacyJavaScriptIdentifierPart()) index -= 1
    return source.substring(index + 1, end) in setOf("return", "throw", "case", "delete", "typeof", "void", "new", "in", "of", "yield", "await")
}

private fun skipJavaScriptRegexLiteral(source: String, start: Int): Int? {
    var inCharacterClass = false
    var index = start + 1
    while (index < source.length) {
        when (source[index]) {
            '\\' -> index += 2
            '[' -> {
                inCharacterClass = true
                index += 1
            }
            ']' -> {
                inCharacterClass = false
                index += 1
            }
            '/' -> if (!inCharacterClass) {
                index += 1
                while (source.getOrNull(index)?.isLetter() == true) index += 1
                return index
            } else {
                index += 1
            }
            '\n', '\r' -> return null
            else -> index += 1
        }
    }
    return null
}

private fun isInlineJavaScript(script: Element): Boolean {
    if (script.hasAttr("src")) return false
    if (!script.hasAttr("type")) return true
    return script.attr("type").trim().lowercase() in STANDARD_JAVASCRIPT_MIME_TYPES
}

private val STANDARD_JAVASCRIPT_MIME_TYPES = setOf(
    "text/javascript",
    "application/javascript",
    "text/ecmascript",
    "application/ecmascript",
)

/**
 * 実測legacy構造に不要な字句を拒否しつつ、コメントだけを空白化する。
 * 取消のstage2は状態変更であるため、理解不能な構文を許容してはならない。
 */
private fun sanitizeLegacyCancelScript(script: String): String? {
    val output = StringBuilder(script.length)
    val parentheses = ArrayDeque<Char>()
    var index = 0
    while (index < script.length) {
        val char = script[index]
        if (char == '/' && script.getOrNull(index + 1) == '/') {
            val end = script.indexOf('\n', index).let { if (it == -1) script.length else it }
            repeat(end - index) { output.append(' ') }
            index = end
            continue
        }
        if (char == '/' && script.getOrNull(index + 1) == '*') {
            val close = script.indexOf("*/", index + 2)
            if (close == -1) return null
            repeat(close + 2 - index) { output.append(' ') }
            index = close + 2
            continue
        }
        if (char == '\'' || char == '"') {
            val end = skipLegacyJavaScriptString(script, index) ?: return null
            output.append(legacyCancelStringToken(script.substring(index + 1, end - 1)))
            index = end
            continue
        }
        if (char == '`' || char == '/' || script.startsWith("=>", index) ||
            isLegacyKeywordAt(script, index, "function") || isLegacyKeywordAt(script, index, "class")
        ) return null
        when (char) {
            '(', '[', '{' -> parentheses.addLast(char)
            ')' -> if (parentheses.removeLastOrNull() != '(') return null
            ']' -> if (parentheses.removeLastOrNull() != '[') return null
            '}' -> if (parentheses.removeLastOrNull() != '{') return null
        }
        output.append(char)
        index++
    }
    return output.toString().takeIf { parentheses.isEmpty() }
}

private fun skipLegacyJavaScriptString(source: String, start: Int): Int? {
    val quote = source[start]
    var index = start + 1
    while (index < source.length) {
        when (source[index]) {
            '\\' -> index += 2
            quote -> return index + 1
            '\n', '\r' -> return null
            else -> index++
        }
    }
    return null
}

/**
 * 文字列の中身をそのまま正規表現へ渡さない。そうすると、文字列に埋め込まれた偽のスクリプトを
 * 実行可能コードと取り違えるためである。実測済みの6値だけを記号化し、その他の文字列は候補外にする。
 */
private fun legacyCancelStringToken(value: String): String = when {
    value == CANCEL_CONFIRMATION_MESSAGE -> "__CANCEL_MESSAGE__"
    value == "" -> "__EMPTY__"
    value == "#F1F1FF" -> "__CONFIRM_COLOR__"
    value == "OPACUSR001" -> "__CANCEL_OK_CODE__"
    value == "input" -> "__INPUT_ELEMENT__"
    value == "hidden" -> "__HIDDEN_INPUT_TYPE__"
    else -> "__OTHER_STRING__"
}

private fun isLegacyKeywordAt(source: String, start: Int, keyword: String): Boolean =
    source.startsWith(keyword, start) &&
        (start == 0 || !source[start - 1].isLegacyJavaScriptIdentifierPart()) &&
        (start + keyword.length == source.length || !source[start + keyword.length].isLegacyJavaScriptIdentifierPart())

private fun Char.isLegacyJavaScriptIdentifierPart(): Boolean = isLetterOrDigit() || this == '_' || this == '$'

/**
 * DevToolsで採取した取消確認ページのlegacy script構造。
 * ブラウザ判別を含む外側構造から、OKコードhiddenを作る`for`ループまで一致させる。文字列は引用符の種類
 * だけを許容し、制御値・変数名・呼出し順は実測値へ固定する。内側の `if (0 != 1)` 断片だけでは許可しない。
 */
private val OBSERVED_LEGACY_CANCEL_OUTER_IF = Regex(
    """(?x)
    if \s* \( \s* document \. all \s* \|\| \s* IS_EXPLORER_11 \s* \|\| \s* isEdge \s* \)
    """.trimIndent(),
)

private val OBSERVED_LEGACY_CANCEL_CONFIRMATION = Regex(
    """(?xs)
    if \s* \( \s* document \. all \s* \|\| \s* IS_EXPLORER_11 \s* \|\| \s* isEdge \s* \) \s* \{
      \s* if \s* \( \s* 0 \s* != \s* 1 \s* \) \s* \{
        \s* if \s* \( \s* 0 \s* == \s* 1 \s* \) \s* \{
          \s* rest \s*=\s* confirm \s* \( \s* __CANCEL_MESSAGE__ \s* , \s* __EMPTY__ \s* \) \s* ;
        \s* \} \s* else \s* \{
          \s* rest \s*=\s* lbConfirm \s* \( \s* __CANCEL_MESSAGE__ \s* , \s* __EMPTY__ \s* , \s* __CONFIRM_COLOR__ \s* \) \s* ;
        \s* \} \s* \}
        \s* else \s* \{
          \s* if \s* \( \s* 0 \s* == \s* 1 \s* \) \s* \{
            \s* rest \s*=\s* confirm \s* \( \s* __CANCEL_MESSAGE__ \s* , \s* __EMPTY__ \s* \) \s* ;
          \s* \} \s* else \s* \{
            \s* rest \s*=\s* lbConfirm1 \s* \( \s* __CANCEL_MESSAGE__ \s* , \s* __EMPTY__ \s* , \s* __CONFIRM_COLOR__ \s* \) \s* ;
          \s* \} \s* \}
      \s* \} \s* else \s* \{
        \s* rest \s*=\s* window \. confirm \s* \( \s* __CANCEL_MESSAGE__ \s* \) \s* ;
      \s* \}
    \s* if \s* \( \s* rest \s* \) \s* \{
      \s* okArray \s* \[ \s* okArray \. length \s* \] \s*=\s* __CANCEL_OK_CODE__ \s* ;
      \s* submitFlg \s*=\s* false \s* ;
    \s* \}
    \s* else \s* \{ \s* return \s+ cancelDialog \s* \( \s* \) \s* ; \s* \}
    \s* for \s* \( \s* var \s+ i \s*=\s* 0 \s* ; \s* i \s* < \s* okArray \. length \s* ; \s* i \s* \+\+ \s* \) \s* \{
      \s* var \s+ newHidden \s*=\s* document \. createElement \s* \( \s* __INPUT_ELEMENT__ \s* \) \s* ;
      \s* newHidden \s*\.\s* type \s*=\s* __HIDDEN_INPUT_TYPE__ \s* ;
      \s* newHidden \s*\.\s* name \s*=\s* OK_CODES_NAME \s* ;
      \s* newHidden \s*\.\s* value \s*=\s* okArray \s* \[ \s* i \s* \] \s* ;
      \s* document \. prevRequestForm \. appendChild \s* \( \s* newHidden \s* \) \s* ;
    \s* \}
    """.trimIndent(),
)

/** 設定とライブ確認で確定している12館。 */
internal val PICKUP_LIBRARY_CODES = setOf("001", "002", "003", "004", "101", "102", "103", "104", "105", "106", "107", "109")
