package com.fallgist.nishinomiyalibrary.data.backup

import kotlinx.serialization.Serializable

/**
 * 設定インポート・エクスポートのファイル形式(docs/design/settings-export-import.md §3)。
 * Roomエンティティを直接シリアライズせず、この専用DTOへ写す。DBスキーマ変更が
 * エクスポート形式の破壊に直結しないよう結合を切るためである。
 *
 * 第1段ではパスワードを含めない。[BackupMember.password] / [BackupMember.passwordEncrypted] は
 * 第2段(§4のアプリ内固定鍵AES-GCM暗号化)で使う欄を先取りしたものであり、現時点では
 * 常に出力せず、読み込み時も無視する。
 */
const val BACKUP_FORMAT_VERSION = 1

/** 読み込み側が対応できるformatVersionの上限。これを超えるファイルは拒否する。 */
const val BACKUP_MAX_SUPPORTED_FORMAT_VERSION = 1

@Serializable
data class BackupPayload(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val exportedAt: String,
    val appVersion: String,
    val sourceDbVersion: Int,
    val members: List<BackupMember>,
    val settings: BackupSettings,
    val autoReservation: BackupAutoReservation,
    val readingRecords: List<BackupReadingRecord>,
    val readingHistoryCheckpoints: List<BackupReadingHistoryCheckpoint>,
    val reservationCartItems: List<BackupReservationCartItem>,
)

@Serializable
data class BackupMember(
    val id: Long,
    val name: String,
    val colorHex: String,
    val cardNumber: String,
    val sortOrder: Int,
    /** 第2段用の予約欄。第1段では常にfalseで出力し、読み込み時も無視する。 */
    val passwordEncrypted: Boolean = false,
    /** 第2段用の予約欄。第1段では常にnullで出力し、読み込み時も無視する。 */
    val password: String? = null,
)

@Serializable
data class BackupSettings(
    val syncHour: Int,
    val syncMinute: Int,
    val notifyReturnReminder: Boolean,
    val notifyPickupReady: Boolean,
    val defaultCalendarLibrary: String,
    val returnReminderDaysBefore: Int,
    val diagnosticLogEnabled: Boolean,
    val autoReservationEnabled: Boolean,
)

@Serializable
data class BackupAutoReservation(
    val rules: List<BackupAutoReservationRule>,
    val controls: List<BackupAutoReservationControl>,
)

@Serializable
data class BackupAutoReservationRule(
    val id: Long,
    val enabled: Boolean,
    val sortOrder: Int,
    val terms: List<BackupAutoReservationTerm>,
)

/** [kind] は "INCLUDE" / "EXCLUDE" のいずれか。未知の名前は読み込み時に拒否する。 */
@Serializable
data class BackupAutoReservationTerm(
    val kind: String,
    val sortOrder: Int,
    val original: String,
    val normalized: String,
)

/** [status] は[com.fallgist.nishinomiyalibrary.domain.model.AutoReservationControlStatus]の名前文字列。 */
@Serializable
data class BackupAutoReservationControl(
    val tilcod: String,
    val firstCandidateDate: String,
    val expiresOn: String,
    val status: String,
    val preparedMemberId: Long? = null,
)

@Serializable
data class BackupReadingRecord(
    val memberId: Long,
    val tilcod: String,
    val title: String,
    val loanDate: String,
    val library: String,
    val titleNormalized: String,
)

@Serializable
data class BackupReadingHistoryCheckpoint(
    val memberId: Long,
    val tilcod: String,
    val loanDate: String,
)

@Serializable
data class BackupReservationCartItem(
    val memberId: Long,
    val tilcod: String,
    val title: String,
    val writerLine: String? = null,
    val addedAtEpochMillis: Long,
)
