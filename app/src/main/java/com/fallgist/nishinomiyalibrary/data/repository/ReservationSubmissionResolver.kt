package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.DirectReservationAttempt
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.InvalidPickupLibraryException
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationListSnapshot
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationSession
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationSnapshotSource
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationWriteBoundaryAware
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.ReservationItemResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
import com.fallgist.nishinomiyalibrary.domain.model.UnknownReason

/**
 * 予約確定の応答を解決する内部実装。
 *
 * メンバー内での送信順と末尾の一覧照合は、従来の手動予約と同じ契約で保持する。
 * Roomや画面状態には依存させず、呼出し側が結果をカート削除・送信館記録へ利用する。
 */
internal class ReservationSubmissionResolver(
    private val gateway: ReservationGateway,
) {
    suspend fun resolveMember(
        cardNumber: String,
        password: String,
        targets: List<ReservationTarget>,
        pickupLibraryCode: String,
        beforeWrite: () -> Unit = {},
    ): ReservationMemberSubmissionResult {
        var session: ReservationSession? = null
        val provisional = linkedMapOf<ReservationTarget, Provisional>()
        val postBoundaries = linkedMapOf<ReservationTarget, ReservationPostBoundary>()
        var latestSnapshot: ReservationListSnapshot? = null
        try {
            session = try {
                gateway.openAuthenticatedSession(cardNumber, password)
            } catch (_: LibraryError.Auth) {
                return allFailure(targets, FailureReason.AUTH)
            } catch (_: LibraryError.Parse) {
                return allFailure(targets, FailureReason.SITE_RESPONSE_CHANGED)
            } catch (_: LibraryError.Maintenance) {
                return allFailure(targets, FailureReason.SITE_MAINTENANCE)
            } catch (_: LibraryError.Network) {
                return allFailure(targets, FailureReason.NETWORK)
            } catch (exception: Exception) {
                rethrowIfCancellation(exception)
                return allFailure(targets, FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE)
            }
            (session as? ReservationWriteBoundaryAware)?.setBeforeWriteBoundary(beforeWrite)

            var index = 0
            while (index < targets.size) {
                val target = targets[index]
                val attempt = try {
                    requireNotNull(session).directReserve(target.tilcod, pickupLibraryCode)
                } catch (_: InvalidPickupLibraryException) {
                    failCurrentAndAbortRemaining(provisional, targets, index, FailureReason.INVALID_PICKUP_LIBRARY)
                    break
                } catch (_: LibraryError.Parse) {
                    failCurrentAndAbortRemaining(provisional, targets, index, FailureReason.SITE_RESPONSE_CHANGED)
                    break
                } catch (_: LibraryError.Maintenance) {
                    failCurrentAndAbortRemaining(provisional, targets, index, FailureReason.SITE_MAINTENANCE)
                    break
                } catch (_: LibraryError.Network) {
                    failCurrentAndAbortRemaining(provisional, targets, index, FailureReason.NETWORK)
                    break
                }

                postBoundaries[target] = attempt.postBoundary
                when (attempt) {
                    DirectReservationAttempt.SessionExpiredBeforeSubmit -> {
                        val expiredSession = requireNotNull(session)
                        (expiredSession as? ReservationWriteBoundaryAware)?.setBeforeWriteBoundary(null)
                        expiredSession.close()
                        val retrySession = try {
                            gateway.openAuthenticatedSession(cardNumber, password)
                        } catch (_: LibraryError.Auth) {
                            failCurrentAndAbortRemaining(provisional, targets, index, FailureReason.AUTH)
                            break
                        } catch (_: LibraryError.Parse) {
                            failCurrentAndAbortRemaining(provisional, targets, index, FailureReason.SITE_RESPONSE_CHANGED)
                            break
                        } catch (_: LibraryError.Maintenance) {
                            failCurrentAndAbortRemaining(provisional, targets, index, FailureReason.SITE_MAINTENANCE)
                            break
                        } catch (_: LibraryError.Network) {
                            failCurrentAndAbortRemaining(provisional, targets, index, FailureReason.NETWORK)
                            break
                        } catch (exception: Exception) {
                            rethrowIfCancellation(exception)
                            failCurrentAndAbortRemaining(provisional, targets, index, FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE)
                            break
                        }
                        (retrySession as? ReservationWriteBoundaryAware)?.setBeforeWriteBoundary(beforeWrite)
                        session = retrySession
                        val retried = try {
                            retrySession.directReserve(target.tilcod, pickupLibraryCode)
                        } catch (_: InvalidPickupLibraryException) {
                            failCurrentAndAbortRemaining(provisional, targets, index, FailureReason.INVALID_PICKUP_LIBRARY)
                            break
                        } catch (_: LibraryError.Parse) {
                            failCurrentAndAbortRemaining(provisional, targets, index, FailureReason.SITE_RESPONSE_CHANGED)
                            break
                        } catch (_: LibraryError.Maintenance) {
                            failCurrentAndAbortRemaining(provisional, targets, index, FailureReason.SITE_MAINTENANCE)
                            break
                        } catch (_: LibraryError.Network) {
                            failCurrentAndAbortRemaining(provisional, targets, index, FailureReason.NETWORK)
                            break
                        }
                        postBoundaries[target] = retried.postBoundary
                        if (retried == DirectReservationAttempt.SessionExpiredBeforeSubmit) {
                            failCurrentAndAbortRemaining(provisional, targets, index, FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT)
                            break
                        }
                        val handled = handleAttempt(
                            attempt = retried,
                            session = retrySession,
                            target = target,
                            targets = targets,
                            index = index,
                            provisional = provisional,
                        )
                        latestSnapshot = handled.snapshot ?: latestSnapshot
                        if (!handled.canContinue) break
                    }

                    else -> {
                        val handled = handleAttempt(
                            attempt = attempt,
                            session = requireNotNull(session),
                            target = target,
                            targets = targets,
                            index = index,
                            provisional = provisional,
                        )
                        latestSnapshot = handled.snapshot ?: latestSnapshot
                        if (!handled.canContinue) break
                    }
                }
                index++
            }

            // 即時照合していない Submitted/Duplicate だけを、メンバー末尾で一回取得して解決する。
            val requiresFinalVerification = provisional.values.any { it == Provisional.Submitted || it == Provisional.Duplicate }
            val finalSnapshot = if (requiresFinalVerification) {
                fetchReservationSnapshot(requireNotNull(session))
            } else {
                null
            }
            latestSnapshot = finalSnapshot ?: latestSnapshot
            val reservedTilcods = finalSnapshot?.reservations?.map { it.tilcod }?.filter(String::isNotBlank)?.toSet()
            return ReservationMemberSubmissionResult(
                results = targets.map { target ->
                    val outcome = resolve(provisional[target], target.tilcod, reservedTilcods)
                    ReservationSubmissionResult(
                        target = target,
                        outcome = outcome,
                        postBoundary = postBoundaries[target] ?: ReservationPostBoundary.NOT_SENT,
                        fallbackToNextMember = outcome.fallbackToNextMember(),
                        sessionReusable = outcome.sessionReusable(),
                        latestReservationSnapshot = latestSnapshot,
                    )
                },
                latestReservationSnapshot = latestSnapshot,
            )
        } finally {
            (session as? ReservationWriteBoundaryAware)?.setBeforeWriteBoundary(null)
            session?.close()
        }
    }

    private suspend fun handleAttempt(
        attempt: DirectReservationAttempt,
        session: ReservationSession,
        target: ReservationTarget,
        targets: List<ReservationTarget>,
        index: Int,
        provisional: MutableMap<ReservationTarget, Provisional>,
    ): AttemptHandling = when (attempt) {
        DirectReservationAttempt.Submitted -> {
            provisional[target] = Provisional.Submitted
            AttemptHandling()
        }
        DirectReservationAttempt.DuplicateDetected -> {
            provisional[target] = Provisional.Duplicate
            AttemptHandling()
        }
        DirectReservationAttempt.RejectedBeforeSubmit -> {
            provisional[target] = Provisional.Failure(FailureReason.REJECTED_BY_SITE)
            AttemptHandling()
        }
        is DirectReservationAttempt.LimitExceeded -> {
            provisional[target] = Provisional.Failure(FailureReason.RESERVATION_LIMIT_EXCEEDED, attempt.message)
            AttemptHandling()
        }
        DirectReservationAttempt.Registered,
        DirectReservationAttempt.StayedOnConfirmation,
        -> resolveConfirmation(session, target, targets, index, provisional)
        DirectReservationAttempt.IndeterminateAfterPost -> resolveIndeterminateAfterPost(session, target, targets, index, provisional)
        DirectReservationAttempt.SessionExpiredBeforeSubmit -> error("再認証前に処理してはいけません")
    }

    private suspend fun resolveConfirmation(
        session: ReservationSession,
        target: ReservationTarget,
        targets: List<ReservationTarget>,
        index: Int,
        provisional: MutableMap<ReservationTarget, Provisional>,
    ): AttemptHandling {
        val snapshot = fetchSnapshotForConfirmation(session)
        if (snapshot == null) {
            abortAsIndeterminate(provisional, targets, index, UnknownReason.VERIFICATION_UNAVAILABLE)
            return AttemptHandling(canContinue = false)
        }
        val reservedTilcods = snapshot.reservations.map { it.tilcod }.filter(String::isNotBlank).toSet()
        if (target.tilcod in reservedTilcods) {
            provisional[target] = Provisional.VerifiedSuccess
            return AttemptHandling(snapshot = snapshot)
        }
        if (snapshot.complete) {
            provisional[target] = Provisional.Failure(FailureReason.REJECTED_BY_SITE)
            return AttemptHandling(snapshot = snapshot)
        }
        abortAsIndeterminate(provisional, targets, index, UnknownReason.VERIFICATION_UNAVAILABLE)
        return AttemptHandling(canContinue = false, snapshot = snapshot)
    }

    private suspend fun resolveIndeterminateAfterPost(
        session: ReservationSession,
        target: ReservationTarget,
        targets: List<ReservationTarget>,
        index: Int,
        provisional: MutableMap<ReservationTarget, Provisional>,
    ): AttemptHandling {
        val snapshot = fetchReservationSnapshot(session)
        val reservedTilcods = snapshot?.reservations?.map { it.tilcod }?.filter(String::isNotBlank)?.toSet()
        if (reservedTilcods?.contains(target.tilcod) == true) {
            provisional[target] = Provisional.VerifiedSuccess
            return AttemptHandling(snapshot = snapshot)
        }
        abortAsIndeterminate(
            provisional,
            targets,
            index,
            if (snapshot == null) UnknownReason.VERIFICATION_UNAVAILABLE else UnknownReason.POST_RESPONSE_UNEXPECTED,
        )
        return AttemptHandling(canContinue = false, snapshot = snapshot)
    }

    private suspend fun fetchSnapshotForConfirmation(session: ReservationSession): ReservationListSnapshot? =
        fetchReservationSnapshot(session)

    /**
     * スナップショット対応セッションでは完全性情報を失わないため、必ずそちらを優先する。
     * 未対応のテスト用・旧実装セッションだけは従来どおり不完全な一覧として扱う。
     */
    private suspend fun fetchReservationSnapshot(session: ReservationSession): ReservationListSnapshot? = try {
        (session as? ReservationSnapshotSource)?.fetchReservationSnapshot()
            ?: ReservationListSnapshot(session.fetchReservations(), complete = false)
    } catch (exception: Exception) {
        rethrowIfCancellation(exception)
        null
    }

    private fun abortAsIndeterminate(
        provisional: MutableMap<ReservationTarget, Provisional>,
        targets: List<ReservationTarget>,
        index: Int,
        reason: UnknownReason,
    ) {
        provisional[targets[index]] = Provisional.Indeterminate(reason)
        targets.drop(index + 1).forEach { provisional[it] = Provisional.Failure(FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE) }
    }

    private fun failCurrentAndAbortRemaining(
        provisional: MutableMap<ReservationTarget, Provisional>,
        targets: List<ReservationTarget>,
        index: Int,
        reason: FailureReason,
    ) {
        provisional[targets[index]] = Provisional.Failure(reason)
        targets.drop(index + 1).forEach { target ->
            provisional[target] = Provisional.Failure(
                if (reason == FailureReason.INVALID_PICKUP_LIBRARY || reason == FailureReason.AUTH || reason == FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT) reason
                else FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE,
            )
        }
    }

    private fun allFailure(targets: List<ReservationTarget>, reason: FailureReason) = ReservationMemberSubmissionResult(
        results = targets.map { target ->
            val outcome = ReservationOutcome.Failure(reason)
            ReservationSubmissionResult(
                target = target,
                outcome = outcome,
                postBoundary = ReservationPostBoundary.NOT_SENT,
                fallbackToNextMember = outcome.fallbackToNextMember(),
                sessionReusable = outcome.sessionReusable(),
            )
        },
        latestReservationSnapshot = null,
    )

    private fun resolve(provisional: Provisional?, tilcod: String, reservedTilcods: Set<String>?): ReservationOutcome = when (provisional) {
        null -> ReservationOutcome.Failure(FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE)
        Provisional.VerifiedSuccess -> ReservationOutcome.Success
        is Provisional.Failure -> ReservationOutcome.Failure(provisional.reason, provisional.siteMessage)
        Provisional.Submitted, is Provisional.Indeterminate, Provisional.Duplicate -> when {
            reservedTilcods == null -> ReservationOutcome.Unknown(UnknownReason.VERIFICATION_UNAVAILABLE)
            tilcod in reservedTilcods && provisional == Provisional.Duplicate -> ReservationOutcome.AlreadyReserved
            tilcod in reservedTilcods -> ReservationOutcome.Success
            provisional is Provisional.Indeterminate -> ReservationOutcome.Unknown(provisional.reason)
            else -> ReservationOutcome.Unknown(UnknownReason.POST_RESPONSE_UNEXPECTED)
        }
    }

    private sealed interface Provisional {
        data object Submitted : Provisional
        data object VerifiedSuccess : Provisional
        data object Duplicate : Provisional
        data class Failure(val reason: FailureReason, val siteMessage: String? = null) : Provisional
        data class Indeterminate(val reason: UnknownReason) : Provisional
    }

    private data class AttemptHandling(
        val canContinue: Boolean = true,
        val snapshot: ReservationListSnapshot? = null,
    )
}

/** Resolverが扱う「予約確定POST」を送った可能性の境界。 */
internal enum class ReservationPostBoundary {
    NOT_SENT,
    SENT_OR_UNKNOWN,
}

/**
 * 自動予約・手動予約の共通結果。Roomへ記録するかは呼出し側が判断する。
 *
 * [fallbackToNextMember] と [sessionReusable] は将来の自動予約オーケストレータ用であり、
 * 手動カート予約・即時予約の送信順や表示結果を変えるためには使わない。
 */
internal data class ReservationSubmissionResult(
    val target: ReservationTarget,
    val outcome: ReservationOutcome,
    val postBoundary: ReservationPostBoundary,
    val fallbackToNextMember: Boolean,
    val sessionReusable: Boolean,
    val latestReservationSnapshot: ReservationListSnapshot? = null,
)

internal data class ReservationMemberSubmissionResult(
    val results: List<ReservationSubmissionResult>,
    val latestReservationSnapshot: ReservationListSnapshot?,
) {
    val itemResults: List<ReservationItemResult>
        get() = results.map { ReservationItemResult(it.target, it.outcome) }
}

private val DirectReservationAttempt.postBoundary: ReservationPostBoundary
    get() = when (this) {
        DirectReservationAttempt.Submitted,
        DirectReservationAttempt.Registered,
        DirectReservationAttempt.StayedOnConfirmation,
        DirectReservationAttempt.IndeterminateAfterPost,
        is DirectReservationAttempt.LimitExceeded,
        -> ReservationPostBoundary.SENT_OR_UNKNOWN
        DirectReservationAttempt.DuplicateDetected,
        DirectReservationAttempt.RejectedBeforeSubmit,
        DirectReservationAttempt.SessionExpiredBeforeSubmit,
        -> ReservationPostBoundary.NOT_SENT
    }

private fun ReservationOutcome.fallbackToNextMember(): Boolean = when (this) {
    is ReservationOutcome.Failure -> reason in setOf(
        FailureReason.AUTH,
        FailureReason.NETWORK,
        FailureReason.RESERVATION_LIMIT_EXCEEDED,
        FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT,
    )
    ReservationOutcome.Success,
    ReservationOutcome.AlreadyReserved,
    is ReservationOutcome.Unknown,
    -> false
}

private fun ReservationOutcome.sessionReusable(): Boolean = when (this) {
    is ReservationOutcome.Unknown -> false
    is ReservationOutcome.Failure -> reason !in setOf(
        FailureReason.AUTH,
        FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT,
        FailureReason.SITE_RESPONSE_CHANGED,
        FailureReason.SITE_MAINTENANCE,
        FailureReason.NETWORK,
        FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE,
    )
    ReservationOutcome.Success,
    ReservationOutcome.AlreadyReserved,
    -> true
}

private fun rethrowIfCancellation(exception: Exception) {
    if (exception is kotlinx.coroutines.CancellationException) throw exception
}
