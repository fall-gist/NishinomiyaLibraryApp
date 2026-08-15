package com.fallgist.nishinomiyalibrary.data.backup

import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.AppSettings
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
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

/**
 * インポート結果。3値(docs/design/settings-export-import.md §6.3)。
 * - [Rejected]: 検証で拒否。既存データは一切変更されていない
 * - [Success]: すべて完了
 * - [AppliedWithWarning]: Roomへの取り込みは完了したが、後処理
 *   (`CredentialStore`の全消去・設定の書き込み・同期スケジュールの再構成のいずれか)に失敗した。
 *   `replaceBackupData`のRoomトランザクション確定後はロールバックされないため、
 *   「無変更の拒否」と同じ型で表さない。
 */
sealed interface BackupImportResult {
    data class Success(
        val importedMemberCount: Int,
        /** 通知設定がオンだった場合、インポート後に通知権限の導線を出す必要があるか。 */
        val requestNotificationPermission: Boolean,
    ) : BackupImportResult

    data class AppliedWithWarning(
        val importedMemberCount: Int,
        val requestNotificationPermission: Boolean,
        val message: String,
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
 * 第2段のスコープ外のため行わない(読み込んでも無視する)が、[CredentialStore]の全消去
 * (§6.2)は第1段でも必須のため行う。
 */
class BackupImporter(
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val scheduleStarter: SyncScheduleStarter,
    private val credentialStore: CredentialStore,
) : BackupImportPort {

    override suspend fun import(jsonText: String): BackupImportResult {
        val payload = try {
            BackupPayloadCodec.decode(jsonText)
        } catch (exception: BackupValidationException) {
            return BackupImportResult.Rejected(exception.message ?: "読み込めませんでした")
        }

        val members: List<MemberEntity>
        try {
            members = payload.members.map { it.toEntity() }
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
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // Roomトランザクションが確定していない(または始まってすらいない)ため、
            // 既存データは変更されていない。Rejectedとして良い。
            return BackupImportResult.Rejected("インポート中にエラーが発生しました。時間をおいて再試行してください")
        }

        // ここから先はRoomトランザクションが確定済み。以降の失敗はロールバックされないため、
        // Rejectedへ丸めず、データは置き換わったことが伝わるAppliedWithWarningとして扱う(§6.3)。
        // 後処理は3つあり、保存先も機構も独立している(EncryptedSharedPreferences/DataStore/
        // WorkManager)ため、先の失敗で後続をスキップしない。すべて試みたうえで、
        // 失敗したものをすべて案内に含める(第1.7段: 第1.6段は先頭の失敗で以降をスキップし、
        // 案内もその1件しか伝わらない欠陥があった)。
        val requestNotificationPermission = payload.settings.notifyReturnReminder || payload.settings.notifyPickupReady
        val warnings = mutableListOf<String>()

        // §6.2: パスワード書き込み(第2段)の前に必ず全消去する順序。第1段では書き込みを
        // 行わないが、全消去自体は第1段でも必須。失敗すると旧端末のパスワードが残ったままになり、
        // §6.2で防ごうとした「同じidの別人のパスワードが使われる」状態そのものになる。
        try {
            credentialStore.clearAll()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            warnings += "保存済みのパスワードが残っている可能性があります。各メンバーのパスワードを設定し直してください"
        }

        // DataStoreの設定を書き込む。値域はBackupPayloadCodec.decodeで検証済みのため
        // SettingsStore.update内のrequireで例外にはならない想定。全消去の成否に関わらず実行する。
        try {
            settingsStore.update(payload.settings.toAppSettings())
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            warnings += "設定の保存に失敗しました。設定画面で同期時刻と通知設定を確認してください"
        }

        // 同期時刻を移行しても、WorkManagerへの登録は端末ごとに別のため必ず引き直す。
        // 上の2つの成否に関わらず実行する。
        try {
            scheduleStarter.scheduleFromSettings()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            warnings += "自動同期の再設定に失敗しました。設定画面で同期時刻を開き直してください"
        }

        if (warnings.isNotEmpty()) {
            return BackupImportResult.AppliedWithWarning(
                importedMemberCount = members.size,
                requestNotificationPermission = requestNotificationPermission,
                message = "取り込みは完了しましたが、" + warnings.joinToString("\n"),
            )
        }

        return BackupImportResult.Success(
            importedMemberCount = members.size,
            requestNotificationPermission = requestNotificationPermission,
        )
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
