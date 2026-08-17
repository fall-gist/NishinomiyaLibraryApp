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
        /**
         * パスワードの復号に失敗し、未設定のまま残ったメンバー数(§4)。0なら全員復元できている。
         * 復号失敗は該当メンバーだけの問題であり、インポート全体は失敗させない。
         */
        val passwordRestoreFailedCount: Int = 0,
    ) : BackupImportResult

    data class AppliedWithWarning(
        val importedMemberCount: Int,
        val requestNotificationPermission: Boolean,
        val message: String,
        val passwordRestoreFailedCount: Int = 0,
    ) : BackupImportResult

    data class Rejected(val message: String) : BackupImportResult
}

/** [SettingsScreenController]から呼ぶための境界。SAFのURI取得はCompose側の責務。 */
interface BackupImportPort {
    suspend fun import(jsonText: String): BackupImportResult
}

/**
 * バックアップJSONを検証し、単一のRoomトランザクションで全置換する。
 * 方式は全置換のみ(docs/design/settings-export-import.md §6)。
 *
 * パスワードは[CredentialStore]を全消去した後に書き込む(§6.2・§6.3)。全消去に失敗した場合は
 * パスワード書き込みだけをスキップする(消せていない場所へ書くと新旧が混在するため)。
 * メンバーごとの復号失敗はそのメンバーだけを未設定として扱い、インポート全体を失敗させない(§4)。
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
        var passwordRestoreFailedCount = 0

        // §6.2: パスワード書き込み(第2段)の前に必ず全消去する順序。失敗すると旧端末のパスワードが
        // 残ったままになり、§6.2で防ごうとした「同じidの別人のパスワードが使われる」状態そのものになる。
        val clearAllSucceeded = try {
            credentialStore.clearAll()
            true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            warnings += "保存済みのパスワードが残っている可能性があります。各メンバーのパスワードを設定し直してください"
            false
        }

        // §6.3: clearAllが失敗したときはパスワード書き込みだけをスキップする(消せていない場所へ
        // 書くと新旧が混在するため)。設定の書き込みと同期スケジュールの再構成は、そのときも実行する。
        if (clearAllSucceeded) {
            payload.members.forEach { member ->
                val encrypted = member.password ?: return@forEach
                if (!member.passwordEncrypted) return@forEach
                try {
                    credentialStore.savePassword(member.id, BackupSecret.decrypt(encrypted))
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    // 復号(または書き込み)に失敗したパスワードは、そのメンバーのみ未設定として扱う。
                    // 他の移行データを道連れにせず、インポート全体は失敗させない(§4)。
                    passwordRestoreFailedCount += 1
                }
            }
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
                message = formatWarningMessage(warnings),
                passwordRestoreFailedCount = passwordRestoreFailedCount,
            )
        }

        return BackupImportResult.Success(
            importedMemberCount = members.size,
            requestNotificationPermission = requestNotificationPermission,
            passwordRestoreFailedCount = passwordRestoreFailedCount,
        )
    }

    /**
     * 複数の後処理が失敗した場合の案内文を整形する(§6.3、§11)。改行で連ねるだけだと2件目以降が
     * 「取り込みは完了しましたが、」との接続を失うため、導入文を1回だけ出し、各項目を箇条書きにする。
     */
    private fun formatWarningMessage(warnings: List<String>): String =
        "取り込みは完了しましたが、次の問題が発生しました。\n" + warnings.joinToString("\n") { "・$it" }
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

/**
 * パスワード復元失敗の案内文(§4)。1人以上が復元できなかった場合だけメッセージを返す。
 * 復号失敗はそのメンバーだけの問題であり、インポート全体の成否には影響しない。
 *
 * 設定画面([com.fallgist.nishinomiyalibrary.ui.settings.BackupSection])と初期画面
 * ([com.fallgist.nishinomiyalibrary.ui.home.MemberRegistrationForm])の両方から参照する
 * 共通の文言(docs/design/settings-export-import.md §5.1: 同じ処理の結果を画面ごとに
 * 別の言葉で説明しない)。片方だけ直して静かにズレることを防ぐため、ここへ集約する。
 */
internal fun passwordRestoreMessage(failedCount: Int): String? =
    if (failedCount > 0) "${failedCount}人分のパスワードを復元できませんでした。再入力してください" else null

/**
 * backupImporterが未設定のテスト等での既定実装。呼ばれることを想定しない。
 * [com.fallgist.nishinomiyalibrary.ui.settings.SettingsScreenController]と
 * [com.fallgist.nishinomiyalibrary.ui.home.HomeScreenController]の両方から参照する共通実装。
 */
internal object NoOpBackupImportPort : BackupImportPort {
    override suspend fun import(jsonText: String): BackupImportResult =
        error("バックアップのインポートが設定されていません")
}
