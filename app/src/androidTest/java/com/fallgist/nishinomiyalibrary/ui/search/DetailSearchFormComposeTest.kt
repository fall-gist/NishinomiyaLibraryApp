package com.fallgist.nishinomiyalibrary.ui.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getBoundsInRoot
import com.fallgist.nishinomiyalibrary.domain.model.SearchQuery
import com.fallgist.nishinomiyalibrary.ui.theme.NishinomiyaLibraryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 詳細検索の入力欄の操作ボタンが、見出しの行にあり常に押せることを検証する。 */
class DetailSearchFormComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setForm(
        initial: SearchQuery,
        onSearch: (SearchQuery) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            NishinomiyaLibraryTheme {
                DetailSearchForm(initial = initial, onSearch = onSearch, onDismiss = onDismiss)
            }
        }
    }

    @Test
    fun `検索クリア戻るが表示されていて検索を押すとonSearchが呼ばれる`() {
        var searched: SearchQuery? = null
        setForm(SearchQuery(keyword = "本"), onSearch = { searched = it })

        composeRule.onNodeWithTag(DetailSearchFormTestTags.CLEAR).assertIsDisplayed()
        composeRule.onNodeWithTag(DetailSearchFormTestTags.BACK).assertIsDisplayed()
        composeRule.onNodeWithTag(DetailSearchFormTestTags.SUBMIT).assertIsDisplayed().assertHasClickAction().performClick()

        assertEquals("本", searched?.keyword)
    }

    @Test
    fun `操作ボタンは入力欄より上の見出しの行にある`() {
        setForm(SearchQuery(keyword = "本"))

        val header = composeRule.onNodeWithTag(DetailSearchFormTestTags.HEADER).getBoundsInRoot()
        val submit = composeRule.onNodeWithTag(DetailSearchFormTestTags.SUBMIT).getBoundsInRoot()
        val first = composeRule.onNodeWithTag(DetailSearchFormTestTags.FIRST_FIELD).getBoundsInRoot()
        assertTrue(submit.top >= header.top && submit.bottom <= header.bottom)
        assertTrue(submit.bottom <= first.top)
    }

    @Test
    fun `条件が無いまま検索を押すと見出しの直下に文言が出てonSearchは呼ばれない`() {
        var called = false
        setForm(SearchQuery(), onSearch = { called = true })

        composeRule.onNodeWithTag(DetailSearchFormTestTags.SUBMIT).performClick()

        composeRule.onNodeWithText("語・分類・出版年月・書誌種別のどれかを指定してください").assertIsDisplayed()
        assertEquals(false, called)
    }

    @Test
    fun `戻るでonDismissが呼ばれる`() {
        var dismissed = false
        setForm(SearchQuery(keyword = "本"), onDismiss = { dismissed = true })

        composeRule.onNodeWithTag(DetailSearchFormTestTags.BACK).performClick()

        assertTrue(dismissed)
    }
}
