package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.DirectReservationAttempt
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationListSnapshot
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationSession
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationSnapshotSource
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
import com.fallgist.nishinomiyalibrary.domain.model.UnknownReason
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationSubmissionResolverTest {
    @Test
    fun `POST後不明は再送せず照合snapshotと未確認送信境界を返す`() = runBlocking {
        val session = FakeSession(
            attempt = DirectReservationAttempt.IndeterminateAfterPost,
            reservations = emptyList(),
        )
        val resolver = ReservationSubmissionResolver(FakeGateway(session))

        val result = resolver.resolveMember("card", "password", listOf(target()), "106").results.single()

        assertEquals(1, session.directCalls)
        assertEquals(1, session.fetchCalls)
        // 既存契約どおり、メンバー末尾の一括照合対象外のPOST後不明は一覧が取得できても
        // 表示上は検証不能へ倒す。一方、Resolver契約には実際に照合したsnapshotを保持する。
        assertEquals(ReservationOutcome.Unknown(UnknownReason.VERIFICATION_UNAVAILABLE), result.outcome)
        assertEquals(ReservationPostBoundary.SENT_OR_UNKNOWN, result.postBoundary)
        assertFalse(result.fallbackToNextMember)
        assertFalse(result.sessionReusable)
        assertNotNull(result.latestReservationSnapshot)
        assertEquals(emptyList<Reservation>(), result.latestReservationSnapshot?.reservations)
    }

    @Test
    fun `POST前セッション切れだけ一度再認証し二度目は送信せずフォールバック可能にする`() = runBlocking {
        val first = FakeSession(DirectReservationAttempt.SessionExpiredBeforeSubmit)
        val second = FakeSession(DirectReservationAttempt.SessionExpiredBeforeSubmit)
        val gateway = object : ReservationGateway {
            var opens = 0
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession =
                if (opens++ == 0) first else second
        }
        val resolver = ReservationSubmissionResolver(gateway)

        val result = resolver.resolveMember("card", "password", listOf(target()), "106").results.single()

        assertEquals(2, gateway.opens)
        assertEquals(1, first.directCalls)
        assertEquals(1, second.directCalls)
        assertEquals(ReservationOutcome.Failure(FailureReason.SESSION_EXPIRED_BEFORE_SUBMIT), result.outcome)
        assertEquals(ReservationPostBoundary.NOT_SENT, result.postBoundary)
        assertTrue(result.fallbackToNextMember)
        assertFalse(result.sessionReusable)
        assertTrue(first.closed)
        assertTrue(second.closed)
    }

    @Test
    fun `確認画面滞留は完全な照合snapshotで業務的拒否へ確定する`() = runBlocking {
        val session = SnapshotSession(
            DirectReservationAttempt.StayedOnConfirmation,
            ReservationListSnapshot(emptyList(), complete = true),
        )
        val resolver = ReservationSubmissionResolver(FakeGateway(session))

        val result = resolver.resolveMember("card", "password", listOf(target()), "106").results.single()

        assertEquals(ReservationOutcome.Failure(FailureReason.REJECTED_BY_SITE), result.outcome)
        assertEquals(ReservationPostBoundary.SENT_OR_UNKNOWN, result.postBoundary)
        assertFalse(result.fallbackToNextMember)
        assertTrue(result.sessionReusable)
        assertEquals(true, result.latestReservationSnapshot?.complete)
    }

    @Test
    fun `末尾照合はSnapshotSessionの完全なsnapshotを一回だけ利用する`() = runBlocking {
        val cases = listOf(
            DirectReservationAttempt.Submitted to ReservationOutcome.Success,
            DirectReservationAttempt.DuplicateDetected to ReservationOutcome.AlreadyReserved,
            DirectReservationAttempt.IndeterminateAfterPost to ReservationOutcome.Success,
        )

        cases.forEach { (attempt, expectedOutcome) ->
            val session = SnapshotSession(
                attempt,
                ReservationListSnapshot(
                    listOf(Reservation(1, "資料", "", "", LocalDate.of(2030, 1, 1), null, ReservationState.WAITING, null, "tilcod")),
                    complete = true,
                ),
            )
            val resolver = ReservationSubmissionResolver(FakeGateway(session))

            val result = resolver.resolveMember("card", "password", listOf(target()), "106").results.single()

            assertEquals(expectedOutcome, result.outcome)
            assertEquals(true, result.latestReservationSnapshot?.complete)
            assertEquals(1, session.snapshotFetchCalls)
            assertEquals(0, session.fetchCalls)
        }
    }

    @Test
    fun `新規sessionでdirectReserve中にキャンセルされたら所有権移譲前に一度closeする`() = runBlocking {
        val session = CancellingDirectSession()
        val resolver = ReservationSubmissionResolver(FakeGateway(session))

        try {
            resolver.resolveMemberInSession(null, "card", "password", target(), "106")
            throw AssertionError("CancellationExceptionが送出されませんでした")
        } catch (_: CancellationException) {
            // 期待どおり呼出側へ伝播する。
        }

        assertEquals(1, session.closeCalls)
    }

    @Test
    fun `新規sessionで末尾snapshot取得中にキャンセルされたら所有権移譲前に一度closeする`() = runBlocking {
        val session = CancellingSnapshotSession()
        val resolver = ReservationSubmissionResolver(FakeGateway(session))

        try {
            resolver.resolveMemberInSession(null, "card", "password", target(), "106")
            throw AssertionError("CancellationExceptionが送出されませんでした")
        } catch (_: CancellationException) {
            // 期待どおり呼出側へ伝播する。
        }

        assertEquals(1, session.closeCalls)
    }

    private fun target() = ReservationTarget(null, 1, "tilcod", "資料")

    private class FakeGateway(private val session: ReservationSession) : ReservationGateway {
        override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession = session
    }

    private open class FakeSession(
        private val attempt: DirectReservationAttempt,
        private val reservations: List<Reservation> = emptyList(),
    ) : ReservationSession {
        var directCalls = 0
        var fetchCalls = 0
        var closed = false
        var closeCalls = 0

        override open suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt {
            directCalls++
            return attempt
        }

        override suspend fun fetchReservations(): List<Reservation> {
            fetchCalls++
            return reservations
        }

        override fun close() {
            closed = true
            closeCalls++
        }
    }

    private class CancellingDirectSession : FakeSession(DirectReservationAttempt.Submitted) {
        override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt {
            directCalls++
            throw CancellationException("テスト用")
        }
    }

    private class CancellingSnapshotSession : FakeSession(DirectReservationAttempt.Submitted), ReservationSnapshotSource {
        override suspend fun fetchReservationSnapshot(): ReservationListSnapshot =
            throw CancellationException("テスト用")
    }

    private class SnapshotSession(
        attempt: DirectReservationAttempt,
        private val snapshot: ReservationListSnapshot,
    ) : FakeSession(attempt), ReservationSnapshotSource {
        var snapshotFetchCalls = 0

        override suspend fun fetchReservationSnapshot(): ReservationListSnapshot {
            snapshotFetchCalls++
            return snapshot
        }
    }
}
