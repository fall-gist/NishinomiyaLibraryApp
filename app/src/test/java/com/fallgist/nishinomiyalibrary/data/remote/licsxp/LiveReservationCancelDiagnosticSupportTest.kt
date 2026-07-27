package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LiveReservationCancelDiagnosticSupportTest {

    @Test
    fun `明示フラグが不足すれば gateway を生成せず通信できない`() = runBlocking {
        var gatewayFactoryCalls = 0
        val result = runLiveReservationCancelDiagnosticIfAuthorized(
            environment = mapOf(
                LiveReservationCancelDiagnosticConfig.CARD_NUMBER to "1234",
                LiveReservationCancelDiagnosticConfig.PASSWORD to "secret",
                LiveReservationCancelDiagnosticConfig.CANCEL_TILCOD to "1000000000001",
            ),
            gatewayFactory = {
                gatewayFactoryCalls += 1
                error("認可されていない診断で gateway を生成してはいけない")
            },
            logger = LiveReservationDiagnosticLogger(),
        )

        assertNull(result)
        assertEquals(0, gatewayFactoryCalls)
    }

    @Test
    fun `取消専用の同意フラグが無いと通常予約診断のCONFIRM_FLAGだけでは成立しない`() {
        val config = LiveReservationCancelDiagnosticConfig.from(
            mapOf(
                LiveReservationCancelDiagnosticConfig.ENABLE_FLAG to "YES_I_UNDERSTAND",
                LiveReservationDiagnosticConfig.CONFIRM_FLAG to "RESERVE_ON_PRODUCTION",
                LiveReservationCancelDiagnosticConfig.CARD_NUMBER to "1234",
                LiveReservationCancelDiagnosticConfig.PASSWORD to "secret",
                LiveReservationCancelDiagnosticConfig.CANCEL_TILCOD to "1000000000001",
            ),
        )

        assertNull(config)
    }

    @Test
    fun `対象が一覧に無い場合はcheckで停止し取消を呼ばない`() = runBlocking {
        val gateway = FakeCancelGateway(before = emptyList())
        val config = authorizedConfig()

        try {
            runLiveReservationCancelDiagnostic(gateway, config, LiveReservationDiagnosticLogger())
            fail("対象なしでも例外が発生しなかった")
        } catch (exception: IllegalStateException) {
            assertEquals("対象の予約が一覧にありません", exception.message)
        }

        assertEquals(0, gateway.session.cancelCalls)
        assertEquals(listOf("fetch-before", "close"), gateway.session.calls)
    }

    @Test
    fun `複数件一致した場合はcheckで停止し取消を呼ばない`() = runBlocking {
        val gateway = FakeCancelGateway(
            before = listOf(
                reservation(tilcod = "1000000000001", cancelCode = "c1"),
                reservation(tilcod = "1000000000001", cancelCode = "c2"),
            ),
        )
        val config = authorizedConfig()

        try {
            runLiveReservationCancelDiagnostic(gateway, config, LiveReservationDiagnosticLogger())
            fail("複数一致でも例外が発生しなかった")
        } catch (exception: IllegalStateException) {
            assertEquals("対象が複数件一致したため、どれを取り消すか決められません", exception.message)
        }

        assertEquals(0, gateway.session.cancelCalls)
    }

    @Test
    fun `cancelCodeが空の行では取消を呼ばない`() = runBlocking {
        val gateway = FakeCancelGateway(
            before = listOf(reservation(tilcod = "1000000000001", cancelCode = "")),
        )
        val config = authorizedConfig()

        try {
            runLiveReservationCancelDiagnostic(gateway, config, LiveReservationDiagnosticLogger())
            fail("cancelCodeが空でも例外が発生しなかった")
        } catch (exception: IllegalStateException) {
            assertEquals("対象行に取消コードがないため取消を行いません", exception.message)
        }

        assertEquals(0, gateway.session.cancelCalls)
    }

    @Test
    fun `一意に特定できた場合だけ取消を1回呼び前後の一覧を照合する`() = runBlocking {
        val gateway = FakeCancelGateway(
            before = listOf(reservation(tilcod = "1000000000001", cancelCode = "c1")),
        )
        val config = authorizedConfig()

        val report = runLiveReservationCancelDiagnostic(gateway, config, LiveReservationDiagnosticLogger())

        assertEquals(1, gateway.session.cancelCalls)
        assertEquals("c1", gateway.session.cancelledWith)
        assertFalse(report.stillPresentAfter)
        assertTrue(report.attempt is ReservationCancelAttempt.Cancelled)
        assertEquals(listOf("fetch-before", "cancel", "fetch-after", "close"), gateway.session.calls)
    }

    private fun authorizedConfig(): LiveReservationCancelDiagnosticConfig = requireNotNull(
        LiveReservationCancelDiagnosticConfig.from(
            mapOf(
                LiveReservationCancelDiagnosticConfig.ENABLE_FLAG to "YES_I_UNDERSTAND",
                LiveReservationCancelDiagnosticConfig.CANCEL_CONFIRM_FLAG to "CANCEL_ON_PRODUCTION",
                LiveReservationCancelDiagnosticConfig.CARD_NUMBER to "1234",
                LiveReservationCancelDiagnosticConfig.PASSWORD to "secret",
                LiveReservationCancelDiagnosticConfig.CANCEL_TILCOD to "1000000000001",
            ),
        ),
    )

    private fun reservation(tilcod: String, cancelCode: String) = Reservation(
        memberId = 0,
        title = "",
        materialType = "",
        pickupLibrary = "",
        reservedDate = LocalDate.of(2030, 1, 1),
        queuePosition = null,
        state = ReservationState.WAITING,
        holdExpiryDate = null,
        tilcod = tilcod,
        cancelCode = cancelCode,
    )

    private class FakeCancelGateway(before: List<Reservation>) : ReservationGateway {
        val session = FakeCancelSession(before)

        override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession = session
    }

    private class FakeCancelSession(private val before: List<Reservation>) : ReservationSession {
        val calls = mutableListOf<String>()
        var cancelCalls = 0
        var cancelledWith: String? = null
        private var cancelled = false

        override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt =
            error("この診断では使わない")

        override suspend fun fetchReservations(): List<Reservation> {
            calls += if (cancelled) "fetch-after" else "fetch-before"
            return if (cancelled) emptyList() else before
        }

        override suspend fun cancelReservation(cancelCode: String, expectedTilcod: String): ReservationCancelAttempt {
            calls += "cancel"
            cancelCalls += 1
            cancelledWith = cancelCode
            cancelled = true
            return ReservationCancelAttempt.Cancelled
        }

        override fun close() {
            calls += "close"
        }
    }
}
