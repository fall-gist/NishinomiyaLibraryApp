package com.fallgist.nishinomiyalibrary.data.backup

import com.fallgist.nishinomiyalibrary.data.local.APP_DATABASE_VERSION
import com.fallgist.nishinomiyalibrary.data.local.SettingsStore
import com.fallgist.nishinomiyalibrary.data.local.dao.AutoReservationDao
import com.fallgist.nishinomiyalibrary.data.local.dao.MemberDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReadingRecordDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReservationCartDao
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
 */
class BackupExporter(
    private val memberDao: MemberDao,
    private val settingsStore: SettingsStore,
    private val autoReservationDao: AutoReservationDao,
    private val readingRecordDao: ReadingRecordDao,
    private val reservationCartDao: ReservationCartDao,
    private val appVersion: String,
    private val sourceDbVersion: Int = APP_DATABASE_VERSION,
) : BackupExportPort {

    override suspend fun export(): String = BackupPayloadCodec.encode(buildPayload())

    suspend fun buildPayload(now: OffsetDateTime = OffsetDateTime.now()): BackupPayload {
        val members = memberDao.getAll()
        val settings = settingsStore.settings.first()
        val rules = autoReservationDao.getRules()
        val termsByRuleId = autoReservationDao.getTerms(rules.map { it.id }).groupBy { it.ruleId }
        val controls = autoReservationDao.getAllControls()
        val readingRecords = readingRecordDao.observeAll().first()
        val checkpoints = readingRecordDao.getAllHistoryCheckpoints()
        val cartItems = reservationCartDao.getAll()

        return BackupPayload(
            formatVersion = BACKUP_FORMAT_VERSION,
            exportedAt = ISO_OFFSET_FORMATTER.format(now),
            appVersion = appVersion,
            sourceDbVersion = sourceDbVersion,
            members = members.map { it.toBackup() },
            settings = settings.toBackup(),
            autoReservation = BackupAutoReservation(
                rules = rules.map { rule -> rule.toBackup(termsByRuleId[rule.id].orEmpty()) },
                controls = controls.map { it.toBackup() },
            ),
            readingRecords = readingRecords.map { it.toBackup() },
            readingHistoryCheckpoints = checkpoints.map { it.toBackup() },
            reservationCartItems = cartItems.map { it.toBackup() },
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

private fun MemberEntity.toBackup() = BackupMember(
    id = id,
    name = name,
    colorHex = colorHex,
    cardNumber = cardNumber,
    sortOrder = sortOrder,
    passwordEncrypted = false,
    password = null,
)

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
