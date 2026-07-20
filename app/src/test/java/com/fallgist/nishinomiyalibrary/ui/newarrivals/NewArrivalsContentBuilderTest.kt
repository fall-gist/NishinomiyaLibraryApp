package com.fallgist.nishinomiyalibrary.ui.newarrivals

import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import org.junit.Assert.assertEquals
import org.junit.Test

class NewArrivalsContentBuilderTest {
    private fun arrival(
        tilcod: String,
        title: String,
        volume: String = "",
        author: String = "",
        publisher: String = "",
        published: String = "",
        lendable: Boolean? = null,
    ) = NewArrival(tilcod, title, volume, author, publisher, published, "", lendable)

    private val items = listOf(
        arrival("1", "吾輩は猫である", author = "夏目 漱石／著", publisher = "岩波書店", published = "2026/06", lendable = true),
        arrival("2", "SFアンソロジー", volume = "2", author = "日本SF作家クラブ／編", published = "2026/05", lendable = false),
    )

    @Test
    fun query空なら全件を整形して返す() {
        val rows = NewArrivalsContentBuilder.build(items, query = "")

        assertEquals(2, rows.size)
        assertEquals("吾輩は猫である", rows[0].title)
        // 著者・出版者・出版年月を中黒でつなぐ(空欄は除外)
        assertEquals("夏目 漱石／著 ・ 岩波書店 ・ 2026/06", rows[0].subtitle)
        assertEquals(true, rows[0].lendable)
        // 巻次があれば書名に付す
        assertEquals("SFアンソロジー 2", rows[1].title)
        assertEquals("日本SF作家クラブ／編 ・ 2026/05", rows[1].subtitle)
    }

    @Test
    fun 書名と著者で絞り込む() {
        assertEquals(listOf("吾輩は猫である"), NewArrivalsContentBuilder.build(items, "猫").map { it.title })
        assertEquals(listOf("吾輩は猫である"), NewArrivalsContentBuilder.build(items, "漱石").map { it.title })
        assertEquals(emptyList<String>(), NewArrivalsContentBuilder.build(items, "存在しない").map { it.title })
    }

    @Test
    fun 全角半角と大文字小文字のゆらぎを吸収して絞り込む() {
        // "ＳＦ"(全角)や小文字 "sf" でも "SFアンソロジー" に一致する
        assertEquals(listOf("SFアンソロジー 2"), NewArrivalsContentBuilder.build(items, "ＳＦ").map { it.title })
        assertEquals(listOf("SFアンソロジー 2"), NewArrivalsContentBuilder.build(items, "sf").map { it.title })
    }
}
