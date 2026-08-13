package com.fallgist.nishinomiyalibrary.ui.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.ui.components.EmptyNote
import com.fallgist.nishinomiyalibrary.ui.components.MemberDot
import com.fallgist.nishinomiyalibrary.ui.components.MemberFilterRow
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfEditItem

/** 重要な操作領域を見た目に依存せずCompose回帰テストから特定する。 */
object BookshelfScreenTestTags {
    const val CREATE_SHELF = "bookshelf-create-shelf"

    fun bookCard(memberId: Long, shelfNo: Int, tilcod: String): String =
        "bookshelf-book-$memberId-$shelfNo-$tilcod"
}

object BookshelfEditingDialogTestTags {
    const val ADD_ITEM_CONFIRM = "bookshelf-add-item-confirm"
    const val ERROR_COPY = "bookshelf-error-copy"
    const val ERROR_CLOSE = "bookshelf-error-close"
    const val RESULT_COPY = "bookshelf-result-copy"
    const val RESULT_CLOSE = "bookshelf-result-close"

    fun editItemDelete(tilcod: String): String = "bookshelf-edit-item-delete-$tilcod"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    state: BookshelfUiState,
    editingState: BookshelfEditingUiState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onSelectMember: (Long?) -> Unit,
    onOpenMenu: () -> Unit,
    onOpenDetail: (tilcod: String, title: String) -> Unit,
    onRequestCreateShelf: () -> Unit,
    onRequestEditShelf: (BookshelfShelfTarget) -> Unit,
    onRequestDeleteShelf: (BookshelfShelfTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Column(modifier = modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "本棚", onOpenMenu = onOpenMenu)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(
                onClick = onRequestCreateShelf,
                enabled = !editingState.processing,
                modifier = Modifier.testTag(BookshelfScreenTestTags.CREATE_SHELF),
            ) {
                Text(if (editingState.processing) "本棚を処理中…" else "本棚を作成")
            }
        }
        MemberFilterRow(
            members = state.members,
            selectedMemberId = state.selectedMemberId,
            onSelect = onSelectMember,
        )
        // 本棚はLazyRow(横)の中に列ごとのLazyColumn(縦)が並ぶ構造。縦プルが列内リストに
        // 消費されず親のPullToRefreshBoxへ届くかは実機未検証(docs/design/pull-to-refresh.md §4.7)。
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.columns.isEmpty()) {
                // 空状態でもプルできるよう、スクロール可能なコンポーネントで包む。
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyNote("マイ本棚の登録はありません")
                }
            } else {
                // 全員分の本棚を横並びで一覧できるようにする(確定仕様)。
                LazyRow(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.columns) { column ->
                        ShelfColumnView(
                            column = column,
                            editingDisabled = editingState.processing,
                            onOpenDetail = onOpenDetail,
                            onRequestEditShelf = onRequestEditShelf,
                            onRequestDeleteShelf = onRequestDeleteShelf,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfColumnView(
    column: ShelfColumn,
    editingDisabled: Boolean,
    onOpenDetail: (tilcod: String, title: String) -> Unit,
    onRequestEditShelf: (BookshelfShelfTarget) -> Unit,
    onRequestDeleteShelf: (BookshelfShelfTarget) -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(14.dp)),
    ) {
        // 本棚タイトル: 頭にメンバー識別色。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            MemberDot(column.memberColorHex, size = 11.dp)
            Text(
                text = column.shelfName,
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ShelfOverflowMenu(
                enabled = !editingDisabled,
                onEdit = {
                    onRequestEditShelf(
                        BookshelfShelfTarget(
                            memberId = column.memberId,
                            shelfNo = column.shelfNo,
                            memberName = column.memberName,
                            shelfName = column.shelfName,
                            itemCount = column.books.size,
                            shelfCount = column.memberShelfCount,
                            items = column.books.map { BookshelfEditItem(it.tilcod, it.title, it.memo, it.memo) },
                        ),
                    )
                },
                onDelete = {
                    onRequestDeleteShelf(
                        BookshelfShelfTarget(
                            memberId = column.memberId,
                            shelfNo = column.shelfNo,
                            memberName = column.memberName,
                            shelfName = column.shelfName,
                            itemCount = column.books.size,
                            shelfCount = column.memberShelfCount,
                        ),
                    )
                },
            )
        }
        HorizontalDivider(color = colors.line)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
        ) {
            if (column.books.isEmpty()) {
                item {
                    EmptyNote("登録資料はありません")
                }
            } else {
                items(column.books) { book ->
                    ShelfBookView(
                        book = book,
                        shelf = column,
                        onClick = { onOpenDetail(book.tilcod, book.title) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelfBookView(
    book: ShelfBook,
    shelf: ShelfColumn,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.paper)
            .border(1.dp, colors.line, RoundedCornerShape(10.dp))
            .testTag(BookshelfScreenTestTags.bookCard(shelf.memberId, shelf.shelfNo, book.tilcod))
            .clickable(enabled = book.tilcod.isNotBlank(), onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier
                    .weight(1f),
            ) {
                Text(
                    text = book.title,
                    color = colors.ink,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.memo.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(text = book.memo, color = colors.ink2, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(2.dp))
                Text(text = "登録 ${book.registeredDateLabel}", color = colors.ink2, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ShelfOverflowMenu(enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // Text と DropdownMenu を Box で包み、親Rowから見た子要素数を展開状態によらず1つに保つ。
    // 兄弟のままだと親の Arrangement.spacedBy が DropdownMenu も子として数え、
    // 展開時に⋮の位置がずれる(修正3)。
    Box {
        Text(
            text = "⋮",
            color = LocalAppColors.current.ink2,
            fontSize = 20.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) { expanded = true }.padding(horizontal = 5.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("本棚を編集") }, onClick = { expanded = false; onEdit() })
            DropdownMenuItem(text = { Text("本棚を削除", color = LocalAppColors.current.alert) }, onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
fun BookshelfEditingDialogs(
    editingState: BookshelfEditingUiState,
    onSelectCreateMember: (Long) -> Unit,
    onSelectAddItemMember: (Long) -> Unit,
    onSelectAddItemShelf: (Int) -> Unit,
    onUpdateInput: (String) -> Unit,
    onUpdateEditShelfMemo: (String, String) -> Unit,
    onRequestInputConfirmation: () -> Unit,
    onDismissDialog: () -> Unit,
    onConfirm: () -> Unit,
    onDismissConfirmation: () -> Unit,
    onClearResult: () -> Unit,
    onClearError: () -> Unit,
    onRequestDeleteItem: (BookshelfItemTarget) -> Unit = {},
) {
    val colors = LocalAppColors.current
    val clipboardManager = LocalClipboardManager.current
    editingState.errorMessage?.let { message ->
        DisableSelection {
            AlertDialog(
                onDismissRequest = onClearError,
                title = { Text("本棚の操作を完了できませんでした") },
                text = { Text(message, color = colors.alert) },
                confirmButton = {
                    Button(
                        onClick = onClearError,
                        modifier = Modifier.testTag(BookshelfEditingDialogTestTags.ERROR_CLOSE),
                    ) { Text("閉じる") }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { clipboardManager.setText(AnnotatedString(message)) },
                        modifier = Modifier.testTag(BookshelfEditingDialogTestTags.ERROR_COPY),
                    ) { Text("メッセージをコピー") }
                },
            )
        }
    }
    editingState.dialog?.let { dialog ->
        if (dialog is BookshelfEditingDialog.AddItem) {
            AddItemDialog(
                dialog = dialog,
                members = editingState.members,
                shelves = editingState.addItemShelves,
                shelvesLoaded = editingState.addItemShelvesLoadedForMemberId == dialog.memberId,
                inputError = editingState.inputError,
                onSelectMember = onSelectAddItemMember,
                onSelectShelf = onSelectAddItemShelf,
                onMemoChange = onUpdateInput,
                onConfirm = onRequestInputConfirmation,
                onDismiss = onDismissDialog,
            )
        } else {
            BookshelfEditingInputDialog(
                dialog = dialog,
                members = editingState.members,
                inputError = editingState.inputError,
                editingDisabled = editingState.processing,
                onSelectCreateMember = onSelectCreateMember,
                onInputChange = onUpdateInput,
                onEditMemoChange = onUpdateEditShelfMemo,
                onConfirm = onRequestInputConfirmation,
                onDismiss = onDismissDialog,
                onRequestDeleteItem = onRequestDeleteItem,
            )
        }
    }
    editingState.pendingConfirmation?.let { confirmation ->
        BookshelfEditingConfirmDialog(confirmation, onConfirm, onDismissConfirmation)
    }
    editingState.result?.let { result -> BookshelfEditingResultDialog(result, onClearResult) }
}

@Composable
private fun AddItemDialog(
    dialog: BookshelfEditingDialog.AddItem,
    members: List<com.fallgist.nishinomiyalibrary.domain.model.Member>,
    shelves: List<com.fallgist.nishinomiyalibrary.domain.model.BookshelfContent>,
    shelvesLoaded: Boolean,
    inputError: String?,
    onSelectMember: (Long) -> Unit,
    onSelectShelf: (Int) -> Unit,
    onMemoChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var memberMenuExpanded by remember(dialog) { mutableStateOf(false) }
    var shelfMenuExpanded by remember(dialog.memberId) { mutableStateOf(false) }
    val memberName = members.find { it.id == dialog.memberId }?.name ?: "メンバーを選択"
    val shelfName = shelves.find { it.shelfNo == dialog.shelfNo }?.name ?: "本棚を選択"
    val confirmEnabled = dialog.memberId != null && shelvesLoaded && shelves.isNotEmpty() &&
        dialog.shelfNo != null && shelves.any { it.shelfNo == dialog.shelfNo } &&
        dialog.memo.length <= BookshelfEditingUiController.MAX_MEMO_LENGTH
    DisableSelection {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("本棚へ追加") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("資料名：${dialog.title}", fontSize = 12.sp)
                Text("対象メンバー", color = LocalAppColors.current.ink2, fontSize = 12.sp)
                Text(
                    memberName,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LocalAppColors.current.card)
                        .border(1.dp, LocalAppColors.current.line, RoundedCornerShape(8.dp))
                        .clickable { memberMenuExpanded = true }.padding(12.dp),
                )
                DropdownMenu(expanded = memberMenuExpanded, onDismissRequest = { memberMenuExpanded = false }) {
                    members.forEach { member ->
                        DropdownMenuItem(text = { Text(member.name) }, onClick = {
                            memberMenuExpanded = false
                            onSelectMember(member.id)
                        })
                    }
                }
                Text("追加先の本棚", color = LocalAppColors.current.ink2, fontSize = 12.sp)
                Text(
                    shelfName,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LocalAppColors.current.card)
                        .border(1.dp, LocalAppColors.current.line, RoundedCornerShape(8.dp))
                        .clickable(enabled = dialog.memberId != null && shelvesLoaded && shelves.isNotEmpty()) { shelfMenuExpanded = true }.padding(12.dp),
                )
                DropdownMenu(expanded = shelfMenuExpanded, onDismissRequest = { shelfMenuExpanded = false }) {
                    shelves.forEach { shelf ->
                        DropdownMenuItem(text = { Text(shelf.name) }, onClick = {
                            shelfMenuExpanded = false
                            onSelectShelf(shelf.shelfNo)
                        })
                    }
                }
                if (dialog.memberId != null && !shelvesLoaded) {
                    Text("本棚を読み込んでいます", color = LocalAppColors.current.ink2, fontSize = 12.sp)
                } else if (dialog.memberId != null && shelves.isEmpty()) {
                    Text("先に本棚を作成してください", color = LocalAppColors.current.alert, fontSize = 12.sp)
                }
                OutlinedTextField(
                    value = dialog.memo,
                    onValueChange = onMemoChange,
                    label = { Text("メモ（1000文字以内）") },
                    isError = inputError != null,
                    minLines = 3,
                    maxLines = 6,
                )
                inputError?.let { Text(it, color = LocalAppColors.current.alert, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    modifier = Modifier.testTag(BookshelfEditingDialogTestTags.ADD_ITEM_CONFIRM),
                ) { Text("確認へ") }
            },
            dismissButton = { OutlinedButton(onClick = onDismiss) { Text("戻る") } },
        )
    }
}

/** 本棚編集ダイアログの資料行から、既存の資料削除フローが使う確認対象を組み立てる。 */
private fun BookshelfShelfTarget.toItemTarget(item: BookshelfEditItem) = BookshelfItemTarget(
    memberId = memberId,
    shelfNo = shelfNo,
    shelfName = shelfName,
    tilcod = item.tilcod,
    title = item.title,
    memo = item.originalMemo,
    itemCount = itemCount,
    shelfCount = shelfCount,
)

@Composable
private fun BookshelfEditingInputDialog(
    dialog: BookshelfEditingDialog,
    members: List<com.fallgist.nishinomiyalibrary.domain.model.Member>,
    inputError: String?,
    editingDisabled: Boolean,
    onSelectCreateMember: (Long) -> Unit,
    onInputChange: (String) -> Unit,
    onEditMemoChange: (String, String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onRequestDeleteItem: (BookshelfItemTarget) -> Unit,
) {
    var memberMenuExpanded by remember(dialog) { mutableStateOf(false) }
    val title = when (dialog) {
        is BookshelfEditingDialog.CreateShelf -> "本棚を作成"
        is BookshelfEditingDialog.AddItem -> "本棚へ追加"
        is BookshelfEditingDialog.EditShelf -> "本棚を編集"
    }
    DisableSelection {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dialog is BookshelfEditingDialog.CreateShelf) {
                    val memberName = members.find { it.id == dialog.memberId }?.name ?: "メンバーを選択"
                    Text("対象メンバー", color = LocalAppColors.current.ink2, fontSize = 12.sp)
                    // Text と DropdownMenu を Box で包み、展開時に周囲のレイアウトが動かないようにする(修正3と同じ理由)。
                    Box {
                        Text(
                            memberName,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LocalAppColors.current.card)
                                .border(1.dp, LocalAppColors.current.line, RoundedCornerShape(8.dp))
                                .clickable { memberMenuExpanded = true }.padding(12.dp),
                        )
                        DropdownMenu(expanded = memberMenuExpanded, onDismissRequest = { memberMenuExpanded = false }) {
                            members.forEach { member ->
                                DropdownMenuItem(text = { Text(member.name) }, onClick = {
                                    memberMenuExpanded = false
                                    onSelectCreateMember(member.id)
                                })
                            }
                        }
                    }
                }
                val value = when (dialog) {
                    is BookshelfEditingDialog.CreateShelf -> dialog.name
                    is BookshelfEditingDialog.AddItem -> dialog.memo
                    is BookshelfEditingDialog.EditShelf -> dialog.name
                }
                val isMemo = dialog is BookshelfEditingDialog.AddItem
                OutlinedTextField(
                    value = value,
                    onValueChange = onInputChange,
                    label = { Text(if (isMemo) "メモ（1000文字以内）" else "本棚名（50文字以内）") },
                    isError = inputError != null,
                    minLines = if (isMemo) 3 else 1,
                    maxLines = if (isMemo) 6 else 1,
                )
                if (dialog is BookshelfEditingDialog.EditShelf) {
                    Text("資料メモ（1000文字以内）", color = LocalAppColors.current.ink2, fontSize = 12.sp)
                    Column(modifier = Modifier.height(280.dp).verticalScroll(rememberScrollState())) {
                        dialog.items.forEach { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(item.title, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                // 資料削除は本棚編集(名前+メモの一括更新)とは別の送信経路のため、
                                // ここでの削除は編集中の入力を保存しない(修正1)。
                                Text(
                                    text = "削除",
                                    color = LocalAppColors.current.alert,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .testTag(BookshelfEditingDialogTestTags.editItemDelete(item.tilcod))
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(enabled = !editingDisabled) {
                                            onRequestDeleteItem(dialog.target.toItemTarget(item))
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            OutlinedTextField(
                                value = item.newMemo,
                                onValueChange = { onEditMemoChange(item.tilcod, it) },
                                label = { Text("メモ") },
                                minLines = 2,
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                inputError?.let { Text(it, color = LocalAppColors.current.alert, fontSize = 12.sp) }
                }
            },
            confirmButton = { Button(onClick = onConfirm) { Text("確認へ") } },
            dismissButton = { OutlinedButton(onClick = onDismiss) { Text("戻る") } },
        )
    }
}

@Composable
private fun BookshelfEditingConfirmDialog(
    confirmation: BookshelfEditingConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    val destructive = BookshelfEditingContentBuilder.isDestructive(confirmation)
    DisableSelection {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(BookshelfEditingContentBuilder.confirmationTitle(confirmation)) },
            text = { Text(BookshelfEditingContentBuilder.confirmationMessage(confirmation)) },
            confirmButton = {
                Button(
                    onClick = onConfirm,
                    colors = if (destructive) ButtonDefaults.buttonColors(containerColor = colors.alert, contentColor = colors.card) else ButtonDefaults.buttonColors(),
                ) { Text(BookshelfEditingContentBuilder.confirmLabel(confirmation)) }
            },
            dismissButton = { OutlinedButton(onClick = onDismiss) { Text("戻る") } },
        )
    }
}

@Composable
private fun BookshelfEditingResultDialog(result: BookshelfEditingResultMessage, onClose: () -> Unit) {
    val colors = LocalAppColors.current
    val clipboardManager = LocalClipboardManager.current
    val color = when (result.kind) {
        BookshelfEditingResultKind.APPLIED -> colors.greenInk
        BookshelfEditingResultKind.ALREADY_REGISTERED -> colors.cautionInk
        BookshelfEditingResultKind.UNKNOWN -> colors.cautionInk
        BookshelfEditingResultKind.FAILURE -> colors.alert
    }
    DisableSelection {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(result.title) },
            text = { Text(result.message, color = color) },
            confirmButton = {
                Button(
                    onClick = onClose,
                    modifier = Modifier.testTag(BookshelfEditingDialogTestTags.RESULT_CLOSE),
                ) { Text("閉じる") }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { clipboardManager.setText(AnnotatedString(result.message)) },
                    modifier = Modifier.testTag(BookshelfEditingDialogTestTags.RESULT_COPY),
                ) { Text("メッセージをコピー") }
            },
        )
    }
}
