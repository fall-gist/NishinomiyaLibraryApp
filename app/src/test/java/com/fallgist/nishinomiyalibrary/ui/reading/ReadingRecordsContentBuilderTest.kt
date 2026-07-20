package com.fallgist.nishinomiyalibrary.ui.reading

import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingRecordsContentBuilderTest {
    private val papa = Member(id = 1, name = "パパ", colorHex = "#3D6DB5", cardNumber = "", sortOrder = 0)
    private val hana = Member(id = 2, name = "はな", colorHex = "", cardNumber = "", sortOrder = 1)

    @Test
    fun rows_formatDateAndFallBackForUnknownColor() {
        val records = listOf(
            ReadingRecord(papa.id, "100", "本A", LocalDate.of(2026, 7, 1), "中央図書館"),
            ReadingRecord(hana.id, "200", "本B", LocalDate.of(2025, 12, 24), "高須分室"),
        )

        val rows = ReadingRecordsContentBuilder.build(listOf(papa, hana), records)

        assertEquals(2, rows.size)
        assertEquals("パパ", rows[0].memberName)
        assertEquals("2026/7/1", rows[0].loanDateLabel)
        assertEquals("中央図書館", rows[0].library)
        // 書誌詳細遷移用にタイトルコードを保持する
        assertEquals("100", rows[0].tilcod)
        // 識別色が空のメンバーはフォールバック色になる
        assertEquals("#6E675C", rows[1].memberColorHex)
        assertEquals("2025/12/24", rows[1].loanDateLabel)
    }
}
