package com.fallgist.nishinomiyalibrary.ui.reservations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
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
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelTarget
import com.fallgist.nishinomiyalibrary.ui.components.BulkActionBar
import com.fallgist.nishinomiyalibrary.ui.components.EmptyNote
import com.fallgist.nishinomiyalibrary.ui.components.MemberDot
import com.fallgist.nishinomiyalibrary.ui.components.MemberDotGap
import com.fallgist.nishinomiyalibrary.ui.components.MemberDotIndent
import com.fallgist.nishinomiyalibrary.ui.components.MemberFilterRow
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import com.fallgist.nishinomiyalibrary.ui.components.SelectionCheckbox
import com.fallgist.nishinomiyalibrary.ui.components.SelectionModeBar
import com.fallgist.nishinomiyalibrary.ui.detail.BookDetailCancelTarget
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReservationsScreen(
    state: ReservationsUiState,
    cancelState: ReservationCancelUiState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onSelectMember: (Long?) -> Unit,
    onOpenMenu: () -> Unit,
    // 経路3: 予約中一覧から開く書誌詳細には取消対象(cancellableな行のみ非null)を添えて渡す。
    // 他画面(検索結果・新着等)のonOpenDetailは2引数のままにして依存させない(第3引数はここだけ)。
    onOpenDetail: (tilcod: String, title: String, cancelTarget: BookDetailCancelTarget?) -> Unit,
    onToggleSelection: (ReservationCancelKey) -> Unit,
    onEnterSelection: (ReservationCancelKey, cancellable: Boolean) -> Unit,
    onExitSelection: () -> Unit,
    onRequestSingleCancel: (ReservationCancelCandidate) -> Unit,
    onRequestBulkCancel: (List<ReservationCancelCandidate>) -> Unit,
    onConfirmCancel: () -> Unit,
    onDismissCancelConfirmation: () -> Unit,
    onClearCancelResults: () -> Unit,
    onClearCancelError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 選択モード中の戻るキーは選択モードから抜けるだけにする(§3.8-2)。それ以外は既存の戻る動作を妨げない。
    BackHandler(enabled = cancelState.selectionMode, onBack = onExitSelection)
    val colors = LocalAppColors.current
    Column(modifier = modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "予約中", onOpenMenu = onOpenMenu)
        // 選択モードの上部バーは一覧の最上部、メンバー絞り込みチップの上に出す(設計§3.4)。
        if (cancelState.selectionMode) {
            SelectionModeBar(
                selectedCount = cancelState.selectedKeys.size,
                onClearSelection = onExitSelection,
            )
        }
        MemberFilterRow(
            members = state.members,
            selectedMemberId = state.selectedMemberId,
            onSelect = onSelectMember,
            countByMemberId = state.countByMemberId,
            totalCount = state.totalCount,
        )
        // 「長押しで複数選択」の案内(設計§3.6)。選択モード中・一覧が空のときは出さない。
        if (!cancelState.selectionMode && state.rows.isNotEmpty()) {
            Text(
                text = "長押しで複数選択",
                color = colors.ink2,
                fontSize = 11.sp,
                modifier = Modifier
                    .padding(horizontal = 18.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.chipBg)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        // BulkCancelBarと取消エラー表示はプル領域の外に置く(絞り込み行の直下)。
        // 空のときに出さない条件は元のrows.isEmpty()分岐のまま維持する。
        if (state.rows.isNotEmpty()) {
            // 一斉取消のバーは選択モードのときだけ出す(設計§3.5)。
            if (cancelState.selectionMode) {
                BulkActionBar(
                    selectedCount = cancelState.selectedKeys.size,
                    actionLabel = "一斉取消",
                    enabled = cancelState.canCancelSelection,
                    onClick = {
                        val candidates = ReservationsContentBuilder.cancelCandidates(state.rows, cancelState.selectedKeys)
                        onRequestBulkCancel(candidates)
                    },
                )
            }
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
            if (cancelState.processing) {
                Text(
                    text = if (cancelState.waitingForAutomaticReservation) "自動予約処理の完了待ち…" else "取消を処理中…",
                    color = colors.ink2,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.rows.isEmpty()) {
                // 空状態でもプルできるよう、スクロール可能なコンポーネントで包む。
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyNote("予約中の本はありません")
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp),
                ) {
                    items(state.rows) { row ->
                        ReservationRowView(
                            row = row,
                            selectionMode = cancelState.selectionMode,
                            selected = row.cancellable && row.cancelKey in cancelState.selectedKeys,
                            selectionEnabled = !cancelState.processing,
                            onClick = {
                                onOpenDetail(row.tilcod, row.title, ReservationsContentBuilder.cancelTargetForDetail(row))
                            },
                            onToggleSelection = { onToggleSelection(row.cancelKey) },
                            onEnterSelectionMode = { onEnterSelection(row.cancelKey, row.cancellable) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReservationRowView(
    row: ReservationRow,
    /** 長押しによる選択モード(`docs/design/selection-mode.md` §3)。trueの間はタップが選択の切り替えになる。 */
    selectionMode: Boolean,
    selected: Boolean,
    selectionEnabled: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onEnterSelectionMode: () -> Unit,
    onRequestCancel: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
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
    ) {
        // 行の長押しを常に選択モードの受け口にするため、一覧の行はテキスト選択(コピー)を止める
        // (設計§3.2 案A)。書誌名のコピーは書誌詳細のポップアップ側でSelectionContainerの内側のまま可能。
        DisableSelection {
        // 一斉取消のチェックボックスは、選択モードのときだけ、行タップ領域(下のColumn)とは別のRowに
        // 置く(貸出中一覧のLoanRowViewと同じ流儀)。取消可能な行にだけ出す。
        if (selectionMode && row.cancellable) {
            Row(
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionCheckbox(
                    checked = selected,
                    enabled = selectionEnabled,
                    onToggle = onToggleSelection,
                    checkedColor = colors.alert,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = row.tilcod.isNotBlank() || (selectionMode && row.cancellable),
                    onClick = {
                        if (selectionMode) {
                            if (row.cancellable && selectionEnabled) onToggleSelection()
                        } else {
                            onClick()
                        }
                    },
                    onLongClick = {
                        if (!selectionMode && row.cancellable && selectionEnabled) onEnterSelectionMode()
                    },
                )
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
        ) {
            // ドットは書誌名の左に置く(2026-08-05・個別行にメンバー名は出さない。絞り込み行の再掲を避ける)。
            // レイアウト追い込み第3次(2026-08-06)項目10: ドットは書誌名と同じRow(CenterVertically)に
            // 置き、書誌名の縦中央で揃える(予約中一覧は元々この形)。間隔はMemberDotGapで3画面共通化。
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MemberDotGap)) {
                MemberDot(row.memberColorHex)
                Text(
                    text = row.title,
                    color = colors.ink,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            // 項目11: 書誌名より下の行(館名・予約順位・取置期限・取消ボタン)にMemberDotIndentを与え、
            // 書誌名のインデントと揃える。1行目(ステータスラベル・チェックボックス)には付けない。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = MemberDotIndent),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 受取館は接頭辞なしで館名のみ表示する。サイトが未定かつアプリの送信記録も無い行では
                    // pickupLabelがnullになり、項目自体を出さない(館名を捏造しない)。
                    row.pickupLabel?.let {
                        Text(
                            text = it,
                            color = colors.ink2,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    row.queueLabel?.let {
                        Text(
                            text = it,
                            color = colors.ink2,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 取置期限も同じRowに並べるだけにする(queueLabelと排他の想定だが、両方非nullでも
                    // どちらかを捨てず両方表示する。受取可能時の赤太字は維持)。
                    row.holdExpiryLabel?.let {
                        Text(
                            text = it,
                            color = if (row.isReady) colors.alert else colors.ink2,
                            fontSize = 11.sp,
                            fontWeight = if (row.isReady) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 状態表示の移動(設計§3.7)。チェックボックスが常時出ないため、書誌名の下の行
                    // (受取館・予約順位・取置期限と同じ行)の末尾へ移す。体裁(11sp・Bold)・isReadyのときの
                    // 色(greenInk)は変えない。取消できない行(提供可能・移送中等)にも従来どおり出す
                    // (設計§3.7、選択の可否と状態表示の有無は別)。
                    Text(
                        text = row.statusLabel,
                        color = if (row.isReady) colors.greenInk else colors.ink2,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                // 取消ボタンは取消可能な行(cancelCodeが非空)だけに出す。無効化ではなく非表示にする。
                // 書誌詳細の「この予約を取り消す」ボタン(BookDetailView)と同じ色・流儀を踏襲。
                // 「受取館/順位」行の右端に収めるため、Material3のButton既定(高さ40dp)より
                // heightとcontentPaddingを詰めて行の高さ増加を抑える(タップ領域は32dpを確保し押しやすさは維持)。
                // 行内の単独操作ボタンは選択モードの間は出さない(設計§3.5.1)。選択モード中はタイルの
                // タップが選択の切り替えであり、単独取消ボタンを残すと誤タップで単独取消が走り、
                // 選択モード中の選択を壊してしまう(貸出中一覧のLoanRowViewと同じ対応)。
                if (row.cancellable && !selectionMode) {
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onRequestCancel,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.alert, contentColor = colors.card),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                    ) { Text("取消") }
                }
            }
        }
        } // DisableSelection
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
                        text = "${summary.cancelledCount}件取消／${summary.unknownCount}件確認できず／${summary.failedCount}件失敗" +
                            "／一覧整理の警告${summary.cleanupWarningCount}件",
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
