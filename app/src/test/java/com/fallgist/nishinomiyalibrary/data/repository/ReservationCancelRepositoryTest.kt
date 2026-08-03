package com.fallgist.nishinomiyalibrary.data.repository

import android.content.Context
import androidx.room.Room
import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationEntity
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.DirectReservationAttempt
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationCancelAttempt
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationSession
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationWriteBoundaryAware
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.HideFailureReason
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelOutcome
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelTarget
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.model.UnknownReason
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReservationCancelRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var credentials: CredentialStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        credentials = CredentialStore(context)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `複数件を順に処理し成功はローカルからも削除する`() = runBlocking {
        val member = addMember("複数件", "1")
        database.reservationDao().insert(reservation(member, "one"))
        database.reservationDao().insert(reservation(member, "two"))
        val attempts = mapOf(
            "one" to ReservationCancelAttempt.Cancelled,
            "two" to ReservationCancelAttempt.Rejected("越えています"),
        )
        val repository = repository(fakeGateway(attempts))

        val result = repository.cancelReservations(
            listOf(
                target(member, "one"),
                target(member, "two"),
            ),
        )

        assertEquals(ReservationCancelOutcome.Cancelled, result.members.single().itemResults[0].outcome)
        assertEquals(
            ReservationCancelOutcome.Rejected("越えています"),
            result.members.single().itemResults[1].outcome,
        )
        // 成功した"one"だけローカルからも削除され、拒否された"two"は残る。
        assertEquals(listOf("two"), database.reservationDao().getForMember(member).map { it.cancelCode })
    }

    @Test
    fun `CancelledAndHiddenもCancelledと同じくローカルから即時削除し成功として扱う`() = runBlocking {
        // 12回目のライブ実測(2026-07-28)どおり、取消後に対象行が一覧から消える(CancelledAndHidden)場合も
        // 一覧に残る(Cancelled)場合も、どちらも取消は成立している。両方とも成功として同じ扱いにする。
        val member = addMember("非表示区別", "8")
        database.reservationDao().insert(reservation(member, "hidden"))
        database.reservationDao().insert(reservation(member, "stillListed"))
        val attempts = mapOf(
            "hidden" to ReservationCancelAttempt.CancelledAndHidden,
            "stillListed" to ReservationCancelAttempt.Cancelled,
        )
        val repository = repository(fakeGateway(attempts))

        val result = repository.cancelReservations(
            listOf(
                target(member, "hidden"),
                target(member, "stillListed"),
            ),
        )

        assertEquals(ReservationCancelOutcome.CancelledAndHidden, result.members.single().itemResults[0].outcome)
        assertEquals(ReservationCancelOutcome.Cancelled, result.members.single().itemResults[1].outcome)
        // どちらも成功のため、両方ともローカルから削除される。
        assertEquals(emptyList<String>(), database.reservationDao().getForMember(member).map { it.cancelCode })
    }

    @Test
    fun `1件が失敗しても残りのメンバーは処理を続行する`() = runBlocking {
        val bad = addMember("失敗", "2")
        val good = addMember("成功", "3")
        val gateway = object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession {
                if (cardNumber == "2") throw LibraryError.Auth(null)
                return cancelOnlySession { _, _ -> ReservationCancelAttempt.Cancelled }
            }
        }
        val repository = repository(gateway)

        val result = repository.cancelReservations(
            listOf(
                target(bad, "x"),
                target(good, "y"),
            ),
        )

        assertEquals(
            ReservationCancelOutcome.Failure(FailureReason.AUTH),
            result.members[0].itemResults.single().outcome,
        )
        assertEquals(ReservationCancelOutcome.Cancelled, result.members[1].itemResults.single().outcome)
    }

    @Test
    fun `結果は資料ごとに返り成否不明はUnknownになる`() = runBlocking {
        val member = addMember("成否不明", "4")
        val repository = repository(fakeGateway(mapOf("z" to ReservationCancelAttempt.IndeterminateAfterPost)))

        val result = repository.cancelReservations(listOf(target(member, "z")))

        assertEquals(
            ReservationCancelOutcome.Unknown(UnknownReason.VERIFICATION_UNAVAILABLE),
            result.members.single().itemResults.single().outcome,
        )
    }

    @Test
    fun `同一メンバーで同じ取消コードでもtilcodが異なる予約は削除しない`() = runBlocking {
        val member = addMember("3キー削除", "6")
        val cancelCode = "shared"
        val targetTilcod = "tilcod-target"
        val otherTilcod = "tilcod-other"
        database.reservationDao().insert(reservation(member, cancelCode, targetTilcod))
        database.reservationDao().insert(reservation(member, cancelCode, otherTilcod))
        val repository = repository(fakeGateway(mapOf(cancelCode to ReservationCancelAttempt.Cancelled)))

        val result = repository.cancelReservations(
            listOf(ReservationCancelTarget(member, targetTilcod, cancelCode)),
        )

        assertEquals(ReservationCancelOutcome.Cancelled, result.members.single().itemResults.single().outcome)
        assertEquals(listOf(otherTilcod), database.reservationDao().getForMember(member).map { it.tilcod })
    }

    @Test
    fun `取消セッションには対象のtilcodをexpectedTilcodとして渡す`() = runBlocking {
        val member = addMember("対象伝達", "7")
        val receivedRequests = mutableListOf<Pair<String, String>>()
        val gateway = object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession =
                cancelOnlySession { cancelCode, expectedTilcod ->
                    receivedRequests += cancelCode to expectedTilcod
                    ReservationCancelAttempt.Cancelled
                }
        }
        val repository = repository(gateway)

        repository.cancelReservations(listOf(ReservationCancelTarget(member, "tilcod-expected", "cancel-expected")))

        assertEquals(listOf("cancel-expected" to "tilcod-expected"), receivedRequests)
    }

    @Test
    fun `セッション切れは一度だけ再ログインして同じ対象を再試行する`() = runBlocking {
        val member = addMember("再ログイン", "5")
        var opens = 0
        val gateway = object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession {
                opens++
                return if (opens == 1) {
                    cancelOnlySession { _, _ -> ReservationCancelAttempt.SessionExpiredBeforeSubmit }
                } else {
                    cancelOnlySession { _, _ -> ReservationCancelAttempt.Cancelled }
                }
            }
        }
        val repository = repository(gateway)

        val result = repository.cancelReservations(listOf(target(member, "retry")))

        assertEquals(2, opens)
        assertEquals(ReservationCancelOutcome.Cancelled, result.members.single().itemResults.single().outcome)
    }

    @Test
    fun `再認証後の取消POSTは書込み世代を一度だけ増加させる`() = runBlocking {
        val member = addMember("再認証取消", "retry-cancel-write")
        val gate = ReservationOperationGate()
        val first = writeAwareCancelSession { ReservationCancelAttempt.SessionExpiredBeforeSubmit }
        val second = writeAwareCancelSession { beforeWrite ->
            beforeWrite()
            ReservationCancelAttempt.Cancelled
        }
        var opens = 0
        val repository = ReservationCancelRepositoryImpl(
            database.reservationDao(), database.memberDao(), credentials,
            object : ReservationGateway {
                override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession =
                    if (opens++ == 0) first else second
            },
            gate,
        )

        repository.cancelReservations(listOf(target(member, "retry-cancel-write")))

        assertEquals(1L, gate.currentWriteGeneration())
        assertEquals(listOf(true, false), first.boundaryAssignments)
        assertEquals(listOf(true, false), second.boundaryAssignments)
        assertTrue(first.closed)
    }

    @Test
    fun `非表示警告でも取消成功として削除し状態不安定なら同一メンバー残件を中止する`() = runBlocking {
        val member = addMember("一覧整理警告", "cleanup-warning")
        database.reservationDao().insert(reservation(member, "first"))
        database.reservationDao().insert(reservation(member, "second"))
        val repository = repository(
            fakeGateway(
                mapOf(
                    "first" to ReservationCancelAttempt.CancelledHideUnknown,
                    "second" to ReservationCancelAttempt.Cancelled,
                ),
            ),
        )

        val result = repository.cancelReservations(listOf(target(member, "first"), target(member, "second")))

        assertEquals(ReservationCancelOutcome.CancelledHideUnknown, result.members.single().itemResults[0].outcome)
        assertEquals(
            ReservationCancelOutcome.Failure(FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE),
            result.members.single().itemResults[1].outcome,
        )
        assertEquals(listOf("second"), database.reservationDao().getForMember(member).map { it.cancelCode })
    }

    @Test
    fun `明示的な非表示拒否は警告にして同一メンバーの次件を続行する`() = runBlocking {
        val member = addMember("明示拒否", "cleanup-rejected")
        database.reservationDao().insert(reservation(member, "first"))
        database.reservationDao().insert(reservation(member, "second"))
        val repository = repository(
            fakeGateway(
                mapOf(
                    "first" to ReservationCancelAttempt.CancelledHideNotCompleted(HideFailureReason.REJECTED_BY_SITE),
                    "second" to ReservationCancelAttempt.CancelledAndHidden,
                ),
            ),
        )

        val result = repository.cancelReservations(listOf(target(member, "first"), target(member, "second")))

        assertEquals(
            ReservationCancelOutcome.CancelledHideNotCompleted(HideFailureReason.REJECTED_BY_SITE),
            result.members.single().itemResults[0].outcome,
        )
        assertEquals(ReservationCancelOutcome.CancelledAndHidden, result.members.single().itemResults[1].outcome)
        assertEquals(emptyList<String>(), database.reservationDao().getForMember(member).map { it.cancelCode })
    }

    @Test
    fun `再認証に失敗した取消は書込み世代を増加させない`() = runBlocking {
        val member = addMember("再認証取消失敗", "retry-cancel-no-write")
        val gate = ReservationOperationGate()
        var opens = 0
        val repository = ReservationCancelRepositoryImpl(
            database.reservationDao(), database.memberDao(), credentials,
            object : ReservationGateway {
                override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession {
                    opens++
                    if (opens == 2) throw LibraryError.Auth(null)
                    return writeAwareCancelSession { ReservationCancelAttempt.SessionExpiredBeforeSubmit }
                }
            },
            gate,
        )

        repository.cancelReservations(listOf(target(member, "retry-cancel-no-write")))

        assertEquals(2, opens)
        assertEquals(0L, gate.currentWriteGeneration())
    }

    private suspend fun addMember(name: String, cardNumber: String): Long {
        val id = database.memberDao().insert(MemberEntity(name = name, colorHex = "#000000", cardNumber = cardNumber, sortOrder = 0))
        credentials.savePassword(id, "password")
        return id
    }

    private fun reservation(
        memberId: Long,
        cancelCode: String,
        tilcod: String = tilcodFor(cancelCode),
    ): ReservationEntity = ReservationEntity(
        memberId = memberId,
        title = "資料",
        materialType = "図書",
        pickupLibrary = "",
        reservedDate = LocalDate.of(2030, 1, 1),
        queuePosition = 1,
        state = ReservationState.WAITING,
        holdExpiryDate = null,
        firstReadyNotifiedAt = null,
        cancelCode = cancelCode,
        tilcod = tilcod,
    )

    private fun target(memberId: Long, cancelCode: String): ReservationCancelTarget =
        ReservationCancelTarget(memberId, tilcodFor(cancelCode), cancelCode)

    private fun tilcodFor(cancelCode: String): String = "tilcod-$cancelCode"

    private fun repository(gateway: ReservationGateway) = ReservationCancelRepositoryImpl(
        database.reservationDao(), database.memberDao(), credentials, gateway,
    )

    private fun fakeGateway(attempts: Map<String, ReservationCancelAttempt>): ReservationGateway =
        object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession =
                cancelOnlySession { cancelCode, _ ->
                    requireNotNull(attempts[cancelCode]) { "未定義のcancelCode: $cancelCode" }
                }
        }

    /**
     * 取消以外(directReserve/fetchReservations)を呼ばないことを前提に、取消動作だけを差し替えるフェイク。
     * ReservationSessionはインターフェースのため、このテストで使わないメソッドも形式上実装が必要。
     */
    private fun cancelOnlySession(
        cancel: suspend (cancelCode: String, expectedTilcod: String) -> ReservationCancelAttempt,
    ): ReservationSession =
        object : ReservationSession {
            override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt =
                error("このテストではdirectReserveを呼ばない")
            override suspend fun fetchReservations(): List<Reservation> = error("このテストではfetchReservationsを呼ばない")
            override suspend fun cancelReservation(
                cancelCode: String,
                expectedTilcod: String,
            ): ReservationCancelAttempt = cancel(cancelCode, expectedTilcod)
            override fun close() = Unit
        }

    private class WriteAwareCancelSession(
        private val cancel: suspend ((() -> Unit)) -> ReservationCancelAttempt,
    ) : ReservationSession, ReservationWriteBoundaryAware {
        private var beforeWrite: (() -> Unit)? = null
        val boundaryAssignments = mutableListOf<Boolean>()
        var closed = false

        override fun setBeforeWriteBoundary(callback: (() -> Unit)?) {
            boundaryAssignments += callback != null
            beforeWrite = callback
        }

        override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt =
            error("このテストではdirectReserveを使用しません")

        override suspend fun fetchReservations(): List<Reservation> =
            error("このテストではfetchReservationsを使用しません")

        override suspend fun cancelReservation(
            cancelCode: String,
            expectedTilcod: String,
        ): ReservationCancelAttempt = cancel(requireNotNull(beforeWrite))

        override fun close() {
            closed = true
        }
    }

    private fun writeAwareCancelSession(
        cancel: suspend ((() -> Unit)) -> ReservationCancelAttempt,
    ) = WriteAwareCancelSession(cancel)
}
