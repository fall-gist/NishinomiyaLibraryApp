package com.fallgist.nishinomiyalibrary.ui.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.ui.components.EmptyNote
import com.fallgist.nishinomiyalibrary.ui.components.MemberDot
import com.fallgist.nishinomiyalibrary.ui.components.MemberFilterRow
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

/** 重要な操作領域を見た目に依存せずCompose回帰テストから特定する。 */
object BookshelfScreenTestTags {
    const val CREATE_SHELF = "bookshelf-create-shelf"

    fun bookCard(memberId: Long, shelfNo: Int, tilcod: String): String =
        "bookshelf-book-$memberId-$shelfNo-$tilcod"

    fun bookMenu(memberId: Long, shelfNo: Int, tilcod: String): String =
        "${bookCard(memberId, shelfNo, tilcod)}-menu"
}

object BookshelfEditingDialogTestTags {
    const val ADD_ITEM_CONFIRM = "bookshelf-add-item-confirm"
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
    onRequestRenameShelf: (BookshelfShelfTarget) -> Unit,
    onRequestDeleteShelf: (BookshelfShelfTarget) -> Unit,
    onRequestEditMemo: (BookshelfItemTarget) -> Unit,
    onRequestDeleteItem: (BookshelfItemTarget) -> Unit,
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
                            onRequestRenameShelf = onRequestRenameShelf,
                            onRequestDeleteShelf = onRequestDeleteShelf,
                            onRequestEditMemo = onRequestEditMemo,
                            onRequestDeleteItem = onRequestDeleteItem,
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
    onRequestRenameShelf: (BookshelfShelfTarget) -> Unit,
    onRequestDeleteShelf: (BookshelfShelfTarget) -> Unit,
    onRequestEditMemo: (BookshelfItemTarget) -> Unit,
    onRequestDeleteItem: (BookshelfItemTarget) -> Unit,
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
            Text(text = column.memberName, color = colors.ink2, fontSize = 11.sp)
            ShelfOverflowMenu(
                enabled = !editingDisabled,
                onRename = {
                    onRequestRenameShelf(
                        BookshelfShelfTarget(
                            memberId = column.memberId,
                            shelfNo = column.shelfNo,
                            memberName = column.memberName,
                            shelfName = column.shelfName,
                            itemCount = column.books.size,
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
                        editingDisabled = editingDisabled,
                        onClick = { onOpenDetail(book.tilcod, book.title) },
                        onRequestEditMemo = onRequestEditMemo,
                        onRequestDeleteItem = onRequestDeleteItem,
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
    editingDisabled: Boolean,
    onClick: () -> Unit,
    onRequestEditMemo: (BookshelfItemTarget) -> Unit,
    onRequestDeleteItem: (BookshelfItemTarget) -> Unit,
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
            ShelfBookOverflowMenu(
                enabled = !editingDisabled && book.tilcod.isNotBlank(),
                testTag = BookshelfScreenTestTags.bookMenu(shelf.memberId, shelf.shelfNo, book.tilcod),
                onEditMemo = {
                    onRequestEditMemo(book.toItemTarget(shelf))
                },
                onDelete = {
                    onRequestDeleteItem(book.toItemTarget(shelf))
                },
            )
        }
    }
}

private fun ShelfBook.toItemTarget(shelf: ShelfColumn) = BookshelfItemTarget(
    memberId = shelf.memberId,
    shelfNo = shelf.shelfNo,
    shelfName = shelf.shelfName,
    tilcod = tilcod,
    title = title,
    memo = memo,
)

@Composable
private fun ShelfOverflowMenu(enabled: Boolean, onRename: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text = "⋮",
        color = LocalAppColors.current.ink2,
        fontSize = 20.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) { expanded = true }.padding(horizontal = 5.dp),
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(text = { Text("名前を変更") }, onClick = { expanded = false; onRename() })
        DropdownMenuItem(text = { Text("本棚を削除", color = LocalAppColors.current.alert) }, onClick = { expanded = false; onDelete() })
    }
}

@Composable
private fun ShelfBookOverflowMenu(
    enabled: Boolean,
    testTag: String,
    onEditMemo: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text = "⋮",
        color = LocalAppColors.current.ink2,
        fontSize = 18.sp,
        modifier = Modifier
            .testTag(testTag)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { expanded = true }
            .padding(horizontal = 4.dp),
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(text = { Text("メモを編集") }, onClick = { expanded = false; onEditMemo() })
        DropdownMenuItem(text = { Text("本棚から削除", color = LocalAppColors.current.alert) }, onClick = { expanded = false; onDelete() })
    }
}

@Composable
fun BookshelfEditingDialogs(
    editingState: BookshelfEditingUiState,
    onSelectCreateMember: (Long) -> Unit,
    onSelectAddItemMember: (Long) -> Unit,
    onSelectAddItemShelf: (Int) -> Unit,
    onUpdateInput: (String) -> Unit,
    onRequestInputConfirmation: () -> Unit,
    onDismissDialog: () -> Unit,
    onConfirm: () -> Unit,
    onDismissConfirmation: () -> Unit,
    onClearResult: () -> Unit,
    onClearError: () -> Unit,
) {
    val colors = LocalAppColors.current
    editingState.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onClearError,
            title = { Text("本棚の操作を完了できませんでした") },
            text = { Text(message, color = colors.alert) },
            confirmButton = { Button(onClick = onClearError) { Text("閉じる") } },
        )
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
                onSelectCreateMember = onSelectCreateMember,
                onInputChange = onUpdateInput,
                onConfirm = onRequestInputConfirmation,
                onDismiss = onDismissDialog,
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

@Composable
private fun BookshelfEditingInputDialog(
    dialog: BookshelfEditingDialog,
    members: List<com.fallgist.nishinomiyalibrary.domain.model.Member>,
    inputError: String?,
    onSelectCreateMember: (Long) -> Unit,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var memberMenuExpanded by remember(dialog) { mutableStateOf(false) }
    val title = when (dialog) {
        is BookshelfEditingDialog.CreateShelf -> "本棚を作成"
        is BookshelfEditingDialog.AddItem -> "本棚へ追加"
        is BookshelfEditingDialog.RenameShelf -> "本棚名を変更"
        is BookshelfEditingDialog.EditItemMemo -> "資料メモを編集"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dialog is BookshelfEditingDialog.CreateShelf) {
                    val memberName = members.find { it.id == dialog.memberId }?.name ?: "メンバーを選択"
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
                                onSelectCreateMember(member.id)
                            })
                        }
                    }
                }
                val value = when (dialog) {
                    is BookshelfEditingDialog.CreateShelf -> dialog.name
                    is BookshelfEditingDialog.AddItem -> dialog.memo
                    is BookshelfEditingDialog.RenameShelf -> dialog.name
                    is BookshelfEditingDialog.EditItemMemo -> dialog.memo
                }
                val isMemo = dialog is BookshelfEditingDialog.EditItemMemo || dialog is BookshelfEditingDialog.AddItem
                if (dialog is BookshelfEditingDialog.EditItemMemo) {
                    Text("資料名：${dialog.target.title}\n本棚：${dialog.target.shelfName}", fontSize = 12.sp)
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onInputChange,
                    label = { Text(if (isMemo) "メモ（1000文字以内）" else "本棚名（50文字以内）") },
                    isError = inputError != null,
                    minLines = if (isMemo) 3 else 1,
                    maxLines = if (isMemo) 6 else 1,
                )
                inputError?.let { Text(it, color = LocalAppColors.current.alert, fontSize = 12.sp) }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("確認へ") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("戻る") } },
    )
}

@Composable
private fun BookshelfEditingConfirmDialog(
    confirmation: BookshelfEditingConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    val destructive = BookshelfEditingContentBuilder.isDestructive(confirmation)
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

@Composable
private fun BookshelfEditingResultDialog(result: BookshelfEditingResultMessage, onClose: () -> Unit) {
    val colors = LocalAppColors.current
    val color = when (result.kind) {
        BookshelfEditingResultKind.APPLIED -> colors.greenInk
        BookshelfEditingResultKind.ALREADY_REGISTERED -> colors.cautionInk
        BookshelfEditingResultKind.UNKNOWN -> colors.cautionInk
        BookshelfEditingResultKind.FAILURE -> colors.alert
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(result.title) },
        text = { Text(result.message, color = color) },
        confirmButton = { Button(onClick = onClose) { Text("閉じる") } },
    )
}
