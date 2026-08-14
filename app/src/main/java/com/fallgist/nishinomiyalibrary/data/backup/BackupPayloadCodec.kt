package com.fallgist.nishinomiyalibrary.data.backup

import com.fallgist.nishinomiyalibrary.data.local.RETURN_REMINDER_DAYS_RANGE
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationControlStatus
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationTermKind
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/** 読み込みを拒否する理由。すべて利用者向けの日本語メッセージを持つ。既存データは一切変更されない。 */
sealed class BackupValidationException(message: String) : Exception(message)

class BackupMalformedException(message: String = "ファイルを読み込めませんでした。破損しているか、対応していない形式です") :
    BackupValidationException(message)

class BackupUnsupportedFormatVersionException(version: Int) :
    BackupValidationException("このファイルは新しいバージョンのアプリで作成されています(formatVersion=$version)。アプリを更新してから読み込んでください")

class BackupUnknownEnumException(field: String, value: String) :
    BackupValidationException("$field に未知の値 \"$value\" が含まれています")

class BackupReferentialIntegrityException(message: String) : BackupValidationException(message)

class BackupValueRangeException(message: String) : BackupValidationException(message)

/** JSON⇔[BackupPayload]。パース検証と拒否理由の判定を担う純Kotlin実装。 */
object BackupPayloadCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(payload: BackupPayload): String = json.encodeToString(BackupPayload.serializer(), payload)

    /**
     * JSON文字列を検証済みの[BackupPayload]へ変換する。
     * 拒否条件(パース不能・formatVersion未対応・未知enum・参照整合性違反・値域違反)に該当する場合は
     * [BackupValidationException]を投げる。書き込みは一切行わない。
     */
    fun decode(jsonText: String): BackupPayload {
        val formatVersion = readFormatVersionOrNull(jsonText)
            ?: throw BackupMalformedException()
        if (formatVersion < 1 || formatVersion > BACKUP_MAX_SUPPORTED_FORMAT_VERSION) {
            throw BackupUnsupportedFormatVersionException(formatVersion)
        }

        val payload = try {
            json.decodeFromString(BackupPayload.serializer(), jsonText)
        } catch (exception: SerializationException) {
            throw BackupMalformedException()
        } catch (exception: IllegalArgumentException) {
            throw BackupMalformedException()
        }

        validate(payload)
        return payload
    }

    private fun readFormatVersionOrNull(jsonText: String): Int? = try {
        json.parseToJsonElement(jsonText).jsonObject["formatVersion"]?.jsonPrimitive?.intOrNull
    } catch (exception: SerializationException) {
        null
    } catch (exception: IllegalStateException) {
        // ルート要素がオブジェクトでない(配列・スカラー値のみ等)場合。
        null
    }

    private fun validate(payload: BackupPayload) {
        requireSyncTimeRange(payload.settings.syncHour, payload.settings.syncMinute)
        requireReminderDaysRange(payload.settings.returnReminderDaysBefore)

        val memberIds = payload.members.map { it.id }
        if (memberIds.distinct().size != memberIds.size) {
            throw BackupValueRangeException("メンバーのidが重複しています")
        }

        val ruleIds = payload.autoReservation.rules.map { it.id }
        if (ruleIds.distinct().size != ruleIds.size) {
            throw BackupValueRangeException("自動予約ルールのidが重複しています")
        }

        payload.autoReservation.rules.forEach { rule ->
            rule.terms.forEach { term -> requireKnownTermKind(term.kind) }
        }
        payload.autoReservation.controls.forEach { control ->
            requireKnownControlStatus(control.status)
            requireIsoDate(control.firstCandidateDate, "自動予約制御の firstCandidateDate")
            requireIsoDate(control.expiresOn, "自動予約制御の expiresOn")
        }
        payload.readingRecords.forEach { record ->
            requireIsoDate(record.loanDate, "読書記録の loanDate")
            requireExistingMember(memberIds, record.memberId, "読書記録")
        }
        payload.readingHistoryCheckpoints.forEach { checkpoint ->
            requireIsoDate(checkpoint.loanDate, "読書履歴チェックポイントの loanDate")
            requireExistingMember(memberIds, checkpoint.memberId, "読書履歴チェックポイント")
        }
        payload.reservationCartItems.forEach { item ->
            requireExistingMember(memberIds, item.memberId, "予約カート項目")
        }
    }

    private fun requireExistingMember(memberIds: List<Long>, memberId: Long, subject: String) {
        if (memberId !in memberIds) {
            throw BackupReferentialIntegrityException(
                "$subject が参照するメンバー(id=$memberId)が見つかりません",
            )
        }
    }

    private fun requireKnownTermKind(kind: String) {
        try {
            AutoReservationTermKind.valueOf(kind)
        } catch (exception: IllegalArgumentException) {
            throw BackupUnknownEnumException("自動予約ルールの kind", kind)
        }
    }

    private fun requireKnownControlStatus(status: String) {
        try {
            AutoReservationControlStatus.valueOf(status)
        } catch (exception: IllegalArgumentException) {
            throw BackupUnknownEnumException("自動予約制御の status", status)
        }
    }

    private fun requireIsoDate(value: String, subject: String) {
        try {
            LocalDate.parse(value)
        } catch (exception: DateTimeParseException) {
            throw BackupMalformedException()
        }
    }

    private fun requireSyncTimeRange(hour: Int, minute: Int) {
        if (hour !in 0..23) throw BackupValueRangeException("同期時刻の時が範囲外です(0〜23): $hour")
        if (minute !in 0..59) throw BackupValueRangeException("同期時刻の分が範囲外です(0〜59): $minute")
    }

    private fun requireReminderDaysRange(days: Int) {
        if (days !in RETURN_REMINDER_DAYS_RANGE) {
            throw BackupValueRangeException("通知日数が範囲外です(1〜7日前): $days")
        }
    }
}
