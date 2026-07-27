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
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelOutcome
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelTarget
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.model.UnknownReason
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
                ReservationCancelTarget(member, "one"),
                ReservationCancelTarget(member, "two"),
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
    fun `1件が失敗しても残りのメンバーは処理を続行する`() = runBlocking {
        val bad = addMember("失敗", "2")
        val good = addMember("成功", "3")
        val gateway = object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession {
                if (cardNumber == "2") throw LibraryError.Auth(null)
                return cancelOnlySession { ReservationCancelAttempt.Cancelled }
            }
        }
        val repository = repository(gateway)

        val result = repository.cancelReservations(
            listOf(
                ReservationCancelTarget(bad, "x"),
                ReservationCancelTarget(good, "y"),
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

        val result = repository.cancelReservations(listOf(ReservationCancelTarget(member, "z")))

        assertEquals(
            ReservationCancelOutcome.Unknown(UnknownReason.VERIFICATION_UNAVAILABLE),
            result.members.single().itemResults.single().outcome,
        )
    }

    @Test
    fun `セッション切れは一度だけ再ログインして同じ対象を再試行する`() = runBlocking {
        val member = addMember("再ログイン", "5")
        var opens = 0
        val gateway = object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession {
                opens++
                return if (opens == 1) {
                    cancelOnlySession { ReservationCancelAttempt.SessionExpiredBeforeSubmit }
                } else {
                    cancelOnlySession { ReservationCancelAttempt.Cancelled }
                }
            }
        }
        val repository = repository(gateway)

        val result = repository.cancelReservations(listOf(ReservationCancelTarget(member, "retry")))

        assertEquals(2, opens)
        assertEquals(ReservationCancelOutcome.Cancelled, result.members.single().itemResults.single().outcome)
    }

    private suspend fun addMember(name: String, cardNumber: String): Long {
        val id = database.memberDao().insert(MemberEntity(name = name, colorHex = "#000000", cardNumber = cardNumber, sortOrder = 0))
        credentials.savePassword(id, "password")
        return id
    }

    private fun reservation(memberId: Long, cancelCode: String): ReservationEntity = ReservationEntity(
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
    )

    private fun repository(gateway: ReservationGateway) = ReservationCancelRepositoryImpl(
        database.reservationDao(), database.memberDao(), credentials, gateway,
    )

    private fun fakeGateway(attempts: Map<String, ReservationCancelAttempt>): ReservationGateway =
        object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession =
                cancelOnlySession { cancelCode -> requireNotNull(attempts[cancelCode]) { "未定義のcancelCode: $cancelCode" } }
        }

    /**
     * 取消以外(directReserve/fetchReservations)を呼ばないことを前提に、取消動作だけを差し替えるフェイク。
     * ReservationSessionはインターフェースのため、このテストで使わないメソッドも形式上実装が必要。
     */
    private fun cancelOnlySession(cancel: suspend (String) -> ReservationCancelAttempt): ReservationSession =
        object : ReservationSession {
            override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt =
                error("このテストではdirectReserveを呼ばない")
            override suspend fun fetchReservations(): List<Reservation> = error("このテストではfetchReservationsを呼ばない")
            override suspend fun cancelReservation(cancelCode: String): ReservationCancelAttempt = cancel(cancelCode)
            override fun close() = Unit
        }
}
