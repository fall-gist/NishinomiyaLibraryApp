package com.fallgist.nishinomiyalibrary.data.remote.licsxp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveReservationListDiagnosticSupportTest {
    @Test
    fun `明示フラグが不足すれば gateway を生成せず通信できない`() = runBlocking {
        var gatewayFactoryCalls = 0
        val result = runLiveReservationListInspectionIfAuthorized(
            environment = mapOf(
                LiveReservationListInspectDiagnosticConfig.CARD_NUMBER to "1234",
                LiveReservationListInspectDiagnosticConfig.PASSWORD to "secret",
            ),
            gatewayFactory = {
                gatewayFactoryCalls += 1
                error("認可されていない診断で gateway を生成してはいけない")
            },
            logger = LiveReservationDiagnosticLogger(),
        )

        assertFalse(result)
        assertEquals(0, gatewayFactoryCalls)
    }

    @Test
    fun `取消専用の同意フラグを要求しない(書き込みをしないため)`() {
        // LICSXP_LIVE_CANCEL_CONFIRMを渡さなくてもconfigは成立する。この診断は取消POSTを送らない。
        val config = LiveReservationListInspectDiagnosticConfig.from(
            mapOf(
                LiveReservationListInspectDiagnosticConfig.ENABLE_FLAG to "YES_I_UNDERSTAND",
                LiveReservationListInspectDiagnosticConfig.INSPECT_FLAG to "INSPECT_ONLY",
                LiveReservationListInspectDiagnosticConfig.CARD_NUMBER to "1234",
                LiveReservationListInspectDiagnosticConfig.PASSWORD to "secret",
            ),
        )

        assertEquals(
            LiveReservationListInspectDiagnosticConfig(cardNumber = "1234", password = "secret", highlightTilcod = null),
            config,
        )
    }

    @Test
    fun `専用フラグが無ければ他の診断のフラグが揃っていても成立しない`() {
        val config = LiveReservationListInspectDiagnosticConfig.from(
            mapOf(
                LiveReservationListInspectDiagnosticConfig.ENABLE_FLAG to "YES_I_UNDERSTAND",
                LiveReservationCancelDiagnosticConfig.CANCEL_CONFIRM_FLAG to "CANCEL_ON_PRODUCTION",
                LiveReservationListInspectDiagnosticConfig.CARD_NUMBER to "1234",
                LiveReservationListInspectDiagnosticConfig.PASSWORD to "secret",
            ),
        )

        assertNull(config)
    }

    @Test
    fun `任意項目のtilcodは指定時だけ設定される`() {
        val withoutTilcod = LiveReservationListInspectDiagnosticConfig.from(baseEnvironment())
        val withTilcod = LiveReservationListInspectDiagnosticConfig.from(
            baseEnvironment() + (LiveReservationListInspectDiagnosticConfig.INSPECT_TILCOD to "1000000000001"),
        )

        assertNull(requireNotNull(withoutTilcod).highlightTilcod)
        assertEquals("1000000000001", requireNotNull(withTilcod).highlightTilcod)
    }

    @Test
    fun `認可済みなら一覧観測を1回呼ぶ`() = runBlocking {
        var gatewayFactoryCalls = 0
        var inspectCalls = 0
        val session = object : ReservationSession, ReservationListInspector {
            override suspend fun directReserve(tilcod: String, pickupLibraryCode: String) = error("この診断では使わない")
            override suspend fun fetchReservations() = error("この診断では使わない")
            override fun close() = Unit
            override suspend fun inspectReservationList(): ReservationListInspection {
                inspectCalls += 1
                return ReservationListInspection(summaryReservationCount = 0, parsedRowCount = 0, rows = emptyList())
            }
        }
        val gateway = object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession = session
        }

        val result = runLiveReservationListInspectionIfAuthorized(
            environment = baseEnvironment(),
            gatewayFactory = {
                gatewayFactoryCalls += 1
                gateway
            },
            logger = LiveReservationDiagnosticLogger(),
        )

        assertTrue(result)
        assertEquals(1, gatewayFactoryCalls)
        assertEquals(1, inspectCalls)
    }

    private fun baseEnvironment(): Map<String, String> = mapOf(
        LiveReservationListInspectDiagnosticConfig.ENABLE_FLAG to "YES_I_UNDERSTAND",
        LiveReservationListInspectDiagnosticConfig.INSPECT_FLAG to "INSPECT_ONLY",
        LiveReservationListInspectDiagnosticConfig.CARD_NUMBER to "1234",
        LiveReservationListInspectDiagnosticConfig.PASSWORD to "secret",
    )
}
