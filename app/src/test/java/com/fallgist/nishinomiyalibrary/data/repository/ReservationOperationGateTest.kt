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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
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
            gate.withOperation(ReservationOperationType.AUTOMATIC_RESERVATION) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        first.cancel()
        runCatching { first.await() }

        gate.withOperation(ReservationOperationType.MANUAL_RESERVATION) { /* POST前失敗相当: markWriteStartedを呼ばない。 */ }
        assertEquals(0L, gate.currentWriteGeneration())
        gate.withOperation(ReservationOperationType.MANUAL_RESERVATION) { markWriteStarted() }
        assertEquals(1L, gate.currentWriteGeneration())
    }

    @Test
    fun `自動予約の保持中は複数の手動予約と手動取消を待機先付きで観測する`() = runBlocking {
        val gate = ReservationOperationGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val automatic = async {
            gate.withOperation(ReservationOperationType.AUTOMATIC_RESERVATION) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val reservation = async { gate.withOperation(ReservationOperationType.MANUAL_RESERVATION) { } }
        awaitGateState(gate) { it.waitingOperations.size == 1 }
        val cancellation = async { gate.withOperation(ReservationOperationType.MANUAL_CANCELLATION) { } }
        val waiting = awaitGateState(gate) { it.waitingOperations.size == 2 }

        assertEquals(ReservationOperationType.AUTOMATIC_RESERVATION, waiting.activeOperation)
        assertEquals(
            listOf(
                ReservationOperationWait(ReservationOperationType.MANUAL_RESERVATION, ReservationOperationType.AUTOMATIC_RESERVATION),
                ReservationOperationWait(ReservationOperationType.MANUAL_CANCELLATION, ReservationOperationType.AUTOMATIC_RESERVATION),
            ),
            waiting.waitingOperations,
        )

        release.complete(Unit)
        automatic.await()
        reservation.await()
        cancellation.await()
        assertEquals(ReservationOperationGateState(), gate.state.value)
    }

    @Test
    fun `手動操作同士の待機は自動予約待ちとして観測しない`() = runBlocking {
        val gate = ReservationOperationGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reservation = async {
            gate.withOperation(ReservationOperationType.MANUAL_RESERVATION) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val cancellation = async { gate.withOperation(ReservationOperationType.MANUAL_CANCELLATION) { } }

        val waiting = awaitGateState(gate) { it.waitingOperations.isNotEmpty() }
        assertEquals(
            listOf(ReservationOperationWait(ReservationOperationType.MANUAL_CANCELLATION, ReservationOperationType.MANUAL_RESERVATION)),
            waiting.waitingOperations,
        )
        assertTrue(
            "手動取消は自動予約ではなく手動予約を待機している",
            !waiting.isWaitingFor(ReservationOperationType.MANUAL_CANCELLATION, ReservationOperationType.AUTOMATIC_RESERVATION),
        )

        release.complete(Unit)
        reservation.await()
        cancellation.await()
    }

    @Test
    fun `待機中と実行中のキャンセル後に観測状態を残さず後続操作を完了できる`() = runBlocking {
        val gate = ReservationOperationGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val automatic = async {
            gate.withOperation(ReservationOperationType.AUTOMATIC_RESERVATION) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val waitingReservation = async { gate.withOperation(ReservationOperationType.MANUAL_RESERVATION) { } }
        awaitGateState(gate) { it.waitingOperations.isNotEmpty() }
        waitingReservation.cancel()
        runCatching { waitingReservation.await() }
        assertEquals(
            ReservationOperationGateState(activeOperation = ReservationOperationType.AUTOMATIC_RESERVATION),
            awaitGateState(gate) { it.waitingOperations.isEmpty() },
        )

        automatic.cancel()
        runCatching { automatic.await() }
        assertEquals(ReservationOperationGateState(), awaitGateState(gate) { it.activeOperation == null })

        // キャンセルされた処理がMutexを保持したままになっていないことまで確認する。
        withTimeout(1_000) {
            gate.withOperation(ReservationOperationType.MANUAL_CANCELLATION) { markWriteStarted() }
        }
        assertEquals(ReservationOperationGateState(), gate.state.value)
        assertEquals(1L, gate.currentWriteGeneration())
    }

    @Test
    fun `取得前のキャンセル後に観測状態を残さず後続操作を完了できる`() = runBlocking {
        val gate = ReservationOperationGate()
        val beforeAcquire = async(start = CoroutineStart.LAZY) {
            gate.withOperation(ReservationOperationType.MANUAL_RESERVATION) { markWriteStarted() }
        }

        beforeAcquire.cancel()
        runCatching { beforeAcquire.await() }

        assertGateReleasedAndAllowsNext(gate)
    }

    @Test
    fun `取得直後のblock開始時キャンセル後に観測状態を残さず後続操作を完了できる`() = runBlocking {
        val gate = ReservationOperationGate()
        val blockStarted = CompletableDeferred<Unit>()
        val operation = async {
            gate.withOperation(ReservationOperationType.MANUAL_RESERVATION) {
                blockStarted.complete(Unit)
                awaitCancellation()
            }
        }
        blockStarted.await()

        operation.cancel()
        runCatching { operation.await() }

        assertGateReleasedAndAllowsNext(gate)
    }

    @Test
    fun `block実行中のキャンセルと例外後に観測状態を残さず後続操作を完了できる`() = runBlocking {
        val gate = ReservationOperationGate()
        val workStarted = CompletableDeferred<Unit>()
        val keepWorking = CompletableDeferred<Unit>()
        val operation = async {
            gate.withOperation(ReservationOperationType.MANUAL_RESERVATION) {
                workStarted.complete(Unit)
                keepWorking.await()
            }
        }
        workStarted.await()

        operation.cancel()
        runCatching { operation.await() }
        assertGateReleasedAndAllowsNext(gate)

        runCatching {
            gate.withOperation(ReservationOperationType.MANUAL_RESERVATION) {
                throw IllegalStateException("テスト例外")
            }
        }
        assertGateReleasedAndAllowsNext(gate)
    }

    private suspend fun awaitGateState(
        gate: ReservationOperationGate,
        condition: (ReservationOperationGateState) -> Boolean,
    ): ReservationOperationGateState = withTimeout(1_000) {
        while (!condition(gate.state.value)) delay(1)
        gate.state.value
    }

    private suspend fun assertGateReleasedAndAllowsNext(gate: ReservationOperationGate) {
        assertEquals(ReservationOperationGateState(), awaitGateState(gate) { it == ReservationOperationGateState() })
        withTimeout(1_000) {
            gate.withOperation(ReservationOperationType.MANUAL_CANCELLATION) { markWriteStarted() }
        }
        assertEquals(ReservationOperationGateState(), gate.state.value)
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
