package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.dao.LoanDao
import com.fallgist.nishinomiyalibrary.data.local.dao.MemberDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReservationDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReservationPickupSubmissionDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReadingRecordDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ShelfItemDao
import com.fallgist.nishinomiyalibrary.data.local.dao.SyncLogDao
import com.fallgist.nishinomiyalibrary.data.local.dao.UserSummaryDao
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingHistoryCheckpointEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.SyncLogEntity
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.UserData
import com.fallgist.nishinomiyalibrary.data.sync.PostSyncNotifier
import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationPickupSubmissionRecord
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecordKey
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.model.UserSummary
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SyncLog
import com.fallgist.nishinomiyalibrary.domain.repository.SyncResult
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatusRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val memberDao: MemberDao,
    private val loanDao: LoanDao,
    private val reservationDao: ReservationDao,
    private val shelfItemDao: ShelfItemDao,
    private val userSummaryDao: UserSummaryDao,
    private val syncLogDao: SyncLogDao,
    private val credentialStore: CredentialStore,
    private val gateway: LibraryGateway,
    private val postSyncNotifier: PostSyncNotifier,
    private val clock: Clock,
    private val readingRecordDao: ReadingRecordDao = database.readingRecordDao(),
    private val pickupSubmissionDao: ReservationPickupSubmissionDao = database.reservationPickupSubmissionDao(),
) : StatusRepository {
    private val syncMutex = Mutex()

    override fun loans(): Flow<List<Loan>> = loanDao.observeAll().map { loans -> loans.map { it.toDomain() } }

    override fun reservations(): Flow<List<Reservation>> =
        reservationDao.observeAll().map { reservations -> reservations.map { it.toDomain() } }

    override fun pickupSubmissions(): Flow<List<ReservationPickupSubmissionRecord>> =
        pickupSubmissionDao.observeAll().map { submissions ->
            submissions.map {
                ReservationPickupSubmissionRecord(
                    memberId = it.memberId,
                    tilcod = it.tilcod,
                    pickupLibraryCode = it.pickupLibraryCode,
                    origin = it.origin,
                )
            }
        }

    override fun shelf(memberId: Long): Flow<List<ShelfItem>> =
        shelfItemDao.observeForMember(memberId).map { shelf -> shelf.map { it.toDomain() } }

    override fun summaries(): Flow<List<UserSummary>> =
        userSummaryDao.observeAll().map { summaries -> summaries.map { it.toDomain() } }

    override fun lastSync(): Flow<SyncLog?> = syncLogDao.observeLatest().map { it?.toDomain() }

    override suspend fun syncAll(trigger: SyncTrigger): SyncResult = syncMutex.withLock {
        // 手動更新のクールダウン(5分)は所有者判断で撤廃した(2026-07-28)。家庭内利用のみを想定しており
        // サーバへの負荷となるほどのリクエストはそもそも送れないため、利用者の良心に委ねる。
        // なお、リクエスト間の最小間隔500ms(LicsXpSessionのMINIMUM_REQUEST_INTERVAL_MILLIS)は
        // 別レイヤーの安全策として維持している(docs/spec.md §5, docs/backend-design.md参照)。
        val startedAt = clock.millis()

        val logId = syncLogDao.insert(
            SyncLogEntity(
                startedAtEpochMillis = startedAt,
                finishedAtEpochMillis = null,
                trigger = trigger.name,
                succeeded = null,
                details = "",
            ),
        )
        val outcomes = mutableListOf<MemberSyncOutcome>()
        for (member in memberDao.getAll()) {
            outcomes += syncMember(member.id, member.cardNumber)
        }

        val failureCount = outcomes.count { !it.succeeded }
        val result = SyncResult.Completed(
            syncedMemberCount = outcomes.size - failureCount,
            failedMemberCount = failureCount,
        )
        syncLogDao.update(
            SyncLogEntity(
                id = logId,
                startedAtEpochMillis = startedAt,
                finishedAtEpochMillis = clock.millis(),
                trigger = trigger.name,
                succeeded = result.isCompleteSuccess,
                details = outcomes.toDetails(),
            ),
        )
        val successfulMemberIds = outcomes.asSequence()
            .filter { it.succeeded }
            .map { it.memberId }
            .toSet()
        if (successfulMemberIds.isNotEmpty()) {
            try {
                postSyncNotifier.notifyAfterSuccessfulSync(successfulMemberIds)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // 通知送信の失敗は同期済みデータを巻き戻さず、次回同期で再判定する。
            }
        }
        return result
    }

    private suspend fun syncMember(memberId: Long, cardNumber: String): MemberSyncOutcome {
        val password = try {
            credentialStore.getPassword(memberId)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return MemberSyncOutcome(memberId, "資格情報エラー")
        } ?: return MemberSyncOutcome(memberId, "資格情報未設定")

        return try {
            val knownReadingRecordKeys = readingRecordDao.getHistoryCheckpointKeys(memberId)
                .map { key -> ReadingRecordKey(key.tilcod, key.loanDate) }
                .toSet()
            val userData = gateway.fetchUserData(cardNumber, password, knownReadingRecordKeys).withMemberId(memberId)
            val currentLoansAsReadingRecords = userData.loans
                .asSequence()
                .filter { loan -> loan.tilcod.isNotBlank() }
                .map { loan ->
                    ReadingRecord(
                        memberId = memberId,
                        tilcod = loan.tilcod,
                        title = loan.title,
                        loanDate = loan.loanDate,
                        library = loan.lendingLibrary,
                    )
                }
                .toList()
            val recordsToUpsert = (userData.readingRecords + currentLoansAsReadingRecords)
                .distinctBy { record -> ReadingRecordKey(record.tilcod, record.loanDate) }
            val historyCheckpoints = userData.readingRecords
                .distinctBy { record -> ReadingRecordKey(record.tilcod, record.loanDate) }
                .map { record ->
                    ReadingHistoryCheckpointEntity(
                        memberId = record.memberId,
                        tilcod = record.tilcod,
                        loanDate = record.loanDate,
                    )
                }
            database.replaceMemberSnapshot(
                memberId = memberId,
                loans = userData.loans.map { it.toEntity(it.memberId) },
                reservations = excludeCancelledReservations(userData.reservations).map { it.toEntity(it.memberId) },
                shelves = userData.shelves.map { it.toEntity(memberId) },
                shelfItems = userData.shelfItems.map { it.toEntity(it.memberId) },
                summary = userData.summary.toEntity(userData.summary.memberId),
                readingRecords = recordsToUpsert.map { it.toEntity() },
                readingHistoryCheckpoints = historyCheckpoints,
            )
            MemberSyncOutcome(memberId, null)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            MemberSyncOutcome(memberId, exception.safeFailureType())
        }
    }

    private fun UserData.withMemberId(memberId: Long): UserData = copy(
        summary = summary.copy(memberId = memberId),
        loans = loans.map { it.copy(memberId = memberId) },
        reservations = reservations.map { it.copy(memberId = memberId) },
        shelfItems = shelfItems.map { it.copy(memberId = memberId) },
        readingRecords = readingRecords.map { it.copy(memberId = memberId) },
    )

    private fun Exception.safeFailureType(): String = when (this) {
        is LibraryError.Network -> "通信エラー"
        is LibraryError.Auth -> "認証エラー"
        is LibraryError.Parse -> "データ形式エラー"
        is LibraryError.Maintenance -> "メンテナンス中"
        else -> "不明なエラー"
    }

    private data class MemberSyncOutcome(val memberId: Long, val failureType: String?) {
        val succeeded: Boolean get() = failureType == null
    }

    private fun List<MemberSyncOutcome>.toDetails(): String = when {
        isEmpty() -> "対象メンバーなし"
        else -> joinToString(separator = "\n") { outcome ->
            if (outcome.succeeded) "memberId=${outcome.memberId}:成功"
            else "memberId=${outcome.memberId}:${outcome.failureType}"
        }
    }

}

/**
 * 同期時に取消済み(CANCELLED)予約を保存対象から除外する純関数。
 * 除外しないと、取消成立で即時削除した行が次回同期でサイトの「取消」行として復活してしまう
 * (docs/ui-design.md「方針: 予約取消の導線」§取消済み行はアプリの一覧に出さない)。
 * ユニットテストのためinternalで公開する(app/src/test/.../StatusRepositoryImplSyncTest.kt)。
 */
internal fun excludeCancelledReservations(reservations: List<Reservation>): List<Reservation> =
    reservations.filter { it.state != ReservationState.CANCELLED }
