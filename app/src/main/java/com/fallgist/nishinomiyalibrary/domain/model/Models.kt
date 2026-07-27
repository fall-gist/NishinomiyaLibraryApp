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

enum class ReservationState { WAITING, READY, UNKNOWN }

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

/** 予約取消の依頼単位。1件の予約(cancelCode)に対応する。 */
data class ReservationCancelTarget(
    val memberId: Long,
    val cancelCode: String,
)

sealed interface ReservationCancelOutcome {
    data object Cancelled : ReservationCancelOutcome
    /** 現在は生成されない。一般語による拒否判定が誤検出を招くことが実測(2026-07-27)で判明したため。 */
    data class Rejected(val siteMessage: String) : ReservationCancelOutcome
    /**
     * 実測(2026-07-27)判明: 取消は2段階で、1回目のPOSTは確認ダイアログ（「予約の取消を行います。
     * よろしいですか？」）を返す画面がそのまま応答として返ってくるだけで、取り消されていない。
     * OK後に何を送信すべきかは未特定のため、ここで打ち切る。siteMessageは確認ダイアログ文言そのもの。
     * 成功と誤解されないよう、[Unknown] とは区別できるようにしている。
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
