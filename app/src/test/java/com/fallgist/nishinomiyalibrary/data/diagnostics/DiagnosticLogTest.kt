package com.fallgist.nishinomiyalibrary.data.diagnostics

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogTest {
    private fun fixedClock(epochMillis: Long): Clock =
        Clock.fixed(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC)

    @Test
    fun `記録OFFの間はrecordしても1件も入らない`() {
        val log = DiagnosticLog(fixedClock(0L))
        // recording は既定でfalse
        log.record("request", "GET /foo")
        log.record("response", "GET /foo status=200 redirect=-")

        assertTrue(log.entries.value.isEmpty())
    }

    @Test
    fun `記録ONにすると入りOFFに戻すとそれ以降は入らない`() {
        val log = DiagnosticLog(fixedClock(0L))
        log.recording = true
        log.record("request", "GET /foo")
        assertEquals(1, log.entries.value.size)

        log.recording = false
        log.record("request", "GET /bar")
        assertEquals(1, log.entries.value.size)
    }

    @Test
    fun `上限1000件を超えると古いものからリングバッファとして捨てられる`() {
        val log = DiagnosticLog(fixedClock(0L))
        log.recording = true
        repeat(1005) { index -> log.record("request", "GET /$index") }

        val entries = log.entries.value
        assertEquals(1000, entries.size)
        // 先頭5件(0..4)は捨てられ、5番目以降が残る
        assertEquals("GET /5", entries.first().message)
        assertEquals("GET /1004", entries.last().message)
    }

    @Test
    fun `clearで全件消える`() {
        val log = DiagnosticLog(fixedClock(0L))
        log.recording = true
        log.record("request", "GET /foo")
        assertEquals(1, log.entries.value.size)

        log.clear()
        assertTrue(log.entries.value.isEmpty())
        assertEquals("", log.formatted())
    }

    @Test
    fun `messageは2000文字で切り詰められる`() {
        val log = DiagnosticLog(fixedClock(0L))
        log.recording = true
        log.record("request", "x".repeat(3000))

        assertEquals(2000, log.entries.value.single().message.length)
    }

    @Test
    fun `formattedはAsia_Tokyoの時刻でHH_mm_ss_SSS category messageの行を連結する`() {
        // 2026-07-25T00:00:00Z は Asia/Tokyo で 09:00:00.000
        val log = DiagnosticLog(fixedClock(Instant.parse("2026-07-25T00:00:00.500Z").toEpochMilli()))
        log.recording = true
        log.record("request", "GET /foo")

        assertEquals("09:00:00.500 [request] GET /foo", log.formatted())
    }

    @Test
    fun `DiagnosticLogObserverのenabledはrecordingに連動する`() {
        val log = DiagnosticLog(fixedClock(0L))
        val observer = DiagnosticLogObserver(log)

        assertFalse(observer.enabled)
        log.recording = true
        assertTrue(observer.enabled)
        log.recording = false
        assertFalse(observer.enabled)
    }
}
