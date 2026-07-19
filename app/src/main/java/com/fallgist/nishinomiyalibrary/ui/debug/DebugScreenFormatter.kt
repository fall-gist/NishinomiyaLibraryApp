package com.fallgist.nishinomiyalibrary.ui.debug

import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.model.UserSummary
import com.fallgist.nishinomiyalibrary.domain.repository.SyncLog
import com.fallgist.nishinomiyalibrary.domain.repository.SyncResult
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DebugScreenDisplay(
    val memberLines: List<String> = emptyList(),
    val loanLines: List<String> = emptyList(),
    val reservationLines: List<String> = emptyList(),
    val shelfLines: List<String> = emptyList(),
    val summaryLines: List<String> = emptyList(),
    val readingRecordCount: Int = 0,
    val readingRecordLines: List<String> = emptyList(),
    val lastSyncLine: String = "最終同期: まだ同期されていません",
)

/** DBのドメイン値を、識別子や秘密情報を含まない表示文字列へ変換する。 */
object DebugScreenFormatter {
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    private val tokyoZone = ZoneId.of("Asia/Tokyo")

    fun format(
        members: List<Member>,
        loans: List<Loan>,
        reservations: List<Reservation>,
        shelvesByMember: Map<Long, List<ShelfItem>>,
        summaries: List<UserSummary>,
        lastSync: SyncLog?,
    ): DebugScreenDisplay {
        val names = members.associate { it.id to it.name }
        return DebugScreenDisplay(
            memberLines = members.map { member ->
                "表示名: ${member.name}\n識別色: ${member.colorHex}"
            },
            loanLines = loans.map { loan ->
                "${memberName(names, loan.memberId)}\n${loan.title}\n返却期限: ${loan.dueDate}\n状態: ${loan.status}"
            },
            reservationLines = reservations.map { reservation ->
                buildString {
                    append(memberName(names, reservation.memberId))
                    append('\n').append(reservation.title)
                    append("\n状態: ").append(reservationState(reservation.state))
                    append("\n予約日: ").append(reservation.reservedDate)
                    append("\n受取館: ").append(reservation.pickupLibrary)
                    reservation.queuePosition?.let { append("\n順番: ").append(it) }
                    reservation.holdExpiryDate?.let { append("\n取置期限: ").append(it) }
                }
            },
            shelfLines = members.flatMap { member ->
                shelvesByMember[member.id].orEmpty().map { item ->
                    buildString {
                        append(member.name)
                        append("\n本棚: ").append(item.shelfName)
                        append('\n').append(item.title)
                        append("\n登録日: ").append(item.registeredDate)
                        if (item.memo.isNotBlank()) append("\nメモ: ").append(item.memo)
                    }
                }
            },
            summaryLines = summaries.map { summary ->
                "${memberName(names, summary.memberId)}\n貸出: ${summary.loanCount}件 / 予約: ${summary.reservationCount}件 / 本棚: ${summary.shelfCount}件 / カート: ${summary.cartCount}件"
            },
            lastSyncLine = formatLastSync(lastSync),
        )
    }

    fun formatManualSyncResult(result: SyncResult): String = when (result) {
        is SyncResult.Completed -> when {
            result.failedMemberCount == 0 -> "同期が完了しました（${result.syncedMemberCount}人）"
            else -> "同期が完了しました（一部失敗: ${result.failedMemberCount}人、成功: ${result.syncedMemberCount}人）"
        }

        is SyncResult.SkippedCooldown -> "同期は待機中です。しばらくしてから再試行してください"
    }

    fun syncFailureMessage(): String = "同期に失敗しました。通信状況を確認して再試行してください"

    fun scheduleFailureMessage(): String = "自動同期の設定に失敗しました。次回画面を開いたときに再試行します"

    /** 検索結果には利用者名と記録情報だけを表示し、認証情報は含めない。 */
    fun formatReadingRecords(records: List<ReadingRecord>, members: List<Member>): List<String> {
        val names = members.associate { it.id to it.name }
        return records.map { record ->
            "${memberName(names, record.memberId)}\n${record.title}\n貸出日: ${record.loanDate}\n貸出館: ${record.library}"
        }
    }

    private fun formatLastSync(log: SyncLog?): String {
        if (log == null) return "最終同期: まだ同期されていません"
        val startedAt = Instant.ofEpochMilli(log.startedAtEpochMillis).atZone(tokyoZone).format(timeFormatter)
        val trigger = when (log.trigger) {
            SyncTrigger.MANUAL -> "手動"
            SyncTrigger.SCHEDULED -> "自動"
        }
        val result = when (log.succeeded) {
            true -> "成功"
            false -> "一部または全件失敗"
            null -> "実行中"
        }
        return "最終同期: $startedAt\n種別: $trigger\n結果: $result"
    }

    private fun memberName(names: Map<Long, String>, memberId: Long): String =
        names[memberId] ?: "不明なメンバー"

    private fun reservationState(state: ReservationState): String = when (state) {
        ReservationState.WAITING -> "順番待ち"
        ReservationState.READY -> "受取可能"
        ReservationState.UNKNOWN -> "不明"
    }
}
