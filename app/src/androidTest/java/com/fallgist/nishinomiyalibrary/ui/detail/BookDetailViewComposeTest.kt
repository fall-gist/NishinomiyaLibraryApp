package com.fallgist.nishinomiyalibrary.ui.detail

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.ui.reservationcart.ReservationUiState
import com.fallgist.nishinomiyalibrary.ui.shelf.BookshelfEditingUiState
import com.fallgist.nishinomiyalibrary.ui.theme.NishinomiyaLibraryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BookDetailViewComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val member = Member(1, "父", "#111111", "", 0)

    @Test
    fun `有効な資料詳細は本棚追加を有効化し正確な資料を渡す`() {
        var requested: Pair<String, String>? = null
        setDetail(
            editing = BookshelfEditingUiState(initialized = true, members = listOf(member)),
            onRequest = { tilcod, title -> requested = tilcod to title },
        )

        composeRule.onNodeWithTag(BookDetailViewTestTags.ADD_TO_BOOKSHELF).assertIsEnabled().performClick()
        assertEquals("t-1" to "資料A", requested)
    }

    @Test
    fun `処理中とメンバーなしでは本棚追加を無効化し案内する`() {
        setDetail(
            editing = BookshelfEditingUiState(
                initialized = true,
                members = listOf(member),
                processingMutation = com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutation.DeleteItem(1, 1, "x"),
            ),
        )
        composeRule.onNodeWithTag(BookDetailViewTestTags.ADD_TO_BOOKSHELF).assertIsNotEnabled()

        setDetail(editing = BookshelfEditingUiState(initialized = true))
        composeRule.onNodeWithTag(BookDetailViewTestTags.ADD_TO_BOOKSHELF).assertIsNotEnabled()
        composeRule.onNodeWithText("先に設定からメンバーを登録してください", substring = true).assertExists()
    }

    private fun setDetail(
        editing: BookshelfEditingUiState,
        onRequest: (String, String) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            NishinomiyaLibraryTheme(darkTheme = false) {
                BookDetailView(
                    detail = BookDetailUiState(open = true, tilcod = "t-1", title = "資料A", loading = false),
                    onBack = {},
                    reservation = ReservationUiState(),
                    onSelectReservationMember = {},
                    onSelectPickupLibrary = {},
                    onAddToCart = {},
                    onRequestReserveNow = {},
                    onOpenOfficialBookDetail = {},
                    bookshelfEditing = editing,
                    onRequestAddToBookshelf = onRequest,
                    onRequestCancel = {},
                )
            }
        }
    }
}
