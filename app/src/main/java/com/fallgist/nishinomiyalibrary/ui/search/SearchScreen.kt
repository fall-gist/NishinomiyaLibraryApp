package com.fallgist.nishinomiyalibrary.ui.search

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.ui.components.BulkActionBar
import com.fallgist.nishinomiyalibrary.ui.components.EmptyNote
import com.fallgist.nishinomiyalibrary.ui.components.MemberDot
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import com.fallgist.nishinomiyalibrary.ui.components.SelectionCheckbox
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkCartAdditionCandidate
import com.fallgist.nishinomiyalibrary.ui.reservationcart.BulkCartAdditionConfirmDialog
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onOpenDetail: (tilcod: String, title: String) -> Unit,
    onOpenMenu: () -> Unit,
    onToggleCartSelection: (String) -> Unit,
    onRequestBulkCartAddition: (List<BulkCartAdditionCandidate>) -> Unit,
    onSelectBulkCartAdditionMember: (Long) -> Unit,
    onConfirmBulkCartAddition: () -> Unit,
    onDismissBulkCartAdditionConfirmation: () -> Unit,
    onClearBulkCartAdditionResult: () -> Unit,
    onClearBulkCartAdditionError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SearchView(
        state = state,
        onQueryChange = onQueryChange,
        onSearch = onSearch,
        onLoadMore = onLoadMore,
        onOpenDetail = onOpenDetail,
        onOpenMenu = onOpenMenu,
        onToggleCartSelection = onToggleCartSelection,
        onRequestBulkCartAddition = onRequestBulkCartAddition,
        onSelectBulkCartAdditionMember = onSelectBulkCartAdditionMember,
        onConfirmBulkCartAddition = onConfirmBulkCartAddition,
        onDismissBulkCartAdditionConfirmation = onDismissBulkCartAdditionConfirmation,
        onClearBulkCartAdditionResult = onClearBulkCartAdditionResult,
        onClearBulkCartAdditionError = onClearBulkCartAdditionError,
        modifier = modifier,
    )
}

@Composable
private fun SearchView(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onOpenDetail: (tilcod: String, title: String) -> Unit,
    onOpenMenu: () -> Unit,
    onToggleCartSelection: (String) -> Unit,
    onRequestBulkCartAddition: (List<BulkCartAdditionCandidate>) -> Unit,
    onSelectBulkCartAdditionMember: (Long) -> Unit,
    onConfirmBulkCartAddition: () -> Unit,
    onDismissBulkCartAdditionConfirmation: () -> Unit,
    onClearBulkCartAdditionResult: () -> Unit,
    onClearBulkCartAdditionError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    // IMEの変換合成が崩れるため、入力値は画面ローカルに保持しControllerへは通知のみ渡す
    var queryText by remember { mutableStateOf("") }
    Column(modifier = modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "蔵書検索", onOpenMenu = onOpenMenu)
        OutlinedTextField(
            value = queryText,
            onValueChange = {
                queryText = it
                onQueryChange(it)
            },
            placeholder = { Text("書名・著者などのキーワード", fontSize = 13.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(queryText) }),
            trailingIcon = {
                Text(
                    text = "🔍",
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSearch(queryText) }
                        .padding(8.dp),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
        )
        if (state.suggestions.isNotEmpty()) {
            SuggestionList(
                suggestions = state.suggestions,
                onTap = { suggestion ->
                    queryText = suggestion
                    onSearch(suggestion)
                },
            )
        }
        // BulkActionBarと一斉追加の結果・エラー表示は結果一覧の直上に置く(予約中一覧のBulkCancelBarと同じ配置方針)。
        if (state.results.isNotEmpty()) {
            BulkActionBar(
                selectedCount = state.selectedCartTilcods.size,
                actionLabel = "カートへ追加",
                enabled = state.canRequestBulkCartAddition,
                onClick = {
                    onRequestBulkCartAddition(
                        SearchContentBuilder.cartAdditionCandidates(state.results, state.selectedCartTilcods),
                    )
                },
                containerColor = colors.green,
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
        }
        Spacer(Modifier.height(8.dp))
        when {
            state.searching -> EmptyNote("検索しています…")

            state.errorMessage != null -> EmptyNote(state.errorMessage)

            state.executedQuery == null -> EmptyNote("キーワードを入力して蔵書をさがせます")

            state.results.isEmpty() -> EmptyNote("「${state.executedQuery}」に一致する蔵書はありません")

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "「${state.executedQuery}」の検索結果",
                            color = colors.ink,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("${state.totalCount}件", color = colors.ink2, fontSize = 11.sp)
                    }
                }
                items(state.results) { row ->
                    ResultRowView(
                        row = row,
                        selected = row.tilcod in state.selectedCartTilcods,
                        selectionEnabled = !state.bulkCartAdditionProcessing,
                        onClick = { onOpenDetail(row.tilcod, row.title) },
                        onToggleSelection = { onToggleCartSelection(row.tilcod) },
                    )
                }
                if (state.hasNext) {
                    item {
                        Text(
                            text = if (state.loadingMore) "読み込み中…" else "さらに読み込む",
                            color = colors.green,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !state.loadingMore, onClick = onLoadMore)
                                .padding(vertical = 10.dp),
                        )
                    }
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
}

@Composable
private fun SuggestionList(suggestions: List<String>, onTap: (String) -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(10.dp)),
    ) {
        suggestions.take(8).forEach { suggestion ->
            Text(
                text = "🔍 $suggestion",
                color = colors.ink,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTap(suggestion) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ResultRowView(
    row: SearchResultRow,
    selected: Boolean,
    selectionEnabled: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    val colors = LocalAppColors.current
    // 一斉カート追加(`docs/design/bulk-selection.md` §7.3)のチェックボックスは、行タップ領域(下段のRow)
    // とは別のRowに置く(予約中一覧のReservationRowViewと同じ流儀)。tilcodが空の行には出さない。
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
                .clickable(onClick = onClick)
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
                if (row.writerLine.isNotBlank()) {
                    Text(row.writerLine, color = colors.ink2, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (row.materialType.isNotBlank()) {
                    Text(row.materialType, color = colors.ink2, fontSize = 11.sp)
                }
                row.lendable?.let { lendable ->
                    Spacer(Modifier.height(5.dp))
                    LendPill(lendable)
                }
                if (row.readEntries.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    ReadBadgeRow(row.readEntries)
                }
            }
            Text("›", color = colors.green, fontSize = 16.sp)
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

@Composable
private fun ReadBadgeRow(entries: List<ReadBadgeEntry>) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = "📗よんだ",
            color = colors.greenInk,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(colors.greenBg)
                .padding(horizontal = 7.dp, vertical = 1.dp),
        )
        entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MemberDot(entry.memberColorHex, size = 7.dp)
                Text(
                    text = "${entry.memberName} ${entry.loanMonthLabel}",
                    color = colors.ink2,
                    fontSize = 10.5.sp,
                )
            }
        }
    }
}
