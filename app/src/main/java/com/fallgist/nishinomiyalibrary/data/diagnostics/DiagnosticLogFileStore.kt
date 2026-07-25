package com.fallgist.nishinomiyalibrary.data.diagnostics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 1ファイルあたりの上限バイト数。超えたら次の連番ファイルへ切り替える。 */
private const val MAX_FILE_BYTES = 256 * 1024L

/** 保持する世代数の上限。超えたら最も古いファイルを削除する。 */
private const val MAX_GENERATIONS = 8

/** 画面操作から書き込みスレッドの完了を待つ上限秒数。 */
private const val STORE_OPERATION_TIMEOUT_SECONDS = 5L

private val FILE_ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
private val FILE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(FILE_ZONE)

private val GENERATION_FILE_REGEX = Regex("""diagnostic-(\d{3})\.log""")

/**
 * 診断ログをアプリ専用領域(`filesDir/diagnostics/`)へ永続化する協力者。
 * 通信スレッドをブロックしないよう、書き込みは単一の直列化スレッドで行う。
 * [append] はそのスレッドへ投入するだけで即座に戻る。ストレージ不足等の例外は握りつぶし、
 * 呼び出し元(通信・画面)へは一切伝播させない。
 */
@Singleton
class DiagnosticLogFileStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val diagnosticsDir = File(context.filesDir, "diagnostics")
    private val exportDir = File(diagnosticsDir, "export")
    private val exportFile = File(exportDir, "diagnostic-export.log")

    // 書き込み・ローテーション・消去・エクスポートを1本のスレッドで直列化し、競合を避ける。
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var currentIndex: Int = 0
    private var currentFile: File

    init {
        diagnosticsDir.mkdirs()
        currentIndex = generationFiles().maxOfOrNull { indexOf(it) } ?: 0
        currentFile = fileFor(currentIndex)
    }

    /** 1レコードを追記する。呼び出し元スレッドはブロックしない。例外は握りつぶす。 */
    fun append(epochMillis: Long, category: String, message: String) {
        executor.execute {
            runCatching { writeLine(formatLine(epochMillis, category, message)) }
        }
    }

    /** メモリ側の消去に合わせて、保持中の全世代とエクスポート済みファイルを消す。 */
    fun clear() {
        runOnStoreThreadAndWait {
            diagnosticsDir.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
            if (exportFile.exists()) exportFile.delete()
            currentIndex = 0
            currentFile = fileFor(currentIndex)
        }
    }

    /** 保持中の全世代ファイルの合計バイト数。 */
    fun totalPersistedBytes(): Long =
        generationFiles().sumOf { it.length() }

    /**
     * 保持中の全世代を時系列順(古い→新しい)に連結した1ファイルを作って返す。
     * 呼ぶたびに [exportFile] を上書きする。失敗時はnull。
     */
    fun exportToSingleFile(): File? = runOnStoreThreadAndWait {
        runCatching {
            exportDir.mkdirs()
            exportFile.outputStream().use { out ->
                generationFiles().forEach { file ->
                    file.inputStream().use { it.copyTo(out) }
                }
            }
            exportFile
        }.getOrNull()
    }

    /**
     * 画面操作から呼ばれるため、書き込み待ち行列が詰まっていても無期限には待たない。
     * 待ち時間内に終わらなければ null を返し、処理自体は書き込みスレッドで続行させる。
     */
    private fun <T> runOnStoreThreadAndWait(block: () -> T): T? {
        val latch = CountDownLatch(1)
        var result: T? = null
        executor.execute {
            result = runCatching(block).getOrNull()
            latch.countDown()
        }
        if (!latch.await(STORE_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null
        return result
    }

    private fun writeLine(line: String) {
        val lineBytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (currentFile.exists() && currentFile.length() + lineBytes.size > MAX_FILE_BYTES) {
            rotate()
        }
        diagnosticsDir.mkdirs()
        currentFile.appendBytes(lineBytes)
    }

    /** 次の連番ファイルへ切り替える。世代数が上限に達していれば最も古いものを削除する。 */
    private fun rotate() {
        val existing = generationFiles()
        if (existing.size >= MAX_GENERATIONS) {
            existing.minByOrNull { indexOf(it) }?.delete()
        }
        currentIndex += 1
        currentFile = fileFor(currentIndex)
    }

    private fun generationFiles(): List<File> =
        diagnosticsDir.listFiles()
            ?.filter { it.isFile && GENERATION_FILE_REGEX.matches(it.name) }
            ?.sortedBy { indexOf(it) }
            ?: emptyList()

    private fun indexOf(file: File): Int =
        GENERATION_FILE_REGEX.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun fileFor(index: Int): File = File(diagnosticsDir, "diagnostic-%03d.log".format(index))

    /**
     * `<ISO-8601のローカル時刻>\t<category>\t<message>` の1行を作る。
     * message中の改行・タブは必ず1レコード1行になるよう `\n` `\t` の2文字へエスケープする。
     * 復帰(\r)も同様に1行保証のため `\r` の2文字へエスケープする。
     */
    private fun formatLine(epochMillis: Long, category: String, message: String): String {
        val time = FILE_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis))
        val escapedMessage = message
            .replace("\r\n", "\n")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "$time\t$category\t$escapedMessage"
    }
}
