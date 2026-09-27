package com.fallgist.nishinomiyalibrary.ui.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
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
import kotlinx.coroutines.withTimeoutOrNull
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
                    //
                    // さらに§4.6: 実機調査で、検知を各行の「⠿」に付けたままだと、長押し成立直後の
                    // 再構成(全行のメモを畳む)でverticalScroll内部の部品が木から外れ、その子である「⠿」の
                    // 検知も道連れでonDragCancelされる不具合が見つかった。そのため検知そのものを
                    // verticalScrollの外側(一覧を囲むBox)へ移し、PointerEventPass.Initialで
                    // 祖先から先に指のイベントを見て消費することで、スクロール内部の部品の付け外しの
                    // 影響を受けないようにしている。
                    // ------------------------------------------------------------------
                    val density = LocalDensity.current
                    val haptics = LocalHapticFeedback.current
                    val scrollState = rememberScrollState()
                    val coroutineScope = rememberCoroutineScope()
                    val viewConfiguration = LocalViewConfiguration.current
                    // レビュー指摘: pointerInputの検知(下記)はeditingDisabledがキーのため、
                    // 一覧の並びが変わってもダイアログを開いている間ずっと同じコルーチンで動き続ける。
                    // そのコルーチンの中で直接`dialog`(このコンポーザブルの引数)を読むと、
                    // rememberUpdatedStateを使わない限りコルーチンが開始した時点の古い並びに固定されてしまう
                    // (2回目以降のドラッグで、掴んだ資料の現在位置を古い並びから取ってしまう不具合の原因)。
                    // ドラッグ開始位置の計算・移動先の計算は、必ずこの`latestDialog`(常に最新)経由で行う。
                    val latestDialog by rememberUpdatedState(dialog)
                    var draggedTilcod by remember { mutableStateOf<String?>(null) }
                    // 長押し成立後、指が押した位置からtouch slop以上動くまでは並びを変えない(§4.8)。
                    // §4.7で一覧の端に合わせきれず、タイルと枠がずれている状態のまま長押ししただけで
                    // 並びが変わってしまうのを防ぐためのフラグ。一度trueになったら、このドラッグの間はtrueのまま。
                    var hasMovedPastTouchSlop by remember { mutableStateOf(false) }
                    // 掴んだ書誌の「⠿」が指の真下に来るための、タイルの上端から見た指のオフセット(§4.8)。
                    // タイルの画面上の上端は常に「pointerRootY - grabOffset」で求める。
                    // (§4.7までの指の移動量の累積・行の高さでの割り算は使わない。落とす先は下記のとおり
                    // 画面上の位置そのもの(タイルの中心yと他の行の中心yの位置関係)から直接決める)
                    var grabOffset by remember { mutableFloatStateOf(0f) }
                    var pointerRootY by remember { mutableFloatStateOf(0f) }
                    var containerTopRoot by remember { mutableFloatStateOf(0f) }
                    var containerBottomRoot by remember { mutableFloatStateOf(0f) }
                    // 一覧を囲むBoxの画面上の位置(§4.6)。Box内で受け取る指の座標(Boxローカル)を、
                    // 「⠿」のboundsInRoot・containerTopRoot/BottomRootと同じ画面座標系に直すために使う。
                    var containerPositionInRoot by remember { mutableStateOf(Offset.Zero) }
                    // 各「⠿」の画面上の範囲(§4.6)。Box側で受けた指の位置がどの行の「⠿」に乗っているかの判定
                    // (ヒットテスト)に使う。boundsInRoot()はclipBounds既定trueのため、verticalScrollの
                    // clipScrollableContainer等の祖先で切り取られた範囲になる(見えていない部分を
                    // 押せないようにするためには、この「切り取られた」性質がむしろ正しい)。
                    val handleBoundsRoot = remember { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
                    // 各「⠿」の中心の画面上のy(§4.7レビュー指摘対応)。boundsInRootは祖先で切り取られるため、
                    // 掴んだ行が一覧の見えている範囲の外(上端の外)へ出ると中心yが実際より下に潰れてしまい、
                    // §4.7のdiff計算(畳んだ後のずれ)を過小評価する。畳んだ後の位置合わせには、切り取られない
                    // positionInRoot().y + size.height/2fをこちらに記録して使う。
                    // (positionInRoot()は祖先のgraphicsLayerのtranslationY等も反映するため、
                    // 待っている間に自動スクロールで指が動いても、ここで測るdiffは畳みによるずれだけになる)
                    val handleCenterRootY = remember { mutableStateMapOf<String, Float>() }
                    // 各行の「枠」(行全体のBox。translationYを持たない=落とす先の枠の位置)の画面上の上端と高さ(§4.8)。
                    // 切り取られない座標(positionInRoot・size)で記録する。落とす先は、掴んだタイルの中心yと
                    // 他の行の枠の中心yの位置関係から決める(BookshelfEditItemDrag.targetIndexByPosition)。
                    // 離した後の位置合わせ(§4.8方針2)にも使う。
                    val rowFrameTopRoot = remember { mutableStateMapOf<String, Float>() }
                    val rowFrameHeightRoot = remember { mutableStateMapOf<String, Float>() }
                    val bringIntoViewRequesters = remember { mutableStateMapOf<String, BringIntoViewRequester>() }
                    // レビュー指摘(付随): 資料の削除等で一覧から無くなった資料番号のエントリが
                    // 上記のマップに残り続けないよう、最新の並びに無いキーを毎回の再構成後に取り除く。
                    SideEffect {
                        val currentTilcods = dialog.items.map { it.tilcod }.toSet()
                        handleBoundsRoot.keys.retainAll(currentTilcods)
                        handleCenterRootY.keys.retainAll(currentTilcods)
                        rowFrameTopRoot.keys.retainAll(currentTilcods)
                        rowFrameHeightRoot.keys.retainAll(currentTilcods)
                        bringIntoViewRequesters.keys.retainAll(currentTilcods)
                    }

                    // 掴んだ書誌以外の各行の中心yの一覧(現在の並び順)を求める。フレーム未計測の行は除く
                    // (計測が届き次第、次のフレームや指のイベントで自然に反映される)。
                    fun otherRowCentersExcluding(tilcod: String): List<Float> =
                        latestDialog.items.mapNotNull { other ->
                            if (other.tilcod == tilcod) return@mapNotNull null
                            val top = rowFrameTopRoot[other.tilcod] ?: return@mapNotNull null
                            val height = rowFrameHeightRoot[other.tilcod] ?: dragThresholdPx
                            top + height / 2f
                        }

                    // §4.8: 落とす先を、指の移動量ではなく画面上の位置(掴んだタイルの中心yと他の行の中心yの
                    // 位置関係)から決める。長押しして指をtouch slop以上動かすまでは並びを変えない
                    // (§4.7で一覧の端に合わせきれず、タイルと枠がずれている状態のまま長押ししただけで
                    // 並びが変わるのを防ぐ)。
                    fun applyDragProgress() {
                        val tilcod = draggedTilcod ?: return
                        if (!hasMovedPastTouchSlop) return
                        // 必ず最新の並び(latestDialog)から件数と現在位置を取る(古い並びに固定されないため)。
                        val items = latestDialog.items
                        val currentIndex = items.indexOfFirst { it.tilcod == tilcod }
                        if (currentIndex == -1) return
                        // 自分の枠がまだ計測されていない間は、正しい高さ・落とす先が求まらないため何もしない。
                        val myFrameTop = rowFrameTopRoot[tilcod] ?: return
                        val myHeight = rowFrameHeightRoot[tilcod] ?: dragThresholdPx
                        val tileTop = pointerRootY - grabOffset
                        val tileCenterY = tileTop + myHeight / 2f
                        val otherCenters = otherRowCentersExcluding(tilcod)
                        val targetIndex = BookshelfEditItemDrag.targetIndexByPosition(tileCenterY, otherCenters)
                            .coerceIn(0, items.size - 1)
                        if (targetIndex != currentIndex) onMoveEditShelfItemTo(tilcod, targetIndex)
                    }

                    // 長押しが成立し、並べ替えを開始する。pointerRootYには押した位置の画面上のyを渡す。
                    fun startDrag(tilcod: String, rootPointerY: Float) {
                        // latestDialogに存在しない資料番号では開始しない
                        // (古いdialogを直接読むと、2回目以降のドラッグで開始位置がずれて誤った位置へ飛ぶ)。
                        val startIndex = latestDialog.items.indexOfFirst { it.tilcod == tilcod }
                        if (startIndex == -1) return
                        draggedTilcod = tilcod
                        hasMovedPastTouchSlop = false
                        pointerRootY = rootPointerY
                        // 畳んだ後(§4.7の合わせ込み後)にhandleCenterRootY基準で更新するまでの仮のgrabOffset。
                        // 未計測の間はrootPointerY自身を使い、オフセット0(タイル上端=指の位置)として破綻を避ける。
                        grabOffset = rootPointerY - (rowFrameTopRoot[tilcod] ?: rootPointerY)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }

                    // 離した後、配置が確定してから動かした書誌を見せる(§4.8方針2)。
                    // §4.7の合わせ込みと同じ考え方で、動かした書誌の枠の位置が変わるのを
                    // 数フレームの上限つきで待つ(畳む処理と同じくアニメーションが無いことが前提)。
                    // 待っている間に新しいドラッグが始まったら、この処理は中止する。
                    suspend fun settleReleasedPlacement(tilcod: String, releasedTopRoot: Float) {
                        val topBeforeSettle = rowFrameTopRoot[tilcod]
                        var settledTop = topBeforeSettle
                        var framesWaited = 0
                        while (framesWaited < 5) {
                            if (draggedTilcod != null) return
                            withFrameNanos { }
                            framesWaited++
                            val current = rowFrameTopRoot[tilcod]
                            settledTop = current
                            if (current != null && current != topBeforeSettle) break
                        }
                        if (draggedTilcod != null) return
                        val finalTop = settledTop ?: return
                        // 動かした書誌の上端(finalTop)が、離した時点のタイルの上端(releasedTopRoot)に
                        // 来るように一覧をスクロールする(離した場所に書誌が残って見える)。
                        val diff = finalTop - releasedTopRoot
                        if (diff != 0f) {
                            scrollState.scrollBy(diff)
                        }
                        if (draggedTilcod != null) return
                        // それでも一覧の見えている範囲からはみ出す場合は、見える位置へ寄せる。
                        val topAfterScroll = rowFrameTopRoot[tilcod] ?: finalTop
                        val heightAfterScroll = rowFrameHeightRoot[tilcod] ?: dragThresholdPx
                        val bottomAfterScroll = topAfterScroll + heightAfterScroll
                        if (topAfterScroll < containerTopRoot || bottomAfterScroll > containerBottomRoot) {
                            bringIntoViewRequesters[tilcod]?.bringIntoView()
                        }
                    }

                    // 指を離して並べ替えを終える。
                    fun finishDrag() {
                        val moved = draggedTilcod ?: return
                        // 離した時点のタイルの画面上の上端を覚えておく(§4.8方針2)。配置確定後、この位置へ戻す。
                        val releasedTopRoot = pointerRootY - grabOffset
                        draggedTilcod = null
                        hasMovedPastTouchSlop = false
                        grabOffset = 0f
                        coroutineScope.launch { settleReleasedPlacement(moved, releasedTopRoot) }
                    }

                    // コルーチンの取り消し(ダイアログが閉じた等)やポインタが失われた場合に、
                    // 並べ替えの状態を破棄する(finishDragと違い、離した後の位置合わせは行わない)。
                    fun cancelDrag() {
                        draggedTilcod = null
                        hasMovedPastTouchSlop = false
                        grabOffset = 0f
                    }

                    // 指が一覧の見えている範囲の上端・下端に近づいている間、その方向へスクロールし続ける(自動スクロール)。
                    LaunchedEffect(draggedTilcod) {
                        val tilcod = draggedTilcod ?: return@LaunchedEffect
                        // ------------------------------------------------------------------
                        // §4.7: 長押しで畳むと、掴んだ書誌より上の行が縮んでスクロール位置(px)はそのままのため、
                        // 掴んだ書誌の画面上の位置が指からずれる(スクロールが0の1行目では起きない)。
                        // 畳んだ後のレイアウトが確定してからでないと正しい位置が取れないため、
                        // 「⠿」の中心y(handleCenterRootY)が開始時の値から変わるのを待つ。
                        // (レビュー指摘対応: handleBoundsRoot=boundsInRoot()はclipBounds既定trueのため、
                        // verticalScrollのclipScrollableContainer等の祖先で切り取られる。掴んだ行が
                        // 一覧の見えている範囲の外(上端の外)へ出ると、切り取られた範囲の中心yは実際より
                        // 下に潰れてしまい、diffを過小評価する。位置合わせには切り取られない
                        // handleCenterRootY(positionInRoot().y + size.height/2f)を使う。
                        // positionInRootは祖先のgraphicsLayerのtranslationY等も反映するため、
                        // 待っている間に自動スクロールで指が動いても、ここで測るdiffは畳みによるずれだけになる)
                        // 畳む→再コンポジション→再レイアウト→onGloballyPositioned通知は、通常1〜2フレームで
                        // 届く想定だが、1行目やスクロール0など畳んでも位置が変わらない場合は変化が来ないため、
                        // 永久に待たないよう最大5フレームで打ち切る(値が変わらなければdiff計算は実質0になる)。
                        // 「最初の変化で確定とみなす」のは、畳む処理(collapsed分岐の出し入れ)に
                        // アニメーションが無く、1回のレイアウトで最終位置へ飛ぶことが前提である。
                        // ------------------------------------------------------------------
                        val centerYBeforeSettle = handleCenterRootY[tilcod]
                        var settledCenterY = centerYBeforeSettle
                        var framesWaited = 0
                        while (framesWaited < 5) {
                            withFrameNanos { }
                            framesWaited++
                            val current = handleCenterRootY[tilcod]
                            settledCenterY = current
                            if (current != null && current != centerYBeforeSettle) break
                        }
                        // 待っている間にドラッグが終了・取り消されていれば、以降の合わせ込みは行わない
                        // (finishDrag/cancelDragが既に状態をリセットしている)。
                        if (settledCenterY != null && draggedTilcod == tilcod) {
                            // grabOffset: 畳んだ後の「⠿」の中心 − 自分の枠の上端(§4.8)。
                            // これでタイルの上端(pointerRootY - grabOffset)を求めれば、常に「⠿」が指の真下に来る。
                            val myFrameTop = rowFrameTopRoot[tilcod]
                            if (myFrameTop != null) {
                                grabOffset = settledCenterY - myFrameTop
                            }
                            val diff = pointerRootY - settledCenterY
                            if (diff != 0f) {
                                // 指は動いていないので、このスクロールは並べ替え(落とす先の判定)には影響しない
                                // (落とす先は画面上の位置から直接決めるため、§4.7のように補正値へ足す必要はない)。
                                scrollState.scrollBy(-diff)
                            }
                        }

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
                                scrollState.scrollBy(scrollDelta)
                            }
                            // §4.8: 自動スクロールで周りの行が動くため、スクロールした・しないに関わらず
                            // 毎回、最新の位置から落とす先を計算し直す。
                            applyDragProgress()
                            delay(16)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .height(280.dp)
                            .onGloballyPositioned { coordinates ->
                                containerPositionInRoot = coordinates.positionInRoot()
                                containerTopRoot = containerPositionInRoot.y
                                containerBottomRoot = containerTopRoot + coordinates.size.height
                            }
                            // §4.6: 並べ替えの指の検知はここ(verticalScrollの外側)で行う。
                            .pointerInput(editingDisabled) {
                                if (editingDisabled) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    val downRootPosition = containerPositionInRoot + down.position
                                    // 押した位置がどの行の「⠿」の範囲かを判定する。範囲外なら何もしない
                                    // (従来どおりverticalScrollへイベントが渡り、スクロールできる)。
                                    val tilcod = handleBoundsRoot.entries
                                        .firstOrNull { (_, bounds) -> bounds.contains(downRootPosition) }
                                        ?.key ?: return@awaitEachGesture
                                    val pointerId = down.id

                                    // 長押しの成立待ち。時間切れ(nullが返る)なら成立。それまでに指が離れる・
                                    // 他の部品に消費される・touchSlopを超えて動けば、並べ替えを始めずに終える
                                    // (ここでは消費しないので、そのままverticalScrollのスクロールに渡る)。
                                    val declinedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == pointerId }
                                            if (change == null || !change.pressed || change.isConsumed) return@withTimeoutOrNull
                                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                                return@withTimeoutOrNull
                                            }
                                        }
                                    } != null
                                    if (declinedEarly) return@awaitEachGesture

                                    startDrag(tilcod, downRootPosition.y)
                                    if (draggedTilcod != tilcod) return@awaitEachGesture

                                    // 長押し成立後は、この指の変化をすべてInitialパスで消費し、
                                    // verticalScrollを含む子には渡さない。
                                    var endedNormally = false
                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == pointerId }
                                            if (change == null || !change.pressed) {
                                                change?.consume()
                                                finishDrag()
                                                endedNormally = true
                                                break
                                            }
                                            val deltaY = change.positionChange().y
                                            change.consume()
                                            pointerRootY += deltaY
                                            // §4.8: 押した位置(down.position)からtouch slopを超えて動くまでは
                                            // 並びを変えない(一度超えたら、このドラッグの間はtrueのまま)。
                                            if (!hasMovedPastTouchSlop &&
                                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                            ) {
                                                hasMovedPastTouchSlop = true
                                            }
                                            applyDragProgress()
                                        }
                                    } finally {
                                        // 正常終了(finishDragを既に呼んだ)後にcancelDragを二重に走らせない。
                                        if (!endedNormally) cancelDrag()
                                    }
                                }
                            },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState),
                        ) {
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
                                    // この外側のBox(行の「枠」。translationYを持たない)の画面上の上端と高さを
                                    // 切り取られない座標で記録し、落とす先の判定(§4.8)に使う。
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onGloballyPositioned { coordinates ->
                                                rowFrameTopRoot[item.tilcod] = coordinates.positionInRoot().y
                                                rowFrameHeightRoot[item.tilcod] = coordinates.size.height.toFloat()
                                            },
                                    ) {
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
                                                .graphicsLayer {
                                                    if (isDragged) {
                                                        // タイルの上端 = 指の位置(pointerRootY) - grabOffset(§4.8)。
                                                        // 自分の枠(rowFrameTopRoot、translationYを持たない)の
                                                        // 上端からのずれをtranslationYとして描く。
                                                        val myFrameTop = rowFrameTopRoot[item.tilcod] ?: 0f
                                                        val tileTop = pointerRootY - grabOffset
                                                        translationY = tileTop - myFrameTop
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
                                            // 指の検知そのものは一覧を囲むBox側(verticalScrollの外)で行う(§4.6)。
                                            // ここでは見た目とtestTagのほか、押した位置がこの行かどうかの判定(ヒットテスト)に使う
                                            // 画面上の範囲(boundsInRoot、見えている範囲で切り取られる)をhandleBoundsRootへ、
                                            // §4.7の位置合わせに使う切り取られない中心yをhandleCenterRootYへ記録する。
                                            Text(
                                                "⠿",
                                                color = LocalAppColors.current.ink2,
                                                fontSize = 18.sp,
                                                modifier = Modifier
                                                    .testTag(BookshelfEditingDialogTestTags.editItemDragHandle(item.tilcod))
                                                    .onGloballyPositioned { coordinates ->
                                                        handleBoundsRoot[item.tilcod] = coordinates.boundsInRoot()
                                                        handleCenterRootY[item.tilcod] =
                                                            coordinates.positionInRoot().y + coordinates.size.height / 2f
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
