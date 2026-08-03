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
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ReservationHideFormParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ReservationListParser
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.ReservationListRow
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.SummaryParser
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
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

/**
 * 状態変更 POST の直前を通知できる予約セッション。
 *
 * 実通信実装だけが担う内部契約であり、既存のテスト用 [ReservationSession] 実装には要求しない。
 * コールバックは一つの論理的な予約・取消操作につき最初の状態変更 POST の直前に一度だけ呼ばれる。
 */
internal interface ReservationWriteBoundaryAware {
    fun setBeforeWriteBoundary(callback: (() -> Unit)?)
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

/**
 * 予約状況一覧1行分の観測結果（診断専用、読み取り専用）。
 * 12回目のライブ実測(2026-07-28)で、取消後も対象行が一覧に残り、予約状態列が「取消」・取消ボタンの
 * 位置に「非表示」ボタンへ変わることが判明した。この構造を確定させるための最小限のフィールドだけを持つ。
 * **資料名・書誌タイトルは絶対に含めない。** cancelCodeも値そのものは持たず、有無の真偽値だけを持つ。
 */
internal data class ReservationListRowInspection(
    val tilcod: String,
    /** 予約状態列の生テキスト（「予約中」「提供可能」「取消」等）。 */
    val stateText: String,
    /** [ReservationListParser]が返すstate enum値（生テキストをどう解釈しているかの現状把握用）。 */
    val state: ReservationState,
    /** [ReservationListParser]のcancelCode抽出（`input[onclick*=yoykCancel]`限定）が非空を返したか。値は持たない。 */
    val cancelCodePresent: Boolean,
    /** その行の`input[onclick]`全部から読み取った関数名（yoykCancel/yoykHihyoji等）。引数の値は含まない。 */
    val buttonFunctionNames: List<String>,
    /** その行のセルに付与されているclass属性値（赤字表示等のCSSクラス名）。重複は除く。 */
    val cellClassNames: List<String>,
)

/** 予約状況一覧の観測結果。取消・非表示・予約のいずれのPOSTも行わない。 */
internal data class ReservationListInspection(
    val summaryReservationCount: Int?,
    val parsedRowCount: Int,
    val rows: List<ReservationListRowInspection>,
)

/**
 * 予約状況一覧のHTML構造だけを読み取る診断専用の口。[ReservationConfirmationInspector]と同じ流儀で、
 * 既存の[ReservationSession]を壊さずに追加するため別インターフェースへ切り出す。
 * 実装は`fetchReservationSnapshot`と同じ経路（メニューGET→一覧表示POST）だけを使い、
 * 取消・非表示・予約のいずれのPOSTも送らない。[ReservationListParser]の解釈ロジックは変更しない。
 */
internal interface ReservationListInspector {
    suspend fun inspectReservationList(): ReservationListInspection
}

/** 通常機能へ公開しない、明示承認済みライブ非表示診断の内部入口。 */
internal interface ReservationHideDiagnosticCapability {
    suspend fun hideCancelledReservationForDiagnostic(expectedTilcod: String): ReservationHideDiagnosticResult
}

/** 秘密値・HTML・資料名を含まないライブ非表示診断の結果。 */
internal enum class ReservationHideDiagnosticResult {
    HIDDEN,
    TARGET_NOT_FOUND,
    TARGET_NOT_UNIQUE,
    TARGET_NOT_CANCELLED,
    HIDE_BUTTON_MISSING,
    LIST_INCOMPLETE_BEFORE_POST,
    FORM_CHANGED,
    SESSION_EXPIRED_BEFORE_POST,
    INDETERMINATE_AFTER_POST,
    STILL_PRESENT_AFTER_POST,
    LIST_INCOMPLETE_AFTER_POST,
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
 * 2段階目を送る（送信先の詳細は[LicsXpSession.resolveReservationCancelAction]を参照）。
 *
 * **12回目のライブ取消診断(2026-07-28)で、実サイトの取消が成立することを確認した**
 * （2段階目応答の画面固有メッセージが「予約の取消が完了しました。」だった）。同時に行った
 * 読み取り専用の一覧観測(`liveReservationListInspect`)で、取消後の実サイトの挙動が判明した:
 * 取消しても対象行は一覧から消えず、予約状態列が「取消」（[ReservationState.CANCELLED]）になり、
 * 取消ボタン(`yoykCancel`)の位置に「非表示」ボタン(`yoykHihyoji`)が置かれる。「非表示」ボタンを
 * 押すと初めて一覧から消える。所有者の方針（非表示操作を将来アプリへ組み込むかもしれない）により、
 * [Cancelled]（取消済みで一覧に残っている）と[CancelledAndHidden]（一覧に無い）を分けている。
 *
 * [Rejected] はかつて「できません」「越えています」等の一般語で判定していたが、これらは
 * 全ページに埋め込まれた共通JSの定数（`仮パスワードでは利用できません。パスワード変更を行なって
 * ください。` 等）にも一致してしまい誤検出することが実測で判明したため、現在は使用していない
 * （型としては残すが、生成箇所は無い）。
 */
sealed interface ReservationCancelAttempt {
    /**
     * 取消POST後に完全な一覧を再取得し、送信前に固定した対象`tilcod`行が「取消」状態
     * （[ReservationState.CANCELLED]）で残っており、かつ取消ボタンの位置に「非表示」ボタン
     * (`yoykHihyoji`)が置かれていることを確認できた。取消は成立しているが、一覧からはまだ
     * 消えていない状態（実測(2026-07-28)どおり、非表示ボタンを押すまで一覧に残る）。
     * 状態文字列とボタンの両方が一致した場合だけを成功とする。片方だけの一致
     * （状態のみ／ボタンのみ）は[IndeterminateAfterPost]へ倒す（確証がなければ成否不明へ倒す方針）。
     */
    data object Cancelled : ReservationCancelAttempt
    /**
     * 取消POST後に完全な一覧を再取得し、送信前に固定した対象`tilcod`行が一覧から消えていたことを
     * 確認できた。既に非表示化されたのか、サイトが別の理由で即時に一覧から消したのかは区別しない。
     */
    data object CancelledAndHidden : ReservationCancelAttempt
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
) : ReservationSession, ReservationConfirmationInspector, ReservationSnapshotSource, ReservationListInspector, ReservationWriteBoundaryAware, ReservationHideDiagnosticCapability {
    private var beforeWriteBoundary: (() -> Unit)? = null

    override fun setBeforeWriteBoundary(callback: (() -> Unit)?) {
        beforeWriteBoundary = callback
    }

    private fun markWriteBoundaryOnce(marked: Boolean): Boolean {
        if (!marked) beforeWriteBoundary?.invoke()
        return true
    }

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
            // 確認画面の解析と受取館検証を完了してから、確定 POST の直前で書込み境界を通知する。
            markWriteBoundaryOnce(marked = false)
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
     * Stage 15専用の単発ライブ診断。HTML actionは一切採用せず、固定先へのPOSTは最大1回だけ行う。
     */
    override suspend fun hideCancelledReservationForDiagnostic(expectedTilcod: String): ReservationHideDiagnosticResult {
        if (expectedTilcod.isBlank()) return ReservationHideDiagnosticResult.TARGET_NOT_FOUND
        return session.withExclusiveRequestSequence {
            val before = fetchDiagnosticReservationList()
                ?: return@withExclusiveRequestSequence ReservationHideDiagnosticResult.SESSION_EXPIRED_BEFORE_POST
            if (!before.complete) return@withExclusiveRequestSequence ReservationHideDiagnosticResult.LIST_INCOMPLETE_BEFORE_POST
            val sameTilcod = before.rows.filter { it.reservation.tilcod == expectedTilcod }
            if (sameTilcod.isEmpty()) return@withExclusiveRequestSequence ReservationHideDiagnosticResult.TARGET_NOT_FOUND
            val cancelled = sameTilcod.filter { it.reservation.state == ReservationState.CANCELLED }
            if (cancelled.isEmpty()) return@withExclusiveRequestSequence ReservationHideDiagnosticResult.TARGET_NOT_CANCELLED
            if (cancelled.size != 1) return@withExclusiveRequestSequence ReservationHideDiagnosticResult.TARGET_NOT_UNIQUE
            val target = cancelled.single()
            val hideCode = target.hideCode ?: return@withExclusiveRequestSequence ReservationHideDiagnosticResult.HIDE_BUTTON_MISSING
            // 同じコードが複数行に現れる場合、送信後の消失を対象行へ帰属できない。
            if (before.rows.count { it.hideCode == hideCode } != 1) {
                return@withExclusiveRequestSequence ReservationHideDiagnosticResult.TARGET_NOT_UNIQUE
            }
            val form = try {
                ReservationHideFormParser.parse(before.page.html).buildForm(hideCode)
            } catch (_: ParseException) {
                return@withExclusiveRequestSequence ReservationHideDiagnosticResult.FORM_CHANGED
            }
            try {
                postReservationHideExactlyOnce(form, before.page)
            } catch (_: LibraryError.Network) {
                // 送信開始後の通信失敗は再送せず、成否不明として終了する。
                return@withExclusiveRequestSequence ReservationHideDiagnosticResult.INDETERMINATE_AFTER_POST
            }
            val after = fetchDiagnosticReservationList()
                ?: return@withExclusiveRequestSequence ReservationHideDiagnosticResult.INDETERMINATE_AFTER_POST
            if (!after.complete) return@withExclusiveRequestSequence ReservationHideDiagnosticResult.LIST_INCOMPLETE_AFTER_POST
            val sameTilcodAfter = after.rows.filter { it.reservation.tilcod == expectedTilcod }
            if (after.rows.any { it.hideCode == hideCode } ||
                sameTilcodAfter.any { it.reservation.state == ReservationState.CANCELLED }
            ) {
                ReservationHideDiagnosticResult.STILL_PRESENT_AFTER_POST
            } else if (sameTilcodAfter.any { it.reservation.state == ReservationState.UNKNOWN }) {
                ReservationHideDiagnosticResult.INDETERMINATE_AFTER_POST
            } else {
                // 同一tilcodに取消前からあった非取消行だけが残る場合、対象取消行の消失と両立する。
                ReservationHideDiagnosticResult.HIDDEN
            }
        }
    }

    private suspend fun LicsXpSession.ExclusiveRequestSequence.fetchDiagnosticReservationList(): DiagnosticReservationList? {
        val menu = get("WOpacMnuTopInitAction.do", mapOf("WebLinkFlag" to "1"))
        requireNotMaintenance(menu) { session.noteDiagnostic("reservation-hide-diagnostic", "メンテナンス") }
        if (isLoginForm(Jsoup.parse(menu))) return null
        session.updateTokens(menu)
        val summaryCount = runCatching { SummaryParser.parse(menu).reservationCount }.getOrNull()
        val tokens = session.requireTokens()
        val page = postReservationList(
            "WOpacMnuTopToPwdLibraryAction.do",
            mapOf("gamen" to "usrrsv"),
            FormBody.Builder().add("hash", tokens.hash).add("gamenid", tokens.gamenId).build(),
        )
        requireNotMaintenance(page.html) { session.noteDiagnostic("reservation-hide-diagnostic", "メンテナンス") }
        if (isLoginForm(Jsoup.parse(page.html))) return null
        val rows = try {
            ReservationListParser.parseRows(page.html)
        } catch (exception: ParseException) {
            throw LibraryError.Parse(exception.screen, exception.reason)
        }
        val activeCount = rows.count { it.reservation.state != ReservationState.CANCELLED }
        return DiagnosticReservationList(page, rows, summaryCount != null && summaryCount == activeCount)
    }

    private data class DiagnosticReservationList(
        val page: LicsXpReservationListPage,
        val rows: List<ReservationListRow>,
        val complete: Boolean,
    )

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
        // 12回目のライブ取消＋一覧観測(2026-07-28)の実測どおり、サマリの予約中件数は取消済み行
        // （state=CANCELLED）だけを数えない。提供可能(READY)・移送中(IN_TRANSIT)は数えられている
        // （実測内訳: 予約中15+提供可能3+移送中1+取消1=20行、サマリ19）。このため完全性は
        // 「サマリ件数 == 取消済みでない行数」で判定する。移送中を除外してはならない。
        val activeReservationCount = reservations.count { it.state != ReservationState.CANCELLED }
        val complete = summaryReservationCount != null && summaryReservationCount == activeReservationCount
        if (!complete) {
            session.noteDiagnostic(
                "fetch-reservations",
                "サマリ件数と解析行数(取消除く)が不一致 " +
                    "(summary=${summaryReservationCount?.toString() ?: "取得不可"}, " +
                    "active=$activeReservationCount, parsed=${reservations.size})",
            )
        }
        return ReservationListSnapshot(reservations, complete)
    }

    /**
     * 予約状況一覧を1回取得し、行ごとの構造を観測する（診断専用、読み取り専用）。
     * 通信経路は[fetchReservationSnapshot]と同一（メニューGET→一覧表示POST）で、
     * 取消・非表示・予約のいずれのPOSTも送らない。[ReservationListParser]の解釈結果
     * （tilcod・state・cancelCode有無）はそのまま流用し、解釈ロジックには一切手を加えない。
     */
    override suspend fun inspectReservationList(): ReservationListInspection {
        val menu = session.get("WOpacMnuTopInitAction.do", mapOf("WebLinkFlag" to "1"))
        requireNotMaintenance(menu) { session.noteDiagnostic("inspect-reservation-list", "メンテナンス") }
        if (isLoginForm(Jsoup.parse(menu))) {
            session.noteDiagnostic("inspect-reservation-list", "ログインフォームが返った")
            throw LibraryError.Auth(null)
        }
        session.updateTokens(menu)
        val summaryReservationCount = runCatching { SummaryParser.parse(menu).reservationCount }.getOrNull()
        val tokens = session.requireTokens()
        val html = session.post(
            "WOpacMnuTopToPwdLibraryAction.do",
            mapOf("gamen" to "usrrsv"),
            FormBody.Builder().add("hash", tokens.hash).add("gamenid", tokens.gamenId).build(),
        )
        requireNotMaintenance(html) { session.noteDiagnostic("inspect-reservation-list", "メンテナンス") }
        if (isLoginForm(Jsoup.parse(html))) {
            session.noteDiagnostic("inspect-reservation-list", "一覧取得でログインフォームが返った")
            throw LibraryError.Auth(null)
        }
        val reservations = try {
            ReservationListParser.parse(html)
        } catch (exception: ParseException) {
            session.noteDiagnostic("inspect-reservation-list", "${exception.screen}: ${exception.reason}")
            throw LibraryError.Parse(exception.screen, exception.reason)
        }
        return ReservationListInspection(
            summaryReservationCount = summaryReservationCount,
            parsedRowCount = reservations.size,
            rows = inspectReservationListRows(html, reservations),
        )
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
            var writeBoundaryMarked = false
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
            // 13回目のライブ観測(2026-07-28)実測どおり、取消済み行を非表示にせず同じ書誌を再予約すると
            // 同一tilcodの行が複数になり得る（普通の運用操作であり実運用で必ず起きる）。tilcodだけでは
            // 行の同一性を追えないサイト仕様のため、一意性は「取消可能な行（cancelCodeが非空）」の
            // 中で判定する。取消可能な行が同一tilcodに2つある本当に曖昧なケースは従来どおり停止する。
            val (targetTilcod, cancelledBeforeCount) = try {
                val reservations = ReservationListParser.parse(listHtml)
                val target = reservations.filter { it.cancelCode == cancelCode }.singleOrNull()
                    ?: throw ParseException("reservation-cancel", "取消コードに対応する予約行を一意に特定できません")
                if (target.tilcod.isBlank()) {
                    throw ParseException("reservation-cancel", "取消対象の資料コードを取得できません")
                }
                if (target.tilcod != expectedTilcod) {
                    throw ParseException("reservation-cancel", "取消対象の資料コードが依頼時の値と一致しません")
                }
                if (reservations.count { it.tilcod == target.tilcod && it.cancelCode.isNotBlank() } != 1) {
                    throw ParseException("reservation-cancel", "取消対象の資料コードを一意に特定できません")
                }
                // 送信後の判定(resolveCancelByListDiff)で「取消済み行が1つ増えたか」を確認するための基準値。
                val cancelledBeforeCount = reservations.count {
                    it.tilcod == target.tilcod && it.state == ReservationState.CANCELLED
                }
                target.tilcod to cancelledBeforeCount
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
            writeBoundaryMarked = markWriteBoundaryOnce(writeBoundaryMarked)
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
            // 診断専用。判定には使わない（forms=/attrs=/action=等の読み取り結果を記録するだけ）。
            // 10回目のライブ診断で、実サイトのprevRequestFormにaction属性が無いことが分かった。
            // 11回目のライブ診断(2026-07-28)で、実サイトのprevRequestFormはname/method=postのみを
            // 持ちaction属性が存在しないこと、ページ側JSはactionを設定せずsubmit()するだけであること
            // まで確認できた。このため送信先は「1段階目の現在のドキュメントURL」（下のactionUrl解決を
            // 参照）で確定させ、この診断は補助情報としてのみ残す。
            // レビュー指摘: この呼び出しはnoteDiagnostic本体（safelyObserve/runCatching）へ渡す前に
            // 引数として先に評価されるため、ここで例外が漏れるとcancelReservation自体が失敗し、
            // ReservationCancelRepositoryImplの汎用catchでMEMBER_ABORTED_AFTER_SITE_CHANGEへ化けて
            // 同一会員の残件処理まで中断してしまう。診断は判定に関与しないため、失敗しても
            // cancelReservationの結果には影響させない。
            // レビュー指摘: 実サイトのstage1は巨大scriptを多数含み、tail=の候補ごとの再走査コストは
            // 無視できないため、診断が無効なとき（通常経路）はinspectPrevRequestFormDiagnostic自体を
            // 呼ばない。ラムダを受けるnoteDiagnosticオーバーロードが、有効時だけこれを評価する。
            session.noteDiagnostic("cancel-reservation-prevform") {
                try {
                    inspectPrevRequestFormDiagnostic(stage1Html)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    "exception=${exception::class.simpleName}"
                }
            }
            // 2段階目は状態変更POSTである。JSの一般的な到達可能性は推定せず、直前に利用者が指定した
            // cancelCode/tilcodを含む一覧と、実測済みの取消確認プロトコル署名が両方そろう場合だけ送る。
            if (!stageInspection.matched) {
                return@withExclusiveRequestSequence resolveCancelByListDiff(session, targetTilcod, cancelledBeforeCount, ::listForm)
            }
            val confirmationForm = try {
                ReservationCancelConfirmationFormParser.parse(
                    html = stage1Html,
                    expectedStage1Fields = stage1ExpectedFields(cancelForm),
                )
            } catch (exception: ParseException) {
                session.noteDiagnostic("cancel-reservation", "${exception.screen}: ${exception.reason}")
                return@withExclusiveRequestSequence resolveCancelByListDiff(session, targetTilcod, cancelledBeforeCount, ::listForm)
            }
            // actionが省略されている場合（実測(2026-07-28)どおりの実サイト相当）は、HTML標準どおり
            // 「現在のドキュメントURL」＝1段階目に実際に送ったURL(stage1Page.url、クエリ付き)を使う。
            // ハードコードはしない。1段階目はリダイレクトしないことを実測済みのため、ここで安全に使える。
            val actionUrl = try {
                session.resolveReservationCancelAction(confirmationForm.action, stage1Page.url)
            } catch (exception: ParseException) {
                session.noteDiagnostic("cancel-reservation", "${exception.screen}: ${exception.reason}")
                return@withExclusiveRequestSequence resolveCancelByListDiff(session, targetTilcod, cancelledBeforeCount, ::listForm)
            }
            session.noteDiagnostic("cancel-reservation", "対象一致の取消確認プロトコル署名と再送フォームを検出し2段階目を送信")
            writeBoundaryMarked = markWriteBoundaryOnce(writeBoundaryMarked)
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
            resolveCancelByListDiff(session, targetTilcod, cancelledBeforeCount, ::listForm)
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
 * 取消後一覧照合の結果。[resolveCancelByListDiff]内だけで使う中間状態で、外部には公開しない。
 * `null`（完全性を確認できない・解析できない・ログインフォームが返った等）は
 * [ReservationCancelAttempt.IndeterminateAfterPost]へ倒す。
 * 1段階目のみで確認ダイアログが出なかった場合と、2段階目送信後に確認ダイアログが出なかった場合の
 * 両方から呼ばれる共通ロジックであり、cancelReservationから重複を避けるために切り出した。
 */
private enum class CancelListDiffOutcome {
    /**
     * 対象tilcodの行が1つも無い。13回目のライブ観測(2026-07-28)で判明したとおり、同一tilcodに
     * 取消済み行が併存し得るサイトだが、対象tilcodの行が全て消えている状態は、並行操作（他端末等）が
     * 無い限り「取消＋非表示」以外では起きない。そのためこのケースだけは基準値による場合分けをしない。
     */
    REMOVED_FROM_LIST,
    /**
     * 「取消」状態(CANCELLED)かつ非表示ボタン(yoykHihyoji)ありの行数が、送信前に記録した基準値
     * （対象tilcodのうち送信前から取消済みだった行数）+1になり、かつ取消可能な行(cancelCodeが非空)が
     * 0になった。「取消済み行が1つ増えた」だけでなく「取消可能な行が無くなった」も同時に要求するのは、
     * 確証がなければ成否不明へ倒す既存方針のためである。
     */
    CANCELLED_COUNT_INCREMENTED,
    /** 上記以外（増分が0や2以上、取消可能な行が残っている、状態とボタンの片方だけ一致 等）。 */
    STILL_PRESENT_OTHERWISE,
}

/**
 * 取消後のメニューと一覧を各1回だけ再取得し、対象tilcodの行の変化から成否を判定する。
 *
 * 13回目のライブ観測(2026-07-28)実測どおり、取消済み行を非表示にせず同じ書誌を再予約すると同一tilcod
 * の行が複数になり得る（普通の運用操作であり実運用で必ず起きる）。tilcodだけでは行の同一性を追えない
 * サイト仕様のため、単純な行の消失ではなく「取消可能な行の消失」＋「取消済み行の増分」で判定する。
 * [cancelledBeforeCount]は送信前一覧における対象tilcod・state=CANCELLEDの行数（基準値）。
 */
private suspend fun LicsXpSession.ExclusiveRequestSequence.resolveCancelByListDiff(
    session: LicsXpSession,
    targetTilcod: String,
    cancelledBeforeCount: Int,
    listForm: () -> FormBody,
): ReservationCancelAttempt {
    val outcome = try {
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
            val rows = ReservationListParser.parseRows(refetched.html)
            // 12回目のライブ取消＋一覧観測(2026-07-28)の実測どおり、サマリの予約中件数は取消済み行
            // （state=CANCELLED）だけを数えない。提供可能(READY)・移送中(IN_TRANSIT)は数えられている
            // （実測内訳: 予約中15+提供可能3+移送中1+取消1=20行、サマリ19）。移送中を除外してはならない。
            val activeRowCount = rows.count { it.reservation.state != ReservationState.CANCELLED }
            if (summaryReservationCount == null || summaryReservationCount != activeRowCount) {
                session.noteDiagnostic(
                    "cancel-reservation",
                    "取消後一覧の完全性を確認できない " +
                        "(summary=${summaryReservationCount?.toString() ?: "取得不可"}, " +
                        "active=$activeRowCount, parsed=${rows.size})",
                )
                null
            } else {
                val matches = rows.filter { it.reservation.tilcod == targetTilcod }
                when {
                    matches.isEmpty() -> CancelListDiffOutcome.REMOVED_FROM_LIST
                    else -> {
                        val hasCancellableRow = matches.any { it.reservation.cancelCode.isNotBlank() }
                        val cancelledWithHideButtonCount = matches.count {
                            it.reservation.state == ReservationState.CANCELLED && it.hideButtonPresent
                        }
                        if (!hasCancellableRow && cancelledWithHideButtonCount == cancelledBeforeCount + 1) {
                            CancelListDiffOutcome.CANCELLED_COUNT_INCREMENTED
                        } else {
                            CancelListDiffOutcome.STILL_PRESENT_OTHERWISE
                        }
                    }
                }
            }
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        session.noteDiagnostic("cancel-reservation", "取消後の一覧を解析できなかった")
        null
    }
    return when (outcome) {
        CancelListDiffOutcome.REMOVED_FROM_LIST -> ReservationCancelAttempt.CancelledAndHidden
        CancelListDiffOutcome.CANCELLED_COUNT_INCREMENTED -> ReservationCancelAttempt.Cancelled
        CancelListDiffOutcome.STILL_PRESENT_OTHERWISE, null -> ReservationCancelAttempt.IndeterminateAfterPost
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
    // 13回目のライブ観測(2026-07-28)実測どおり、同一tilcodに取消済み行が併存し得るため、一意性は
    // cancelReservationの対象特定と同様に「取消可能な行（cancelCodeが非空）」の中で判定する
    // （stage1応答は1段階目POSTの直後であり、実際の取消はまだ起きていないため、この時点の一覧は
    // 送信前の一覧と同じ内容のはずである）。
    val targetStillPresent = runCatching {
        val reservations = ReservationListParser.parse(html)
        reservations.filter { it.cancelCode == cancelCode }.singleOrNull()?.tilcod == targetTilcod &&
            reservations.count { it.tilcod == targetTilcod && it.cancelCode.isNotBlank() } == 1
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
            val source = sanitizeLegacyCancelScript(candidate.text) ?: return@candidateLoop
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

/**
 * 署名候補の切り出し結果。[text]は既存どおり[sanitizeLegacyCancelScript]へそのまま渡して判定に使う
 * （挙動は無変更）。[endIndex]は診断専用の`tail=`抽出（候補の直後に続く文を読む）にだけ使う、
 * script内での候補終端位置（終端の1つ後ろ）。
 */
private data class CandidateExtractionMatch(
    val text: String,
    val endIndex: Int,
)

private data class CandidateExtractionInspection(
    val candidates: List<CandidateExtractionMatch>,
    val outcome: CandidateExtractionOutcome,
)

private fun inspectObservedCancelConfirmationCandidates(script: String): CandidateExtractionInspection {
    val candidates = mutableListOf<CandidateExtractionMatch>()
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
                        candidates += CandidateExtractionMatch(script.substring(index, candidateEnd), candidateEnd)
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

/**
 * 診断専用（読み取り専用）: 1段階目応答の`form[name=prevRequestForm]`の構造と、`prevRequestForm`を
 * 参照するscript文を1行のサマリへまとめる。
 *
 * 10回目のライブ診断で判明したとおり、実サイトの`prevRequestForm`にはaction属性が無く、
 * [ReservationCancelConfirmationFormParser.parse]がParseExceptionで停止する。一方、ブラウザ実測の
 * 2段階目送信先はクエリ無しの`WOpacUsrRsvCancelAction.do`だった（docs/site-research.md:573付近）。
 * action属性が空ならHTML仕様上は現在のドキュメントURLへ送られるはずでこの実測と矛盾するが、
 * hidden追加後の送信処理（採取済み`reservation_cancel_confirmation_live_fragment.js`のfor文より後）
 * は未採取であり、送信先を決めている実際のコードが分からない。この関数は、その未採取部分を推測する
 * 手がかりとして、prevRequestFormの属性とscript内の関連文をそのまま（機微値はマスクして）記録する
 * だけであり、既存の2段階目送信可否・送信内容の判定には一切関与しない。
 *
 * `stmts=`は識別子`prevRequestForm`を含む文だけを拾うため、`var f = document.prevRequestForm;`の
 * ような別名束縛や`document.forms["prevRequestForm"]`のような文字列経由の参照では、送信先を決める
 * 本体処理（`f.action = ...`, `f.submit()`等）を取りこぼす。レビュー指摘により、識別子名に依存しない
 * 採取として`tail=`を追加した。既存の取消確認プロトコル署名の候補切り出し（外側if/else→
 * if(rest)/else→for(okArray)の3文）が候補を切り出せたときだけ、その候補の直後に続く文を最大6件、
 * 同じ字句走査規則で機械的に読む。呼出し元（[cancelReservation]）はこの関数の呼び出しを
 * try/catchで包んでおり、ここで例外が発生しても取消の判定・送信内容には影響しない。
 */
private fun inspectPrevRequestFormDiagnostic(html: String): String {
    val document = Jsoup.parse(html)
    val forms = document.select("form[name=prevRequestForm]")
    val summary = StringBuilder("forms=").append(forms.size)
    if (forms.size == 1) {
        val form = forms.single()
        val attributeNames = form.attributes().map { it.key }.sorted()
        summary.append(" attrs=").append(attributeNames.joinToString(","))
        listOf("action", "method", "target", "enctype", "id").forEach { attributeName ->
            val value = form.attr(attributeName).trim()
            summary.append(' ').append(attributeName).append('=')
                .append(maskDiagnosticValue(value.ifEmpty { "(empty)" }))
        }
        val controls = form.select(
            "input[name]:not([disabled]), select[name]:not([disabled]), textarea[name]:not([disabled])",
        )
        summary.append(" controls=").append(controls.size)
    }
    val inlineScripts = document.select("script").filter(::isInlineJavaScript).map { it.data() }

    val statements = inlineScripts
        .flatMap { script -> extractPrevRequestFormStatements(script) ?: emptyList() }
        .distinct()
        .take(MAX_PREV_REQUEST_FORM_STATEMENTS)
        .map { statement -> truncateDiagnosticStatement(sanitizeDiagnosticStatement(statement), MAX_PREV_REQUEST_FORM_STATEMENT_LENGTH) }
    summary.append(" stmts=")
    summary.append(if (statements.isEmpty()) "(none)" else statements.joinToString("|"))

    val tailStatements = inlineScripts
        .flatMap(::extractSignatureCandidateTailStatements)
        .distinct()
        .take(MAX_TAIL_STATEMENTS)
        .map { statement -> truncateDiagnosticStatement(sanitizeDiagnosticStatement(statement), MAX_TAIL_STATEMENT_LENGTH) }
    summary.append(" tail=")
    summary.append(if (tailStatements.isEmpty()) "(none)" else tailStatements.joinToString("|"))

    val okCodesName = runCatching { ReservationCancelConfirmationFormParser.extractOkCodesFieldName(document) }.getOrNull()
    summary.append(" okCodesName=").append(maskDiagnosticValue(okCodesName ?: "(unresolved)"))
    return summary.toString()
}

private const val MAX_PREV_REQUEST_FORM_STATEMENTS = 8
private const val MAX_PREV_REQUEST_FORM_STATEMENT_LENGTH = 160
private const val MAX_TAIL_STATEMENTS = 6
private const val MAX_TAIL_STATEMENT_LENGTH = 200
private const val PREV_REQUEST_FORM_IDENTIFIER = "prevRequestForm"
private val LONG_DIGIT_RUN = Regex("""\d{6,}""")
private val WHITESPACE_RUN = Regex("""\s+""")

/** 8文字以上の英数字トークンのうち、数字を1文字以上含むものだけを対象にする（純粋な識別子は潰さない）。 */
private val DIGIT_BEARING_ALNUM_TOKEN = Regex("""(?=[0-9A-Za-z]*\d)[0-9A-Za-z]{8,}""")

/** これらの語を含む文は、値の一部だけでなく文全体を伏せる（hash/passwordの類は部分マスクでは不十分）。 */
private val REDACTED_STATEMENT_MARKERS = listOf("hash", "password", "passwd", "j_username", "j_password")

private fun maskLongDigitRuns(value: String): String = LONG_DIGIT_RUN.replace(value, "<num>")

private fun maskAlphanumericTokens(value: String): String = DIGIT_BEARING_ALNUM_TOKEN.replace(value, "<tok>")

/** 属性値・okCodesName向け: 数字だけの長い並びと、数字混じりの英数字トークンをマスクする。 */
private fun maskDiagnosticValue(value: String): String = maskAlphanumericTokens(maskLongDigitRuns(value))

/**
 * stmts=/tail=向け: hash等の秘密値を示唆する語を含む文は、値の一部だけでなく文全体を`[REDACTED]`にする。
 * 該当しなければ[maskDiagnosticValue]と同じマスクだけを適用する。
 */
private fun sanitizeDiagnosticStatement(statement: String): String =
    if (REDACTED_STATEMENT_MARKERS.any { marker -> statement.contains(marker, ignoreCase = true) }) "[REDACTED]"
    else maskDiagnosticValue(statement)

private fun truncateDiagnosticStatement(statement: String, maxLength: Int): String =
    if (statement.length <= maxLength) statement else statement.take(maxLength) + "…"

/**
 * 診断専用の字句走査結果。コメント・通常文字列・正規表現の内部を避けながら、丸括弧・角括弧の外にある
 * `;`/`{`/`}`の位置から「文」境界を求める。[startBoundaries]は文の開始位置（先頭0を含む。`;`/`{`/`}`の
 * 直後も含む）、[endBoundaries]は文の終了位置（`;`/`}`自体の位置。`{`は終了側に含めない＝仕様どおり）、
 * [identifierPositions]は[extractPrevRequestFormStatements]専用の識別子出現位置。
 *
 * 既知の制約: `{`/`}`は丸括弧・角括弧のような深さ管理をしない。そのため、あるstatementの内部に
 * ネストしたブロック（if/for等）が含まれる場合、そのブロック内側の`}`だけを見て文の終端と誤認する
 * ことがある。診断専用のヒント表示であり、既存の判定ロジック（[sanitizeLegacyCancelScript]や
 * [inspectObservedCancelConfirmationCandidates]の候補切り出しそのもの）には使っていないため許容する。
 */
private data class StatementBoundaryScan(
    val startBoundaries: List<Int>,
    val endBoundaries: List<Int>,
    val identifierPositions: List<Int>,
)

private fun scanStatementBoundaries(script: String, identifier: String? = null): StatementBoundaryScan? {
    val startBoundaries = mutableListOf(0)
    val endBoundaries = mutableListOf<Int>()
    val identifierPositions = mutableListOf<Int>()
    val parentheses = ArrayDeque<Char>()
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
            if (close == -1) return null
            index = close + 2
            continue
        }
        if (char == '`') return null
        if (char == '\'' || char == '"') {
            index = skipLegacyJavaScriptString(script, index) ?: return null
            continue
        }
        if (char == '/' && isLikelyJavaScriptRegexLiteral(script, index)) {
            index = skipJavaScriptRegexLiteral(script, index) ?: return null
            continue
        }
        if (char.isLegacyJavaScriptIdentifierPart()) {
            var end = index + 1
            while (script.getOrNull(end)?.isLegacyJavaScriptIdentifierPart() == true) end += 1
            if (identifier != null && script.substring(index, end) == identifier) identifierPositions += index
            index = end
            continue
        }
        when (char) {
            '(', '[' -> parentheses.addLast(char)
            ')' -> if (parentheses.removeLastOrNull() != '(') return null
            ']' -> if (parentheses.removeLastOrNull() != '[') return null
            ';', '{', '}' -> if (parentheses.isEmpty()) {
                if (char == ';' || char == '}') endBoundaries += index
                startBoundaries += index + 1
            }
        }
        index += 1
    }
    if (parentheses.isNotEmpty()) return null
    return StatementBoundaryScan(startBoundaries, endBoundaries, identifierPositions)
}

/**
 * 診断専用の字句走査。識別子`prevRequestForm`を含む「文」を、コメント・通常文字列・正規表現の内部を
 * 避けながら抽出する。取消確認プロトコル署名検査や[sanitizeLegacyCancelScript]と同じ保守的方針で、
 * template literalが現れたscriptや、未終端のコメント・文字列・正規表現、丸括弧・角括弧の不整合がある
 * scriptはnullを返して丸ごと対象外にする。
 *
 * 「文」の境界は、丸括弧・角括弧の外にある`;`/`{`/`}`の直後を開始、`;`/`{`ではなく`;`/`}`を終了とする
 * （仕様どおり、開始側だけ`{`も境界に含む）。判定・送信内容には一切使わない。
 */
private fun extractPrevRequestFormStatements(script: String): List<String>? {
    val scan = scanStatementBoundaries(script, PREV_REQUEST_FORM_IDENTIFIER) ?: return null
    if (scan.identifierPositions.isEmpty()) return emptyList()
    val statements = LinkedHashSet<String>()
    scan.identifierPositions.forEach { position ->
        val start = scan.startBoundaries.lastOrNull { it <= position } ?: 0
        // 終了境界の`;`/`}`自体は「文」の一部として含める（末尾の区切り記号ごと1文とする）。
        val end = scan.endBoundaries.firstOrNull { it >= position }?.plus(1) ?: script.length
        if (end <= start) return@forEach
        val normalized = script.substring(start, end).trim().replace(WHITESPACE_RUN, " ")
        if (normalized.isNotEmpty()) statements += normalized
    }
    return statements.toList()
}

/**
 * 診断専用。identifierを問わず、[position]から始まる文を最大[maxCount]件、同じ字句走査規則で
 * 機械的に切り出す。署名候補（[inspectObservedCancelConfirmationCandidates]）の終端位置から呼ぶことで、
 * 別名束縛（`var f = document.prevRequestForm; f.action = ...; f.submit();`）や文字列経由の参照
 * （`document.forms["prevRequestForm"]`）のように識別子ベースの[extractPrevRequestFormStatements]が
 * 取りこぼす送信処理を、識別子名に依存せず読む。scriptが字句走査できない場合は空リストを返す
 * （呼出し元は複数scriptを合算するため、例外にせず空で返す）。
 */
private fun extractStatementsAfter(script: String, position: Int, maxCount: Int): List<String> {
    val scan = scanStatementBoundaries(script) ?: return emptyList()
    val statements = mutableListOf<String>()
    var cursor = scan.startBoundaries.firstOrNull { it >= position } ?: return emptyList()
    while (statements.size < maxCount && cursor < script.length) {
        val end = scan.endBoundaries.firstOrNull { it >= cursor }?.plus(1) ?: script.length
        if (end <= cursor) break
        val normalized = script.substring(cursor, end).trim().replace(WHITESPACE_RUN, " ")
        if (normalized.isNotEmpty()) statements += normalized
        if (end >= script.length) break
        cursor = end
    }
    return statements
}

/**
 * 診断専用。1つのscript内にある取消確認プロトコル署名候補（切り出せたものすべて。厳密な固定正規表現
 * に一致するかどうかは問わない＝「候補を切り出せたとき」の定義どおり）それぞれについて、直後に続く
 * 文を読む。既存の[inspectKnownCancelConfirmationProtocolSignature]の判定（matched等）には一切影響
 * しない、完全に独立した読み取りである。
 */
private fun extractSignatureCandidateTailStatements(script: String): List<String> {
    val extraction = inspectObservedCancelConfirmationCandidates(script)
    if (extraction.candidates.isEmpty()) return emptyList()
    return extraction.candidates.flatMap { candidate -> extractStatementsAfter(script, candidate.endIndex, MAX_TAIL_STATEMENTS) }
}

/**
 * 診断専用（読み取り専用）。予約状況一覧テーブルの各行から、[ReservationListRowInspection]が持つ
 * 追加フィールド（予約状態列の生テキスト・行内の`input[onclick]`全部の関数名・セルのclass属性値）を
 * 読み取り、[reservations]（[ReservationListParser.parse]の結果、`tbody > tr`と同じ順）と行番号で
 * 対応付ける。[ReservationListParser]自体は呼ばず解釈も変えない。テーブルや列が見つからない場合は
 * 空リストを返す（診断専用のため、本体の判定のように例外にはしない）。
 */
private fun inspectReservationListRows(html: String, reservations: List<Reservation>): List<ReservationListRowInspection> {
    val document = Jsoup.parse(html)
    val table = document.selectFirst("table[summary=予約状況一覧表]") ?: return emptyList()
    val headerCells = table.select("thead tr").lastOrNull()?.select("th") ?: return emptyList()
    val stateColumnIndex = headerCells.indexOfFirst { it.text().trim().contains("予約状態") }
    return table.select("tbody > tr").mapIndexed { index, row ->
        val cells = row.children().filter { it.tagName() == "th" || it.tagName() == "td" }
        val stateText = if (stateColumnIndex >= 0) {
            cells.getOrNull(stateColumnIndex)?.text()?.trim().orEmpty()
        } else {
            ""
        }
        val buttonFunctionNames = row.select("input[onclick]").mapNotNull { button ->
            ONCLICK_FUNCTION_NAME_REGEX.find(button.attr("onclick"))?.groupValues?.get(1)
        }
        val cellClassNames = cells.flatMap { it.className().split(Regex("""\s+""")) }
            .filter { it.isNotBlank() }
            .distinct()
        val reservation = reservations.getOrNull(index)
        ReservationListRowInspection(
            tilcod = reservation?.tilcod.orEmpty(),
            stateText = stateText,
            state = reservation?.state ?: ReservationState.UNKNOWN,
            cancelCodePresent = reservation?.cancelCode?.isNotBlank() ?: false,
            buttonFunctionNames = buttonFunctionNames,
            cellClassNames = cellClassNames,
        )
    }
}

/** `javascript:yoykCancel('123')` / `yoykHihyoji('123')` のような呼び出しから関数名だけを取り出す。引数値は含めない。 */
private val ONCLICK_FUNCTION_NAME_REGEX = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

/** 設定とライブ確認で確定している12館。 */
internal val PICKUP_LIBRARY_CODES = setOf("001", "002", "003", "004", "101", "102", "103", "104", "105", "106", "107", "109")
