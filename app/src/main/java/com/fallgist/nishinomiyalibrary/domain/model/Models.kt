package com.fallgist.nishinomiyalibrary.domain.model

import java.time.LocalDate
import java.text.Normalizer
import java.util.Locale

data class Member(
    val id: Long,
    val name: String,
    val colorHex: String,
    val cardNumber: String,
    val sortOrder: Int,
)

data class Loan(
    val memberId: Long,
    val title: String,
    val materialType: String,
    val lendingLibrary: String,
    val loanDate: LocalDate,
    val dueDate: LocalDate,
    val status: String,
    /** 貸出一覧の書誌詳細リンクから取得するタイトルコード。旧データは空文字列。 */
    val tilcod: String = "",
)

/**
 * 読書記録の検索・保存で共通利用するタイトル正規化。
 * Unicode NFKCにより全角英数を半角へ寄せ、空白と大文字小文字の差を吸収する。
 */
object ReadingRecordTitleNormalizer {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .filterNot(Char::isWhitespace)
        .lowercase(Locale.ROOT)
}

/** サイトの読書履歴、および同期時に併合する現在貸出の永続記録。 */
data class ReadingRecord(
    val memberId: Long,
    val tilcod: String,
    val title: String,
    val loanDate: LocalDate,
    val library: String,
)

/** 差分同期で既知判定に使う、メンバー内一意の履歴キー。 */
data class ReadingRecordKey(
    val tilcod: String,
    val loanDate: LocalDate,
)

/** 書誌ごとの既読表示に必要な、個人を特定しない最小限の情報。 */
data class ReadingInfo(
    val memberId: Long,
    val loanDate: LocalDate,
    val library: String,
)

data class Reservation(
    val memberId: Long,
    val title: String,
    val materialType: String,
    val pickupLibrary: String,
    val reservedDate: LocalDate,
    val queuePosition: Int?,
    val state: ReservationState,
    val holdExpiryDate: LocalDate?,
    /** 予約一覧の書誌詳細リンク(hTilcod)から取得するタイトルコード。旧データは空文字列。 */
    val tilcod: String = "",
    /** 取消ボタン(yoykCancel)から取得する予約コード。取消ボタンが無い行(提供可能等)では空文字列。 */
    val cancelCode: String = "",
)

/**
 * 12回目のライブ取消＋一覧観測(2026-07-28)で確定した実測内訳（予約状況一覧、全20行）:
 * 予約中15行(WAITING) + 提供可能3行(READY) + 移送中1行(IN_TRANSIT) + 取消1行(CANCELLED) = 20行。
 * サマリの予約中件数(19)は取消済み行だけを除いた数であり、移送中・提供可能は数えられている。
 * [CANCELLED]は予約状態列が「取消」の行（取消ボタンが「非表示」ボタン(yoykHihyoji)へ置き換わる）。
 * [IN_TRANSIT]は予約状態列が「移送中」の行（取消・非表示いずれのボタンも無い）。
 * Roomへは`LocalDateConverters.reservationStateToString`が`name`文字列で保存するため、
 * 定数追加にRoomマイグレーションは不要（列型は変わらない）。
 */
enum class ReservationState { WAITING, READY, UNKNOWN, CANCELLED, IN_TRANSIT }

data class Shelf(
    val no: Int,
    val name: String,
)

data class ShelfItem(
    val memberId: Long,
    val tilcod: String,
    val title: String,
    val memo: String,
    val registeredDate: LocalDate,
    val shelfNo: Int = 0,
    val shelfName: String = "",
)

data class UserSummary(
    val memberId: Long,
    /** 登録資料数ではなく、マイ本棚の本棚数。 */
    val shelfCount: Int,
    val loanCount: Int,
    val reservationCount: Int,
    val cartCount: Int,
)

data class SearchHit(
    val tilcod: String,
    val title: String,
    val writerLine: String,
    val materialType: String,
)

data class SearchPage(
    val hits: List<SearchHit>,
    val totalCount: Int,
    val hasNext: Boolean,
)

data class BookDetail(
    val tilcod: String,
    val fields: Map<String, String>,
    val isbn: String?,
    val holdings: List<Holding>,
    val holdingCount: Int,
    val availableCount: Int,
    val reservationCount: Int,
)

data class Holding(
    val library: String,
    val materialType: String,
    val callNumber: String,
    val location: String,
    val lendable: String,
    val status: String,
)

data class Library(val code: String, val name: String)

data class ClosedDay(val libraryCode: String, val date: LocalDate)

/**
 * 新着資料の1冊。公式サイトはジャンル別に分かれているが、本アプリはジャンルを保持せず
 * 全ジャンルを統合した書誌リストとして扱う。tilcod で名寄せ・書誌詳細へ連携する。
 */
data class NewArrival(
    val tilcod: String,
    val title: String,
    val volume: String,
    val author: String,
    val publisher: String,
    val publishedYearMonth: String,
    val classification: String,
    /** 貸出可否。○=true / ×=false / 判定不能=null。 */
    val lendable: Boolean?,
)

/** アプリ内の予約カートに保存する、まだサイトへ送信していない候補。 */
data class ReservationCartItem(
    val id: Long,
    val memberId: Long,
    val tilcod: String,
    val title: String,
    val writerLine: String?,
    val addedAtEpochMillis: Long,
)

/** 予約バッチの入力。cartItemId が null の場合は即時予約である。 */
data class ReservationTarget(
    val cartItemId: Long?,
    val memberId: Long,
    val tilcod: String,
    val title: String,
    /** 検索結果由来の著者等。カート追加時に失わず保存する。 */
    val writerLine: String? = null,
)

/** UI の最終確認後にだけ Repository へ渡す予約条件。 */
data class ReservationConfirmation(
    val pickupLibraryCode: String,
    val confirmedAtEpochMillis: Long,
)

data class ReservationItemResult(
    val target: ReservationTarget,
    val outcome: ReservationOutcome,
)

sealed interface ReservationOutcome {
    data object Success : ReservationOutcome
    data object AlreadyReserved : ReservationOutcome
    /** siteMessage はサイトが返した文言（例: 予約制限超過時のダイアログ文言）。無ければ null。 */
    data class Failure(val reason: FailureReason, val siteMessage: String? = null) : ReservationOutcome
    data class Unknown(val reason: UnknownReason) : ReservationOutcome
}

enum class FailureReason {
    AUTH,
    INVALID_PICKUP_LIBRARY,
    REJECTED_BY_SITE,
    RESERVATION_LIMIT_EXCEEDED,
    SESSION_EXPIRED_BEFORE_SUBMIT,
    SITE_RESPONSE_CHANGED,
    SITE_MAINTENANCE,
    NETWORK,
    MEMBER_ABORTED_AFTER_SITE_CHANGE,
}

enum class UnknownReason {
    POST_CONNECTION_LOST,
    POST_RESPONSE_UNEXPECTED,
    VERIFICATION_UNAVAILABLE,
}

data class MemberReservationResult(
    val memberId: Long,
    val itemResults: List<ReservationItemResult>,
)

data class ReservationBatchResult(
    val members: List<MemberReservationResult>,
)

/** 予約取消の依頼単位。memberId・資料コード・予約取消コードで対象を固定する。 */
data class ReservationCancelTarget(
    val memberId: Long,
    val tilcod: String,
    val cancelCode: String,
) {
    init {
        require(memberId > 0) { "memberIdが不正です" }
        require(tilcod.isNotBlank()) { "tilcodが空です" }
        require(cancelCode.isNotBlank()) { "cancelCodeが空です" }
    }
}

sealed interface ReservationCancelOutcome {
    /**
     * 取消が成立し、対象行が「取消」状態で一覧に残っている（非表示にできる状態）。
     * 12回目のライブ実測(2026-07-28)どおり、実サイトは取消後も対象行を一覧から消さない。
     */
    data object Cancelled : ReservationCancelOutcome
    /**
     * 取消が成立し、対象行が一覧に無い。既に非表示化されたのか、サイトが別の理由で即時に
     * 一覧から消したのかは区別しない。所有者の方針により、非表示操作を将来アプリへ組み込む
     * 可能性を見込んで[Cancelled]とは別の結果型にしている。
     */
    data object CancelledAndHidden : ReservationCancelOutcome
    /** 現在は生成されない。一般語による拒否判定が誤検出を招くことが実測(2026-07-27)で判明したため。 */
    data class Rejected(val siteMessage: String) : ReservationCancelOutcome
    /**
     * 互換性のため残している結果型。現行の取消実装は既知の2段階確認プロトコルを送信し、
     * 取消後の一覧照合で成否を判定するため、この型は生成しない。
     */
    data class ConfirmationRequired(val siteMessage: String) : ReservationCancelOutcome
    data class Failure(val reason: FailureReason) : ReservationCancelOutcome
    data class Unknown(val reason: UnknownReason) : ReservationCancelOutcome
}

data class ReservationCancelItemResult(
    val target: ReservationCancelTarget,
    val outcome: ReservationCancelOutcome,
)

data class MemberReservationCancelResult(
    val memberId: Long,
    val itemResults: List<ReservationCancelItemResult>,
)

data class ReservationCancelBatchResult(
    val members: List<MemberReservationCancelResult>,
)
