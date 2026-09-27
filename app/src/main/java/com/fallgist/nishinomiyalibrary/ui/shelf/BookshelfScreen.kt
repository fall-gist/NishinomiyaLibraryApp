package com.fallgist.nishinomiyalibrary.ui.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.fallgist.nishinomiyalibrary.ui.components.EmptyNote
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    const val BULK_ADD_ITEMS_CONFIRM = "bookshelf-bulk-add-items-confirm"
    const val BULK_ADD_ITEMS_FINAL_CONFIRM = "bookshelf-bulk-add-items-final-confirm"
    const val BULK_ADD_ITEMS_RESULTS_CLOSE = "bookshelf-bulk-add-items-results-close"

    fun editItemDelete(tilcod: String): String = "bookshelf-edit-item-delete-$tilcod"
    fun editItemDragHandle(tilcod: String): String = "bookshelf-edit-item-drag-$tilcod"
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
    onMoveEditShelfItemTo: (String, Int) -> Unit = { _, _ -> },
    onRequestInputConfirmation: () -> Unit,
    onDismissDialog: () -> Unit,
    onConfirm: () -> Unit,
    onDismissConfirmation: () -> Unit,
    onClearResult: () -> Unit,
    onClearError: () -> Unit,
    onRequestDeleteItem: (BookshelfItemTarget) -> Unit = {},
    // ------------------------------------------------------------------
    // 一斉本棚追加(`docs/design/bulk-bookshelf-add.md` §5.2〜§5.3)
    // ------------------------------------------------------------------
    onConfirmBulkAdd: () -> Unit = {},
    onDismissBulkAddConfirmation: () -> Unit = {},
    onClearBulkAddResults: () -> Unit = {},
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
        when (dialog) {
            is BookshelfEditingDialog.AddItem -> AddItemDialog(
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
            is BookshelfEditingDialog.BulkAddItems -> BulkAddItemsDialog(
                dialog = dialog,
                members = editingState.members,
                shelves = editingState.addItemShelves,
                shelvesLoaded = editingState.addItemShelvesLoadedForMemberId == dialog.memberId,
                inputError = editingState.inputError,
                onSelectMember = onSelectAddItemMember,
                onSelectShelf = onSelectAddItemShelf,
                onConfirm = onRequestInputConfirmation,
                onDismiss = onDismissDialog,
            )
            else -> BookshelfEditingInputDialog(
                dialog = dialog,
                members = editingState.members,
                inputError = editingState.inputError,
                editingDisabled = editingState.processing,
                onSelectCreateMember = onSelectCreateMember,
                onInputChange = onUpdateInput,
                onEditMemoChange = onUpdateEditShelfMemo,
                onMoveEditShelfItemTo = onMoveEditShelfItemTo,
                onConfirm = onRequestInputConfirmation,
                onDismiss = onDismissDialog,
                onRequestDeleteItem = onRequestDeleteItem,
            )
        }
    }
    editingState.pendingConfirmation?.let { confirmation ->
        BookshelfEditingConfirmDialog(confirmation, onConfirm, onDismissConfirmation)
    }
    editingState.bulkAddPendingConfirmation?.let { confirmation ->
        BookshelfBulkAddConfirmDialog(confirmation, onConfirmBulkAdd, onDismissBulkAddConfirmation)
    }
    editingState.result?.let { result -> BookshelfEditingResultDialog(result, onClearResult) }
    // 進捗表示は画面下の帯へ移した(`docs/design/operation-progress-banner.md` §2.5)。閉じられない
    // ダイアログはここでは出さない。結果ダイアログは残す。
    editingState.bulkAddResults?.let { results -> BookshelfBulkAddResultsDialog(results, onClearBulkAddResults) }
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
                                onSelectMember(member.id)
                            })
                        }
                    }
                }
                Text("追加先の本棚", color = LocalAppColors.current.ink2, fontSize = 12.sp)
                Box {
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

/**
 * 一斉本棚追加(`docs/design/bulk-bookshelf-add.md` §5.2)のメンバー・本棚選択ダイアログ。
 * [AddItemDialog]とほぼ同じ構成だが、メモ欄を持たない(一斉追加のメモは常に空文字)。
 */
@Composable
private fun BulkAddItemsDialog(
    dialog: BookshelfEditingDialog.BulkAddItems,
    members: List<com.fallgist.nishinomiyalibrary.domain.model.Member>,
    shelves: List<com.fallgist.nishinomiyalibrary.domain.model.BookshelfContent>,
    shelvesLoaded: Boolean,
    inputError: String?,
    onSelectMember: (Long) -> Unit,
    onSelectShelf: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var memberMenuExpanded by remember(dialog) { mutableStateOf(false) }
    var shelfMenuExpanded by remember(dialog.memberId) { mutableStateOf(false) }
    val memberName = members.find { it.id == dialog.memberId }?.name ?: "メンバーを選択"
    val shelfName = shelves.find { it.shelfNo == dialog.shelfNo }?.name ?: "本棚を選択"
    val confirmEnabled = dialog.memberId != null && shelvesLoaded && shelves.isNotEmpty() &&
        dialog.shelfNo != null && shelves.any { it.shelfNo == dialog.shelfNo }
    DisableSelection {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("本棚へ追加（${dialog.items.size}件）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("対象メンバー", color = LocalAppColors.current.ink2, fontSize = 12.sp)
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
                                onSelectMember(member.id)
                            })
                        }
                    }
                }
                Text("追加先の本棚", color = LocalAppColors.current.ink2, fontSize = 12.sp)
                Box {
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
                }
                if (dialog.memberId != null && !shelvesLoaded) {
                    Text("本棚を読み込んでいます", color = LocalAppColors.current.ink2, fontSize = 12.sp)
                } else if (dialog.memberId != null && shelves.isEmpty()) {
                    Text("先に本棚を作成してください", color = LocalAppColors.current.alert, fontSize = 12.sp)
                }
                inputError?.let { Text(it, color = LocalAppColors.current.alert, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    modifier = Modifier.testTag(BookshelfEditingDialogTestTags.BULK_ADD_ITEMS_CONFIRM),
                ) { Text("確認へ") }
            },
            dismissButton = { OutlinedButton(onClick = onDismiss) { Text("戻る") } },
        )
    }
}

/** 一斉本棚追加の最終確認(`docs/design/bulk-bookshelf-add.md` §5.2「太郎の本棚『読みたい』へ5件を追加します」)。 */
@Composable
private fun BookshelfBulkAddConfirmDialog(
    confirmation: BookshelfBulkAddConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DisableSelection {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("本棚に追加しますか？") },
            text = {
                Text("${confirmation.memberName}の本棚『${confirmation.shelfName}』へ${confirmation.itemCount}件を追加します")
            },
            confirmButton = {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.testTag(BookshelfEditingDialogTestTags.BULK_ADD_ITEMS_FINAL_CONFIRM),
                ) { Text("この本棚に追加") }
            },
            dismissButton = { OutlinedButton(onClick = onDismiss) { Text("戻る") } },
        )
    }
}

/** 一斉本棚追加の結果(件ごとの成否、`docs/design/bulk-bookshelf-add.md` §5.3)。 */
@Composable
private fun BookshelfBulkAddResultsDialog(results: BookshelfBulkAddResultSummary, onClose: () -> Unit) {
    val colors = LocalAppColors.current
    DisableSelection {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("本棚への追加結果") },
            text = {
                Column {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(results.rows) { row ->
                            val color = when (row.message.kind) {
                                BookshelfEditingResultKind.APPLIED -> colors.greenInk
                                BookshelfEditingResultKind.ALREADY_REGISTERED -> colors.cautionInk
                                BookshelfEditingResultKind.UNKNOWN -> colors.cautionInk
                                BookshelfEditingResultKind.FAILURE -> colors.alert
                                BookshelfEditingResultKind.NOT_ATTEMPTED -> colors.ink2
                            }
                            Text(
                                "${row.title}：${row.message.message}",
                                color = color,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                    if (results.localRefreshRequired) {
                        Text(
                            "表示更新に失敗しました。画面を更新してください",
                            color = colors.cautionInk,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onClose,
                    modifier = Modifier.testTag(BookshelfEditingDialogTestTags.BULK_ADD_ITEMS_RESULTS_CLOSE),
                ) { Text("閉じる") }
            },
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

/**
 * 本棚編集の資料メモ欄(縮小版)。`OutlinedTextField`の最小の高さ(56dp)をやめ、
 * `BasicTextField`と自前の枠で余白を詰める(docs/design/bookshelf-order.md §4.5)。
 * 値の保持・文字数の検証はこれまでどおり呼び出し元([onValueChange]の先)に委ねる。
 */
@Composable
private fun CompactMemoField(value: String, onValueChange: (String) -> Unit) {
    val colors = LocalAppColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.paper, RoundedCornerShape(6.dp))
            .border(1.dp, colors.line, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        textStyle = TextStyle(fontSize = 12.sp, color = colors.ink),
        minLines = 1,
        maxLines = 3,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.ink),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) {
                    Text("メモ", fontSize = 12.sp, color = colors.ink2)
                }
                innerTextField()
            }
        },
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookshelfEditingInputDialog(
    dialog: BookshelfEditingDialog,
    members: List<com.fallgist.nishinomiyalibrary.domain.model.Member>,
    inputError: String?,
    editingDisabled: Boolean,
    onSelectCreateMember: (Long) -> Unit,
    onInputChange: (String) -> Unit,
    onEditMemoChange: (String, String) -> Unit,
    onMoveEditShelfItemTo: (String, Int) -> Unit = { _, _ -> },
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onRequestDeleteItem: (BookshelfItemTarget) -> Unit,
) {
    var memberMenuExpanded by remember(dialog) { mutableStateOf(false) }
    val dragThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }
    val title = when (dialog) {
        is BookshelfEditingDialog.CreateShelf -> "本棚を作成"
        is BookshelfEditingDialog.AddItem -> "本棚へ追加"
        // 一斉追加は[BulkAddItemsDialog]が専用で描画する。ここには到達しないが、
        // sealed interfaceの網羅性を保つために分岐だけ用意する。
        is BookshelfEditingDialog.BulkAddItems -> "本棚へ追加"
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
                    is BookshelfEditingDialog.BulkAddItems -> ""
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
                    Text(
                        "資料メモ（1000文字以内）・「⠿」の長押しドラッグで並べ替え",
                        color = LocalAppColors.current.ink2,
                        fontSize = 12.sp,
                    )
                    // ------------------------------------------------------------------
                    // ドラッグの操作性改善(docs/design/bookshelf-order.md §4.5)。
                    // 以前は各行の「⠿」ごとにドラッグ検知を持ち、キーを資料番号にしていたため、
                    // 1回入れ替わると行の並びが変わって検知が途切れ「隣とだけ入れ替わる」不具合になっていた。
                    // ここではドラッグの状態(どの資料を掴んでいるか・指の位置)を一覧の側(この画面)でまとめて持ち、
                    // 一覧の並びはmoveEditShelfItemTo経由でControllerへ通知する(並びの正本はControllerのまま)。
                    // ------------------------------------------------------------------
                    val density = LocalDensity.current
                    val haptics = LocalHapticFeedback.current
                    val scrollState = rememberScrollState()
                    val coroutineScope = rememberCoroutineScope()
                    // ドラッグ中は全行のメモを畳んで高さを揃える(§4.5)ため、実測した行の高さを
                    // 「1件分の移動量」として使う(未計測の間はしきい値48dpで代用)。
                    var rowHeightPx by remember { mutableFloatStateOf(dragThresholdPx) }
                    var draggedTilcod by remember { mutableStateOf<String?>(null) }
                    var dragStartIndex by remember { mutableStateOf(0) }
                    // 指の移動量(px)の累積。自動スクロール分もここへ足し込む。AutoReservationRuleDragと同じ考え方だが、
                    // 1ステップずつ隣と入れ替えるのではなく、開始位置+ステップ数で目標位置を直接計算する
                    // (何件もまたいで一度に動かせるようにするため)。
                    var accumulatedPx by remember { mutableFloatStateOf(0f) }
                    // 並びの入れ替え(1行ぶん=rowHeightPx)で吸収しきれなかった端数。掴んだタイルをこの分だけ
                    // graphicsLayerのtranslationYでずらし、指に付いて動いて見えるようにする(§4.5レビュー指摘対応)。
                    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
                    var pointerRootY by remember { mutableFloatStateOf(0f) }
                    var containerTopRoot by remember { mutableFloatStateOf(0f) }
                    var containerBottomRoot by remember { mutableFloatStateOf(0f) }
                    val handleTopRoot = remember { mutableStateMapOf<String, Float>() }
                    val bringIntoViewRequesters = remember { mutableStateMapOf<String, BringIntoViewRequester>() }

                    fun applyDragProgress() {
                        val tilcod = draggedTilcod ?: return
                        val items = dialog.items
                        val currentIndex = items.indexOfFirst { it.tilcod == tilcod }
                        if (currentIndex == -1) return
                        val rawSteps = com.fallgist.nishinomiyalibrary.ui.settings.AutoReservationRuleDrag.steps(accumulatedPx, rowHeightPx)
                        val targetIndex = (dragStartIndex + rawSteps).coerceIn(0, items.lastIndex)
                        // 一覧の端で移動しきれない(クランプされた)分も含め、実際に反映された行数(appliedSteps)
                        // ぶんだけをaccumulatedPxから差し引いた残りが、指の位置とタイルの現在位置のずれになる。
                        val appliedSteps = targetIndex - dragStartIndex
                        dragOffsetPx = accumulatedPx - appliedSteps * rowHeightPx
                        if (targetIndex != currentIndex) onMoveEditShelfItemTo(tilcod, targetIndex)
                    }

                    // 指が一覧の見えている範囲の上端・下端に近づいている間、その方向へスクロールし続ける(自動スクロール)。
                    // スクロールした分もaccumulatedPxへ足し込み、スクロール中も落とす先を更新し続ける。
                    LaunchedEffect(draggedTilcod) {
                        if (draggedTilcod == null) return@LaunchedEffect
                        val edgeZonePx = with(density) { 56.dp.toPx() }
                        val maxSpeedPx = with(density) { 20.dp.toPx() }
                        while (true) {
                            val distanceFromTop = pointerRootY - containerTopRoot
                            val distanceFromBottom = containerBottomRoot - pointerRootY
                            val scrollDelta = when {
                                distanceFromTop in 0f..edgeZonePx -> -maxSpeedPx * (1f - distanceFromTop / edgeZonePx)
                                distanceFromBottom in 0f..edgeZonePx -> maxSpeedPx * (1f - distanceFromBottom / edgeZonePx)
                                else -> 0f
                            }
                            if (scrollDelta != 0f) {
                                val consumed = scrollState.scrollBy(scrollDelta)
                                if (consumed != 0f) {
                                    accumulatedPx += consumed
                                    applyDragProgress()
                                }
                            }
                            delay(16)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .height(280.dp)
                            .onGloballyPositioned { coordinates ->
                                containerTopRoot = coordinates.positionInRoot().y
                                containerBottomRoot = containerTopRoot + coordinates.size.height
                            },
                    ) {
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
                            dialog.items.forEach { item ->
                                key(item.tilcod) {
                                    val isDragged = item.tilcod == draggedTilcod
                                    // ドラッグ開始時に全行のメモを畳み、書名だけの短い行にする(§4.5)。
                                    val collapsed = draggedTilcod != null
                                    val requester = remember(item.tilcod) {
                                        BringIntoViewRequester().also { bringIntoViewRequesters[item.tilcod] = it }
                                    }
                                    // 掴んだタイルはgraphicsLayerのtranslationYでレイアウト上の位置からずらして描く
                                    // (指に付いて動く§4.5)。そのためレイアウト上の元の場所は空いたままになるので、
                                    // 同じ場所に「落とす先」を示す空の枠を背面(zIndexなし)に描く。
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        if (isDragged) {
                                            Box(
                                                modifier = Modifier
                                                    .matchParentSize()
                                                    .background(LocalAppColors.current.paper, RoundedCornerShape(8.dp))
                                                    .border(1.dp, LocalAppColors.current.line, RoundedCornerShape(8.dp)),
                                            )
                                        }
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .zIndex(if (isDragged) 1f else 0f)
                                                .bringIntoViewRequester(requester)
                                                .onGloballyPositioned { coordinates ->
                                                    if (collapsed) rowHeightPx = coordinates.size.height.toFloat()
                                                }
                                                .graphicsLayer {
                                                    if (isDragged) {
                                                        translationY = dragOffsetPx
                                                        alpha = 0.6f
                                                        shadowElevation = 12f
                                                    }
                                                }
                                                // タイル: 書誌ごとの領域が分かるよう、背景と枠を付ける(§4.5)。
                                                .background(LocalAppColors.current.card, RoundedCornerShape(8.dp))
                                                .border(
                                                    width = 1.dp,
                                                    color = if (isDragged) LocalAppColors.current.green else LocalAppColors.current.line,
                                                    shape = RoundedCornerShape(8.dp),
                                                )
                                                .padding(8.dp),
                                        ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            // 「⠿」の長押しドラッグで並べ替えを開始する(docs/design/bookshelf-order.md §4.5)。
                                            Text(
                                                "⠿",
                                                color = LocalAppColors.current.ink2,
                                                fontSize = 18.sp,
                                                modifier = Modifier
                                                    .testTag(BookshelfEditingDialogTestTags.editItemDragHandle(item.tilcod))
                                                    .onGloballyPositioned { coordinates ->
                                                        handleTopRoot[item.tilcod] = coordinates.positionInRoot().y
                                                    }
                                                    .pointerInput(item.tilcod, editingDisabled) {
                                                        if (editingDisabled) return@pointerInput
                                                        detectDragGesturesAfterLongPress(
                                                            onDragStart = { offset ->
                                                                val startIndex = dialog.items.indexOfFirst { it.tilcod == item.tilcod }
                                                                if (startIndex != -1) {
                                                                    draggedTilcod = item.tilcod
                                                                    dragStartIndex = startIndex
                                                                    accumulatedPx = 0f
                                                                    dragOffsetPx = 0f
                                                                    pointerRootY = (handleTopRoot[item.tilcod] ?: 0f) + offset.y
                                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                }
                                                            },
                                                            onDrag = { change, amount ->
                                                                change.consume()
                                                                if (draggedTilcod == item.tilcod) {
                                                                    accumulatedPx += amount.y
                                                                    pointerRootY += amount.y
                                                                    applyDragProgress()
                                                                }
                                                            },
                                                            onDragEnd = {
                                                                val moved = draggedTilcod
                                                                draggedTilcod = null
                                                                accumulatedPx = 0f
                                                                dragOffsetPx = 0f
                                                                if (moved != null) {
                                                                    // 離した位置(空の枠の位置)にメモ欄が戻り、
                                                                    // 動かした資料が見える位置までスクロールする(§4.5)。
                                                                    coroutineScope.launch { bringIntoViewRequesters[moved]?.bringIntoView() }
                                                                }
                                                            },
                                                            onDragCancel = {
                                                                draggedTilcod = null
                                                                accumulatedPx = 0f
                                                                dragOffsetPx = 0f
                                                            },
                                                        )
                                                    }
                                                    .padding(end = 6.dp),
                                            )
                                            Text(item.title, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                            if (!collapsed) {
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
                                        }
                                        // ドラッグ中は全行のメモ欄を畳む(§4.5)。メモ欄自体もOutlinedTextFieldから
                                        // CompactMemoFieldへ縮小した(文字12sp・余白を詰める)。
                                        if (!collapsed) {
                                            CompactMemoField(
                                                value = item.newMemo,
                                                onValueChange = { onEditMemoChange(item.tilcod, it) },
                                            )
                                        }
                                        }
                                    }
                                }
                            }
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
        // 単件追加(この経路)ではNotAttemptedは発生しない。一斉追加の結果表示と型を共有するための分岐。
        BookshelfEditingResultKind.NOT_ATTEMPTED -> colors.ink2
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
