package com.fallgist.nishinomiyalibrary.ui.newarrivals

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.fallgist.nishinomiyalibrary.ui.components.BulkActionBar
import com.fallgist.nishinomiyalibrary.ui.components.ClearSelectionWarningDialog
import com.fallgist.nishinomiyalibrary.ui.components.EmptyNote
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import com.fallgist.nishinomiyalibrary.ui.components.SelectionCheckbox
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkCartAdditionCandidate
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkCartAdditionConfirmDialog
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkDirectReservationConfirmDialog
import com.fallgist.nishinomiyalibrary.ui.reservationcart.ReservationResultsDialog
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdatePhase

@Composable
fun NewArrivalsScreen(
    state: NewArrivalsUiState,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenDetail: (tilcod: String, title: String) -> Unit,
    onToggleCartSelection: (String) -> Unit,
    onRequestBulkCartAddition: (List<BulkCartAdditionCandidate>) -> Unit,
    onSelectBulkCartAdditionMember: (Long) -> Unit,
    onConfirmBulkCartAddition: () -> Unit,
    onDismissBulkCartAdditionConfirmation: () -> Unit,
    onClearBulkCartAdditionResult: () -> Unit,
    onClearBulkCartAdditionError: () -> Unit,
    onConfirmPendingRefresh: () -> Unit,
    onDismissPendingRefresh: () -> Unit,
    onConfirmPendingRefreshAndDisableWarning: () -> Unit,
    onRequestBulkDirectReservation: (List<BulkCartAdditionCandidate>) -> Unit,
    onSelectBulkDirectReservationMember: (Long) -> Unit,
    onSelectBulkDirectReservationPickupLibrary: (String) -> Unit,
    onConfirmBulkDirectReservation: () -> Unit,
    onDismissBulkDirectReservationConfirmation: () -> Unit,
    onClearBulkDirectReservationResults: () -> Unit,
    onClearBulkDirectReservationError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    // IMEの変換合成が崩れるため、入力値は画面ローカルに保持しControllerへは通知のみ渡す
    var queryText by remember { mutableStateOf(state.query) }
    Column(modifier = modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(
            title = "新着資料",
            onOpenMenu = onOpenMenu,
            below = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when (state.updatePhase) {
                            NewArrivalUpdatePhase.FETCHING -> "新着資料を更新中"
                            NewArrivalUpdatePhase.AUTOMATIC_RESERVATION -> "自動予約を判定・実行中"
                            null -> "ジャンル横断でまとめた新着 ${state.totalCount}件"
                        },
                        color = colors.ink2,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = "↻ 更新",
                        color = colors.green,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(enabled = !state.refreshing, onClick = onRefresh)
                            .background(colors.greenBg)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            },
        )
        Text(
            text = NewArrivalsLastFetchedTextBuilder.build(state.lastFetchedAtEpochMillis),
            color = colors.ink2,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
        if (state.autoReservationEnabled) {
            Text(
                text = "自動予約がONです。更新後に自動予約を判定します",
                color = colors.greenInk,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 3.dp),
            )
        }
        OutlinedTextField(
            value = queryText,
            onValueChange = {
                queryText = it
                onQueryChange(it)
            },
            placeholder = { Text("書名・著者でしぼりこむ", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
        )
        if (state.rows.isNotEmpty()) {
            // 件数規則の統一(設計追補§6.1)。バーの件数は全選択件数ではなく「表示中の選択」に揃える。
            // 実行対象(cartAdditionCandidates)と同じ集合を数えることで、バーの件数と実際の処理件数の
            // ずれ(絞り込みで隠れた選択を含めて数えてしまう不具合)を無くす。
            val displayedCandidates = NewArrivalsContentBuilder.cartAdditionCandidates(state.rows, state.selectedCartTilcods)
            BulkActionBar(
                selectedCount = displayedCandidates.size,
                actionLabel = "カートへ追加",
                enabled = state.canRequestBulkCartAddition,
                onClick = { onRequestBulkCartAddition(displayedCandidates) },
                containerColor = colors.green,
                contentColor = colors.card,
            )
            // 一斉直接予約(設計追補§5、機能F)。「カートへ追加」の下にもう1つボタンを置く。
            // カートを経由しない、取り返しのつかない操作のため、確認の厳しさは緩めない(§5.4)。
            BulkActionBar(
                selectedCount = displayedCandidates.size,
                actionLabel = "予約する",
                enabled = state.canRequestBulkDirectReservation,
                onClick = { onRequestBulkDirectReservation(displayedCandidates) },
                containerColor = colors.alert,
                contentColor = colors.card,
            )
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
        when {
            !state.initialized -> EmptyNote("読み込んでいます…")

            state.rows.isEmpty() && state.query.isNotBlank() ->
                EmptyNote("「${state.query}」に一致する新着資料はありません")

            state.rows.isEmpty() && state.refreshFailed ->
                EmptyNote("新着資料を取得できませんでした。通信状況を確認して「更新」してください")

            state.rows.isEmpty() ->
                EmptyNote("新着資料はまだありません。「更新」で取得できます")

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
            ) {
                if (state.refreshFailed) {
                    item {
                        EmptyNote(
                            "最新の取得に失敗しました。表示は前回の取得内容です",
                            modifier = Modifier.padding(0.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
                items(state.rows) { row ->
                    NewArrivalRowView(
                        row = row,
                        selected = row.tilcod in state.selectedCartTilcods,
                        selectionEnabled = !state.anyBulkActionProcessing,
                        onClick = { onOpenDetail(row.tilcod, row.title) },
                        onToggleSelection = { onToggleCartSelection(row.tilcod) },
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
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
    // 巡回(「更新」)で選択が解除される前の確認(設計追補§6.2)。
    if (state.pendingRefreshConfirmation) {
        ClearSelectionWarningDialog(
            operationLabel = "更新",
            onConfirm = onConfirmPendingRefresh,
            onDismiss = onDismissPendingRefresh,
            onDisableWarning = onConfirmPendingRefreshAndDisableWarning,
        )
    }
    // 一斉直接予約の確認ダイアログ(設計追補§5.3)。メンバー・受取館を選ばせ、最終確認の後にだけ実行する。
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
    // 一斉直接予約の結果(件ごとの成否)。既存の予約結果表示を再利用する(§5.3)。
    if (state.bulkDirectReservationResults.isNotEmpty()) {
        ReservationResultsDialog(
            results = state.bulkDirectReservationResults,
            members = state.members,
            onClose = onClearBulkDirectReservationResults,
        )
    }
}

@Composable
private fun NewArrivalRowView(
    row: NewArrivalRow,
    selected: Boolean,
    selectionEnabled: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    val colors = LocalAppColors.current
    // 一斉カート追加(`docs/design/bulk-selection.md` §7.3)のチェックボックスは行タップ領域とは別の
    // Rowに置く(予約中一覧のReservationRowViewと同じ流儀)。tilcodが空の行には出さない。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp)),
    ) {
        if (row.tilcod.isNotBlank()) {
            Row(modifier = Modifier.padding(start = 4.dp, top = 2.dp)) {
                SelectionCheckbox(
                    checked = selected,
                    enabled = selectionEnabled,
                    onToggle = onToggleSelection,
                    checkedColor = colors.green,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = row.tilcod.isNotBlank(), onClick = onClick)
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = if (row.tilcod.isNotBlank()) 0.dp else 10.dp,
                    bottom = 10.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    color = colors.ink,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = row.subtitle,
                        color = colors.ink2,
                        fontSize = 11.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            row.lendable?.let { LendPill(it) }
        }
    }
}

@Composable
private fun LendPill(lendable: Boolean) {
    val colors = LocalAppColors.current
    Text(
        text = if (lendable) "○貸出可" else "×貸出不可",
        color = if (lendable) colors.greenInk else colors.alert,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (lendable) colors.greenBg else colors.alertBg)
            .padding(horizontal = 9.dp, vertical = 2.dp),
    )
}
