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
    fun `画面スクリプトはYoy限定なしにどの画面でも記録される`() {
        val log = DiagnosticLog(fixedClock(0L))
        log.recording = true
        val observer = DiagnosticLogObserver(log)

        observer.onScreenScript("/licsxp-opac/WOpacMsgNewMenuDispAction.do", listOf("a:b"), listOf("c:d"))
        val categories = log.entries.value.map { it.category }
        assertEquals(listOf("script", "script-actions", "script-assign"), categories)
    }

    @Test
    fun `同一内容の画面スクリプトを2回記録すると2回目は既出参照の1行だけになる`() {
        val log = DiagnosticLog(fixedClock(0L))
        log.recording = true
        val observer = DiagnosticLogObserver(log)

        observer.onScreenScript("/licsxp-opac/WOpacTifDirectYoyDispAction.do", listOf("exec:X.do"), listOf("exec:body=submit();"))
        val firstRoundCount = log.entries.value.size

        observer.onScreenScript("/licsxp-opac/WOpacMsgNewMenuDispAction.do", listOf("exec:X.do"), listOf("exec:body=submit();"))
        val secondRoundEntries = log.entries.value.drop(firstRoundCount)

        assertEquals(listOf("script"), secondRoundEntries.map { it.category })
        assertTrue(secondRoundEntries.single().message.contains("既出のスクリプトと同一"))
    }

    @Test
    fun `内容が異なる画面スクリプトは重複除去されず通常どおり記録される`() {
        val log = DiagnosticLog(fixedClock(0L))
        log.recording = true
        val observer = DiagnosticLogObserver(log)

        observer.onScreenScript("/a.do", listOf("exec:X.do"), listOf("exec:body=submit();"))
        observer.onScreenScript("/b.do", listOf("exec:Y.do"), listOf("exec:body=submit2();"))

        val scriptCategoryMessages = log.entries.value.filter { it.category == "script" }.map { it.message }
        assertEquals(2, scriptCategoryMessages.size)
        assertFalse(scriptCategoryMessages.any { it.contains("既出のスクリプトと同一") })
    }

    @Test
    fun `clearで既出ハッシュがリセットされ同一内容も再度通常記録される`() {
        val log = DiagnosticLog(fixedClock(0L))
        log.recording = true
        val observer = DiagnosticLogObserver(log)

        observer.onScreenScript("/a.do", listOf("exec:X.do"), listOf("exec:body=submit();"))
        log.clear()
        log.recording = true
        observer.onScreenScript("/a.do", listOf("exec:X.do"), listOf("exec:body=submit();"))

        val scriptCategoryMessages = log.entries.value.filter { it.category == "script" }.map { it.message }
        assertFalse(scriptCategoryMessages.any { it.contains("既出のスクリプトと同一") })
    }

    @Test
    fun `分割記録により関数本体が1行上限で切り捨てられない`() {
        val log = DiagnosticLog(fixedClock(0L))
        log.recording = true
        val observer = DiagnosticLogObserver(log)
        // actionが大量にある画面でも、末尾の関数本体は独立した行として残る。
        val actions = List(200) { index -> "func$index:VeryLongActionTarget$index.do?parameter=value" }

        observer.onScreenScript("/licsxp-opac/WOpacTifDirectYoyDispAction.do", actions, listOf("exec:body=見失ってはいけない"))

        assertTrue(log.entries.value.count { it.category == "script-actions" } > 1)
        assertTrue(log.formatted().contains("exec:body=見失ってはいけない"))
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
    fun `ビルド識別子が未設定なら記録をONにしてもbuild行は出ない`() {
        val log = DiagnosticLog(fixedClock(0L))
        log.recording = true

        assertTrue(log.entries.value.isEmpty())
    }

    @Test
    fun `setBuildIdentity後に記録をONにすると先頭にbuild行が入る`() {
        val log = DiagnosticLog(fixedClock(0L))
        log.setBuildIdentity(gitSha = "b66c006", buildTime = "2026-07-25 12:00", versionName = "1.0")

        log.recording = true

        val first = log.entries.value.first()
        assertEquals("build", first.category)
        assertEquals("commit=b66c006 built=2026-07-25 12:00 version=1.0", first.message)
    }

    @Test
    fun `recordingが既にtrueのままtrueを再代入してもbuild行は増えない`() {
        val log = DiagnosticLog(fixedClock(0L))
        log.setBuildIdentity(gitSha = "b66c006", buildTime = "2026-07-25 12:00", versionName = "1.0")
        log.recording = true
        val countAfterFirstOn = log.entries.value.size

        log.recording = true

        assertEquals(countAfterFirstOn, log.entries.value.size)
    }

    @Test
    fun `clear直後にも先頭にbuild行が入る`() {
        val log = DiagnosticLog(fixedClock(0L))
        log.setBuildIdentity(gitSha = "b66c006", buildTime = "2026-07-25 12:00", versionName = "1.0")
        log.recording = true
        log.record("request", "GET /foo")

        log.clear()

        val entries = log.entries.value
        assertEquals(1, entries.size)
        assertEquals("build", entries.first().category)
    }

    @Test
    fun `recordingがOFFのままclearしてもbuild行は記録されない`() {
        val log = DiagnosticLog(fixedClock(0L))
        log.setBuildIdentity(gitSha = "b66c006", buildTime = "2026-07-25 12:00", versionName = "1.0")

        log.clear()

        assertTrue(log.entries.value.isEmpty())
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
