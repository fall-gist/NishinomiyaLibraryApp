package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.dao.MemberDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReservationCartDao
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationCartItemEntity
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.DirectReservationAttempt
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.InvalidPickupLibraryException
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.PICKUP_LIBRARY_CODES
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationListSnapshot
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationSession
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationSnapshotSource
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.MemberReservationResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartItem
import com.fallgist.nishinomiyalibrary.domain.model.ReservationConfirmation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationItemResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
import com.fallgist.nishinomiyalibrary.domain.model.UnknownReason
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCartRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 予約送信を一列化する。Room transaction は対象読出しと最終削除だけに限定し、通信中は保持しない。
 */
@Singleton
class ReservationCartRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val cartDao: ReservationCartDao,
    private val memberDao: MemberDao,
    private val credentialStore: CredentialStore,
    private val gateway: ReservationGateway,
    private val clock: Clock,
) : ReservationCartRepository {
    private val submissionMutex = Mutex()

    override fun cartItems(): Flow<List<ReservationCartItem>> = cartDao.observeAll().map { items ->
        items.map(ReservationCartItemEntity::toDomain)
    }

    override suspend fun addToCart(target: ReservationTarget) {
        validateTarget(target, permitCartItemId = false)
        require(memberDao.getById(target.memberId) != null) { "存在しないメンバーです" }
        cartDao.insertIgnoreDuplicate(
            ReservationCartItemEntity(
                memberId = target.memberId,
                tilcod = target.tilcod,
                title = target.title,
                writerLine = target.writerLine?.takeIf(String::isNotBlank),
                addedAtEpochMillis = clock.millis(),
            ),
        )
    }

    override suspend fun removeFromCart(cartItemId: Long) {
        require(cartItemId > 0) { "カートIDが不正です" }
        cartDao.delete(cartItemId)
    }

    override suspend fun confirmCart(confirmation: ReservationConfirmation): ReservationBatchResult =
        submissionMutex.withLock {
            validateConfirmation(confirmation)
            // 対象を通信前に固定する。以降の追加・削除は今回の送信対象を変えない。
            val items = cartDao.getAll()
            val targets = items.map { it.toTarget() }
            execute(targets, confirmation).also { result -> deleteCompletedCartItems(result) }
        }

    override suspend fun reserveNow(
        target: ReservationTarget,
        confirmation: ReservationConfirmation,
    ): ReservationBatchResult = submissionMutex.withLock {
        validateConfirmation(confirmation)
        validateTarget(target, permitCartItemId = false)
        execute(listOf(target), confirmation)
    }

    private suspend fun execute(
        targets: List<ReservationTarget>,
        confirmation: ReservationConfirmation,
    ): ReservationBatchResult {
        val grouped = linkedMapOf<Long, MutableList<ReservationTarget>>()
        targets.forEach { target -> grouped.getOrPut(target.memberId) { mutableListOf() } += target }
        return ReservationBatchResult(grouped.map { (memberId, memberTargets) ->
            processMember(memberId, memberTargets, confirmation)
        })
    }

    private suspend fun processMember(
        memberId: Long,
        targets: List<ReservationTarget>,
        confirmation: ReservationConfirmation,
    ): MemberReservationResult {
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
        val provisional = linkedMapOf<ReservationTarget, Provisional>()
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
            var index = 0
            while (index < targets.size) {
                val target = targets[index]
                val attempt = try {
                    requireNotNull(session).directReserve(target.tilcod, confirmation.pickupLibraryCode)
                } catch (_: InvalidPickupLibraryException) {
                    // 共通の選択館なので、以降の同一メンバー項目にもPOSTしない。
                    provisional[target] = Provisional.Failure(FailureReason.INVALID_PICKUP_LIBRARY)
                    targets.drop(index + 1).forEach { provisional[it] = Provisional.Failure(FailureReason.INVALID_PICKUP_LIBRARY) }
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
                when (attempt) {
                    DirectReservationAttempt.Submitted -> provisional[target] = Provisional.Submitted
                    DirectReservationAttempt.DuplicateDetected -> provisional[target] = Provisional.Duplicate
                    DirectReservationAttempt.RejectedBeforeSubmit -> provisional[target] = Provisional.Failure(FailureReason.REJECTED_BY_SITE)
                    is DirectReservationAttempt.LimitExceeded -> {
                        // サイトが上限超過だと明示しているため、予約一覧との照合は行わない。
                        // 資料区分ごとに上限が異なるため、同一メンバーの次の項目は中止せず続行する。
                        provisional[target] = Provisional.Failure(FailureReason.RESERVATION_LIMIT_EXCEEDED, attempt.message)
                    }
                    DirectReservationAttempt.Registered -> {
                        // サイトが成功と言っていても、成否判定の最終根拠は予約一覧照合のままにする
                        // （成功時も失敗時と同じ確認画面が返るため、文言だけを信頼しない方針を維持する）。
                        val canContinue = resolveStayedOnConfirmation(requireNotNull(session), target, targets, index, provisional)
                        if (!canContinue) break
                    }
                    DirectReservationAttempt.StayedOnConfirmation -> {
                        val canContinue = resolveStayedOnConfirmation(requireNotNull(session), target, targets, index, provisional)
                        if (!canContinue) break
                    }
                    DirectReservationAttempt.IndeterminateAfterPost -> {
                        // POSTは再送せず、同じセッションで一度だけ読み取り照合する。
                        val reservedTilcods = try {
                            requireNotNull(session).fetchReservations().map { it.tilcod }.filter(String::isNotBlank).toSet()
                        } catch (exception: Exception) {
                            exception.rethrowIfCancellation()
                            null
                        }
                        if (reservedTilcods?.contains(target.tilcod) == true) {
                            provisional[target] = Provisional.VerifiedSuccess
                        } else {
                            provisional[target] = Provisional.Indeterminate(
                                if (reservedTilcods == null) UnknownReason.VERIFICATION_UNAVAILABLE else UnknownReason.POST_RESPONSE_UNEXPECTED,
                            )
                            targets.drop(index + 1).forEach { provisional[it] = Provisional.Failure(FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE) }
                            break
                        }
                    }
                    DirectReservationAttempt.SessionExpiredBeforeSubmit -> {
                        requireNotNull(session).close()
                        val retrySession = try {
                            gateway.openAuthenticatedSession(member.cardNumber, password)
                        } catch (_: LibraryError.Auth) {
                            provisional[target] = Provisional.Failure(FailureReason.AUTH)
                            targets.drop(index + 1).forEach { provisional[it] = Provisional.Failure(FailureReason.AUTH) }
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
                            exception.rethrowIfCancellation()
                            provisional[target] = Provisional.Failure(FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE)
                            targets.drop(index + 1).forEach { provisional[it] = Provisional.Failure(FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE) }
                            break
                        }
                        session = retrySession
                        val retried = try {
                            retrySession.directReserve(target.tilcod, confirmation.pickupLibraryCode)
                        } catch (_: InvalidPickupLibraryException) {
                            provisional[target] = Provisional.Failure(FailureReason.INVALID_PICKUP_LIBRARY)
                            targets.drop(index + 1).forEach { provisional[it] = Provisional.Failure(FailureReason.INVALID_PICKUP_LIBRARY) }
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
                        when (retried) {
                            DirectReservationAttempt.Submitted -> provisional[target] = Provisional.Submitted
                            DirectReservationAttempt.DuplicateDetected -> provisional[target] = Provisional.Duplicate
                            is DirectReservationAttempt.LimitExceeded -> {
                                provisional[target] = Provisional.Failure(FailureReason.RESERVATION_LIMIT_EXCEEDED, retried.message)
                            }
                            DirectReservationAttempt.Registered -> {
                                val canContinue = resolveStayedOnConfirmation(retrySession, target, targets, index, provisional)
                                if (!canContinue) break
                            }
                            DirectReservationAttempt.StayedOnConfirmation -> {
                                val canContinue = resolveStayedOnConfirmation(retrySession, target, targets, index, provisional)
                                if (!canContinue) break
                            }
                            DirectReservationAttempt.IndeterminateAfterPost -> {
                                val reservedTilcods = try {
                                    retrySession.fetchReservations().map { it.tilcod }.filter(String::isNotBlank).toSet()
                                } catch (exception: Exception) {
                                    exception.rethrowIfCancellation()
                                    null
                                }
                                if (reservedTilcods?.contains(target.tilcod) == true) {
                                    provisional[target] = Provisional.VerifiedSuccess
                                } else {
                                    provisional[target] = Provisional.Indeterminate(
                                        if (reservedTilcods == null) UnknownReason.VERIFICATION_UNAVAILABLE else UnknownReason.POST_RESPONSE_UNEXPECTED,
                                    )
                                    targets.drop(index + 1).forEach { provisional[it] = Provisional.Failure(FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE) }
                                    break
                                }
                            }
                            DirectReservationAttempt.RejectedBeforeSubmit -> provisional[target] = Provisional.Failure(FailureReason.REJECTED_BY_SITE)
                            DirectReservationAttempt.SessionExpiredBeforeSubmit -> {
                                provisional[target] = Provisional.Failure(FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT)
                                targets.drop(index + 1).forEach { provisional[it] = Provisional.Failure(FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT) }
                                break
                            }
                        }
                    }
                }
                index++
            }
            // 即時照合していない Submitted/Duplicate だけを、メンバー末尾で一回取得して解決する。
            val requiresFinalVerification = provisional.values.any { it == Provisional.Submitted || it == Provisional.Duplicate }
            val reservedTilcods = if (requiresFinalVerification) try {
                requireNotNull(session).fetchReservations().map { it.tilcod }.filter(String::isNotBlank).toSet()
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                null
            } else null
            return MemberReservationResult(memberId, targets.map { target ->
                ReservationItemResult(target, resolve(provisional[target], target.tilcod, reservedTilcods))
            })
        } finally {
            session?.close()
        }
    }

    /**
     * 確定POST後も確認画面のままだった場合の解決。サイトは業務的拒否（予約上限超過など）の理由を
     * 表示せず確認画面を再表示するだけなので、予約一覧に対象があるかどうかで成否を判断する。
     * ただし「一覧に対象が無い＝拒否」と断定できるのは、取得した一覧が完全だと確認できたときだけ。
     * 予約上限20件・一覧が非ページングであることは現時点の実測に過ぎず、恒久的なサイト仕様として
     * 保証されたものではない。将来サイトが変わり一覧がページングされた場合、成立しているのに
     * 1ページ目に対象が無いだけで誤って「拒否」と断定してしまう恐れがあるため、完全性を確認できない
     * ときは成否不明へ倒す（詳細は fetchReservationSnapshot と docs/handoff.md を参照）。
     * 資料種別ごとの上限などで拒否された場合、同一メンバーの次の資料は成功し得るため、
     * 一覧照合が完了した場合（成功・拒否のいずれでも）は残り項目の処理を継続する。
     * 一覧取得自体に失敗した場合、および完全性を確認できない場合は成否を判断できないため、
     * 残り項目を中止する。
     * @return true なら残り項目の処理を継続してよい、false なら中止する。
     */
    private suspend fun resolveStayedOnConfirmation(
        session: ReservationSession,
        target: ReservationTarget,
        targets: List<ReservationTarget>,
        index: Int,
        provisional: MutableMap<ReservationTarget, Provisional>,
    ): Boolean {
        val snapshot = try {
            // ReservationSessionは公開APIのため、完全性判定は internal な ReservationSnapshotSource
            // 側にだけ持たせている。実装していないセッション（テストのフェイクなど）に対しては、
            // 完全性を確認できないものとして安全側（成否不明）に倒す。
            (session as? ReservationSnapshotSource)?.fetchReservationSnapshot()
                ?: ReservationListSnapshot(session.fetchReservations(), complete = false)
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            null
        }
        if (snapshot == null) {
            provisional[target] = Provisional.Indeterminate(UnknownReason.VERIFICATION_UNAVAILABLE)
            targets.drop(index + 1).forEach { provisional[it] = Provisional.Failure(FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE) }
            return false
        }
        val reservedTilcods = snapshot.reservations.map { it.tilcod }.filter(String::isNotBlank).toSet()
        if (target.tilcod in reservedTilcods) {
            provisional[target] = Provisional.VerifiedSuccess
            return true
        }
        if (snapshot.complete) {
            provisional[target] = Provisional.Failure(FailureReason.REJECTED_BY_SITE)
            return true
        }
        provisional[target] = Provisional.Indeterminate(UnknownReason.VERIFICATION_UNAVAILABLE)
        targets.drop(index + 1).forEach { provisional[it] = Provisional.Failure(FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE) }
        return false
    }

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

    private suspend fun deleteCompletedCartItems(result: ReservationBatchResult) {
        val ids = result.members.flatMap { it.itemResults }.mapNotNull { item ->
            item.target.cartItemId?.takeIf { item.outcome == ReservationOutcome.Success || item.outcome == ReservationOutcome.AlreadyReserved }
        }
        database.deleteReservationCartItems(ids)
    }

    private fun allFailure(memberId: Long, targets: List<ReservationTarget>, reason: FailureReason) =
        MemberReservationResult(memberId, targets.map { ReservationItemResult(it, ReservationOutcome.Failure(reason)) })

    private fun failCurrentAndAbortRemaining(
        provisional: MutableMap<ReservationTarget, Provisional>,
        targets: List<ReservationTarget>,
        index: Int,
        reason: FailureReason,
    ) {
        provisional[targets[index]] = Provisional.Failure(reason)
        targets.drop(index + 1).forEach { target ->
            provisional[target] = Provisional.Failure(FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE)
        }
    }

    private fun validateTarget(target: ReservationTarget, permitCartItemId: Boolean) {
        require(target.memberId > 0) { "memberIdが不正です" }
        require(target.tilcod.isNotBlank()) { "tilcodが空です" }
        require(target.title.isNotBlank()) { "titleが空です" }
        require(permitCartItemId || target.cartItemId == null) { "カート追加・即時予約にカートIDは指定できません" }
    }

    private fun validateConfirmation(confirmation: ReservationConfirmation) {
        require(confirmation.confirmedAtEpochMillis > 0) { "最終確認時刻が不正です" }
        require(confirmation.pickupLibraryCode in PICKUP_LIBRARY_CODES) { "受取館コードが不正です" }
    }

    private sealed interface Provisional {
        data object Submitted : Provisional
        data object VerifiedSuccess : Provisional
        data object Duplicate : Provisional
        /** siteMessage は予約制限超過などでサイトが返した文言。無ければ null。 */
        data class Failure(val reason: FailureReason, val siteMessage: String? = null) : Provisional
        data class Indeterminate(val reason: UnknownReason) : Provisional
    }
}

private fun ReservationCartItemEntity.toDomain() = ReservationCartItem(id, memberId, tilcod, title, writerLine, addedAtEpochMillis)
private fun ReservationCartItemEntity.toTarget() = ReservationTarget(id, memberId, tilcod, title, writerLine)

private fun Exception.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
