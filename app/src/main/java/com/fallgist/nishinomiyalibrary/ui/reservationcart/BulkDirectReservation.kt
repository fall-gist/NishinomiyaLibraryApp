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
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
import com.fallgist.nishinomiyalibrary.ui.components.MemberDot
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

/**
 * 一斉直接予約(`docs/design/bulk-selection-followup.md` §5、機能F)の確認ダイアログの状態。
 * 対象1件の表示形は一斉カート追加と同じ([BulkCartAdditionCandidate]をそのまま使う)。
 * メンバー・受取館とも初期選択は行わない方が安全だが、受取館は既定館(`SettingsStore.defaultCalendarLibrary`)
 * を初期値にする(§5.3)。メンバーは誤ったメンバーの予約になる事故を避けるため初期選択しない(§7.2と同じ判断)。
 */
data class BulkDirectReservationConfirmationRequest(
    val candidates: List<BulkCartAdditionCandidate>,
    val selectedMemberId: Long? = null,
    val pickupLibraryCode: String = "",
) {
    /** メンバー・受取館が選ばれ、対象が1件以上あるときだけ実行できる(§5.3)。 */
    val canConfirm: Boolean
        get() = selectedMemberId != null && candidates.isNotEmpty() && pickupLibraryCode.isNotBlank()
}

/** 一斉直接予約の確認済みリクエストからRepositoryへ渡す対象を組み立てる純関数。 */
object BulkDirectReservationContentBuilder {
    fun targets(request: BulkDirectReservationConfirmationRequest): List<ReservationTarget> {
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
 * 一斉直接予約の確認ダイアログ(§5.3)。蔵書検索・新着資料の両画面で同じものを使い回す。
 * メンバー選択は[BulkCartAdditionConfirmDialog]と同じチップUI、受取館選択は既存の
 * [PickupLibrarySelector]を使う。**この操作はサイトに実データを作る、取り返しのつかない操作である**
 * (`docs/backend-design.md`の予約に関する不変条件どおり、確認の厳しさを緩めない)。
 */
@Composable
fun BulkDirectReservationConfirmDialog(
    request: BulkDirectReservationConfirmationRequest,
    members: List<Member>,
    libraries: List<Library>,
    onSelectMember: (Long) -> Unit,
    onSelectPickupLibrary: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    val selectedMemberName = members.find { it.id == request.selectedMemberId }?.name
    val pickupLibraryName = libraries.find { it.code == request.pickupLibraryCode }?.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("予約しますか？") },
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
                PickupLibrarySelector(
                    libraries = libraries,
                    selectedCode = request.pickupLibraryCode,
                    onSelect = onSelectPickupLibrary,
                    enabled = true,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (selectedMemberName != null && pickupLibraryName != null) {
                        "${selectedMemberName}の分として${request.candidates.size}件を${pickupLibraryName}受取で予約します"
                    } else {
                        "メンバーと受取館を選択してください"
                    },
                    color = colors.ink,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "この操作は図書館サイトに実際の予約を作成します。取り消しは予約中一覧から行ってください。",
                    color = colors.alert,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = request.canConfirm) { Text("予約する") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("戻る") } },
    )
}
