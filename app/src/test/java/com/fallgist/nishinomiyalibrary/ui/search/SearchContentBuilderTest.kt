package com.fallgist.nishinomiyalibrary.ui.search

import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReadingInfo
import com.fallgist.nishinomiyalibrary.domain.model.SearchHit
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchContentBuilderTest {
    private val papa = Member(id = 1, name = "パパ", colorHex = "#3D6DB5", cardNumber = "", sortOrder = 0)
    private val hana = Member(id = 2, name = "はな", colorHex = "", cardNumber = "", sortOrder = 1)

    @Test
    fun `既読バッジはメンバーごとに最新の貸出だけを古い順で並べる`() {
        val infos = listOf(
            ReadingInfo(papa.id, LocalDate.of(2024, 3, 5), "中央図書館"),
            ReadingInfo(papa.id, LocalDate.of(2025, 6, 1), "高須分室"),
            ReadingInfo(hana.id, LocalDate.of(2024, 11, 20), "高須分室"),
        )

        val badges = SearchContentBuilder.readBadges(listOf(papa, hana), infos)

        assertEquals(2, badges.size)
        assertEquals("はな", badges[0].memberName)
        assertEquals("2024/11", badges[0].loanMonthLabel)
        // 識別色が空のメンバーはフォールバック色
        assertEquals("#6E675C", badges[0].memberColorHex)
        assertEquals("パパ", badges[1].memberName)
        assertEquals("2025/6", badges[1].loanMonthLabel)
    }

    @Test
    fun `検索結果行はタイトルコード一致の既読情報だけを紐付ける`() {
        val hits = listOf(
            SearchHit("100", "銀河鉄道の夜", "宮沢賢治／著", "一般図書"),
            SearchHit("200", "注文の多い料理店", "宮沢賢治／作", "児童図書"),
        )
        val readInfo = mapOf(
            "200" to listOf(ReadingInfo(papa.id, LocalDate.of(2025, 3, 1), "中央図書館")),
        )

        val rows = SearchContentBuilder.resultRows(hits, listOf(papa), readInfo)

        assertEquals(2, rows.size)
        assertEquals(emptyList<ReadBadgeEntry>(), rows[0].readEntries)
        assertNull(rows[0].lendable)
        assertEquals("パパ 2025/3", rows[1].readEntries.single().let { "${it.memberName} ${it.loanMonthLabel}" })
    }

    @Test
    fun `一斉カート追加の候補は選択済みかつ一覧に存在する行だけを組み立てる(design bulk-selection §4,3・§7,4)`() {
        val hits = listOf(
            SearchHit("100", "銀河鉄道の夜", "宮沢賢治／著", "一般図書"),
            SearchHit("200", "注文の多い料理店", "宮沢賢治／作", "児童図書"),
        )
        val rows = SearchContentBuilder.resultRows(hits, emptyList(), emptyMap())

        val candidates = SearchContentBuilder.cartAdditionCandidates(rows, selectedTilcods = setOf("100", "999"))

        assertEquals(1, candidates.size)
        assertEquals("100", candidates.single().tilcod)
        assertEquals("銀河鉄道の夜", candidates.single().title)
        assertEquals("宮沢賢治／著", candidates.single().writerLine)
    }
}
