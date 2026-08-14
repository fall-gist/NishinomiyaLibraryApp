package com.fallgist.nishinomiyalibrary.data.backup

import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.AppSettings
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationControlEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationRuleEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationTermEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingHistoryCheckpointEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingRecordEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationCartItemEntity
import com.fallgist.nishinomiyalibrary.data.sync.SyncScheduleStarter
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationControlStatus
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationTermKind
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

/** インポート結果。既存データを変更しない拒否は[Rejected]で表す。 */
sealed interface BackupImportResult {
    data class Success(
        val importedMemberCount: Int,
        /** 通知設定がオンだった場合、インポート後に通知権限の導線を出す必要があるか。 */
        val requestNotificationPermission: Boolean,
    ) : BackupImportResult

    data class Rejected(val message: String) : BackupImportResult
}

/** [SettingsScreenController]から呼ぶための境界。SAFのURI取得はCompose側の責務。 */
interface BackupImportPort {
    suspend fun import(jsonText: String): BackupImportResult
}

/**
 * バックアップJSONを検証し、単一のRoomトランザクションで全置換する。
 * 方式は全置換のみ(docs/design/settings-export-import.md §6)。パスワードの書き込みは
 * 第1段のスコープ外のため行わない(読み込んでも無視する)。
 */
class BackupImporter(
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val scheduleStarter: SyncScheduleStarter,
) : BackupImportPort {

    override suspend fun import(jsonText: String): BackupImportResult {
        val payload = try {
            BackupPayloadCodec.decode(jsonText)
        } catch (exception: BackupValidationException) {
            return BackupImportResult.Rejected(exception.message ?: "読み込めませんでした")
        }

        return try {
            val members = payload.members.map { it.toEntity() }
            val rules = payload.autoReservation.rules.map { it.toEntity() }
            val terms = payload.autoReservation.rules.flatMap { rule ->
                rule.terms.map { it.toEntity(rule.id) }
            }
            val controls = payload.autoReservation.controls.map { it.toEntity() }
            val readingRecords = payload.readingRecords.map { it.toEntity() }
            val checkpoints = payload.readingHistoryCheckpoints.map { it.toEntity() }
            val cartItems = payload.reservationCartItems.map { it.toEntity() }

            // 検証は既に完了している。ここから先は全て投入するだけで、途中で失敗させない。
            database.replaceBackupData(
                members = members,
                autoReservationRules = rules,
                autoReservationTerms = terms,
                autoReservationControls = controls,
                readingRecords = readingRecords,
                readingHistoryCheckpoints = checkpoints,
                reservationCartItems = cartItems,
            )

            // DataStoreの設定を書き込む。値域はBackupPayloadCodec.decodeで検証済みのため
            // SettingsStore.update内のrequireで例外にはならない。
            settingsStore.update(payload.settings.toAppSettings())

            // 同期時刻を移行しても、WorkManagerへの登録は端末ごとに別のため必ず引き直す。
            scheduleStarter.scheduleFromSettings()

            BackupImportResult.Success(
                importedMemberCount = members.size,
                requestNotificationPermission = payload.settings.notifyReturnReminder ||
                    payload.settings.notifyPickupReady,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            BackupImportResult.Rejected("インポート中にエラーが発生しました。時間をおいて再試行してください")
        }
    }
}

private fun BackupMember.toEntity() = MemberEntity(
    id = id,
    name = name,
    colorHex = colorHex,
    cardNumber = cardNumber,
    sortOrder = sortOrder,
)

private fun BackupAutoReservationRule.toEntity() = AutoReservationRuleEntity(
    id = id,
    enabled = enabled,
    sortOrder = sortOrder,
)

private fun BackupAutoReservationTerm.toEntity(ruleId: Long) = AutoReservationTermEntity(
    ruleId = ruleId,
    kind = AutoReservationTermKind.valueOf(kind),
    sortOrder = sortOrder,
    original = original,
    normalized = normalized,
)

private fun BackupAutoReservationControl.toEntity() = AutoReservationControlEntity(
    tilcod = tilcod,
    firstCandidateDate = LocalDate.parse(firstCandidateDate),
    expiresOn = LocalDate.parse(expiresOn),
    status = AutoReservationControlStatus.valueOf(status),
    preparedMemberId = preparedMemberId,
)

private fun BackupReadingRecord.toEntity() = ReadingRecordEntity(
    memberId = memberId,
    tilcod = tilcod,
    title = title,
    loanDate = LocalDate.parse(loanDate),
    library = library,
    titleNormalized = titleNormalized,
)

private fun BackupReadingHistoryCheckpoint.toEntity() = ReadingHistoryCheckpointEntity(
    memberId = memberId,
    tilcod = tilcod,
    loanDate = LocalDate.parse(loanDate),
)

private fun BackupReservationCartItem.toEntity() = ReservationCartItemEntity(
    memberId = memberId,
    tilcod = tilcod,
    title = title,
    writerLine = writerLine,
    addedAtEpochMillis = addedAtEpochMillis,
)

private fun BackupSettings.toAppSettings() = AppSettings(
    syncHour = syncHour,
    syncMinute = syncMinute,
    notifyReturnReminder = notifyReturnReminder,
    notifyPickupReady = notifyPickupReady,
    defaultCalendarLibrary = defaultCalendarLibrary,
    returnReminderDaysBefore = returnReminderDaysBefore,
    diagnosticLogEnabled = diagnosticLogEnabled,
    autoReservationEnabled = autoReservationEnabled,
)
