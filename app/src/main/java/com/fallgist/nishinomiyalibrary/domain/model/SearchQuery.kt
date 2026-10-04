package com.fallgist.nishinomiyalibrary.domain.model

/**
 * 蔵書検索の条件(docs/design/search-sort-filter.md §3.1)。
 * 語・分類は「含む」で探す。空文字(空白のみを含む)は未指定として扱う。
 * キーワード検索は「[keyword] だけを持つ条件」として同じ経路で送る([keywordOnly])。
 */
data class SearchQuery(
    /** 全項目を対象にするキーワード。 */
    val keyword: String = "",
    val title: String = "",
    val author: String = "",
    val publisher: String = "",
    val classification: String = "",
    val published: PublishedRange = PublishedRange(),
    /** 資料の種類。空は未指定(サイトの既定に任せる)。 */
    val materialKinds: Set<MaterialKind> = emptySet(),
    val stock: StockFilter = StockFilter.ALL,
    /** 並べ替え。null はサイトの既定の並び。 */
    val sort: SearchSort? = null,
) {
    /** 語・分類・年月・種類のどれかが指定されているか。在庫状況・並べ替えだけでは条件にならない。 */
    val hasCondition: Boolean
        get() = keyword.isNotBlank() || title.isNotBlank() || author.isNotBlank() ||
            publisher.isNotBlank() || classification.isNotBlank() ||
            published.isSpecified || materialKinds.isNotEmpty()

    /** 条件の誤りを返す。空なら送信してよい。 */
    fun validate(): List<SearchQueryProblem> {
        val problems = mutableListOf<SearchQueryProblem>()
        if (!hasCondition) problems += SearchQueryProblem.NO_CONDITION
        problems += published.validate()
        return problems
    }

    companion object {
        /** 従来のキーワード検索。 */
        fun keywordOnly(keyword: String): SearchQuery = SearchQuery(keyword = keyword)
    }
}

/** 出版年月の範囲。年は4桁、月は1〜12。いずれの値も省略できる。 */
data class PublishedRange(
    val fromYear: Int? = null,
    val fromMonth: Int? = null,
    val toYear: Int? = null,
    val toMonth: Int? = null,
) {
    val isSpecified: Boolean
        get() = fromYear != null || fromMonth != null || toYear != null || toMonth != null

    fun validate(): List<SearchQueryProblem> {
        val problems = mutableListOf<SearchQueryProblem>()
        listOf(fromYear, toYear).forEach { year ->
            if (year != null && year !in YEAR_RANGE) problems += SearchQueryProblem.YEAR_INVALID
        }
        listOf(fromMonth, toMonth).forEach { month ->
            if (month != null && month !in 1..12) problems += SearchQueryProblem.MONTH_INVALID
        }
        // 月だけで年が無い指定はサイトの意味が不明なため誤りとする。
        if ((fromMonth != null && fromYear == null) || (toMonth != null && toYear == null)) {
            problems += SearchQueryProblem.MONTH_WITHOUT_YEAR
        }
        if (problems.isEmpty() && fromYear != null && toYear != null) {
            // 月の省略は、「から」は1月、「まで」は12月として比べる。
            val from = fromYear * 12 + ((fromMonth ?: 1) - 1)
            val to = toYear * 12 + ((toMonth ?: 12) - 1)
            if (from > to) problems += SearchQueryProblem.RANGE_REVERSED
        }
        return problems.distinct()
    }

    private companion object {
        val YEAR_RANGE = 1000..9999
    }
}

enum class SearchQueryProblem {
    /** 語・分類・年月・種類のどれも指定されていない。 */
    NO_CONDITION,
    YEAR_INVALID,
    MONTH_INVALID,
    MONTH_WITHOUT_YEAR,
    /** 「から」が「まで」より後。 */
    RANGE_REVERSED,
}

/** 資料の種類(サイトの `mngshus`)。 */
enum class MaterialKind(val siteCode: String) {
    GENERAL("1"),
    CHILDREN("2"),
    MAGAZINE_NEWSPAPER("3"),
    CD("4"),
    VIDEO_DVD("5"),
    DAISY("6"),
    OTHER_AUDIOVISUAL("7"),
    LARGE_PRINT_PICTURE_BOOK("8"),
}

/** 在庫状況(サイトの `stockState`)。 */
enum class StockFilter(val siteCode: String) {
    ALL("1"),
    LENDABLE_ONLY("2"),
    READING_ROOM_ONLY("3"),
}

/** 並べ替えの項目(サイトの見出しの `sortKey`)。 */
enum class SearchSortKey(val siteKey: String) {
    BIBLIOGRAPHY_TYPE("SLSTITL.TILSHU"),
    TITLE("SLSTITL.TITLE_RD,SLSTITL.VOLUME_NUM_ST"),
    AUTHOR("SLSTITL.AUTHER_RD"),
    PUBLISHER("SLSTITL.PUBLISH_ST"),
    PUBLISHED("SLSTITL.PUBYMD_ST"),
    CLASSIFICATION("SLSTITL.NDC1,SLSTITL.NDC2,SLSTITL.NDC3");

    companion object {
        fun fromSiteKey(siteKey: String): SearchSortKey? = entries.firstOrNull { it.siteKey == siteKey }
    }
}

enum class SortDirection { ASCENDING, DESCENDING }

data class SearchSort(
    val key: SearchSortKey,
    val direction: SortDirection = SortDirection.ASCENDING,
)
