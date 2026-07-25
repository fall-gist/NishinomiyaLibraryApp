package com.fallgist.nishinomiyalibrary.data.diagnostics

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * ファイル永続化・ローテーション・世代保持・1レコード1行保証・消去・エクスポートの確認。
 * [FakeFilesDirContext] で filesDir だけをテスト専用の一時ディレクトリへ差し替える。
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticLogFileStoreTest {
    private lateinit var tempDir: File
    private lateinit var context: Context

    /** filesDir だけをテスト用の一時ディレクトリへ差し替えるラッパー。 */
    private class FakeFilesDirContext(base: Context, private val fakeFilesDir: File) : ContextWrapper(base) {
        override fun getFilesDir(): File = fakeFilesDir
    }

    @Before
    fun setUp() {
        tempDir = File.createTempFile("diag-store-", "").apply {
            delete()
            mkdirs()
        }
        context = FakeFilesDirContext(RuntimeEnvironment.getApplication(), tempDir)
    }

    private fun diagnosticsDir(): File = File(tempDir, "diagnostics")

    private fun generationFiles(): List<File> =
        diagnosticsDir().listFiles()
            ?.filter { it.isFile && it.name.matches(Regex("""diagnostic-\d{3}\.log""")) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** append は非同期投入のため、単一スレッド上の同期処理(clear/exportToSingleFile)を挟んで完了を待つ。 */
    private fun waitForQuiescence(store: DiagnosticLogFileStore) {
        store.exportToSingleFile()
    }

    @Test
    fun `1レコードごとにタブ区切りで追記され256KB超で次の連番へローテーションする`() {
        val store = DiagnosticLogFileStore(context)
        // 1行あたり約1KBのメッセージを300件書き、256KBを超えさせて複数ファイルに分割させる。
        val bigMessage = "x".repeat(1000)
        repeat(300) { index -> store.append(index.toLong(), "request", bigMessage) }
        waitForQuiescence(store)

        val files = generationFiles()
        assertTrue("複数の世代ファイルに分割されているはず: ${files.size}", files.size > 1)
        files.forEach { file -> assertTrue(file.length() <= 256 * 1024 + 4096) }
    }

    @Test
    fun `9世代目を書き込むと最古の世代が消える`() {
        val store = DiagnosticLogFileStore(context)
        val bigMessage = "x".repeat(1000)
        // 1ファイルを256KB超にするのに十分な件数を9回分書き、9回ローテーションさせる。
        repeat(9) { generation ->
            repeat(300) { index -> store.append((generation * 1000 + index).toLong(), "request", bigMessage) }
            waitForQuiescence(store)
        }

        val files = generationFiles()
        assertTrue("保持世代は8以下のはず: ${files.size}", files.size <= 8)
        // 最古の連番(diagnostic-000.log)は削除されているはず
        assertFalse(files.any { it.name == "diagnostic-000.log" })
    }

    @Test
    fun `メッセージ中の改行とタブがエスケープされ1レコードが必ず1行になる`() {
        val store = DiagnosticLogFileStore(context)
        store.append(0L, "script-assign", "line1\nline2\tafter-tab")
        waitForQuiescence(store)

        val files = generationFiles()
        val lines = files.single().readLines()
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("line1\\nline2\\tafter-tab"))
    }

    @Test
    fun `clearで永続化ファイルが消える`() {
        val store = DiagnosticLogFileStore(context)
        store.append(0L, "request", "GET /foo")
        waitForQuiescence(store)
        assertTrue(generationFiles().isNotEmpty())

        store.clear()
        assertTrue(generationFiles().isEmpty())
    }

    @Test
    fun `exportToSingleFileは保持中の全世代を時系列順に連結する`() {
        val store = DiagnosticLogFileStore(context)
        val bigMessage = "x".repeat(1000)
        repeat(600) { index -> store.append(index.toLong(), "request", "$index-$bigMessage") }

        val exported = store.exportToSingleFile()
        assertTrue(exported != null && exported.exists())
        val indices = exported!!.readLines().map { line ->
            // message列は3列目(タブ区切り)。先頭の連番だけを取り出す。
            line.split("\t").last().substringBefore("-").toInt()
        }
        assertEquals(indices.sorted(), indices)
    }
}
