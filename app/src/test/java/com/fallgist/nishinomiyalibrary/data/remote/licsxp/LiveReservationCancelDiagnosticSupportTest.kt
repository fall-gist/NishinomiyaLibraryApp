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
    fun `取消可能な行が複数件一致した場合はcheckで停止し取消を呼ばない`() = runBlocking {
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
            assertEquals("取消可能な行が複数件一致したため、どれを取り消すか決められません", exception.message)
        }

        assertEquals(0, gateway.session.cancelCalls)
    }

    @Test
    fun `取消済み行が併存していても取消可能な行が一意なら取消を呼ぶ`() = runBlocking {
        // 13回目のライブ観測(2026-07-28)実測どおり、取消済み行を非表示にせず同じ書誌を再予約すると
        // 同一tilcodの行が複数になる（普通の運用操作であり実運用で必ず起きる）。取消可能な行
        // (cancelCodeが非空)が一意なら、取消済み行が併存していても実行できることを確認する。
        val gateway = FakeCancelGateway(
            before = listOf(
                reservation(tilcod = "1000000000001", cancelCode = "c1", state = ReservationState.WAITING),
                reservation(tilcod = "1000000000001", cancelCode = "", state = ReservationState.CANCELLED),
            ),
        )
        val config = authorizedConfig()

        val report = runLiveReservationCancelDiagnostic(gateway, config, LiveReservationDiagnosticLogger())

        assertEquals(1, gateway.session.cancelCalls)
        assertEquals("c1", gateway.session.cancelledWith)
        assertTrue(report.attempt is ReservationCancelAttempt.Cancelled)
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
        assertFalse(report.targetRowPresentAfter)
        assertTrue(report.attempt is ReservationCancelAttempt.Cancelled)
        assertEquals(listOf("fetch-before", "cancel", "fetch-after", "close"), gateway.session.calls)
    }

    @Test
    fun `一覧観測に対応したセッションでは取消後も対象行が取消状態で残っていることを観測できる`() = runBlocking {
        // 12回目のライブ取消＋一覧観測(2026-07-28)で確定した仕様どおり、取消成立後も対象行は
        // 「取消」状態のまま一覧に残り得る。この場合でもattemptはCancelledであり、
        // targetRowPresentAfter(観測値)がtrueであることは失敗を意味しない。
        val gateway = FakeCancelListInspectorGateway(
            before = listOf(reservation(tilcod = "1000000000001", cancelCode = "c1")),
            afterStates = listOf(ReservationState.CANCELLED),
            afterHideButtonPresent = true,
        )
        val config = authorizedConfig()

        val report = runLiveReservationCancelDiagnostic(gateway, config, LiveReservationDiagnosticLogger())

        assertTrue(report.attempt is ReservationCancelAttempt.Cancelled)
        assertTrue(report.targetRowPresentAfter)
    }

    @Test
    fun `一覧観測に対応したセッションで取消後に対象行が消えていればtargetRowPresentAfterはfalseになる`() = runBlocking {
        val gateway = FakeCancelListInspectorGateway(
            before = listOf(reservation(tilcod = "1000000000001", cancelCode = "c1")),
            afterStates = emptyList(),
            afterHideButtonPresent = false,
        )
        val config = authorizedConfig()

        val report = runLiveReservationCancelDiagnostic(gateway, config, LiveReservationDiagnosticLogger())

        assertTrue(report.attempt is ReservationCancelAttempt.CancelledAndHidden)
        assertFalse(report.targetRowPresentAfter)
    }

    @Test
    fun `一覧観測で取消後に対象tilcodの行が2行あればtargetRowPresentAfterはtrueで2行とも記録する`() = runBlocking {
        // 13回目のライブ実測(2026-07-28)の再現: 基準値1(取消済み行1つ併存)の状態から取消すると、
        // 取消済み行が2つ(両方CANCELLED)になる。cancel-afterがsingleOrNullで該当2件以上をnull扱いし
        // 「targetPresent=false」と誤記録していたバグの回帰試験（filterで全件を記録するよう修正済み）。
        val gateway = FakeCancelListInspectorGateway(
            before = listOf(reservation(tilcod = "1000000000001", cancelCode = "c1")),
            afterStates = listOf(ReservationState.CANCELLED, ReservationState.CANCELLED),
            afterHideButtonPresent = true,
        )
        val config = authorizedConfig()
        val originalOut = System.out
        val captured = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(captured, true, "UTF-8"))
        val report = try {
            runLiveReservationCancelDiagnostic(gateway, config, LiveReservationDiagnosticLogger())
        } finally {
            System.setOut(originalOut)
        }
        val output = captured.toString("UTF-8")

        assertTrue(report.attempt is ReservationCancelAttempt.Cancelled)
        assertTrue(report.targetRowPresentAfter)
        // 資料名・cancelCode値は出さず、行数と行ごとのstate・非表示ボタン有無だけを記録する。
        assertTrue(output.contains("cancel-after: targetPresent=true rows=2"))
        assertTrue(output.contains("cancel-after-row[0]: state=CANCELLED hideButtonPresent=true"))
        assertTrue(output.contains("cancel-after-row[1]: state=CANCELLED hideButtonPresent=true"))
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

    private fun reservation(tilcod: String, cancelCode: String, state: ReservationState = ReservationState.WAITING) = Reservation(
        memberId = 0,
        title = "",
        materialType = "",
        pickupLibrary = "",
        reservedDate = LocalDate.of(2030, 1, 1),
        queuePosition = null,
        state = state,
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

    /**
     * [ReservationListInspector]に対応したセッションのフェイク。取消後、対象tilcodの行が
     * [afterStates]の件数だけ（0件なら一覧から消えたことを意味する）残る状態を再現する。
     * 13回目のライブ観測(2026-07-28)実測どおり、取消済み行の併存により2件以上になることもある。
     */
    private class FakeCancelListInspectorGateway(
        private val before: List<Reservation>,
        private val afterStates: List<ReservationState>,
        private val afterHideButtonPresent: Boolean,
    ) : ReservationGateway {
        override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession =
            FakeCancelListInspectorSession(before, afterStates, afterHideButtonPresent)
    }

    private class FakeCancelListInspectorSession(
        private val before: List<Reservation>,
        private val afterStates: List<ReservationState>,
        private val afterHideButtonPresent: Boolean,
    ) : ReservationSession, ReservationListInspector {
        override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt =
            error("この診断では使わない")

        override suspend fun fetchReservations(): List<Reservation> = before

        override suspend fun cancelReservation(cancelCode: String, expectedTilcod: String): ReservationCancelAttempt =
            if (afterStates.isNotEmpty()) ReservationCancelAttempt.Cancelled else ReservationCancelAttempt.CancelledAndHidden

        override fun close() = Unit

        override suspend fun inspectReservationList(): ReservationListInspection {
            val tilcod = before.first().tilcod
            val rows = afterStates.map { state ->
                ReservationListRowInspection(
                    tilcod = tilcod,
                    stateText = "取消",
                    state = state,
                    cancelCodePresent = false,
                    buttonFunctionNames = if (afterHideButtonPresent) listOf("yoykHihyoji") else emptyList(),
                    cellClassNames = emptyList(),
                )
            }
            return ReservationListInspection(summaryReservationCount = null, parsedRowCount = rows.size, rows = rows)
        }
    }
}
