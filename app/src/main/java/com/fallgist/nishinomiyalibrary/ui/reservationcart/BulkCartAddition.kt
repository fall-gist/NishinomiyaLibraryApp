package com.fallgist.nishinomiyalibrary.ui.reservationcart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartAddSummary
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
import com.fallgist.nishinomiyalibrary.ui.components.MemberDot
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

/**
 * 一斉カート追加(`docs/design/bulk-selection.md` §7、機能D)の対象1件のUI表示用データ。
 * 蔵書検索・新着資料の両方から同じ形で組み立てる(§7.4)。詳細(bookDetail)は取りに行かない。
 */
data class BulkCartAdditionCandidate(
    val tilcod: String,
    val title: String,
    val writerLine: String?,
)

/**
 * 一斉カート追加の確認ダイアログの状態。メンバー選択を兼ねる(§7.2)ため選択中のメンバーIDを保持する。
 * 初期選択は行わない(誤ったメンバーのカートへ入る事故を避けるため、常にnullから始まる)。
 */
data class BulkCartAdditionConfirmationRequest(
    val candidates: List<BulkCartAdditionCandidate>,
    val selectedMemberId: Long? = null,
) {
    /** メンバーが選ばれ、対象が1件以上あるときだけ実行できる(§7.2「メンバー未選択では実行ボタンを無効にする」)。 */
    val canConfirm: Boolean get() = selectedMemberId != null && candidates.isNotEmpty()
}

/** 一斉カート追加の表示文言・Repository入力の組み立て。Android非依存でテストする。 */
object BulkCartAdditionContentBuilder {
    /**
     * 結果文言(§7.3)。「5件をカートへ追加しました」／重複ありなら
     * 「3件をカートへ追加しました（2件は既にカートにあります）」。
     * skippedの理由(重複・存在しないメンバー)は区別しない(§10-2)。UI経由では在籍メンバーからしか
     * 選べないため、実際にはskippedは重複だけが原因になる。
     */
    fun resultMessage(summary: ReservationCartAddSummary): String =
        if (summary.skipped > 0) {
            "${summary.added}件をカートへ追加しました（${summary.skipped}件は既にカートにあります）"
        } else {
            "${summary.added}件をカートへ追加しました"
        }

    /** 確認済みリクエストから、Repositoryへ渡す[ReservationTarget]一覧を組み立てる。 */
    fun targets(request: BulkCartAdditionConfirmationRequest): List<ReservationTarget> {
        val memberId = requireNotNull(request.selectedMemberId) { "メンバーが未選択です" }
        return request.candidates.map { candidate ->
            ReservationTarget(
                cartItemId = null,
                memberId = memberId,
                tilcod = candidate.tilcod,
                title = candidate.title,
                writerLine = candidate.writerLine,
            )
        }
    }
}

/**
 * 一斉カート追加の確認ダイアログ(§7.2)。検索・新着資料の両画面で同じものを使い回す。
 * メンバーのチップ(書誌詳細の予約セクションと同じ見た目。[MemberDot] + 名前)を並べて1人選ばせる。
 */
@Composable
fun BulkCartAdditionConfirmDialog(
    request: BulkCartAdditionConfirmationRequest,
    members: List<Member>,
    onSelectMember: (Long) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    val selectedMemberName = members.find { it.id == request.selectedMemberId }?.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("カートへ追加しますか？") },
        text = {
            Column {
                Text("対象メンバーを選んでください", color = colors.ink2, fontSize = 12.sp)
                LazyRow(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(members) { member ->
                        val selected = member.id == request.selectedMemberId
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) colors.green else colors.chipBg)
                                .clickable { onSelectMember(member.id) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MemberDot(member.colorHex, size = 7.dp)
                            Text(member.name, color = if (selected) Color.White else colors.ink2, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = selectedMemberName?.let { "${it}の分として${request.candidates.size}件をカートへ追加します" }
                        ?: "メンバーを選択してください",
                    color = colors.ink,
                    fontSize = 13.sp,
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = request.canConfirm) { Text("追加する") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("戻る") } },
    )
}
