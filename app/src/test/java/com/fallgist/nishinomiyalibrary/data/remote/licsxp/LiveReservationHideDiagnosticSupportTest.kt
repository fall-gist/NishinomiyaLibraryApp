package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveReservationHideDiagnosticSupportTest {
    @Test
    fun `認可不足ではgatewayを生成しない`() = runBlocking {
        var factoryCalls = 0
        val result = runLiveReservationHideDiagnosticIfAuthorized(
            environment = mapOf(
                LiveReservationHideDiagnosticConfig.CARD_NUMBER to "card",
                LiveReservationHideDiagnosticConfig.PASSWORD to "password",
                LiveReservationHideDiagnosticConfig.HIDE_TILCOD to "1000000000001",
            ),
            gatewayFactory = { factoryCalls += 1; error("生成してはいけません") },
            logger = LiveReservationDiagnosticLogger(),
        )
        assertNull(result)
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `診断結果は秘密値を含まない列挙値だけを記録する`() = runBlocking {
        val session = object : ReservationSession, ReservationHideDiagnosticCapability {
            override suspend fun directReserve(tilcod: String, pickupLibraryCode: String) = error("unused")
            override suspend fun fetchReservations() = error("unused")
            override fun close() = Unit
            override suspend fun hideCancelledReservationForDiagnostic(expectedTilcod: String) =
                ReservationHideDiagnosticResult.HIDE_BUTTON_MISSING
        }
        val result = runLiveReservationHideDiagnosticIfAuthorized(
            environment = authorizedEnvironment(),
            gatewayFactory = {
                object : ReservationGateway {
                    override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = session
                }
            },
            logger = LiveReservationDiagnosticLogger(),
        )
        assertEquals(ReservationHideDiagnosticResult.HIDE_BUTTON_MISSING, result)
    }

    private fun authorizedEnvironment() = mapOf(
        LiveReservationHideDiagnosticConfig.ENABLE_FLAG to "YES_I_UNDERSTAND",
        LiveReservationHideDiagnosticConfig.HIDE_CONFIRM_FLAG to "HIDE_ON_PRODUCTION",
        LiveReservationHideDiagnosticConfig.CARD_NUMBER to "card",
        LiveReservationHideDiagnosticConfig.PASSWORD to "password",
        LiveReservationHideDiagnosticConfig.HIDE_TILCOD to "1000000000001",
    )
}
