package com.fallgist.nishinomiyalibrary.ui.shelf

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun `資料カード外側に詳細クリックがありoverflowクリックは伝播しない`() {
        var opened: Pair<String, String>? = null
        setScreen(onOpenDetail = { tilcod, title -> opened = tilcod to title })

        val cardTag = BookshelfScreenTestTags.bookCard(1, 3, "t-1")
        composeRule.onNodeWithTag(cardTag).assertHasClickAction().performClick()
        assertEquals("t-1" to "資料A", opened)

        opened = null
        composeRule.onNodeWithTag(BookshelfScreenTestTags.bookMenu(1, 3, "t-1")).performClick()
        assertEquals(null, opened)
        composeRule.onNodeWithText("本棚から削除").assertExists()
    }

    @Test
    fun `処理中は作成と資料overflow入口が無効になる`() {
        setScreen(
            editingState = BookshelfEditingUiState(
                initialized = true,
                members = listOf(father),
                processingMutation = BookshelfMutation.DeleteItem(1, 3, "t-1", expected),
            ),
        )

        composeRule.onNodeWithTag(BookshelfScreenTestTags.CREATE_SHELF).assertIsNotEnabled()
        composeRule.onNodeWithTag(BookshelfScreenTestTags.bookMenu(1, 3, "t-1")).assertIsNotEnabled()
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
                    onRequestDeleteItem = {},
                )
            }
        }
    }

    private fun setEditingDialogs(editingState: BookshelfEditingUiState) {
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
                )
            }
        }
    }
}
