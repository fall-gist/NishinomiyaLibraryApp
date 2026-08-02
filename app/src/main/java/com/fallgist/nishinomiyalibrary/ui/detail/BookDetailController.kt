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

/**
 * 予約中一覧(経路3)から開いた書誌詳細にだけ渡される、取消ボタン表示用の対象データ。
 *
 * 設計判断(docs/ui-design.md「方針: 予約取消の導線」経路3): 取消ボタンの出し分けは
 * [com.fallgist.nishinomiyalibrary.ui.app.Destination] による分岐ではなく、[BookDetailController.open]
 * の呼び出し元(経路)がこの値を渡すかどうかで行う。理由は2つ:
 * 1. Destinationはナビゲーション用のprivate enumであり、ui.detail(共通オーバーレイ)から参照させると
 *    ナビゲーション実装への逆依存が生まれる。
 * 2. 呼び出し元(ReservationsScreen)は既にどの行がcancellable(tilcod・cancelCodeとも非空)かを
 *    知っているため、そこでnull/非nullを決めれば「経路3以外はnull」も「cancelCode空はnull」も
 *    同じ1箇所(ReservationsContentBuilder.cancelTargetForDetail)で表現できる。
 *
 * ui.reservations の型([com.fallgist.nishinomiyalibrary.ui.reservations.ReservationCancelCandidate]など)
 * には依存しない。ui.reservations側がui.detailに依存する向き(一覧→共通詳細オーバーレイ)は既存だが、
 * 逆向きの依存(共通オーバーレイ→個別画面)は避けるため、ここでは最小限のフィールドだけを持つ。
 */
data class BookDetailCancelTarget(
    val memberId: Long,
    val cancelCode: String,
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
    /** 詳細取得に成功したときだけセットする、サイト上の予約順番待ち人数。 */
    val reservationCount: Int? = null,
    val readRows: List<DetailReadRow> = emptyList(),
    val holdings: List<Holding> = emptyList(),
    /** 非nullのときだけ取消ボタンを表示する。予約中一覧(経路3)から開いたcancellableな行のみ設定される。 */
    val cancelTarget: BookDetailCancelTarget? = null,
    /**
     * trueのときだけ予約セクション(見出し「予約」・メンバー選択・受取館選択・
     * 「カートへ追加」「今すぐ予約」ボタン等の全体)を非表示にする。
     *
     * 既にすでに予約済み・貸出中の資料を見ているホーム画面(「うけとれる予約」「返す本」)・
     * 貸出中一覧・予約中一覧から開いた書誌詳細では、その資料を改めて予約する導線は不要なため
     * このフラグを立てる。それ以外の経路(蔵書検索・新着・読書記録・本棚・予約カート等)では
     * 従来どおり予約セクションを表示するため、既定値はfalse(表示する)。
     *
     * [cancelTarget](取消ボタンの表示可否)とは独立した概念であり、どちらか一方の値からもう
     * 一方を導出できない。例えば予約中一覧(経路3)から開いた場合はこのフラグはtrueだが、
     * cancelTargetはcancelCodeが空でない限りは非nullになる。
     */
    val reservationSectionHiddenAsAlreadyReservedOrOnLoan: Boolean = false,
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

    /** 書誌詳細の表示項目から、予約カートに添える著者行を取り出す。 */
    fun writerLine(fields: List<Pair<String, String>>): String? =
        fields.firstOrNull { (label, _) -> label.contains("著者") || label.contains("作者") }
            ?.second
            ?.takeIf { it.isNotBlank() }

    /**
     * 取消確認の確定と同時に、開いたままの書誌詳細ポップアップを閉じるべきかを判定する純関数。
     *
     * 設計判断(docs/ui-design.md「方針: 予約取消の導線」経路3、"取消成立後の書誌詳細ポップアップ"):
     * 結果(成功/成否不明/失敗)を待たず、確認ダイアログの肯定操作と同時に閉じる。理由:
     * - 取消結果は[com.fallgist.nishinomiyalibrary.ui.reservations.ReservationCancelResultRow]に
     *   tilcodを持たないため、結果を書誌詳細側の対象と突き合わせる手段がない(タイトルが同名の別資料と
     *   区別できない)。
     * - 取消は不可逆な操作であり、確定した時点で対象は速やかにローカルDBから削除され一覧から消える。
     *   確定後に詳細を開いたまま結果を待たせても、利用者にとって有用な情報は増えない
     *   (結果は一覧側のReservationCancelResultsDialogで確認できる)。
     * - 確認ダイアログはUI上モーダルであり、書誌詳細ポップアップが開いている間は他経路(1・2)からの
     *   確認を同時に開始できない。したがって「書誌詳細にcancelTargetが設定されている」ことは、
     *   今回の確認が経路3由来であることの十分条件になる。
     */
    fun shouldCloseDetailAfterCancelConfirm(cancelTarget: BookDetailCancelTarget?): Boolean = cancelTarget != null
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

    /** 予約中一覧以外(検索結果・新着・本棚・貸出中・読書記録・予約カート等)からの起動。取消ボタンは出さない。 */
    fun open(tilcod: String, title: String) = open(tilcod, title, cancelTarget = null)

    /**
     * [cancelTarget]が非nullのときだけ取消ボタンを表示する起動。予約セクションの表示可否は
     * 別途[hideReservationSectionAsAlreadyReservedOrOnLoan]で指定する(既定は表示のまま)。
     * 呼び出し元([com.fallgist.nishinomiyalibrary.ui.reservations.ReservationsContentBuilder.cancelTargetForDetail])が
     * 取消不可の行(cancelCode空)ではnullを渡すため、ここでは受け取った値をそのまま状態へ載せるだけでよい。
     */
    fun open(
        tilcod: String,
        title: String,
        cancelTarget: BookDetailCancelTarget?,
        hideReservationSectionAsAlreadyReservedOrOnLoan: Boolean = false,
    ) {
        if (tilcod.isBlank()) return
        detailJob?.cancel()
        _state.value = BookDetailUiState(
            open = true,
            tilcod = tilcod,
            title = title,
            loading = true,
            cancelTarget = cancelTarget,
            reservationSectionHiddenAsAlreadyReservedOrOnLoan = hideReservationSectionAsAlreadyReservedOrOnLoan,
        )
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
                    reservationCount = detail.reservationCount,
                    readRows = readRows,
                    holdings = detail.holdings,
                    cancelTarget = cancelTarget,
                    reservationSectionHiddenAsAlreadyReservedOrOnLoan = hideReservationSectionAsAlreadyReservedOrOnLoan,
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
                    cancelTarget = cancelTarget,
                    reservationSectionHiddenAsAlreadyReservedOrOnLoan = hideReservationSectionAsAlreadyReservedOrOnLoan,
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
