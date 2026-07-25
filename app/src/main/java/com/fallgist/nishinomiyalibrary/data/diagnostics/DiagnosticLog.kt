package com.fallgist.nishinomiyalibrary.data.diagnostics

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LicsXpDiagnosticObserver
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LicsXpDiagnosticRequest
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val MAX_ENTRIES = 1000
private const val MAX_MESSAGE_LENGTH = 2000
/** 全体の保持量。件数上限に達する前でもこの文字数を超えたら古い行から捨てる。 */
private const val MAX_TOTAL_LENGTH = 400_000
/** 画面スクリプトの重複除去に使う既出ハッシュの保持上限。超えたら古いものから捨てる。 */
private const val SCRIPT_HASH_LIMIT = 64
private val LOG_ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
private val LOG_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(LOG_ZONE)

/** 診断ログの1行。 */
data class DiagnosticLogEntry(
    val epochMillis: Long,
    val category: String,
    val message: String,
)

/**
 * 通信診断ログをメモリ内のリングバッファ（最大1000件）に保持しつつ、[fileStore] があれば
 * ファイルへも永続化する通信診断ログ。[recording] が false の間は [record] が即座に何もしないため、
 * 通常利用時にHTML解析や文字列生成のコストは発生しない。
 *
 * [fileStore] は省略可能な引数とし、省略時(既存ユニットテストが `DiagnosticLog(clock)` の形で
 * 直接生成する場合)はファイル出力を行わずメモリ内保持のみとなる。
 */
@Singleton
class DiagnosticLog @Inject constructor(
    private val clock: Clock,
    private val fileStore: DiagnosticLogFileStore? = null,
) {
    private val lock = Any()
    private val buffer = ArrayDeque<DiagnosticLogEntry>(MAX_ENTRIES)
    private var bufferedLength = 0

    /** 画面スクリプトの重複除去用の既出ハッシュ集合。[clear] でリセットされる。 */
    private val scriptHashSeen = LinkedHashSet<Int>()

    private val _recordingState = MutableStateFlow(false)

    /** 記録のオン/オフ。既定はオフ。 */
    var recording: Boolean
        get() = _recordingState.value
        set(value) {
            _recordingState.value = value
        }

    /** [recording] を観測するためのFlow。 */
    val recordingState: StateFlow<Boolean> = _recordingState.asStateFlow()

    private val _entries = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())

    /** 記録済みログ一覧。新しいものが後ろに来る。 */
    val entries: StateFlow<List<DiagnosticLogEntry>> = _entries.asStateFlow()

    /** [recording] が false のときは何もしない。message は2000文字で切り詰める。 */
    fun record(category: String, message: String) {
        if (!recording) return
        val entry = DiagnosticLogEntry(
            epochMillis = clock.millis(),
            category = category,
            message = message.take(MAX_MESSAGE_LENGTH),
        )
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            bufferedLength += entry.message.length
            while (bufferedLength > MAX_TOTAL_LENGTH && buffer.size > 1) {
                bufferedLength -= buffer.removeFirst().message.length
            }
            _entries.value = buffer.toList()
        }
        // ファイルへの追記は専用スレッドで直列化されるため、ここでは投入するだけで戻る。
        fileStore?.append(entry.epochMillis, entry.category, entry.message)
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            bufferedLength = 0
            scriptHashSeen.clear()
            _entries.value = emptyList()
        }
        fileStore?.clear()
    }

    /** 保存済みログの合計バイト数。[fileStore] が無ければ0。 */
    fun totalPersistedBytes(): Long = fileStore?.totalPersistedBytes() ?: 0L

    /** 保持中の全世代を時系列順に連結した1ファイルを作って返す。[fileStore] が無ければnull。 */
    fun exportToSingleFile(): File? = fileStore?.exportToSingleFile()

    /**
     * 画面スクリプトの内容ハッシュが既出かどうかを判定し、未出であれば記録する。
     * 戻り値は「今回が初出だったか(true)」。上限[SCRIPT_HASH_LIMIT]を超えたら最も古いものから捨てる。
     */
    internal fun registerScriptHash(hash: Int): Boolean = synchronized(lock) {
        val isNew = scriptHashSeen.add(hash)
        if (isNew && scriptHashSeen.size > SCRIPT_HASH_LIMIT) {
            val iterator = scriptHashSeen.iterator()
            iterator.next()
            iterator.remove()
        }
        isNew
    }

    /** `HH:mm:ss.SSS [category] message` を1行ずつ、Asia/Tokyoで整形して連結する。 */
    fun formatted(): String {
        val snapshot = synchronized(lock) { buffer.toList() }
        return snapshot.joinToString(separator = "\n") { entry ->
            "${LOG_TIME_FORMATTER.format(Instant.ofEpochMilli(entry.epochMillis))} [${entry.category}] ${entry.message}"
        }
    }
}

/**
 * [LicsXpDiagnosticObserver] 実装。全HTTP通信の診断情報を [DiagnosticLog] へ1行ずつ記録する。
 * 出力書式は `LiveReservationDiagnosticLogger.observer()` と揃える。
 */
internal class DiagnosticLogObserver(private val log: DiagnosticLog) : LicsXpDiagnosticObserver {
    override val enabled: Boolean get() = log.recording

    override fun onRequest(request: LicsXpDiagnosticRequest) {
        val query = request.query.entries.joinToString("&") { (name, value) -> "$name=$value" }
        val form = request.form.entries.joinToString("&") { (name, value) -> "$name=$value" }
        log.record("request", "${request.method} ${request.path} query=[$query] form=[$form]")
    }

    override fun onResponse(method: String, path: String, statusCode: Int, redirectPath: String?) {
        log.record("response", "$method $path status=$statusCode redirect=${redirectPath ?: "-"}")
    }

    override fun onPage(path: String, classification: String, formFingerprint: String) {
        log.record("page", "$path classification=$classification forms=$formFingerprint")
    }

    /**
     * actionと代入は1行に収まらないため、1行あたりの上限で切り捨てられないよう分割して記録する。
     * 特に関数本体（`関数名:body=...`）は末尾に来るため、まとめて1行にすると必ず失われる。
     *
     * 画面スクリプトは全画面で数十行になり、他の記録をリングバッファから押し出してしまう。
     * 同一内容のスクリプトは多くの画面で繰り返し現れるため、内容ハッシュで重複を除去し、
     * 既出のものは1行の参照記録だけを残す。既出ハッシュの集合は[DiagnosticLog.clear]でリセットされる。
     */
    override fun onScreenScript(path: String, actionTargets: List<String>, fieldAssignments: List<String>) {
        if (actionTargets.isEmpty() && fieldAssignments.isEmpty()) return
        val content = actionTargets.joinToString(" ") + "|" + fieldAssignments.joinToString(" ")
        val hash = content.hashCode()
        val hashHex = "%08x".format(hash)
        if (!log.registerScriptHash(hash)) {
            log.record("script", "$path 既出のスクリプトと同一 (hash=$hashHex)")
            return
        }
        log.record("script", "$path hash=$hashHex")
        actionTargets.chunkedByLength(SCRIPT_CHUNK_LENGTH).forEach { chunk ->
            log.record("script-actions", "$path $chunk")
        }
        fieldAssignments.forEach { assignment ->
            log.record("script-assign", "$path $assignment")
        }
    }

    override fun onSiteMessages(path: String, messages: List<String>) {
        if (messages.isEmpty()) return
        log.record("messages", "$path $messages")
    }
}

private const val SCRIPT_CHUNK_LENGTH = 1500

/** 連結後の長さが上限を超えないよう、要素の切れ目でまとめ直す。 */
private fun List<String>.chunkedByLength(maxLength: Int): List<String> {
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    forEach { element ->
        if (current.isNotEmpty() && current.length + element.length + 2 > maxLength) {
            chunks += current.toString()
            current.setLength(0)
        }
        if (current.isNotEmpty()) current.append(", ")
        current.append(element)
    }
    if (current.isNotEmpty()) chunks += current.toString()
    return chunks
}
