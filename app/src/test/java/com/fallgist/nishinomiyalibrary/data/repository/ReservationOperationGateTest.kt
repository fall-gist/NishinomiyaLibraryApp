package com.fallgist.nishinomiyalibrary.data.repository

import android.content.Context
import androidx.room.Room
import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.DirectReservationAttempt
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationCancelAttempt
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationSession
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationWriteBoundaryAware
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelTarget
import com.fallgist.nishinomiyalibrary.domain.model.ReservationConfirmation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReservationOperationGateTest {
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
    fun `カート 即時予約 取消は同時に状態変更POSTへ入らず世代を一度ずつ進める`() = runBlocking {
        val gate = ReservationOperationGate()
        val network = BlockingWriteGateway()
        val cartMember = addMember("カート", "cart")
        val immediateMember = addMember("即時", "immediate")
        val cancelMember = addMember("取消", "cancel")
        val cart = ReservationCartRepositoryImpl(
            database,
            database.reservationCartDao(),
            database.memberDao(),
            credentials,
            network,
            Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
            operationGate = gate,
        )
        val cancel = ReservationCancelRepositoryImpl(
            database.reservationDao(),
            database.memberDao(),
            credentials,
            network,
            gate,
        )
        cart.addToCart(ReservationTarget(null, cartMember, "cart-book", "カート資料"))

        val cartJob = async { cart.confirmCart(ReservationConfirmation("106", 1_000)) }
        val firstEntered = network.entered.receive()
        assertEquals("cart-book", firstEntered)

        val immediateJob = async {
            cart.reserveNow(
                ReservationTarget(null, immediateMember, "immediate-book", "即時資料"),
                ReservationConfirmation("106", 1_000),
            )
        }
        val cancelJob = async {
            cancel.cancelReservations(listOf(ReservationCancelTarget(cancelMember, "cancel-book", "cancel-code")))
        }

        delay(50)
        assertTrue("ゲート待機中の操作がPOST区間へ入ってはいけません", network.entered.tryReceive().isFailure)
        network.release.complete(Unit)
        cartJob.await()
        immediateJob.await()
        cancelJob.await()

        assertEquals(
            setOf("cart-book", "immediate-book", "cancel-book"),
            setOf(firstEntered, network.entered.receive(), network.entered.receive()),
        )
        // 先に受信済みのカートを含め、3つの論理書込みが1回ずつ世代を進める。
        assertEquals(3L, gate.currentWriteGeneration())
        assertEquals(1, network.maximumConcurrentWrites)
        assertEquals(
            listOf("gate", "request", "gate", "request", "gate", "request"),
            network.writeEvents,
        )
    }

    @Test
    fun `POST前の失敗は世代を進めずキャンセル後は待機操作を解放する`() = runBlocking {
        val gate = ReservationOperationGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            gate.withOperation {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        first.cancel()
        runCatching { first.await() }

        gate.withOperation { /* POST前失敗相当: markWriteStartedを呼ばない。 */ }
        assertEquals(0L, gate.currentWriteGeneration())
        gate.withOperation { markWriteStarted() }
        assertEquals(1L, gate.currentWriteGeneration())
    }

    private suspend fun addMember(name: String, cardNumber: String): Long {
        val id = database.memberDao().insert(
            MemberEntity(name = name, colorHex = "#000000", cardNumber = cardNumber, sortOrder = 0),
        )
        credentials.savePassword(id, "password")
        return id
    }

    private class BlockingWriteGateway : ReservationGateway {
        val entered = Channel<String>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val writeEvents = mutableListOf<String>()
        private val activeMutex = Mutex()
        private var activeWrites = 0
        var maximumConcurrentWrites = 0
            private set

        override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession =
            object : ReservationSession, ReservationWriteBoundaryAware {
                private var beforeWrite: (() -> Unit)? = null

                override fun setBeforeWriteBoundary(callback: (() -> Unit)?) {
                    beforeWrite = callback?.let { markWrite ->
                        {
                            writeEvents += "gate"
                            markWrite()
                        }
                    }
                }

                override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt {
                    beforeWrite?.invoke()
                    writeEvents += "request"
                    enterWrite(tilcod)
                    return DirectReservationAttempt.Submitted
                }

                override suspend fun fetchReservations(): List<Reservation> = listOf(
                    Reservation(0, "", "", "", LocalDate.of(2030, 1, 1), null, ReservationState.WAITING, null, "cart-book"),
                    Reservation(0, "", "", "", LocalDate.of(2030, 1, 1), null, ReservationState.WAITING, null, "immediate-book"),
                )

                override suspend fun cancelReservation(cancelCode: String, expectedTilcod: String): ReservationCancelAttempt {
                    beforeWrite?.invoke()
                    writeEvents += "request"
                    enterWrite(expectedTilcod)
                    return ReservationCancelAttempt.Cancelled
                }

                override fun close() = Unit
            }

        private suspend fun enterWrite(name: String) {
            activeMutex.withLock {
                activeWrites += 1
                maximumConcurrentWrites = maxOf(maximumConcurrentWrites, activeWrites)
            }
            entered.send(name)
            release.await()
            activeMutex.withLock { activeWrites -= 1 }
        }
    }
}
