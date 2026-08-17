package com.fallgist.nishinomiyalibrary.data.backup

import com.fallgist.nishinomiyalibrary.data.local.APP_DATABASE_VERSION
import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationControlEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationRuleEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationTermEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingHistoryCheckpointEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingRecordEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationCartItemEntity
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

/** [SettingsScreenController]から呼ぶための境界。SAFのURI取得はCompose側の責務。 */
interface BackupExportPort {
    /** 書き出し用のJSON文字列を返す。 */
    suspend fun export(): String
}

/**
 * 各Store/DAOから読み出して[BackupPayload]を組み、JSON文字列にする。
 * docs/design/settings-export-import.md §2の「含める」対象だけを読み出す(キャッシュ系は含めない)。
 *
 * Room側の読み出しは[AppDatabase.readBackupSnapshot]で単一トランザクション化している(§7.1)。
 * DataStoreの設定はRoomのトランザクションに含められないが、他のデータと相互参照しないため
 * 不整合の問題は生じない。
 *
 * パスワードは[CredentialStore]から読み出し、[BackupSecret.encrypt]でメンバーごとに暗号化して
 * 格納する(§4、第2段)。パスワード未設定のメンバーは `password = null` / `passwordEncrypted = false`
 * のまま出力する。
 */
class BackupExporter(
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val credentialStore: CredentialStore,
    private val appVersion: String,
    private val sourceDbVersion: Int = APP_DATABASE_VERSION,
) : BackupExportPort {

    override suspend fun export(): String = BackupPayloadCodec.encode(buildPayload())

    suspend fun buildPayload(now: OffsetDateTime = OffsetDateTime.now()): BackupPayload {
        val snapshot = database.readBackupSnapshot()
        val settings = settingsStore.settings.first()
        val termsByRuleId = snapshot.autoReservationTerms.groupBy { it.ruleId }

        return BackupPayload(
            formatVersion = BACKUP_FORMAT_VERSION,
            exportedAt = ISO_OFFSET_FORMATTER.format(now),
            appVersion = appVersion,
            sourceDbVersion = sourceDbVersion,
            members = snapshot.members.map { it.toBackup(credentialStore) },
            settings = settings.toBackup(),
            autoReservation = BackupAutoReservation(
                rules = snapshot.autoReservationRules.map { rule -> rule.toBackup(termsByRuleId[rule.id].orEmpty()) },
                controls = snapshot.autoReservationControls.map { it.toBackup() },
            ),
            readingRecords = snapshot.readingRecords.map { it.toBackup() },
            readingHistoryCheckpoints = snapshot.readingHistoryCheckpoints.map { it.toBackup() },
            reservationCartItems = snapshot.reservationCartItems.map { it.toBackup() },
        )
    }

    companion object {
        private val ISO_OFFSET_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}

/** 既定のエクスポートファイル名(docs/design/settings-export-import.md §3)。 */
object BackupFileNaming {
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    fun suggestedFileName(now: LocalDateTime = LocalDateTime.now()): String =
        "nishinomiya-library-backup-${formatter.format(now)}.json"
}

private fun MemberEntity.toBackup(credentialStore: CredentialStore): BackupMember {
    val plainPassword = credentialStore.getPassword(id)
    return BackupMember(
        id = id,
        name = name,
        colorHex = colorHex,
        cardNumber = cardNumber,
        sortOrder = sortOrder,
        passwordEncrypted = plainPassword != null,
        password = plainPassword?.let(BackupSecret::encrypt),
    )
}

private fun com.fallgist.nishinomiyalibrary.data.local.AppSettings.toBackup() = BackupSettings(
    syncHour = syncHour,
    syncMinute = syncMinute,
    notifyReturnReminder = notifyReturnReminder,
    notifyPickupReady = notifyPickupReady,
    defaultCalendarLibrary = defaultCalendarLibrary,
    returnReminderDaysBefore = returnReminderDaysBefore,
    diagnosticLogEnabled = diagnosticLogEnabled,
    autoReservationEnabled = autoReservationEnabled,
)

private fun AutoReservationRuleEntity.toBackup(terms: List<AutoReservationTermEntity>) = BackupAutoReservationRule(
    id = id,
    enabled = enabled,
    sortOrder = sortOrder,
    terms = terms
        .sortedWith(compareBy({ it.kind.name }, { it.sortOrder }))
        .map { it.toBackup() },
)

private fun AutoReservationTermEntity.toBackup() = BackupAutoReservationTerm(
    kind = kind.name,
    sortOrder = sortOrder,
    original = original,
    normalized = normalized,
)

private fun AutoReservationControlEntity.toBackup() = BackupAutoReservationControl(
    tilcod = tilcod,
    firstCandidateDate = firstCandidateDate.toString(),
    expiresOn = expiresOn.toString(),
    status = status.name,
    preparedMemberId = preparedMemberId,
)

private fun ReadingRecordEntity.toBackup() = BackupReadingRecord(
    memberId = memberId,
    tilcod = tilcod,
    title = title,
    loanDate = loanDate.toString(),
    library = library,
    titleNormalized = titleNormalized,
)

private fun ReadingHistoryCheckpointEntity.toBackup() = BackupReadingHistoryCheckpoint(
    memberId = memberId,
    tilcod = tilcod,
    loanDate = loanDate.toString(),
)

private fun ReservationCartItemEntity.toBackup() = BackupReservationCartItem(
    memberId = memberId,
    tilcod = tilcod,
    title = title,
    writerLine = writerLine,
    addedAtEpochMillis = addedAtEpochMillis,
)
