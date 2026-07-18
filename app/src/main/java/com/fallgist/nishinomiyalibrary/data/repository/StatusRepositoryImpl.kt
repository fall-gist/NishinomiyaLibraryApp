package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.dao.LoanDao
import com.fallgist.nishinomiyalibrary.data.local.dao.MemberDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReservationDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ShelfItemDao
import com.fallgist.nishinomiyalibrary.data.local.dao.SyncLogDao
import com.fallgist.nishinomiyalibrary.data.local.dao.UserSummaryDao
import com.fallgist.nishinomiyalibrary.data.local.entity.SyncLogEntity
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.UserData
import com.fallgist.nishinomiyalibrary.data.sync.PostSyncNotifier
import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
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
) : StatusRepository {
    private val syncMutex = Mutex()

    override fun loans(): Flow<List<Loan>> = loanDao.observeAll().map { loans -> loans.map { it.toDomain() } }

    override fun reservations(): Flow<List<Reservation>> =
        reservationDao.observeAll().map { reservations -> reservations.map { it.toDomain() } }

    override fun shelf(memberId: Long): Flow<List<ShelfItem>> =
        shelfItemDao.observeForMember(memberId).map { shelf -> shelf.map { it.toDomain() } }

    override fun summaries(): Flow<List<UserSummary>> =
        userSummaryDao.observeAll().map { summaries -> summaries.map { it.toDomain() } }

    override fun lastSync(): Flow<SyncLog?> = syncLogDao.observeLatest().map { it?.toDomain() }

    override suspend fun syncAll(trigger: SyncTrigger): SyncResult = syncMutex.withLock {
        val startedAt = clock.millis()
        if (trigger == SyncTrigger.MANUAL) {
            val latestSuccessful = syncLogDao.getLatestSuccessful()
            val nextAllowedAt = latestSuccessful?.finishedAtEpochMillis?.plus(COOLDOWN_MILLIS)
            if (nextAllowedAt != null && startedAt < nextAllowedAt) {
                return SyncResult.SkippedCooldown(nextAllowedAt)
            }
        }

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
        if (result.isCompleteSuccess) {
            try {
                postSyncNotifier.notifyAfterSuccessfulSync()
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
            val userData = gateway.fetchUserData(cardNumber, password).withMemberId(memberId)
            database.replaceMemberSnapshot(
                memberId = memberId,
                loans = userData.loans.map { it.toEntity(it.memberId) },
                reservations = userData.reservations.map { it.toEntity(it.memberId) },
                shelfItems = userData.shelf.map { it.toEntity(it.memberId) },
                summary = userData.summary.toEntity(userData.summary.memberId),
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
        shelf = shelf.map { it.copy(memberId = memberId) },
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

    private companion object {
        const val COOLDOWN_MILLIS = 5 * 60 * 1000L
    }
}
