package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.dao.MemberDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReservationDao
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationCancelAttempt
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationSession
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationWriteBoundaryAware
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.MemberReservationCancelResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelItemResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelOutcome
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelTarget
import com.fallgist.nishinomiyalibrary.domain.model.UnknownReason
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCancelRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 予約取消を送信する。予約確定(ReservationCartRepositoryImpl)と同じ構造で、
 * メンバーごとに分離セッションでログインし、1件ずつ順に処理する。
 *
 * 重要: 取消は利用者の明示操作(UI上の取消ボタン等)からのみ呼び出すこと。
 * 自動処理・バックグラウンド同期からは絶対に呼び出してはならない。
 * サイトへ副作用のある操作であり、誤って自動実行すると利用者の意図しない取消が発生するため。
 */
@Singleton
class ReservationCancelRepositoryImpl @Inject constructor(
    private val reservationDao: ReservationDao,
    private val memberDao: MemberDao,
    private val credentialStore: CredentialStore,
    private val gateway: ReservationGateway,
    private val operationGate: ReservationOperationGate = ReservationOperationGate(),
) : ReservationCancelRepository {
    override suspend fun cancelReservations(targets: List<ReservationCancelTarget>): ReservationCancelBatchResult =
        operationGate.withOperation(ReservationOperationType.MANUAL_CANCELLATION) {
            val grouped = linkedMapOf<Long, MutableList<ReservationCancelTarget>>()
            targets.forEach { target ->
                require(target.memberId > 0) { "memberIdが不正です" }
                require(target.tilcod.isNotBlank()) { "tilcodが空です" }
                require(target.cancelCode.isNotBlank()) { "cancelCodeが空です" }
                grouped.getOrPut(target.memberId) { mutableListOf() } += target
            }
            ReservationCancelBatchResult(
                grouped.map { (memberId, memberTargets) ->
                    processMember(memberId, memberTargets, ::markWriteStarted)
                },
            )
        }

    private suspend fun processMember(
        memberId: Long,
        targets: List<ReservationCancelTarget>,
        beforeWrite: () -> Unit,
    ): MemberReservationCancelResult {
        val memberAndPassword = try {
            memberDao.getById(memberId)?.let { member ->
                credentialStore.getPassword(member.id)?.let { password -> member to password }
            }
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            return allFailure(memberId, targets, FailureReason.AUTH)
        }
        val member = memberAndPassword?.first
        val password = memberAndPassword?.second
        if (member == null || password.isNullOrBlank()) return allFailure(memberId, targets, FailureReason.AUTH)

        var session: ReservationSession? = null
        val results = mutableListOf<ReservationCancelItemResult>()
        try {
            session = try {
                gateway.openAuthenticatedSession(member.cardNumber, password)
            } catch (_: LibraryError.Auth) {
                return allFailure(memberId, targets, FailureReason.AUTH)
            } catch (_: LibraryError.Parse) {
                return allFailure(memberId, targets, FailureReason.SITE_RESPONSE_CHANGED)
            } catch (_: LibraryError.Maintenance) {
                return allFailure(memberId, targets, FailureReason.SITE_MAINTENANCE)
            } catch (_: LibraryError.Network) {
                return allFailure(memberId, targets, FailureReason.NETWORK)
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                return allFailure(memberId, targets, FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE)
            }
            (session as? ReservationWriteBoundaryAware)?.setBeforeWriteBoundary(beforeWrite)

            var index = 0
            var sessionExpiredRetried = false
            while (index < targets.size) {
                val target = targets[index]
                val attempt = try {
                    requireNotNull(session).cancelReservation(target.cancelCode, target.tilcod)
                } catch (_: LibraryError.Parse) {
                    abortRemaining(results, targets, index, FailureReason.SITE_RESPONSE_CHANGED)
                    break
                } catch (_: LibraryError.Maintenance) {
                    abortRemaining(results, targets, index, FailureReason.SITE_MAINTENANCE)
                    break
                } catch (_: LibraryError.Network) {
                    abortRemaining(results, targets, index, FailureReason.NETWORK)
                    break
                } catch (exception: Exception) {
                    exception.rethrowIfCancellation()
                    abortRemaining(results, targets, index, FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE)
                    break
                }

                when (attempt) {
                    // 12回目のライブ実測(2026-07-28)どおり、取消後も対象行は一覧に残り得る（Cancelled）か、
                    // 一覧から消える（CancelledAndHidden）かのいずれかであり、いずれも取消は成立している。
                    // ローカルDBからの即時削除もローカルUI向けの結果も、両方とも成功として同じ扱いにする。
                    ReservationCancelAttempt.Cancelled,
                    ReservationCancelAttempt.CancelledAndHidden,
                    is ReservationCancelAttempt.CancelledHideNotCompleted,
                    ReservationCancelAttempt.CancelledHideUnknown,
                    -> {
                        // 取消成功を確認できた予約は、次回の全置換同期を待たずローカルからも即時削除する。
                        // (同期は予約一覧を全置換するため、ここで消し忘れても次回同期で自己修復する。)
                        reservationDao.deleteByTarget(memberId, target.tilcod, target.cancelCode)
                        val outcome = when (attempt) {
                            ReservationCancelAttempt.Cancelled -> ReservationCancelOutcome.Cancelled
                            ReservationCancelAttempt.CancelledAndHidden -> ReservationCancelOutcome.CancelledAndHidden
                            is ReservationCancelAttempt.CancelledHideNotCompleted ->
                                ReservationCancelOutcome.CancelledHideNotCompleted(attempt.reason)
                            ReservationCancelAttempt.CancelledHideUnknown -> ReservationCancelOutcome.CancelledHideUnknown
                            else -> error("取消成功結果ではありません")
                        }
                        results += ReservationCancelItemResult(target, outcome)
                        index++
                        // 一覧の構造変化・対象不定・POST後不明は同じ会員の次件へ進む根拠を失わせる。
                        // 明示的拒否だけは、完全な一覧と正常応答で安全に確定できた場合の継続結果として扱う。
                        val mustAbortRemaining = when (attempt) {
                            is ReservationCancelAttempt.CancelledHideNotCompleted ->
                                attempt.reason != com.fallgist.nishinomiyalibrary.domain.model.HideFailureReason.REJECTED_BY_SITE
                            ReservationCancelAttempt.CancelledHideUnknown -> true
                            else -> false
                        }
                        if (mustAbortRemaining) {
                            abortRemaining(results, targets, index, FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE)
                            break
                        }
                    }
                    is ReservationCancelAttempt.Rejected -> {
                        results += ReservationCancelItemResult(target, ReservationCancelOutcome.Rejected(attempt.message))
                        index++
                    }
                    is ReservationCancelAttempt.ConfirmationRequired -> {
                        // 確認画面が返っただけで取消は完了していない。成功と誤解させないよう専用の結果にする。
                        results += ReservationCancelItemResult(
                            target,
                            ReservationCancelOutcome.ConfirmationRequired(attempt.message),
                        )
                        index++
                    }
                    ReservationCancelAttempt.IndeterminateAfterPost -> {
                        results += ReservationCancelItemResult(
                            target,
                            ReservationCancelOutcome.Unknown(UnknownReason.VERIFICATION_UNAVAILABLE),
                        )
                        index++
                    }
                    ReservationCancelAttempt.SessionExpiredBeforeSubmit -> {
                        if (sessionExpiredRetried) {
                            // 再ログイン直後にも関わらずセッション切れが続く場合は、以降も同様に失敗し得るため中止する。
                            abortRemaining(results, targets, index, FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT)
                            break
                        }
                        sessionExpiredRetried = true
                        val expiredSession = requireNotNull(session)
                        (expiredSession as? ReservationWriteBoundaryAware)?.setBeforeWriteBoundary(null)
                        expiredSession.close()
                        val retrySession = try {
                            gateway.openAuthenticatedSession(member.cardNumber, password)
                        } catch (_: LibraryError.Auth) {
                            abortRemaining(results, targets, index, FailureReason.AUTH)
                            break
                        } catch (_: LibraryError.Parse) {
                            abortRemaining(results, targets, index, FailureReason.SITE_RESPONSE_CHANGED)
                            break
                        } catch (_: LibraryError.Maintenance) {
                            abortRemaining(results, targets, index, FailureReason.SITE_MAINTENANCE)
                            break
                        } catch (_: LibraryError.Network) {
                            abortRemaining(results, targets, index, FailureReason.NETWORK)
                            break
                        } catch (exception: Exception) {
                            exception.rethrowIfCancellation()
                            abortRemaining(results, targets, index, FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE)
                            break
                        }
                        (retrySession as? ReservationWriteBoundaryAware)?.setBeforeWriteBoundary(beforeWrite)
                        session = retrySession
                        // indexは進めず、同じ対象を再ログイン後のセッションで再試行する。
                    }
                }
            }
            return MemberReservationCancelResult(memberId, results)
        } finally {
            (session as? ReservationWriteBoundaryAware)?.setBeforeWriteBoundary(null)
            session?.close()
        }
    }

    private fun abortRemaining(
        results: MutableList<ReservationCancelItemResult>,
        targets: List<ReservationCancelTarget>,
        fromIndex: Int,
        reason: FailureReason,
    ) {
        targets.drop(fromIndex).forEach { target ->
            results += ReservationCancelItemResult(target, ReservationCancelOutcome.Failure(reason))
        }
    }

    private fun allFailure(
        memberId: Long,
        targets: List<ReservationCancelTarget>,
        reason: FailureReason,
    ) = MemberReservationCancelResult(
        memberId,
        targets.map { target -> ReservationCancelItemResult(target, ReservationCancelOutcome.Failure(reason)) },
    )
}

private fun Exception.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
