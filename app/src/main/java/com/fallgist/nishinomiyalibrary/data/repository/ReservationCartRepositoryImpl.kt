package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.dao.MemberDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReservationCartDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReservationPickupSubmissionDao
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationCartItemEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationPickupSubmissionEntity
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.PICKUP_LIBRARY_CODES
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationGateway
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.MemberReservationResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartItem
import com.fallgist.nishinomiyalibrary.domain.model.ReservationConfirmation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationItemResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.ReservationPickupSubmissionOrigin
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
import com.fallgist.nishinomiyalibrary.domain.repository.ReservationCartRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    private val pickupSubmissionDao: ReservationPickupSubmissionDao = database.reservationPickupSubmissionDao(),
    private val operationGate: ReservationOperationGate = ReservationOperationGate(),
) : ReservationCartRepository {
    private val submissionResolver = ReservationSubmissionResolver(gateway)

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
        operationGate.withOperation {
            validateConfirmation(confirmation)
            // 対象を通信前に固定する。以降の追加・削除は今回の送信対象を変えない。
            val items = cartDao.getAll()
            val targets = items.map { it.toTarget() }
            val execution = execute(targets, confirmation, ::markWriteStarted)
            recordPickupSubmissions(execution, confirmation.pickupLibraryCode)
            deleteCompletedCartItems(execution.result)
            execution.result
        }

    override suspend fun reserveNow(
        target: ReservationTarget,
        confirmation: ReservationConfirmation,
    ): ReservationBatchResult = operationGate.withOperation {
        validateConfirmation(confirmation)
        validateTarget(target, permitCartItemId = false)
        val execution = execute(listOf(target), confirmation, ::markWriteStarted)
        recordPickupSubmissions(execution, confirmation.pickupLibraryCode)
        execution.result
    }

    private suspend fun execute(
        targets: List<ReservationTarget>,
        confirmation: ReservationConfirmation,
        beforeWrite: () -> Unit,
    ): ReservationExecution {
        val grouped = linkedMapOf<Long, MutableList<ReservationTarget>>()
        targets.forEach { target -> grouped.getOrPut(target.memberId) { mutableListOf() } += target }
        val members = grouped.map { (memberId, memberTargets) ->
            processMember(memberId, memberTargets, confirmation, beforeWrite)
        }
        return ReservationExecution(
            result = ReservationBatchResult(members.map(ProcessMemberResult::result)),
            submissions = members.flatMap { it.submissions },
        )
    }

    private suspend fun processMember(
        memberId: Long,
        targets: List<ReservationTarget>,
        confirmation: ReservationConfirmation,
        beforeWrite: () -> Unit,
    ): ProcessMemberResult {
        val memberAndPassword = try {
            memberDao.getById(memberId)?.let { member ->
                credentialStore.getPassword(member.id)?.let { password -> member to password }
            }
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            return ProcessMemberResult(allFailure(memberId, targets, FailureReason.AUTH))
        }
        val member = memberAndPassword?.first
        val password = memberAndPassword?.second
        if (member == null || password.isNullOrBlank()) {
            return ProcessMemberResult(allFailure(memberId, targets, FailureReason.AUTH))
        }

        val resolution = submissionResolver.resolveMember(
            cardNumber = member.cardNumber,
            password = password,
            targets = targets,
            pickupLibraryCode = confirmation.pickupLibraryCode,
            beforeWrite = beforeWrite,
        )
        return ProcessMemberResult(
            result = MemberReservationResult(memberId, resolution.itemResults),
            submissions = resolution.results,
        )
    }

    private suspend fun deleteCompletedCartItems(result: ReservationBatchResult) {
        val ids = result.members.flatMap { it.itemResults }.mapNotNull { item ->
            item.target.cartItemId?.takeIf { item.outcome == ReservationOutcome.Success || item.outcome == ReservationOutcome.AlreadyReserved }
        }
        database.deleteReservationCartItems(ids)
    }

    /**
     * サイト一覧の受取館表示が古い間も、アプリが送信した館を失わないように記録する。
     * 成功だけを確認済みとして残す。AlreadyReserved は今回の送信ではないため記録しない。
     */
    private suspend fun recordPickupSubmissions(execution: ReservationExecution, pickupLibraryCode: String) {
        execution.submissions.forEach { submission ->
            val origin = when (submission.outcome) {
                ReservationOutcome.Success -> ReservationPickupSubmissionOrigin.CONFIRMED_SUBMISSION
                ReservationOutcome.AlreadyReserved -> null
                is ReservationOutcome.Unknown -> submission.postBoundary
                    .takeIf { it == ReservationPostBoundary.SENT_OR_UNKNOWN }
                    ?.let { ReservationPickupSubmissionOrigin.UNVERIFIED_SUBMISSION }
                is ReservationOutcome.Failure -> null
            } ?: return@forEach
            pickupSubmissionDao.upsert(
                ReservationPickupSubmissionEntity(
                    memberId = submission.target.memberId,
                    tilcod = submission.target.tilcod,
                    pickupLibraryCode = pickupLibraryCode,
                    origin = origin,
                ),
            )
        }
    }

    private fun allFailure(memberId: Long, targets: List<ReservationTarget>, reason: FailureReason) =
        MemberReservationResult(memberId, targets.map { ReservationItemResult(it, ReservationOutcome.Failure(reason)) })

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

    private data class ProcessMemberResult(
        val result: MemberReservationResult,
        val submissions: List<ReservationSubmissionResult> = emptyList(),
    )

    private data class ReservationExecution(
        val result: ReservationBatchResult,
        val submissions: List<ReservationSubmissionResult>,
    )
}

private fun ReservationCartItemEntity.toDomain() = ReservationCartItem(id, memberId, tilcod, title, writerLine, addedAtEpochMillis)
private fun ReservationCartItemEntity.toTarget() = ReservationTarget(id, memberId, tilcod, title, writerLine)

private fun Exception.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
