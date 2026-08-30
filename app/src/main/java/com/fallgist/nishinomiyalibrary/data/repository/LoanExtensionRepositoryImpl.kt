package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.dao.LoanDao
import com.fallgist.nishinomiyalibrary.data.local.dao.MemberDao
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LoanExtensionGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LoanExtensionSession
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionItemResult
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
    private val loanDao: LoanDao,
) : LoanExtensionRepository {
    private val submissionMutex = Mutex()

    override suspend fun extendLoan(target: LoanExtensionTarget): LoanExtensionOutcome = submissionMutex.withLock {
        extendLoanLocked(target)
    }

    /**
     * `docs/design/bulk-selection.md` §5.1: 既存[extendLoan]を順に呼ぶだけのループ。
     * Gatewayや1件の延長ロジックは一切変更しない。1件が[LoanExtensionOutcome.Failure]や
     * [LoanExtensionOutcome.Unknown]になっても後続の対象を続けて処理する(CancellationExceptionは
     * 呼び出し元の中断としてそのまま伝播させる)。
     * [onProgress]は1件終えるたびに呼ぶ(UI側の進捗表示 §5.2用)。
     */
    override suspend fun extendLoans(
        targets: List<LoanExtensionTarget>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): LoanExtensionBatchResult {
        val items = targets.mapIndexed { index, target ->
            val item = LoanExtensionItemResult(target, extendLoan(target))
            onProgress(index + 1, targets.size)
            item
        }
        return LoanExtensionBatchResult(items)
    }

    private suspend fun extendLoanLocked(target: LoanExtensionTarget): LoanExtensionOutcome {
        val member = try {
            memberDao.getById(target.memberId)
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            return LoanExtensionOutcome.Failure(FailureReason.AUTH)
        } ?: return LoanExtensionOutcome.Failure(FailureReason.AUTH)

        val password = try {
            credentialStore.getPassword(member.id)
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            return LoanExtensionOutcome.Failure(FailureReason.AUTH)
        }
        if (password.isNullOrBlank()) return LoanExtensionOutcome.Failure(FailureReason.AUTH)

        var session: LoanExtensionSession? = null
        val outcome = try {
            session = try {
                gateway.openAuthenticatedSession(member.cardNumber, password)
            } catch (_: LibraryError.Auth) {
                return LoanExtensionOutcome.Failure(FailureReason.AUTH)
            } catch (_: LibraryError.Parse) {
                return LoanExtensionOutcome.Failure(FailureReason.SITE_RESPONSE_CHANGED)
            } catch (_: LibraryError.Maintenance) {
                return LoanExtensionOutcome.Failure(FailureReason.SITE_MAINTENANCE)
            } catch (_: LibraryError.Network) {
                return LoanExtensionOutcome.Failure(FailureReason.NETWORK)
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                return LoanExtensionOutcome.Failure(FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE)
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

        // 成功時だけローカルへ反映する(`docs/design/loan-extension.md` §6.1)。
        // Unknown/Failureでは一切書き換えない。ローカル反映の失敗は延長そのものの成否を変えない
        // (サイト側では既に延長済みのため、Room書込み失敗を理由にFailureへ落とさない)。
        if (outcome is LoanExtensionOutcome.Extended) {
            try {
                loanDao.applyExtensionResult(target.memberId, target.tilcod, outcome.newDueDate)
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
            }
        }
        return outcome
    }
}

private fun Exception.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
