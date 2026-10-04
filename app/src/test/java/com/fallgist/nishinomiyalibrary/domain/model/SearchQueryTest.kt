package com.fallgist.nishinomiyalibrary.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {
    @Test
    fun `条件が無ければNO_CONDITION`() {
        assertEquals(listOf(SearchQueryProblem.NO_CONDITION), SearchQuery().validate())
        assertEquals(listOf(SearchQueryProblem.NO_CONDITION), SearchQuery(keyword = "  ").validate())
        // 在庫状況・並べ替えだけでは条件にならない
        val onlyOptions = SearchQuery(
            stock = StockFilter.LENDABLE_ONLY,
            sort = SearchSort(SearchSortKey.TITLE, SortDirection.DESCENDING),
        )
        assertFalse(onlyOptions.hasCondition)
        assertEquals(listOf(SearchQueryProblem.NO_CONDITION), onlyOptions.validate())
    }

    @Test
    fun `語・分類・年月・種類のどれか1つで条件になる`() {
        assertTrue(SearchQuery(keyword = "a").validate().isEmpty())
        assertTrue(SearchQuery(title = "a").validate().isEmpty())
        assertTrue(SearchQuery(author = "a").validate().isEmpty())
        assertTrue(SearchQuery(publisher = "a").validate().isEmpty())
        assertTrue(SearchQuery(classification = "726").validate().isEmpty())
        assertTrue(SearchQuery(published = PublishedRange(fromYear = 2010)).validate().isEmpty())
        assertTrue(SearchQuery(materialKinds = setOf(MaterialKind.CD)).validate().isEmpty())
        assertTrue(SearchQuery.keywordOnly("ドラゴンボール").validate().isEmpty())
    }

    @Test
    fun `年は4桁で月は1から12`() {
        fun problems(range: PublishedRange) = SearchQuery(published = range).validate()

        assertEquals(listOf(SearchQueryProblem.YEAR_INVALID), problems(PublishedRange(fromYear = 99)))
        assertEquals(listOf(SearchQueryProblem.YEAR_INVALID), problems(PublishedRange(toYear = 10000)))
        assertEquals(listOf(SearchQueryProblem.MONTH_INVALID), problems(PublishedRange(fromYear = 2010, fromMonth = 0)))
        assertEquals(listOf(SearchQueryProblem.MONTH_INVALID), problems(PublishedRange(toYear = 2010, toMonth = 13)))
        assertEquals(listOf(SearchQueryProblem.MONTH_WITHOUT_YEAR), problems(PublishedRange(fromMonth = 3)))
        assertTrue(problems(PublishedRange(fromYear = 1000, fromMonth = 1, toYear = 9999, toMonth = 12)).isEmpty())
    }

    @Test
    fun `からがまでより後なら誤りで月の省略は端として比べる`() {
        fun problems(range: PublishedRange) = SearchQuery(published = range).validate()

        assertEquals(
            listOf(SearchQueryProblem.RANGE_REVERSED),
            problems(PublishedRange(fromYear = 2020, fromMonth = 5, toYear = 2020, toMonth = 4)),
        )
        assertEquals(
            listOf(SearchQueryProblem.RANGE_REVERSED),
            problems(PublishedRange(fromYear = 2021, toYear = 2020)),
        )
        // 同じ年で月を省略: から=1月、まで=12月なので誤りではない
        assertTrue(problems(PublishedRange(fromYear = 2020, toYear = 2020)).isEmpty())
        assertTrue(problems(PublishedRange(fromYear = 2020, fromMonth = 4, toYear = 2020, toMonth = 4)).isEmpty())
        // 片側だけの指定は比べない
        assertTrue(problems(PublishedRange(fromYear = 2020)).isEmpty())
    }
}
