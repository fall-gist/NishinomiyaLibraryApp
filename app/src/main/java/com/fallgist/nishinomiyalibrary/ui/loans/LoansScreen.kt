package com.fallgist.nishinomiyalibrary.ui.loans

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionTarget
import com.fallgist.nishinomiyalibrary.ui.components.EmptyNote
import com.fallgist.nishinomiyalibrary.ui.components.MemberDot
import com.fallgist.nishinomiyalibrary.ui.components.MemberFilterRow
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoansScreen(
    state: LoansUiState,
    extensionState: LoanExtensionUiState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onSelectMember: (Long?) -> Unit,
    onOpenMenu: () -> Unit,
    onOpenDetail: (tilcod: String, title: String) -> Unit,
    onRequestExtend: (LoanExtensionCandidate) -> Unit,
    onConfirmExtend: () -> Unit,
    onDismissExtendConfirmation: () -> Unit,
    onClearExtendResult: () -> Unit,
    onClearExtendError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Column(modifier = modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "貸出中", onOpenMenu = onOpenMenu)
        MemberFilterRow(
            members = state.members,
            selectedMemberId = state.selectedMemberId,
            onSelect = onSelectMember,
            countByMemberId = state.countByMemberId,
            totalCount = state.totalCount,
        )
        extensionState.errorMessage?.let {
            Text(
                text = it,
                color = colors.alert,
                fontSize = 12.sp,
                // タップで消せるようにする(タップ以外の自動クリア手段が無いため)。
                modifier = Modifier
                    .clickable(onClick = onClearExtendError)
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            )
        }
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.rows.isEmpty()) {
                // 空状態でもプルできるよう、スクロール可能なコンポーネントで包む(PullToRefreshBoxは
                // ネストスクロール経由でジェスチャを受け取るため、素のTextだけでは反応しない)。
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyNote("貸出中の本はありません")
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp),
                ) {
                    items(state.rows) { row ->
                        LoanRowView(
                            row = row,
                            extending = extensionState.processingTarget == LoanExtensionKey(row.memberId, row.tilcod),
                            extendDisabled = extensionState.processing,
                            onClick = { onOpenDetail(row.tilcod, row.title) },
                            onRequestExtend = {
                                onRequestExtend(
                                    LoanExtensionCandidate(
                                        target = LoanExtensionTarget(row.memberId, row.tilcod),
                                        title = row.title,
                                        currentDueDate = row.dueDate,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
    extensionState.pendingConfirmation?.let { candidate ->
        LoanExtensionConfirmDialog(candidate = candidate, onConfirm = onConfirmExtend, onDismiss = onDismissExtendConfirmation)
    }
    extensionState.result?.let { result ->
        LoanExtensionResultDialog(result = result, onClose = onClearExtendResult)
    }
}

@Composable
private fun LoanRowView(
    row: LoanRow,
    extending: Boolean,
    extendDisabled: Boolean,
    onClick: () -> Unit,
    onRequestExtend: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (row.overdue) colors.alertBg else colors.card)
            .border(
                1.dp,
                if (row.overdue) colors.alert.copy(alpha = 0.45f) else colors.line,
                RoundedCornerShape(12.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = row.tilcod.isNotBlank(), onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.width(52.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                MemberDot(row.memberColorHex)
                Text(text = row.memberName, color = colors.ink2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    color = colors.ink,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = row.library, color = colors.ink2, fontSize = 11.sp)
            }
            Text(
                text = row.dueLabel,
                color = if (row.overdue || row.dueSoon) colors.alert else colors.ink2,
                fontSize = 11.sp,
                fontWeight = if (row.overdue || row.dueSoon) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        // 延長ボタンは extendable かつ tilcod が空でない行にだけ出す(`docs/design/loan-extension.md` §6・§9.1)。
        // 除外条件はLoanRow.canExtendに集約し、UI側で条件を再実装しない。
        if (row.canExtend) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 0.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (extending) {
                    Text(
                        text = "延長処理中…",
                        color = colors.ink2,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 10.dp, end = 8.dp),
                    )
                }
                Button(
                    onClick = onRequestExtend,
                    enabled = !extendDisabled,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.green, contentColor = colors.card),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp).padding(bottom = 10.dp),
                ) { Text("延長") }
            }
        } else {
            Spacer(Modifier.height(0.dp))
        }
    }
}

@Composable
fun LoanExtensionConfirmDialog(
    candidate: LoanExtensionCandidate,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("延長しますか？") },
        text = {
            Text("資料名：${candidate.title}\n現在の返却期限：${LoanExtensionContentBuilder.formatDueDate(candidate.currentDueDate)}\n\n延長しますか？")
        },
        confirmButton = { Button(onClick = onConfirm) { Text("延長する") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("戻る") } },
    )
}

@Composable
fun LoanExtensionResultDialog(result: LoanExtensionResultMessage, onClose: () -> Unit) {
    val colors = LocalAppColors.current
    // タイトル・本文色はLoanExtensionContentBuilder.resultMessageが確定した種別(kind)だけで出し分ける。
    // succeeded(2値)から失敗を断定すると、Unknown(成否不明)がFailure(失敗)と混同される(`docs/handoff.md`進行指示15)。
    val textColor = when (result.kind) {
        LoanExtensionResultKind.EXTENDED -> colors.ink
        // 成否不明は成功(green系)・失敗(alert)のどちらとも混同しない専用トーン(予約取消Unknownと同じ流儀)。
        LoanExtensionResultKind.UNKNOWN -> colors.cautionInk
        LoanExtensionResultKind.FAILED -> colors.alert
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(result.title) },
        text = { Text(text = result.message, color = textColor) },
        confirmButton = { Button(onClick = onClose) { Text("閉じる") } },
    )
}
