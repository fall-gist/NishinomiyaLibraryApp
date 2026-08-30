package com.fallgist.nishinomiyalibrary.ui.reservationcart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
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
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.ui.components.BulkActionBar
import com.fallgist.nishinomiyalibrary.ui.components.EmptyNote
import com.fallgist.nishinomiyalibrary.ui.components.MemberDot
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import com.fallgist.nishinomiyalibrary.ui.components.SelectionCheckbox
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

@Composable
fun ReservationCartScreen(
    state: ReservationUiState,
    onSelectPickupLibrary: (String) -> Unit,
    onRemoveFromCart: (Long) -> Unit,
    onRequestConfirmation: () -> Unit,
    onOpenSearch: () -> Unit,
    onClearResults: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenDetail: (tilcod: String, title: String) -> Unit,
    onToggleCartItemSelection: (Long) -> Unit,
    onRequestBulkCartDelete: (List<ReservationCartDeleteCandidate>) -> Unit,
    onConfirmBulkCartDelete: () -> Unit,
    onDismissBulkCartDeleteConfirmation: () -> Unit,
    onRequestClearCart: () -> Unit,
    onConfirmClearCart: () -> Unit,
    onDismissClearCartConfirmation: () -> Unit,
    onClearCartMutationError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val cartFeedback = ReservationCartContentBuilder.feedbackForCart(state.feedback)
    Column(modifier = modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "予約カート", onOpenMenu = onOpenMenu)
        if (!state.initialized) {
            EmptyNote("カートを読み込んでいます…")
        } else if (state.cartItemCount == 0) {
            EmptyCart(onOpenSearch = onOpenSearch)
            if (state.processing) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.waitingForAutomaticReservation) "自動予約処理の完了待ち…" else "予約を処理中…",
                        color = colors.ink2,
                        fontSize = 12.sp,
                    )
                }
            }
        } else {
            PickupLibrarySelector(
                libraries = state.libraries,
                selectedCode = state.pickupLibraryCode,
                onSelect = onSelectPickupLibrary,
                enabled = !state.processing,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )
            if (!state.hasValidPickupLibrary) {
                Text(
                    "受取館を選択してください",
                    color = colors.alert,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                )
            }
            // 一括削除・「カートを空にする」(`docs/design/bulk-selection.md` §6)。
            BulkActionBar(
                selectedCount = state.selectedCartItemIds.size,
                actionLabel = "選択した項目を削除",
                enabled = state.canBulkDeleteFromCart,
                onClick = {
                    onRequestBulkCartDelete(ReservationCartContentBuilder.deleteCandidates(state.cartGroups, state.selectedCartItemIds))
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "カートを空にする",
                    color = if (state.canClearCart) colors.alert else colors.ink2,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = state.canClearCart, onClick = onRequestClearCart)
                        .padding(8.dp),
                )
            }
            state.cartMutationErrorMessage?.let {
                Text(
                    text = it,
                    color = colors.alert,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable(onClick = onClearCartMutationError)
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp),
            ) {
                state.cartGroups.forEach { group ->
                    item(key = "member-${group.member?.id ?: group.items.first().memberId}") {
                        Row(
                            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            MemberDot(group.member?.colorHex.orEmpty())
                            Text(group.member?.name ?: "削除されたメンバー", color = colors.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    items(group.items, key = { it.id }) { item ->
                        CartItemRow(
                            title = item.title,
                            writerLine = item.writerLine,
                            selected = item.id in state.selectedCartItemIds,
                            selectionEnabled = !state.cartMutationProcessing,
                            enabled = !state.processing,
                            onToggleSelection = { onToggleCartItemSelection(item.id) },
                            onRemove = { onRemoveFromCart(item.id) },
                            onClick = { onOpenDetail(item.tilcod, item.title) },
                        )
                    }
                }
                item { Spacer(Modifier.padding(bottom = 12.dp)) }
            }
            Button(
                onClick = onRequestConfirmation,
                enabled = state.canConfirmCart,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                if (state.processing) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.waitingForAutomaticReservation) "自動予約処理の完了待ち…" else "予約を処理中…")
                } else {
                    Text("${state.cartItemCount}件を予約確定へ")
                }
            }
        }
        ReservationNotice(cartFeedback)
    }
    cartFeedback?.takeIf { it.results.isNotEmpty() }?.let { feedback ->
        ReservationResultsDialog(feedback, state.members, onClearResults)
    }
    state.bulkCartDeleteConfirmation?.let { request ->
        ReservationCartBulkDeleteConfirmDialog(
            request = request,
            onConfirm = onConfirmBulkCartDelete,
            onDismiss = onDismissBulkCartDeleteConfirmation,
        )
    }
    if (state.clearCartConfirmationPending) {
        CartClearConfirmDialog(
            itemCount = state.cartItemCount,
            onConfirm = onConfirmClearCart,
            onDismiss = onDismissClearCartConfirmation,
        )
    }
}

/** カート一括削除の確認ダイアログ(`docs/design/bulk-selection.md` §6.3、確認必須)。 */
@Composable
fun ReservationCartBulkDeleteConfirmDialog(
    request: ReservationCartBulkDeleteConfirmationRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("選択した${request.candidates.size}件をカートから削除しますか？") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                items(request.candidates, key = { it.cartItemId }) { candidate ->
                    Text("・${candidate.title}", color = colors.ink, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = colors.alert, contentColor = colors.card),
            ) { Text("削除する") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("戻る") } },
    )
}

/** 「カートを空にする」の確認ダイアログ(`docs/design/bulk-selection.md` §6.2・§6.3、確認必須)。 */
@Composable
fun CartClearConfirmDialog(itemCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("カートを空にしますか？") },
        text = { Text("カートの${itemCount}件をすべて削除します。この操作は元に戻せません。") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = colors.alert, contentColor = colors.card),
            ) { Text("空にする") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("戻る") } },
    )
}

@Composable
fun PickupLibrarySelector(
    libraries: List<Library>,
    selectedCode: String,
    onSelect: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    var expanded by remember { mutableStateOf(false) }
    val selectedName = libraries.find { it.code == selectedCode }?.name ?: "受取館を選択"
    Column(modifier = modifier) {
        Text("受取館", color = colors.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.card)
                    .border(1.dp, colors.line, RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(selectedName, color = colors.ink, fontSize = 13.sp)
                Text("⌄", color = colors.ink2, fontSize = 14.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                libraries.forEach { library ->
                    DropdownMenuItem(
                        text = { Text(library.name) },
                        onClick = {
                            expanded = false
                            onSelect(library.code)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyCart(onOpenSearch: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("予約カートは空です", color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("書誌詳細から資料とメンバーを選んで追加できます。", color = colors.ink2, fontSize = 12.5.sp)
        OutlinedButton(onClick = onOpenSearch) { Text("蔵書検索へ") }
    }
}

@Composable
private fun CartItemRow(
    title: String,
    writerLine: String?,
    selected: Boolean,
    selectionEnabled: Boolean,
    enabled: Boolean,
    onToggleSelection: () -> Unit,
    onRemove: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    // 一括削除(`docs/design/bulk-selection.md` §6.2、カート項目は全件が対象)のチェックボックスは
    // 行タップ領域(内側のRow)とは別に置く。外側のRow自体はクリックできない。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 7.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionCheckbox(
            checked = selected,
            enabled = selectionEnabled,
            onToggle = onToggleSelection,
            checkedColor = colors.alert,
            modifier = Modifier.padding(start = 4.dp),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                writerLine?.let { Text(it, color = colors.ink2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Text(
                "削除",
                color = colors.alert,
                fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onRemove).padding(6.dp),
            )
        }
    }
}

@Composable
private fun ReservationNotice(feedback: ReservationFeedback?) {
    val colors = LocalAppColors.current
    feedback?.notice?.let { Text(it, color = colors.greenInk, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) }
    feedback?.errorMessage?.let { Text(it, color = colors.alert, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) }
}

@Composable
private fun ReservationResultsDialog(
    feedback: ReservationFeedback,
    members: List<com.fallgist.nishinomiyalibrary.domain.model.Member>,
    onClose: () -> Unit,
) {
    val colors = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("予約結果") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(feedback.results) { result ->
                    val memberName = members.find { it.id == result.memberId }?.name ?: "不明なメンバー"
                    Text(
                        "$memberName：${result.title}：${result.outcomeLabel}${result.detail?.let { "（$it）" }.orEmpty()}",
                        color = if (result.completed) colors.greenInk else colors.alert,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("閉じる") } },
    )
}

@Composable
fun ReservationConfirmDialog(
    request: ReservationConfirmationRequest,
    libraryName: String,
    members: List<com.fallgist.nishinomiyalibrary.domain.model.Member>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val memberNames = request.targets.map { target -> members.find { it.id == target.memberId }?.name ?: "不明" }.distinct().joinToString("、")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("予約を確定しますか？") },
        text = {
            Text("対象メンバー：$memberNames\n資料件数：${request.targets.size}件\n受取館：$libraryName\n\nこの操作の後、図書館サイトへ予約を送信します。")
        },
        confirmButton = { Button(onClick = onConfirm) { Text("予約を確定") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("戻る") } },
    )
}
