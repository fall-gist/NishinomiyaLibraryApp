package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.dao.MemberDao
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LoanExtensionGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LoanExtensionSession
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionOutcome
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionTarget
import com.fallgist.nishinomiyalibrary.domain.repository.LoanExtensionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 貸出延長を送信する。予約取消(ReservationCancelRepositoryImpl)と同じく、メンバーごとに
 * 分離セッションでログインしてから1件送信する。
 *
 * 重要: 延長は利用者の明示操作(UI上の延長ボタン等)からのみ呼び出すこと。
 * 自動処理・バックグラウンド同期からは絶対に呼び出してはならない。
 *
 * `docs/design/loan-extension.md` §7・§9.3のとおり、共通書込ゲート(ReservationOperationGate)には
 * 参加させない。貸出延長は予約データに触れず、手動予約・自動予約・予約取消との業務上の競合が無い
 * ためである。二重タップ防止のためだけの専用Mutexだけを持つ。
 */
@Singleton
class LoanExtensionRepositoryImpl @Inject constructor(
    private val memberDao: MemberDao,
    private val credentialStore: CredentialStore,
    private val gateway: LoanExtensionGateway,
) : LoanExtensionRepository {
    private val submissionMutex = Mutex()

    override suspend fun extendLoan(target: LoanExtensionTarget): LoanExtensionOutcome = submissionMutex.withLock {
        val member = try {
            memberDao.getById(target.memberId)
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            return@withLock LoanExtensionOutcome.Failure(FailureReason.AUTH)
        } ?: return@withLock LoanExtensionOutcome.Failure(FailureReason.AUTH)

        val password = try {
            credentialStore.getPassword(member.id)
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            return@withLock LoanExtensionOutcome.Failure(FailureReason.AUTH)
        }
        if (password.isNullOrBlank()) return@withLock LoanExtensionOutcome.Failure(FailureReason.AUTH)

        var session: LoanExtensionSession? = null
        try {
            session = try {
                gateway.openAuthenticatedSession(member.cardNumber, password)
            } catch (_: LibraryError.Auth) {
                return@withLock LoanExtensionOutcome.Failure(FailureReason.AUTH)
            } catch (_: LibraryError.Parse) {
                return@withLock LoanExtensionOutcome.Failure(FailureReason.SITE_RESPONSE_CHANGED)
            } catch (_: LibraryError.Maintenance) {
                return@withLock LoanExtensionOutcome.Failure(FailureReason.SITE_MAINTENANCE)
            } catch (_: LibraryError.Network) {
                return@withLock LoanExtensionOutcome.Failure(FailureReason.NETWORK)
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                return@withLock LoanExtensionOutcome.Failure(FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE)
            }

            try {
                requireNotNull(session).extendLoan(target.tilcod)
            } catch (_: LibraryError.Parse) {
                LoanExtensionOutcome.Failure(FailureReason.SITE_RESPONSE_CHANGED)
            } catch (_: LibraryError.Maintenance) {
                LoanExtensionOutcome.Failure(FailureReason.SITE_MAINTENANCE)
            } catch (_: LibraryError.Network) {
                LoanExtensionOutcome.Failure(FailureReason.NETWORK)
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                LoanExtensionOutcome.Failure(FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE)
            }
        } finally {
            session?.close()
        }
    }
}

private fun Exception.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
