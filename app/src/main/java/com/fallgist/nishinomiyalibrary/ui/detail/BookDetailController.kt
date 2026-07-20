package com.fallgist.nishinomiyalibrary.ui.detail

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.domain.model.Holding
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReadingInfo
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReadingRecordRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SearchRepository
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 書誌詳細の既読情報1行(「たろう 2024/11 に貸出(高須分室)」)。 */
data class DetailReadRow(
    val memberName: String,
    val memberColorHex: String,
    val description: String,
)

/** 書誌詳細ビューの表示状態。[open] が false のときは非表示。 */
data class BookDetailUiState(
    val open: Boolean = false,
    val tilcod: String = "",
    val title: String = "",
    val loading: Boolean = true,
    val errorMessage: String? = null,
    /** サイトの詳細情報テーブルの表示順そのまま(書名・タイトルコードは除外)。 */
    val fields: List<Pair<String, String>> = emptyList(),
    val coverUrl: String? = null,
    /** 在庫数>0 を貸出可として表示する。 */
    val lendable: Boolean? = null,
    val readRows: List<DetailReadRow> = emptyList(),
    val holdings: List<Holding> = emptyList(),
)

/** 書誌詳細の既読情報・詳細項目を組み立てる純関数。 */
object BookDetailContentBuilder {
    private const val FALLBACK_COLOR = "#6E675C"
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy/M", Locale.JAPANESE)
    private val excludedDetailFields = setOf("書名", "書名ヨミ", "タイトルコード")

    fun detailReadRows(members: List<Member>, infos: List<ReadingInfo>): List<DetailReadRow> =
        infos
            .groupBy { it.memberId }
            .map { (_, records) -> records.maxBy { it.loanDate } }
            .sortedBy { it.loanDate }
            .map { info ->
                val member = members.find { it.id == info.memberId }
                DetailReadRow(
                    memberName = member?.name ?: "?",
                    memberColorHex = member?.colorHex?.takeIf { it.isNotBlank() } ?: FALLBACK_COLOR,
                    description = "${monthFormatter.format(info.loanDate)} に貸出(${info.library})",
                )
            }

    fun detailFields(fields: Map<String, String>): List<Pair<String, String>> =
        fields.filterKeys { it !in excludedDetailFields }.toList()
}

/**
 * 全画面共通の書誌詳細Controller。tilcod を持つどの一覧からでも [open] で表示できる。
 * 書誌情報・表紙・既読情報を公式サイト/openBD/ローカル読書記録から都度取得する。
 */
class BookDetailController(
    private val searchRepository: SearchRepository,
    private val readingRecordRepository: ReadingRecordRepository,
    familyRepository: FamilyRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(BookDetailUiState())
    val state: StateFlow<BookDetailUiState> = _state

    private val members = MutableStateFlow<List<Member>>(emptyList())
    private var detailJob: Job? = null

    init {
        scope.launch {
            familyRepository.members().collect { members.value = it }
        }
    }

    fun open(tilcod: String, title: String) {
        if (tilcod.isBlank()) return
        detailJob?.cancel()
        _state.value = BookDetailUiState(open = true, tilcod = tilcod, title = title, loading = true)
        detailJob = scope.launch {
            try {
                val detail = searchRepository.bookDetail(tilcod)
                val coverUrl = detail.isbn?.let { isbn ->
                    try {
                        searchRepository.coverUrl(isbn)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        null
                    }
                }
                val readRows = BookDetailContentBuilder.detailReadRows(
                    members.value,
                    readingRecordRepository.hasRead(tilcod).first(),
                )
                _state.value = BookDetailUiState(
                    open = true,
                    tilcod = detail.tilcod,
                    title = detail.fields["書名"] ?: title,
                    loading = false,
                    fields = BookDetailContentBuilder.detailFields(detail.fields),
                    coverUrl = coverUrl,
                    lendable = detail.availableCount > 0,
                    readRows = readRows,
                    holdings = detail.holdings,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _state.value = BookDetailUiState(
                    open = true,
                    tilcod = tilcod,
                    title = title,
                    loading = false,
                    errorMessage = errorMessage(exception),
                )
            }
        }
    }

    fun close() {
        detailJob?.cancel()
        _state.value = BookDetailUiState(open = false)
    }

    fun release() {
        scope.coroutineContext[Job]?.cancel()
    }

    private fun errorMessage(exception: Exception): String = when (exception) {
        is LibraryError.Maintenance -> "図書館システムはメンテナンス中です。時間をおいて再試行してください"
        is LibraryError.Parse -> "書誌詳細を読み取れませんでした。サイト改修の可能性があります"
        is LibraryError.Network -> "通信に失敗しました。接続状況を確認してください"
        else -> "書誌詳細を取得できませんでした。時間をおいて再試行してください"
    }
}
