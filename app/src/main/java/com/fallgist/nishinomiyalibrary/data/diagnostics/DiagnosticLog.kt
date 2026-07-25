package com.fallgist.nishinomiyalibrary.data.diagnostics

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LicsXpDiagnosticObserver
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LicsXpDiagnosticRequest
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
private val LOG_ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
private val LOG_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(LOG_ZONE)

/** 診断ログの1行。 */
data class DiagnosticLogEntry(
    val epochMillis: Long,
    val category: String,
    val message: String,
)

/**
 * 通信診断ログをメモリ内だけに保持するリングバッファ（最大1000件）。
 * [recording] が false の間は [record] が即座に何もしないため、通常利用時にHTML解析や
 * 文字列生成のコストは発生しない。ファイルやDBへは永続化せず、プロセス生存中のみ保持する。
 */
@Singleton
class DiagnosticLog @Inject constructor(
    private val clock: Clock,
) {
    private val lock = Any()
    private val buffer = ArrayDeque<DiagnosticLogEntry>(MAX_ENTRIES)
    private var bufferedLength = 0

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
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            bufferedLength = 0
            _entries.value = emptyList()
        }
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
     * 調査対象は予約導線に限られるため、予約関連の画面だけを記録する。
     */
    override fun onScreenScript(path: String, actionTargets: List<String>, fieldAssignments: List<String>) {
        if (!path.contains("Yoy")) return
        if (actionTargets.isEmpty() && fieldAssignments.isEmpty()) return
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
