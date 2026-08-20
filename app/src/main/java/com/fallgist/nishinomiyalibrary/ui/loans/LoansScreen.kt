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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.fallgist.nishinomiyalibrary.ui.components.MemberDotGap
import com.fallgist.nishinomiyalibrary.ui.components.MemberDotIndent
import com.fallgist.nishinomiyalibrary.ui.components.MemberFilterRow
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoansScreen(
    state: LoansUiState,
    extensionState: LoanExtensionUiState,
    /** カレンダーの返却期限マスから遷移した際に強調する日付。nullなら強調なし(設計§4.6)。 */
    focusDueDate: LocalDate?,
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
    val listState = rememberLazyListState()
    // rowsがまだ空(初期化前)の間はindexが決まらない。rowsの到着で再計算されるようrememberのキーに入れる。
    val focusIndex = remember(focusDueDate, state.rows) {
        focusDueDate?.let { date -> state.rows.indexOfFirst { it.dueDate == date } }?.takeIf { it >= 0 }
    }
    // 1回のタップにつき1回だけスクロールする(メンバー絞り込みの操作で再スクロールしないため)。
    var scrolledFor by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(focusIndex) {
        val index = focusIndex ?: return@LaunchedEffect
        if (scrolledFor == focusDueDate) return@LaunchedEffect
        listState.animateScrollToItem(index)
        scrolledFor = focusDueDate
    }
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
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp),
                ) {
                    items(state.rows) { row ->
                        LoanRowView(
                            row = row,
                            // 強調はindexではなく日付の一致で決める(絞り込みで行が動いてもずれない、設計§4.6)。
                            highlighted = focusDueDate != null && row.dueDate == focusDueDate,
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
    /** カレンダーからの遷移で対象日として強調するか(設計§4.6)。枠をcolors.greenにするだけで、状態は持たない。 */
    highlighted: Boolean,
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
                if (highlighted) 2.dp else 1.dp,
                when {
                    highlighted -> colors.green
                    row.overdue -> colors.alert.copy(alpha = 0.45f)
                    else -> colors.line
                },
                RoundedCornerShape(12.dp),
            ),
    ) {
        // レイアウト追い込み第3次(2026-08-06)項目10: ドットは書誌名と同じRow(CenterVertically)に置き、
        // 書誌名の縦中央で揃える。外側で「ドット｜Column」と横に並べる旧構造(ドットがColumn全体の
        // 縦位置に付いてしまう)をやめ、Column{ Row(ドット+書誌名) ; Row(下の行) }の形にする。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = row.tilcod.isNotBlank(), onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MemberDotGap),
            ) {
                MemberDot(row.memberColorHex)
                Text(
                    text = row.title,
                    color = colors.ink,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            // 館名・返却期限は書誌名の下の行にまとめ、延長ボタンは行レイアウト追い込み第2次(2026-08-05)
            // 項目8により同じ行の右端(館名の行の右端)へ移す。予約中一覧の取消ボタンと同じ流儀。
            // 項目11: インデントはMemberDotIndent(ドット径+間隔)を書誌名と揃える。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = MemberDotIndent),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 館名と返却期限は同じweight(1f)の組に入れ、館名のすぐ右に少し間隔を空けて置く
                // (予約中一覧の取置期限と同じ配置。項目9)。右端は延長ボタンだけにする。
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.library,
                        color = colors.ink2,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 返却期限の文言は「返却期限」を先頭に付け、予約中一覧の「取置期限 8/19 まで」と揃える
                    // (項目9)。「まで」はLoansContentBuilder.dueLabelが既に付けているため二重付与しない。
                    // 色はalert(赤)をやめ太字+cautionInkにする。従来のoverdue/dueSoonによる色出し分けは
                    // 無くなるが、延滞行の背景色(alertBg)による区別は行レベルで維持する。
                    Text(
                        text = row.dueLabel,
                        color = colors.cautionInk,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 延長ボタンは extendable かつ tilcod が空でない行にだけ出す(`docs/design/loan-extension.md` §6・§9.1)。
                // 除外条件はLoanRow.canExtendに集約し、UI側で条件を再実装しない。
                // 予約一覧の取消ボタン(ReservationsScreen.kt)と同じ流儀: 情報行の右端に収め、
                // heightのみ32dpに詰めてタップ領域を確保しつつ行の高さ増加を抑える。
                if (row.canExtend) {
                    Spacer(Modifier.width(8.dp))
                    if (extending) {
                        Text(
                            text = "延長処理中…",
                            color = colors.ink2,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Button(
                        onClick = onRequestExtend,
                        enabled = !extendDisabled,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.green, contentColor = colors.card),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                    ) { Text("延長") }
                }
            }
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
