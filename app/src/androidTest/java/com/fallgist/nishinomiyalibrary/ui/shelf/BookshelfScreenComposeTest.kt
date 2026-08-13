package com.fallgist.nishinomiyalibrary.ui.shelf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfEditItem
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutation
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutationExpectation
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.ui.theme.NishinomiyaLibraryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 資料カードと編集入口のCompose配線を、見た目の座標に依存せず検証する。 */
class BookshelfScreenComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val father = Member(1, "父", "#111111", "", 0)
    private val book = ShelfBook("資料A", "メモ", "2026/8/9", "t-1")
    private val column = ShelfColumn(1, 3, "父", "#111111", "技術書", listOf(book))
    private val expected = BookshelfMutationExpectation("父", 1)

    @Test
    fun `資料カード外側に詳細クリックがある`() {
        var opened: Pair<String, String>? = null
        setScreen(onOpenDetail = { tilcod, title -> opened = tilcod to title })

        val cardTag = BookshelfScreenTestTags.bookCard(1, 3, "t-1")
        composeRule.onNodeWithTag(cardTag).assertHasClickAction().performClick()
        assertEquals("t-1" to "資料A", opened)
    }

    @Test
    fun `処理中は作成が無効になる`() {
        setScreen(
            editingState = BookshelfEditingUiState(
                initialized = true,
                members = listOf(father),
                processingMutation = BookshelfMutation.DeleteItem(1, 3, "t-1", expected),
            ),
        )

        composeRule.onNodeWithTag(BookshelfScreenTestTags.CREATE_SHELF).assertIsNotEnabled()
    }

    @Test
    fun `本棚編集ダイアログの資料行から削除確認へ進み編集内容が保存されない旨を表示する`() {
        var requested: BookshelfItemTarget? = null
        val target = BookshelfShelfTarget(
            memberId = 1,
            shelfNo = 3,
            memberName = "父",
            shelfName = "技術書",
            itemCount = 1,
            shelfCount = 1,
            items = listOf(BookshelfEditItem("t-1", "資料A", "メモ", "メモ")),
        )
        setEditingDialogs(
            BookshelfEditingUiState(
                initialized = true,
                members = listOf(father),
                dialog = BookshelfEditingDialog.EditShelf(target),
            ),
            onRequestDeleteItem = { requested = it },
        )

        composeRule.onNodeWithTag(BookshelfEditingDialogTestTags.editItemDelete("t-1")).assertHasClickAction().performClick()
        assertEquals("t-1", requested?.tilcod)
        assertEquals(1, requested?.memberId)
        assertEquals(3, requested?.shelfNo)
    }

    @Test
    fun `処理中は本棚編集ダイアログの削除ボタンが無効になる`() {
        val target = BookshelfShelfTarget(
            memberId = 1,
            shelfNo = 3,
            memberName = "父",
            shelfName = "技術書",
            itemCount = 1,
            shelfCount = 1,
            items = listOf(BookshelfEditItem("t-1", "資料A", "メモ", "メモ")),
        )
        setEditingDialogs(
            BookshelfEditingUiState(
                initialized = true,
                members = listOf(father),
                dialog = BookshelfEditingDialog.EditShelf(target),
                processingMutation = BookshelfMutation.DeleteItem(1, 3, "t-2", expected),
            ),
        )

        composeRule.onNodeWithTag(BookshelfEditingDialogTestTags.editItemDelete("t-1")).assertIsNotEnabled()
    }

    @Test
    fun `空棚の案内を保持する`() {
        setScreen(columns = listOf(column.copy(books = emptyList())))

        composeRule.onNodeWithText("登録資料はありません").assertExists()
    }

    @Test
    fun `資料追加ダイアログはloadingと未選択と空棚で確認を無効化する`() {
        setEditingDialogs(
            BookshelfEditingUiState(
                initialized = true,
                members = listOf(father),
                dialog = BookshelfEditingDialog.AddItem("t-1", "資料A", memberId = father.id),
            ),
        )
        composeRule.onNodeWithTag(BookshelfEditingDialogTestTags.ADD_ITEM_CONFIRM).assertIsNotEnabled()
        composeRule.onNodeWithText("本棚を読み込んでいます").assertExists()

        setEditingDialogs(
            BookshelfEditingUiState(
                initialized = true,
                members = listOf(father),
                dialog = BookshelfEditingDialog.AddItem("t-1", "資料A", memberId = father.id),
                addItemShelvesLoadedForMemberId = father.id,
            ),
        )
        composeRule.onNodeWithTag(BookshelfEditingDialogTestTags.ADD_ITEM_CONFIRM).assertIsNotEnabled()
        composeRule.onNodeWithText("先に本棚を作成してください").assertExists()

        setEditingDialogs(
            BookshelfEditingUiState(
                initialized = true,
                members = listOf(father),
                dialog = BookshelfEditingDialog.AddItem("t-1", "資料A"),
            ),
        )
        composeRule.onNodeWithTag(BookshelfEditingDialogTestTags.ADD_ITEM_CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun `資料追加の最終確認には対象とメモを表示する`() {
        val confirmation = BookshelfEditingConfirmation.AddItem(
            memberName = "父",
            shelfName = "父の棚",
            title = "資料A",
            memo = "メモA",
            mutation = BookshelfMutation.AddItem(father.id, 3, "t-1", "メモA", expected),
        )
        setEditingDialogs(BookshelfEditingUiState(pendingConfirmation = confirmation))

        composeRule.onNodeWithText("対象メンバー：父", substring = true).assertExists()
        composeRule.onNodeWithText("本棚：父の棚", substring = true).assertExists()
        composeRule.onNodeWithText("資料名：資料A", substring = true).assertExists()
        composeRule.onNodeWithText("メモ：メモA", substring = true).assertExists()
    }

    @Test
    fun `空のエラーでもコピーと閉じるを表示しコピー操作後もダイアログを維持する`() {
        var errorState by mutableStateOf(BookshelfEditingUiState(errorMessage = ""))
        composeRule.setContent {
            NishinomiyaLibraryTheme(darkTheme = false) {
                BookshelfEditingDialogs(
                    editingState = errorState,
                    onSelectCreateMember = {},
                    onSelectAddItemMember = {},
                    onSelectAddItemShelf = {},
                    onUpdateInput = {},
                    onUpdateEditShelfMemo = { _, _ -> },
                    onRequestInputConfirmation = {},
                    onDismissDialog = {},
                    onConfirm = {},
                    onDismissConfirmation = {},
                    onClearResult = {},
                    onClearError = { errorState = errorState.copy(errorMessage = null) },
                )
            }
        }

        composeRule.onNodeWithTag(BookshelfEditingDialogTestTags.ERROR_COPY).performClick()
        composeRule.onNodeWithText("本棚の操作を完了できませんでした").assertExists()
        composeRule.onNodeWithTag(BookshelfEditingDialogTestTags.ERROR_CLOSE).performClick()
        composeRule.onNodeWithText("本棚の操作を完了できませんでした").assertDoesNotExist()
    }

    @Test
    fun `空の結果でもコピーと閉じるを表示しコピー操作後もダイアログを維持する`() {
        var resultState by mutableStateOf(
            BookshelfEditingUiState(
                result = BookshelfEditingResultMessage("結果", "", BookshelfEditingResultKind.APPLIED),
            ),
        )
        composeRule.setContent {
            NishinomiyaLibraryTheme(darkTheme = false) {
                BookshelfEditingDialogs(
                    editingState = resultState,
                    onSelectCreateMember = {},
                    onSelectAddItemMember = {},
                    onSelectAddItemShelf = {},
                    onUpdateInput = {},
                    onUpdateEditShelfMemo = { _, _ -> },
                    onRequestInputConfirmation = {},
                    onDismissDialog = {},
                    onConfirm = {},
                    onDismissConfirmation = {},
                    onClearResult = { resultState = resultState.copy(result = null) },
                    onClearError = {},
                )
            }
        }

        composeRule.onNodeWithTag(BookshelfEditingDialogTestTags.RESULT_COPY).performClick()
        composeRule.onNodeWithText("結果").assertExists()
        composeRule.onNodeWithTag(BookshelfEditingDialogTestTags.RESULT_CLOSE).performClick()
        composeRule.onNodeWithText("結果").assertDoesNotExist()
    }

    private fun setScreen(
        columns: List<ShelfColumn> = listOf(column),
        editingState: BookshelfEditingUiState = BookshelfEditingUiState(
            initialized = true,
            members = listOf(father),
        ),
        onOpenDetail: (String, String) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            NishinomiyaLibraryTheme(darkTheme = false) {
                BookshelfScreen(
                    state = BookshelfUiState(initialized = true, members = listOf(father), columns = columns),
                    editingState = editingState,
                    isRefreshing = false,
                    onRefresh = {},
                    onSelectMember = {},
                    onOpenMenu = {},
                    onOpenDetail = onOpenDetail,
                    onRequestCreateShelf = {},
                    onRequestEditShelf = {},
                    onRequestDeleteShelf = {},
                )
            }
        }
    }

    private fun setEditingDialogs(
        editingState: BookshelfEditingUiState,
        onRequestDeleteItem: (BookshelfItemTarget) -> Unit = {},
    ) {
        composeRule.setContent {
            NishinomiyaLibraryTheme(darkTheme = false) {
                BookshelfEditingDialogs(
                    editingState = editingState,
                    onSelectCreateMember = {},
                    onSelectAddItemMember = {},
                    onSelectAddItemShelf = {},
                    onUpdateInput = {},
                    onUpdateEditShelfMemo = { _, _ -> },
                    onRequestInputConfirmation = {},
                    onDismissDialog = {},
                    onConfirm = {},
                    onDismissConfirmation = {},
                    onClearResult = {},
                    onClearError = {},
                    onRequestDeleteItem = onRequestDeleteItem,
                )
            }
        }
    }
}
