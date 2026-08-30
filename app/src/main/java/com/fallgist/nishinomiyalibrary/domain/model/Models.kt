package com.fallgist.nishinomiyalibrary.domain.model

import java.time.LocalDate
import java.util.Locale
import java.text.Normalizer

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
    /**
     * 貸出中一覧に延長ボタンが表示されているかどうか（同期のたびに`LoanListParser`が算出）。
     * 表示用の可否フラグに過ぎず、送信に使う延長コード（renewalCode）は持たない。
     * 送信コードは延長実行時に取得し直した一覧からのみ得る(`docs/design/loan-extension.md` §4.1)。
     */
    val extendable: Boolean = false,
)

/**
 * 読書記録の検索・保存で共通利用するタイトル正規化。
 * Unicode NFKCにより全角英数を半角へ寄せ、空白と大文字小文字の差を吸収する。
 */
/** 文字列照合で共通利用するUnicode正規化。 */
object TextNormalizer {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .filterNot(Char::isWhitespace)
        .lowercase(Locale.ROOT)
}

/** 読書記録の既存公開名を維持する互換アダプター。 */
object ReadingRecordTitleNormalizer {
    fun normalize(value: String): String = TextNormalizer.normalize(value)
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

/** 資料が0件の棚も含む、本棚画面用の読み取りモデル。 */
data class BookshelfContent(
    val memberId: Long,
    val shelfNo: Int,
    val name: String,
    val items: List<ShelfItem>,
)

/** 最終確認に表示した対象が、送信直前まで変わっていないことを確認するための値。 */
data class BookshelfMutationExpectation(
    val memberName: String,
    val shelfCount: Int,
    val shelf: BookshelfExpectedShelf? = null,
    val item: BookshelfExpectedItem? = null,
)

data class BookshelfExpectedShelf(
    val shelfNo: Int,
    val name: String,
    val itemCount: Int,
)

data class BookshelfExpectedItem(
    val tilcod: String,
    val title: String,
    val memo: String,
)

/** 本棚を一括編集する際、送信前後で資料行を一意に照合するための値。 */
data class BookshelfEditItem(
    val tilcod: String,
    val title: String,
    val originalMemo: String,
    val newMemo: String,
)

/** 明示的な本棚編集だけで使用する、メンバーを含む操作要求。 */
sealed interface BookshelfMutation {
    val memberId: Long
    val expected: BookshelfMutationExpectation

    data class AddItem(override val memberId: Long, val shelfNo: Int, val tilcod: String, val memo: String, override val expected: BookshelfMutationExpectation) : BookshelfMutation
    data class DeleteItem(override val memberId: Long, val shelfNo: Int, val tilcod: String, override val expected: BookshelfMutationExpectation) : BookshelfMutation
    data class CreateShelf(override val memberId: Long, val name: String, override val expected: BookshelfMutationExpectation) : BookshelfMutation
    data class EditShelf(
        override val memberId: Long,
        val shelfNo: Int,
        val newName: String,
        val items: List<BookshelfEditItem>,
        override val expected: BookshelfMutationExpectation,
    ) : BookshelfMutation
    data class DeleteShelf(override val memberId: Long, val shelfNo: Int, override val expected: BookshelfMutationExpectation) : BookshelfMutation
}

sealed interface BookshelfMutationOutcome {
    data class Applied(val localRefreshRequired: Boolean = false) : BookshelfMutationOutcome
    data class AlreadyRegistered(val localRefreshRequired: Boolean = false) : BookshelfMutationOutcome
    data object Unknown : BookshelfMutationOutcome
    data class Failure(val reason: FailureReason, val diagnosticCode: String? = null) : BookshelfMutationOutcome
}

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

data class Library(val code: String, val name: String) {
    companion object {
        /**
         * 全12館の館コード→館名の対応表。唯一の正本であり、内容(コードと名前の組)は変更しないこと。
         * 旧`CalendarRepositoryImpl`の`private companion object`から移動しただけで、値は1文字も変えていない
         * (`docs/ui-design.md`「方針: 一覧画面の行レイアウト統一」6番)。
         * `CalendarRepositoryImpl.libraries`と、予約中一覧の受取館未定時の名前解決の両方から参照する。
         */
        val ALL_LIBRARIES = listOf(
            Library("001", "中央図書館"),
            Library("002", "北口図書館"),
            Library("003", "鳴尾図書館"),
            Library("004", "北部図書館"),
            Library("101", "越木岩分室"),
            Library("102", "若竹分室"),
            Library("103", "段上分室"),
            Library("104", "上ケ原分室"),
            Library("105", "甲東園分室"),
            Library("106", "高須分室"),
            Library("107", "山口分室"),
            Library("109", "義務教育学校"),
        )
    }
}

/**
 * 予約送信時にアプリが記録した受取館の情報(表示専用の投影)。
 * サイトの受取館表示が「未定」の間、`ReservationsContentBuilder`が名前解決に使う
 * (`docs/ui-design.md`「方針: 一覧画面の行レイアウト統一」6番)。
 * `Reservation.pickupLibrary`(サイトの値)とは別の入力として渡し、混ぜて書き換えないこと。
 */
data class ReservationPickupSubmissionRecord(
    val memberId: Long,
    val tilcod: String,
    val pickupLibraryCode: String,
    /**
     * 送信の確度。`UNVERIFIED_SUBMISSION`はPOST後の成否が確認できていない記録であり、
     * その予約行が本当にアプリの送信で作られた保証が無いため、表示側で区別が必要
     * (`docs/ui-design.md`「方針: 一覧画面の行レイアウト統一」6番)。
     */
    val origin: ReservationPickupSubmissionOrigin,
)

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

/**
 * 一斉カート追加([ReservationCartRepository.addToCart]の複数件版)の結果。
 * `docs/design/bulk-selection.md` §7.1・§10-2: 追加できた件数と、追加できなかった件数を返す。
 * 「追加できなかった」理由(重複・存在しないメンバー等)は区別しない。UIは件数だけを見せる。
 */
data class ReservationCartAddSummary(val added: Int, val skipped: Int)

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
    /**
     * 取消は成立したが、一覧整理の非表示はPOST前に安全側で停止したか、明示的に拒否された。
     * 非表示の未完了は取消失敗ではないため、取消成功としてローカル行は削除する。
     */
    data class CancelledHideNotCompleted(val reason: HideFailureReason) : ReservationCancelOutcome
    /**
     * 取消は成立したが、非表示POST後の通信断・一覧不完全などにより成否を確定できない。
     * 再送はしない。
     */
    data object CancelledHideUnknown : ReservationCancelOutcome
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

/** 取消後の一覧整理を完了できなかった理由。値やサイト文言は保持しない。 */
enum class HideFailureReason {
    TARGET_NOT_UNIQUE,
    FORM_CHANGED,
    LIST_INCOMPLETE,
    REJECTED_BY_SITE,
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

/**
 * 貸出延長の依頼単位。memberIdと対象資料のtilcodだけで対象を固定する。
 * `renewalCode`はここに含めない。UI層が延長コードを保持して渡す設計にしないため
 * (`docs/design/loan-extension.md` §9.1)、renewalCodeはRepository/Gateway層より内側で
 * 延長実行のたびに取得し直す。
 */
data class LoanExtensionTarget(
    val memberId: Long,
    val tilcod: String,
) {
    init {
        require(memberId > 0) { "memberIdが不正です" }
        require(tilcod.isNotBlank()) { "tilcodが空です" }
    }
}

/**
 * 貸出延長1回の試みの結果。画面に依存しない(`docs/design/loan-extension.md` §6・§9.1)。
 * 表示文言はUI層で組み立てる。
 */
sealed interface LoanExtensionOutcome {
    /** 延長が成立した。返却期限が送信前より後ろへ変化したことを一覧照合で確認済み。 */
    data class Extended(val newDueDate: LocalDate) : LoanExtensionOutcome
    /**
     * POST後の完全な照合ができなかった(対象消失・複数化・返却期限を解析できない・通信断等)。
     * 自動再送はしない(§5.2・§9.2)。拒否理由の細分類は未実測のため設けない。
     */
    data object Unknown : LoanExtensionOutcome
    /** POST前に確定した失敗(対象不在、フォーム不一致、認証・通信・メンテナンス等)。既存のFailureReasonを再利用する。 */
    data class Failure(val reason: FailureReason) : LoanExtensionOutcome
}
