package com.fallgist.nishinomiyalibrary.ui.diagnostics

import com.fallgist.nishinomiyalibrary.data.diagnostics.DiagnosticLog
import com.fallgist.nishinomiyalibrary.data.diagnostics.DiagnosticLogEntry
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** ログ閲覧画面の1行。 */
data class DiagnosticLogRow(
    val timeLabel: String,
    val category: String,
    val message: String,
    /** タップ時にコピーする1行分の整形済みテキスト。 */
    val copyText: String,
)

data class DiagnosticLogUiState(
    val initialized: Boolean = false,
    val query: String = "",
    /** 現在のログに実際に現れているカテゴリ一覧(固定リストではない)。 */
    val availableCategories: List<String> = emptyList(),
    val selectedCategories: Set<String> = emptySet(),
    /** 新しいものが先頭に来る絞り込み後の行。 */
    val rows: List<DiagnosticLogRow> = emptyList(),
    val totalCount: Int = 0,
    val persistedBytesLabel: String = "0B",
)

/** 検索・カテゴリ絞り込みと表示整形を担う純関数群。ユニットテストで直接検証する。 */
object DiagnosticLogContentBuilder {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.of("Asia/Tokyo"))

    /** 現在のログに現れているカテゴリを、出現順を保ちつつ重複なく列挙する。 */
    fun availableCategories(entries: List<DiagnosticLogEntry>): List<String> =
        entries.map { it.category }.distinct()

    /**
     * 検索語(部分一致・大文字小文字無視)とカテゴリ(複数選択、空なら全件)で絞り込み、
     * 新しいものが先頭に来る順で返す。
     */
    fun filter(
        entries: List<DiagnosticLogEntry>,
        query: String,
        selectedCategories: Set<String>,
    ): List<DiagnosticLogEntry> {
        val newestFirst = entries.asReversed()
        val trimmedQuery = query.trim()
        return newestFirst.filter { entry ->
            val categoryMatches = selectedCategories.isEmpty() || entry.category in selectedCategories
            val queryMatches = trimmedQuery.isEmpty() ||
                entry.message.contains(trimmedQuery, ignoreCase = true) ||
                entry.category.contains(trimmedQuery, ignoreCase = true)
            categoryMatches && queryMatches
        }
    }

    fun rows(filteredEntries: List<DiagnosticLogEntry>): List<DiagnosticLogRow> = filteredEntries.map { entry ->
        val time = timeFormatter.format(Instant.ofEpochMilli(entry.epochMillis))
        DiagnosticLogRow(
            timeLabel = time,
            category = entry.category,
            message = entry.message,
            copyText = "$time [${entry.category}] ${entry.message}",
        )
    }

    /** 「表示中をコピー」用に、絞り込み結果を1行ずつ連結する。 */
    fun formatForCopy(rows: List<DiagnosticLogRow>): String = rows.joinToString(separator = "\n") { it.copyText }

    /** バイト数をKB/MB表記に整形する。 */
    fun persistedBytesLabel(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1fKB".format(bytes / 1024.0)
        else -> "${bytes}B"
    }
}

/** ログ閲覧画面のFlowを集約するController。検索語・カテゴリ選択の状態も保持する。 */
class DiagnosticLogScreenController(
    private val diagnosticLog: DiagnosticLog,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(DiagnosticLogUiState())
    val state: StateFlow<DiagnosticLogUiState> = _state

    private val query = MutableStateFlow("")
    private val selectedCategories = MutableStateFlow<Set<String>>(emptySet())
    private val observationJob: Job

    init {
        observationJob = scope.launch {
            combine(
                diagnosticLog.entries,
                query,
                selectedCategories,
            ) { entries, currentQuery, currentSelection ->
                val availableCategories = DiagnosticLogContentBuilder.availableCategories(entries)
                // ログ消去等で消えたカテゴリの選択は無効化する。
                val effectiveSelection = currentSelection.filterTo(mutableSetOf()) { it in availableCategories }
                val filtered = DiagnosticLogContentBuilder.filter(entries, currentQuery, effectiveSelection)
                DiagnosticLogUiState(
                    initialized = true,
                    query = currentQuery,
                    availableCategories = availableCategories,
                    selectedCategories = effectiveSelection,
                    rows = DiagnosticLogContentBuilder.rows(filtered),
                    totalCount = entries.size,
                    persistedBytesLabel = DiagnosticLogContentBuilder.persistedBytesLabel(diagnosticLog.totalPersistedBytes()),
                )
            }.collect { newState -> _state.value = newState }
        }
    }

    fun updateQuery(value: String) {
        query.value = value
    }

    fun toggleCategory(category: String) {
        selectedCategories.value = selectedCategories.value.let { current ->
            if (category in current) current - category else current + category
        }
    }

    /** 表示中(絞り込み後)の内容をコピー用に整形して返す。副作用はない。 */
    fun formattedVisibleLog(): String = DiagnosticLogContentBuilder.formatForCopy(_state.value.rows)

    fun clear() {
        diagnosticLog.clear()
    }

    /** 共有用にエクスポートしたファイルを返す。永続化未対応(fileStore省略)ならnull。 */
    fun exportFile(): File? = diagnosticLog.exportToSingleFile()

    fun close() {
        observationJob.cancel()
        scope.coroutineContext[Job]?.cancel()
    }
}
