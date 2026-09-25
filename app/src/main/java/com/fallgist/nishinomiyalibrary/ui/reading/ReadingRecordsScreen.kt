package com.fallgist.nishinomiyalibrary.ui.reading

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.ui.components.BulkActionBar
import com.fallgist.nishinomiyalibrary.ui.components.BulkOverflowAction
import com.fallgist.nishinomiyalibrary.ui.components.EmptyNote
import com.fallgist.nishinomiyalibrary.ui.components.MemberDot
import com.fallgist.nishinomiyalibrary.ui.components.MemberDotGap
import com.fallgist.nishinomiyalibrary.ui.components.MemberDotIndent
import com.fallgist.nishinomiyalibrary.ui.components.MemberFilterRow
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import com.fallgist.nishinomiyalibrary.ui.components.SelectionCheckbox
import com.fallgist.nishinomiyalibrary.ui.components.SelectionHintChip
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkCartAdditionCandidate
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkCartAdditionConfirmDialog
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkDirectReservationConfirmDialog
import com.fallgist.nishinomiyalibrary.ui.reservationcart.ReservationResultsDialog
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReadingRecordsScreen(
    state: ReadingRecordsUiState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onSelectMember: (Long?) -> Unit,
    onQueryChange: (String) -> Unit,
    onOpenMenu: () -> Unit,
    onOpenDetail: (tilcod: String, title: String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onEnterSelectionMode: (String) -> Unit,
    onExitSelectionMode: () -> Unit,
    onRequestBulkCartAddition: (List<BulkCartAdditionCandidate>) -> Unit,
    onSelectBulkCartAdditionMember: (Long) -> Unit,
    onConfirmBulkCartAddition: () -> Unit,
    onDismissBulkCartAdditionConfirmation: () -> Unit,
    onClearBulkCartAdditionResult: () -> Unit,
    onClearBulkCartAdditionError: () -> Unit,
    onRequestBulkDirectReservation: (List<BulkCartAdditionCandidate>) -> Unit,
    onSelectBulkDirectReservationMember: (Long) -> Unit,
    onSelectBulkDirectReservationPickupLibrary: (String) -> Unit,
    onConfirmBulkDirectReservation: () -> Unit,
    onDismissBulkDirectReservationConfirmation: () -> Unit,
    onClearBulkDirectReservationResults: () -> Unit,
    onClearBulkDirectReservationError: () -> Unit,
    /** [BookshelfEditingUiController]の処理中フラグ(`docs/design/bulk-bookshelf-add.md` §5.4)。 */
    bookshelfEditingProcessing: Boolean = false,
    onRequestBulkAddToBookshelf: (List<BulkCartAdditionCandidate>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 選択モード中の戻るキーは選択モードから抜けるだけにする(`docs/design/selection-mode.md` §3.8-2)。
    BackHandler(enabled = state.selectionMode, onBack = onExitSelectionMode)
    val colors = LocalAppColors.current
    // カート追加・直接予約に加え、本棚追加(別Controller)の処理中も一斉操作全体を止める
    // (`docs/design/bulk-bookshelf-add.md` §5.4)。
    val bulkActionsBlocked = state.anyBulkActionProcessing || bookshelfEditingProcessing
    // TextFieldの値をController経由の非同期StateFlow往復にするとIMEの変換合成が崩れるため、
    // 入力値は画面ローカルに保持し、Controllerへは通知のみ行う
    var queryText by remember { mutableStateOf(state.query) }
    Column(modifier = modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "読書記録", onOpenMenu = onOpenMenu)
        OutlinedTextField(
            value = queryText,
            onValueChange = {
                queryText = it
                onQueryChange(it)
            },
            placeholder = { Text("書名・著者でさがす", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
        )
        MemberFilterRow(
            members = state.members,
            selectedMemberId = state.selectedMemberId,
            onSelect = onSelectMember,
        )
        // 通常時は「長押しで複数選択」の案内、選択モード中は「選択解除」+一斉操作ボタンを同じ行に出す
        // (`docs/design/selection-mode.md` §3.4・§3.6)。一覧が空のときは出さない。
        if (state.rows.isNotEmpty()) {
            // 件数規則の統一。バーの件数は全選択件数ではなく「表示中の選択」に揃える。実行対象
            // (cartAdditionCandidates)と同じ集合を数えることで、バーの件数と実際の処理件数のずれを無くす。
            val displayedCandidates = ReadingRecordsContentBuilder.cartAdditionCandidates(state.rows, state.selectedTilcods)
            if (state.selectionMode) {
                BulkActionBar(
                    selectedCount = displayedCandidates.size,
                    actionLabel = "予約する",
                    enabled = state.canRequestBulkDirectReservation && !bookshelfEditingProcessing,
                    onClick = { onRequestBulkDirectReservation(displayedCandidates) },
                    containerColor = colors.green,
                    contentColor = colors.card,
                    overflowActions = listOf(
                        BulkOverflowAction(
                            label = "カートへ追加",
                            enabled = state.canRequestBulkCartAddition && !bookshelfEditingProcessing,
                            onClick = { onRequestBulkCartAddition(displayedCandidates) },
                        ),
                        BulkOverflowAction(
                            label = "本棚へ追加",
                            enabled = !bulkActionsBlocked && displayedCandidates.isNotEmpty(),
                            onClick = { onRequestBulkAddToBookshelf(displayedCandidates) },
                        ),
                    ),
                    leadingContent = { SelectionHintChip(text = "選択解除", onClick = onExitSelectionMode) },
                )
            } else {
                SelectionHintChip(text = "長押しで複数選択", modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            }
        }
        if (state.rows.isNotEmpty()) {
            // 一斉操作の結果・エラー表示は、選択モードでなくても出したままにする(処理の結果であり、選択とは別)。
            state.bulkCartAdditionErrorMessage?.let {
                Text(
                    text = it,
                    color = colors.alert,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable(onClick = onClearBulkCartAdditionError)
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                )
            }
            state.bulkCartAdditionResultMessage?.let {
                Text(
                    text = it,
                    color = colors.greenInk,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable(onClick = onClearBulkCartAdditionResult)
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                )
            }
            state.bulkDirectReservationErrorMessage?.let {
                Text(
                    text = it,
                    color = colors.alert,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable(onClick = onClearBulkDirectReservationError)
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                // 空状態が3種類とも、スクロール可能なコンポーネントで包んでプルを受け取れるようにする。
                state.showActivationHint -> Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyNote(
                        "このメンバーの読書記録がまだありません。" +
                            "図書館サイト側で読書履歴が有効化されていない可能性があります。",
                    )
                }

                state.rows.isEmpty() && state.query.isNotBlank() ->
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        EmptyNote("「${state.query}」に一致する記録はありません")
                    }

                state.rows.isEmpty() ->
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        EmptyNote("読書記録はまだありません")
                    }

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp),
                ) {
                    items(state.rows) { row ->
                        ReadingRowView(
                            row = row,
                            selectionMode = state.selectionMode,
                            selected = row.tilcod in state.selectedTilcods,
                            selectionEnabled = !bulkActionsBlocked,
                            onClick = { onOpenDetail(row.tilcod, row.title) },
                            onToggleSelection = { onToggleSelection(row.tilcod) },
                            onEnterSelectionMode = { onEnterSelectionMode(row.tilcod) },
                        )
                    }
                }
            }
        }
    }
    state.bulkCartAdditionConfirmation?.let { request ->
        BulkCartAdditionConfirmDialog(
            request = request,
            members = state.members,
            onSelectMember = onSelectBulkCartAdditionMember,
            onConfirm = onConfirmBulkCartAddition,
            onDismiss = onDismissBulkCartAdditionConfirmation,
        )
    }
    state.bulkDirectReservationConfirmation?.let { request ->
        BulkDirectReservationConfirmDialog(
            request = request,
            members = state.members,
            libraries = state.libraries,
            onSelectMember = onSelectBulkDirectReservationMember,
            onSelectPickupLibrary = onSelectBulkDirectReservationPickupLibrary,
            onConfirm = onConfirmBulkDirectReservation,
            onDismiss = onDismissBulkDirectReservationConfirmation,
        )
    }
    if (state.bulkDirectReservationResults.isNotEmpty()) {
        ReservationResultsDialog(
            results = state.bulkDirectReservationResults,
            members = state.members,
            onClose = onClearBulkDirectReservationResults,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReadingRowView(
    row: ReadingRow,
    /** 長押しによる選択モード(`docs/design/selection-mode.md` §3)。trueの間はタップが選択の切り替えになる。 */
    selectionMode: Boolean,
    selected: Boolean,
    selectionEnabled: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onEnterSelectionMode: () -> Unit,
) {
    val colors = LocalAppColors.current
    // レイアウト追い込み第3次(2026-08-06)項目10: ドットは書誌名と同じRow(CenterVertically)に置き、
    // 書誌名の縦中央で揃える。外側で「ドット｜Column」と横に並べる旧構造(ドットがColumn全体の
    // 縦中央に付いてしまう)をやめ、Column{ Row(ドット+書誌名) ; Row(下の行) }の形にする。
    // 選択モードのチェックボックス(蔵書検索のResultRowViewと同じ流儀。行タップ領域とは別のRowに置く)は
    // tilcodが空の行には出さない。行の長押しを常に選択モードの受け口にするため、一覧の行はテキスト選択
    // (コピー)を止める(`docs/design/selection-mode.md` §3.2 案A)。書誌名のコピーは書誌詳細のポップアップ側で可能。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp)),
    ) {
        DisableSelection {
            if (selectionMode && row.tilcod.isNotBlank()) {
                Row(modifier = Modifier.padding(start = 4.dp, top = 2.dp)) {
                    SelectionCheckbox(
                        checked = selected,
                        enabled = selectionEnabled,
                        onToggle = onToggleSelection,
                        checkedColor = colors.green,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        enabled = row.tilcod.isNotBlank() || !selectionMode,
                        onClick = {
                            if (selectionMode) {
                                if (row.tilcod.isNotBlank() && selectionEnabled) onToggleSelection()
                            } else {
                                onClick()
                            }
                        },
                        onLongClick = {
                            if (!selectionMode && row.tilcod.isNotBlank() && selectionEnabled) onEnterSelectionMode()
                        },
                    )
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = if (selectionMode && row.tilcod.isNotBlank()) 0.dp else 10.dp,
                        bottom = 10.dp,
                    ),
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
                // 個別行にメンバー名は出さない(2026-08-05・ドットのみ)。絞り込み行での再掲を避ける。
                // 項目11: 書誌名より下の行にMemberDotIndentを与え、書誌名のインデントと揃える。
                Text(
                    text = "${row.loanDateLabel} · ${row.library}",
                    color = colors.ink2,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = MemberDotIndent),
                )
            }
        } // DisableSelection
    }
}
