package com.fallgist.nishinomiyalibrary.ui.reservations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelTarget
import com.fallgist.nishinomiyalibrary.ui.components.EmptyNote
import com.fallgist.nishinomiyalibrary.ui.components.MemberDot
import com.fallgist.nishinomiyalibrary.ui.components.MemberFilterRow
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

@Composable
fun ReservationsScreen(
    state: ReservationsUiState,
    cancelState: ReservationCancelUiState,
    onSelectMember: (Long?) -> Unit,
    onOpenMenu: () -> Unit,
    onOpenDetail: (tilcod: String, title: String) -> Unit,
    onToggleSelection: (ReservationCancelKey) -> Unit,
    onRequestSingleCancel: (ReservationCancelCandidate) -> Unit,
    onRequestBulkCancel: (List<ReservationCancelCandidate>) -> Unit,
    onConfirmCancel: () -> Unit,
    onDismissCancelConfirmation: () -> Unit,
    onClearCancelResults: () -> Unit,
    onClearCancelError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Column(modifier = modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "予約中", onOpenMenu = onOpenMenu)
        MemberFilterRow(
            members = state.members,
            selectedMemberId = state.selectedMemberId,
            onSelect = onSelectMember,
            countByMemberId = state.countByMemberId,
            totalCount = state.totalCount,
        )
        if (state.rows.isEmpty()) {
            EmptyNote("予約中の本はありません")
        } else {
            BulkCancelBar(
                selectedCount = cancelState.selectedKeys.size,
                enabled = cancelState.canCancelSelection,
                onClick = {
                    val candidates = ReservationsContentBuilder.cancelCandidates(state.rows, cancelState.selectedKeys)
                    onRequestBulkCancel(candidates)
                },
            )
            cancelState.errorMessage?.let {
                Text(
                    text = it,
                    color = colors.alert,
                    fontSize = 12.sp,
                    // タップで消せるようにする(タップ以外の自動クリア手段が無いため)。
                    modifier = Modifier
                        .clickable(onClick = onClearCancelError)
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
            ) {
                items(state.rows) { row ->
                    ReservationRowView(
                        row = row,
                        selected = row.cancellable && row.cancelKey in cancelState.selectedKeys,
                        selectionEnabled = !cancelState.processing,
                        onClick = { onOpenDetail(row.tilcod, row.title) },
                        onToggleSelection = { onToggleSelection(row.cancelKey) },
                        onRequestCancel = {
                            onRequestSingleCancel(
                                ReservationCancelCandidate(
                                    target = ReservationCancelTarget(row.memberId, row.tilcod, row.cancelCode),
                                    title = row.title,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
    cancelState.pendingConfirmation?.let { request ->
        ReservationCancelConfirmDialog(
            request = request,
            members = cancelState.members,
            onConfirm = onConfirmCancel,
            onDismiss = onDismissCancelConfirmation,
        )
    }
    if (cancelState.results.isNotEmpty()) {
        ReservationCancelResultsDialog(state = cancelState, onClose = onClearCancelResults)
    }
}

@Composable
private fun BulkCancelBar(selectedCount: Int, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = colors.alert, contentColor = colors.card),
        ) {
            Text(if (selectedCount > 0) "一斉取消（${selectedCount}件）" else "一斉取消")
        }
    }
}

@Composable
private fun ReservationRowView(
    row: ReservationRow,
    selected: Boolean,
    selectionEnabled: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onRequestCancel: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (row.isReady) colors.greenBg else colors.card)
            .border(
                1.dp,
                if (row.isReady) colors.green.copy(alpha = 0.3f) else colors.line,
                RoundedCornerShape(14.dp),
            ),
        verticalAlignment = Alignment.Top,
    ) {
        // チェックボックスは独立したタップ領域を持つ。行タップ(書誌詳細を開く)を誤発火させない。
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggleSelection() },
            enabled = row.cancellable && selectionEnabled,
            colors = CheckboxDefaults.colors(checkedColor = colors.alert),
            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                // 行タップは書誌詳細への遷移。チェックボックスとは別モディファイアなので領域が競合しない。
                .clickable(enabled = row.tilcod.isNotBlank(), onClick = onClick)
                .padding(end = 14.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = row.statusLabel,
                    color = if (row.isReady) colors.greenInk else colors.ink2,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MemberDot(row.memberColorHex)
                    Text(text = row.memberName, color = colors.ink2, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = row.title,
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "受取館 ${row.pickupLabel}", color = colors.ink2, fontSize = 11.sp)
                row.queueLabel?.let { Text(text = it, color = colors.ink2, fontSize = 11.sp) }
            }
            row.holdExpiryLabel?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    color = if (row.isReady) colors.alert else colors.ink2,
                    fontSize = 11.sp,
                    fontWeight = if (row.isReady) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            // 取消ボタンは取消可能な行(cancelCodeが非空)だけに出す。無効化ではなく非表示にする。
            if (row.cancellable) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "取消",
                    color = colors.alert,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onRequestCancel)
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                )
            }
        }
    }
}

@Composable
fun ReservationCancelConfirmDialog(
    request: ReservationCancelConfirmationRequest,
    members: List<Member>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    val notice = "取り消した予約は元に戻せません。この操作の後、図書館サイトへ取消を送信します。"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val title = when (request) {
                is ReservationCancelConfirmationRequest.Single -> "予約を取り消しますか？"
                is ReservationCancelConfirmationRequest.Bulk -> "選択した${request.candidates.size}件の予約を取り消しますか？"
            }
            Text(title)
        },
        text = {
            when (request) {
                is ReservationCancelConfirmationRequest.Single -> {
                    val candidate = request.candidates.single()
                    val memberName = members.find { it.id == candidate.target.memberId }?.name ?: "不明なメンバー"
                    Text("資料名：${candidate.title}\nメンバー：$memberName\n\n$notice")
                }

                is ReservationCancelConfirmationRequest.Bulk -> {
                    Column {
                        LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                            request.candidates.groupBy { it.target.memberId }.forEach { (memberId, group) ->
                                item(key = "member-$memberId") {
                                    val memberName = members.find { it.id == memberId }?.name ?: "不明なメンバー"
                                    Text(
                                        text = memberName,
                                        color = colors.ink,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                                    )
                                }
                                items(group, key = { it.key.toString() }) { candidate ->
                                    Text(
                                        text = "・${candidate.title}",
                                        color = colors.ink,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(bottom = 2.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(notice, color = colors.ink2, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = colors.alert, contentColor = colors.card),
            ) { Text("取消を実行") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("戻る") } },
    )
}

@Composable
fun ReservationCancelResultsDialog(state: ReservationCancelUiState, onClose: () -> Unit) {
    val colors = LocalAppColors.current
    val summary = state.summary
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("取消結果") },
        text = {
            Column {
                // 内訳は一斉取消(経路2)のときだけ出す(docs/ui-design.md「方針: 予約取消の導線」)。
                if (state.resultOrigin == ReservationCancelResultOrigin.BULK) {
                    Text(
                        text = "${summary.cancelledCount}件取消／${summary.unknownCount}件確認できず／${summary.failedCount}件失敗",
                        color = colors.ink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(state.results) { result ->
                        val memberName = state.members.find { it.id == result.memberId }?.name ?: "不明なメンバー"
                        val (color, icon) = when (result.category) {
                            ReservationCancelResultCategory.CANCELLED -> colors.greenInk to "○"
                            // Unknownは成功(green)とも失敗(alert)とも異なる色・記号にする(成否不明を成功と混ぜないため)。
                            ReservationCancelResultCategory.UNKNOWN -> colors.cautionInk to "？"
                            ReservationCancelResultCategory.FAILED -> colors.alert to "×"
                        }
                        Text(
                            text = "$icon $memberName：${result.title}：${result.outcomeLabel}" +
                                (result.detail?.let { "（$it）" } ?: ""),
                            color = color,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("閉じる") } },
    )
}
