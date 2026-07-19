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
