package com.fallgist.nishinomiyalibrary.ui.reservations

import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelTarget
import com.fallgist.nishinomiyalibrary.domain.model.ReservationPickupSubmissionOrigin
import com.fallgist.nishinomiyalibrary.domain.model.ReservationPickupSubmissionRecord
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.ui.detail.BookDetailCancelTarget
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 予約中一覧の1行。受取可能は先頭に集める。 */
data class ReservationRow(
    val memberId: Long,
    val memberColorHex: String,
    val title: String,
    val isReady: Boolean,
    val statusLabel: String,
    /**
     * 受取館の表示名。サイトが未定かつアプリの送信記録も無い行ではnull(項目自体を出さない)。
     * サイトの値を最優先し、未定時だけアプリの送信記録で補う(`docs/ui-design.md`6番)。
     */
    val pickupLabel: String?,
    val queueLabel: String?,
    val holdExpiryLabel: String?,
    /** 書誌詳細リンク用。空文字列のときは遷移しない。 */
    val tilcod: String = "",
    /** 取消ボタン(yoykCancel)由来の予約コード。空文字列の行は取消不可(提供可能・移送中など)。 */
    val cancelCode: String = "",
) {
    /** 取消ボタン・チェックボックスを表示できる行か。取消にはtilcodとcancelCodeの両方が要る。 */
    val cancellable: Boolean get() = tilcod.isNotBlank() && cancelCode.isNotBlank()

    /** チェックボックス選択・取消対象の指定に使う一意キー。[cancellable]な行でのみ意味を持つ。 */
    val cancelKey: ReservationCancelKey get() = ReservationCancelKey(memberId, tilcod, cancelCode)
}

data class ReservationsUiState(
    val initialized: Boolean = false,
    val members: List<Member> = emptyList(),
    val selectedMemberId: Long? = null,
    val rows: List<ReservationRow> = emptyList(),
    /** 絞り込み選択に関係なく、メンバーごとの予約中冊数。 */
    val countByMemberId: Map<Long, Int> = emptyMap(),
    val totalCount: Int = 0,
)

/** 予約中の表示行を組み立てる純関数。受取可能→順番待ちの順に並べる。 */
object ReservationsContentBuilder {
    private const val FALLBACK_COLOR = "#6E675C"
    private val dateFormatter = DateTimeFormatter.ofPattern("M/d(E)", Locale.JAPANESE)

    fun build(
        members: List<Member>,
        reservations: List<Reservation>,
        selectedMemberId: Long?,
        /**
         * サイトの受取館が未定の行を補うアプリの送信記録(2026-08-05追加)。既定は空リストで、
         * 呼出し元を追随させなくても既存呼出しは壊れない([Reservation.pickupLibrary]は変更しない)。
         */
        pickupSubmissions: List<ReservationPickupSubmissionRecord> = emptyList(),
    ): List<ReservationRow> {
        val activeIds = members.map { it.id }.toSet()
        val colorOf = members.associate { it.id to it.colorHex }
        val orderOf = members.associate { it.id to it.sortOrder }
        // (memberId, tilcod) -> 送信記録。同じ組の記録が複数あることは想定しないため後勝ちでよい。
        val submissionOf = pickupSubmissions.associate { (it.memberId to it.tilcod) to it }

        val visible = reservations
            .filter { it.memberId in activeIds && (selectedMemberId == null || selectedMemberId == it.memberId) }

        val ready = visible.filter { it.state == ReservationState.READY }
            .sortedWith(compareBy(nullsLast<LocalDate>()) { it.holdExpiryDate })
        val others = visible.filter { it.state != ReservationState.READY }
            .sortedWith(
                compareBy(
                    { it.queuePosition ?: Int.MAX_VALUE },
                    { orderOf[it.memberId] ?: Int.MAX_VALUE },
                ),
            )

        return (ready + others).map { reservation ->
            ReservationRow(
                memberId = reservation.memberId,
                memberColorHex = colorOf[reservation.memberId]?.takeIf { it.isNotBlank() } ?: FALLBACK_COLOR,
                title = reservation.title,
                isReady = reservation.state == ReservationState.READY,
                statusLabel = statusLabel(reservation.state),
                pickupLabel = resolvePickupLabel(reservation, submissionOf),
                queueLabel = reservation.queuePosition?.let { "予約順位 ${it}番目" },
                // 提供可能でもEmail連絡前はサイト側で取置期限が未設定のため、受取可能行では「未定」を明示する
                holdExpiryLabel = reservation.holdExpiryDate?.let { "取置期限 ${dateFormatter.format(it)} まで" }
                    ?: "取置期限 未定".takeIf { reservation.state == ReservationState.READY },
                tilcod = reservation.tilcod,
                cancelCode = reservation.cancelCode,
            )
        }
    }

    /**
     * チェック選択済みキーに対応する行だけを、取消候補へ変換する純関数。
     * 取消不可の行(cancelCodeまたはtilcodが空)は、選択されていても含めない。
     */
    fun cancelCandidates(rows: List<ReservationRow>, selectedKeys: Set<ReservationCancelKey>): List<ReservationCancelCandidate> =
        rows.filter { it.cancellable && it.cancelKey in selectedKeys }
            .map { row ->
                ReservationCancelCandidate(
                    target = ReservationCancelTarget(row.memberId, row.tilcod, row.cancelCode),
                    title = row.title,
                )
            }

    /**
     * 予約中一覧の行から書誌詳細を開く際に渡す取消対象を組み立てる純関数(経路3)。
     * [ReservationRow.cancellable]がfalseの行(cancelCodeまたはtilcodが空。提供可能・移送中など)では
     * nullを返し、書誌詳細に取消ボタンを表示させない。
     */
    fun cancelTargetForDetail(row: ReservationRow): BookDetailCancelTarget? =
        if (row.cancellable) BookDetailCancelTarget(memberId = row.memberId, cancelCode = row.cancelCode) else null

    /** 絞り込み前の全冊数を、メンバーごと・合計で集計する純関数。0件のメンバーはmapに含めない。 */
    fun countByMember(members: List<Member>, reservations: List<Reservation>): Map<Long, Int> {
        val activeIds = members.map { it.id }.toSet()
        return reservations.filter { it.memberId in activeIds }.groupingBy { it.memberId }.eachCount()
    }

    private fun statusLabel(state: ReservationState): String = when (state) {
        ReservationState.READY -> "受取可能"
        ReservationState.WAITING -> "順番待ち"
        ReservationState.UNKNOWN -> "状態不明"
        // 12回目のライブ取消＋一覧観測(2026-07-28)で確定。以前はUNKNOWN(状態不明)扱いだった状態を
        // 区別できるようになっただけで、一覧への表示可否(フィルタ)は変えていない。
        ReservationState.CANCELLED -> "取消済み"
        ReservationState.IN_TRANSIT -> "移送中"
    }

    /**
     * 受取館表示を解決する純関数(`docs/ui-design.md`「方針: 一覧画面の行レイアウト統一」6番、表示規則1〜5)。
     * 1. サイトの受取館が実館名 → その値を最優先(書き換えない)
     * 2. サイトが未定かつ記録あり・`CONFIRMED_SUBMISSION` → 記録の館コードに対応する館名
     * 3. サイトが未定かつ記録あり・`UNVERIFIED_SUBMISSION` → 「○○館（未確認）」
     *    (POST後の成否が確認できていない記録のため、その予約行が本当にアプリの送信で
     *    作られた保証が無い。館名だけを出すと不確実な値を確定値として断定することになるため、
     *    未確認であることを表示に残す。ここで項目を消してはならない)
     * 4. サイトが未定かつ記録なし(ブラウザ予約・記録機能より前の旧データ) → null(項目を出さない)
     * 5. 記録の館コードが対応表に無い(未知コード) → null(項目を出さない。館名を捏造しない)
     */
    private fun resolvePickupLabel(
        reservation: Reservation,
        submissionOf: Map<Pair<Long, String>, ReservationPickupSubmissionRecord>,
    ): String? {
        val sitePickup = reservation.pickupLibrary.takeIf { it.isNotBlank() }
        if (sitePickup != null) return sitePickup
        val submission = submissionOf[reservation.memberId to reservation.tilcod] ?: return null
        val libraryName = Library.ALL_LIBRARIES.find { it.code == submission.pickupLibraryCode }?.name ?: return null
        return when (submission.origin) {
            ReservationPickupSubmissionOrigin.CONFIRMED_SUBMISSION -> libraryName
            ReservationPickupSubmissionOrigin.UNVERIFIED_SUBMISSION -> "$libraryName（未確認）"
        }
    }
}

/** 予約中画面のFlowを集約するController。 */
class ReservationsScreenController(
    private val familyRepository: FamilyRepository,
    private val statusRepository: StatusRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(ReservationsUiState())
    val state: StateFlow<ReservationsUiState> = _state

    private val selectedMemberId = MutableStateFlow<Long?>(null)
    private val observationJob: Job

    init {
        observationJob = scope.launch {
            combine(
                familyRepository.members(),
                statusRepository.reservations(),
                statusRepository.pickupSubmissions(),
                selectedMemberId,
            ) { members, reservations, pickupSubmissions, selectedId ->
                val effectiveSelection = selectedId?.takeIf { id -> members.any { it.id == id } }
                val countByMemberId = ReservationsContentBuilder.countByMember(members, reservations)
                ReservationsUiState(
                    initialized = true,
                    members = members,
                    selectedMemberId = effectiveSelection,
                    rows = ReservationsContentBuilder.build(members, reservations, effectiveSelection, pickupSubmissions),
                    countByMemberId = countByMemberId,
                    totalCount = countByMemberId.values.sum(),
                )
            }.collect { newState -> _state.value = newState }
        }
    }

    fun selectMember(memberId: Long?) {
        selectedMemberId.value = memberId
    }

    fun close() {
        observationJob.cancel()
        scope.coroutineContext[Job]?.cancel()
    }
}
