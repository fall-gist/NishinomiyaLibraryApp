package com.fallgist.nishinomiyalibrary.ui.detail

import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReadingInfo
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class BookDetailContentBuilderTest {
    private val hana = Member(id = 2, name = "はな", colorHex = "", cardNumber = "", sortOrder = 1)

    @Test
    fun `既読行は貸出月と館を組み立てフォールバック色を使う`() {
        val rows = BookDetailContentBuilder.detailReadRows(
            listOf(hana),
            listOf(ReadingInfo(hana.id, LocalDate.of(2025, 6, 10), "高須分室")),
        )

        assertEquals("2025/6 に貸出(高須分室)", rows.single().description)
        // 識別色が空のメンバーはフォールバック色
        assertEquals("#6E675C", rows.single().memberColorHex)
    }

    @Test
    fun `詳細項目から書名とタイトルコードを除外し表示順を保つ`() {
        val fields = linkedMapOf(
            "書名" to "愛の哲学",
            "書名ヨミ" to "アイ ノ テツガク",
            "著者名" to "サイモン フミ",
            "出版者" to "KADOKAWA",
            "タイトルコード" to "1000000961766",
            "ISBN" to "4-04-067384-4",
        )

        val rows = BookDetailContentBuilder.detailFields(fields)

        assertEquals(
            listOf("著者名" to "サイモン フミ", "出版者" to "KADOKAWA", "ISBN" to "4-04-067384-4"),
            rows,
        )
    }

    @Test
    fun `著者項目から予約カート用の著者行を取り出す`() {
        assertEquals(
            "著者名",
            BookDetailContentBuilder.writerLine(listOf("出版社" to "出版社", "著者名" to "著者名")),
        )
    }

    // 経路3: 取消確定後に書誌詳細ポップアップを閉じるかどうかの判定(docs/ui-design.md「方針: 予約取消の導線」)

    @Test
    fun `取消対象が設定されている書誌詳細は確定と同時に閉じる`() {
        assertEquals(
            true,
            BookDetailContentBuilder.shouldCloseDetailAfterCancelConfirm(BookDetailCancelTarget(memberId = 1, cancelCode = "c1")),
        )
    }

    @Test
    fun `取消対象が無い書誌詳細(経路3以外から開いた)は閉じない`() {
        assertEquals(false, BookDetailContentBuilder.shouldCloseDetailAfterCancelConfirm(null))
    }
}
