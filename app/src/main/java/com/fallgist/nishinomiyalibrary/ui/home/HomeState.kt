package com.fallgist.nishinomiyalibrary.ui.home

import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.repository.SyncLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** ホーム画面の全表示状態。認証情報は一切保持しない。 */
data class HomeUiState(
    val initialized: Boolean = false,
    val members: List<Member> = emptyList(),
    val selectedMemberId: Long? = null,
    val lastSyncText: String = "まだ同期していません",
    val lastSyncFailed: Boolean = false,
    val readyGroups: List<ReadyGroup> = emptyList(),
    val dueGroups: List<DueGroup> = emptyList(),
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
)

/** 同期由来のデータだけで構成する、ホームの内容部分。 */
data class HomeContent(
    val lastSyncText: String,
    val lastSyncFailed: Boolean,
    val readyGroups: List<ReadyGroup>,
    val dueGroups: List<DueGroup>,
)

/** うけとれる予約の1件。受取館は表示しないためタイトルと利用者のみ持つ。 */
data class ReadyItem(
    val memberName: String,
    val memberColorHex: String,
    val title: String,
    /** 書誌詳細リンク用。空文字列のときは遷移しない。 */
    val tilcod: String = "",
)

/** うけとれる予約を取置期限でまとめた1グループ。期限未定([undated])は末尾に置く。 */
data class ReadyGroup(
    val headerLabel: String,
    val undated: Boolean,
    val items: List<ReadyItem>,
)

data class HomeBook(
    val memberName: String,
    val memberColorHex: String,
    val title: String,
    val library: String,
    val overdue: Boolean,
    /** 書誌詳細リンク用。空文字列のときは遷移しない。 */
    val tilcod: String = "",
)

/**
 * 返却本を期限日でまとめた1グループ。
 * [expanded] が true のときは [books] を全冊表示し、false のときは [foldedSummary] だけを見せる。
 */
data class DueGroup(
    val headerLabel: String,
    val count: Int,
    val overdue: Boolean,
    val expanded: Boolean,
    val books: List<HomeBook>,
    val foldedSummary: String,
)

/**
 * リポジトリのドメイン値からホーム表示内容を組み立てる純関数。Android非依存でテストする。
 * 「期限切れ+7日以内は全冊展開、8日以上先は折りたたみ」というモックの規則をここで実装する。
 */
object HomeContentBuilder {
    private const val EXPAND_WINDOW_DAYS = 7L
    private const val FALLBACK_COLOR = "#6E675C"
    private val dateFormatter = DateTimeFormatter.ofPattern("M/d(E)", Locale.JAPANESE)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.JAPANESE)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.JAPANESE)
    private val tokyoZone: ZoneId = ZoneId.of("Asia/Tokyo")

    fun build(
        members: List<Member>,
        loans: List<Loan>,
        reservations: List<Reservation>,
        lastSync: SyncLog?,
        selectedMemberId: Long?,
        today: LocalDate,
    ): HomeContent {
        val activeIds = members.map { it.id }.toSet()
        val nameOf = members.associate { it.id to it.name }
        val colorOf = members.associate { it.id to it.colorHex }
        val orderOf = members.associate { it.id to it.sortOrder }

        fun visible(memberId: Long): Boolean =
            memberId in activeIds && (selectedMemberId == null || selectedMemberId == memberId)

        fun colorFor(memberId: Long) = colorOf[memberId]?.takeIf { it.isNotBlank() } ?: FALLBACK_COLOR
        fun nameFor(memberId: Long) = nameOf[memberId] ?: "?"
        fun orderFor(memberId: Long) = orderOf[memberId] ?: Int.MAX_VALUE

        val visibleReady = reservations.filter { it.state == ReservationState.READY && visible(it.memberId) }
        val readyGroups = buildReadyGroups(visibleReady, ::nameFor, ::colorFor, ::orderFor)

        val visibleLoans = loans.filter { visible(it.memberId) }
        val dueGroups = buildDueGroups(visibleLoans, ::nameFor, ::colorFor, ::orderFor, today)

        return HomeContent(
            lastSyncText = formatLastSync(lastSync, today),
            lastSyncFailed = lastSync?.succeeded == false,
            readyGroups = readyGroups,
            dueGroups = dueGroups,
        )
    }

    /**
     * うけとれる予約を取置期限でグループ化する。期限ありは日付昇順、期限未定は末尾。
     * 受取館は表示しない。グループ内は利用者の並び順→書名の順。
     */
    private fun buildReadyGroups(
        ready: List<Reservation>,
        nameFor: (Long) -> String,
        colorFor: (Long) -> String,
        orderFor: (Long) -> Int,
    ): List<ReadyGroup> {
        if (ready.isEmpty()) return emptyList()

        fun itemOf(reservation: Reservation) = ReadyItem(
            memberName = nameFor(reservation.memberId),
            memberColorHex = colorFor(reservation.memberId),
            title = reservation.title,
            tilcod = reservation.tilcod,
        )

        fun ordered(list: List<Reservation>) =
            list.sortedWith(compareBy({ orderFor(it.memberId) }, { it.title }))

        val groups = mutableListOf<ReadyGroup>()

        ready.filter { it.holdExpiryDate != null }
            .groupBy { it.holdExpiryDate!! }
            .toSortedMap()
            .forEach { (date, sameDate) ->
                groups += ReadyGroup(
                    headerLabel = "${dateFormatter.format(date)} まで",
                    undated = false,
                    items = ordered(sameDate).map(::itemOf),
                )
            }

        val undated = ready.filter { it.holdExpiryDate == null }
        if (undated.isNotEmpty()) {
            groups += ReadyGroup(
                headerLabel = "取置期限 未定",
                undated = true,
                items = ordered(undated).map(::itemOf),
            )
        }

        return groups
    }

    private fun buildDueGroups(
        loans: List<Loan>,
        nameFor: (Long) -> String,
        colorFor: (Long) -> String,
        orderFor: (Long) -> Int,
        today: LocalDate,
    ): List<DueGroup> {
        if (loans.isEmpty()) return emptyList()

        fun bookOf(loan: Loan, overdue: Boolean) = HomeBook(
            memberName = nameFor(loan.memberId),
            memberColorHex = colorFor(loan.memberId),
            title = loan.title,
            library = loan.lendingLibrary,
            overdue = overdue,
            tilcod = loan.tilcod,
        )

        fun foldedSummary(groupLoans: List<Loan>): String = groupLoans
            .groupBy { it.memberId }
            .entries
            .sortedBy { orderFor(it.key) }
            .joinToString("・") { "${nameFor(it.key)}${it.value.size}冊" }

        val groups = mutableListOf<DueGroup>()

        val overdue = loans.filter { it.dueDate.isBefore(today) }
            .sortedWith(compareBy({ it.dueDate }, { orderFor(it.memberId) }))
        if (overdue.isNotEmpty()) {
            groups += DueGroup(
                headerLabel = "期限切れ",
                count = overdue.size,
                overdue = true,
                expanded = true,
                books = overdue.map { bookOf(it, overdue = true) },
                foldedSummary = foldedSummary(overdue),
            )
        }

        loans.filter { !it.dueDate.isBefore(today) }
            .groupBy { it.dueDate }
            .toSortedMap()
            .forEach { (date, groupLoans) ->
                val ordered = groupLoans.sortedBy { orderFor(it.memberId) }
                groups += DueGroup(
                    headerLabel = dueHeaderLabel(date, today),
                    count = ordered.size,
                    overdue = false,
                    expanded = !date.isAfter(today.plusDays(EXPAND_WINDOW_DAYS)),
                    books = ordered.map { bookOf(it, overdue = false) },
                    foldedSummary = foldedSummary(ordered),
                )
            }

        return groups
    }

    private fun dueHeaderLabel(date: LocalDate, today: LocalDate): String {
        val formatted = dateFormatter.format(date)
        return when (date) {
            today -> "きょう $formatted"
            today.plusDays(1) -> "あす $formatted"
            else -> formatted
        }
    }

    private fun formatLastSync(log: SyncLog?, today: LocalDate): String {
        if (log == null) return "まだ同期していません"
        val startedAt = Instant.ofEpochMilli(log.startedAtEpochMillis).atZone(tokyoZone)
        val date = startedAt.toLocalDate()
        val time = timeFormatter.format(startedAt)
        val label = when (ChronoUnit.DAYS.between(date, today)) {
            0L -> "きょう $time"
            1L -> "きのう $time"
            else -> dateTimeFormatter.format(startedAt)
        }
        val suffix = if (log.succeeded == false) "(一部失敗)" else ""
        return "最終同期 $label$suffix"
    }
}
