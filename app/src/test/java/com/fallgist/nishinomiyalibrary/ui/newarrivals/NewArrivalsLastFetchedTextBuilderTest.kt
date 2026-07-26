package com.fallgist.nishinomiyalibrary.ui.newarrivals

import java.time.ZonedDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class NewArrivalsLastFetchedTextBuilderTest {
    @Test
    fun `未取得ならその旨を表示する`() {
        assertEquals("未取得", NewArrivalsLastFetchedTextBuilder.build(null))
    }

    @Test
    fun `直近の取得は日時を整形して表示する`() {
        val fetchedAt = ZonedDateTime.of(2026, 7, 20, 18, 0, 0, 0, ZoneId.of("Asia/Tokyo"))

        assertEquals(
            "最終取得: 7/20(月) 18:00",
            NewArrivalsLastFetchedTextBuilder.build(fetchedAt.toInstant().toEpochMilli()),
        )
    }

    @Test
    fun `数時間前の取得も同じ書式で表示する`() {
        val fetchedAt = ZonedDateTime.of(2026, 7, 25, 9, 30, 0, 0, ZoneId.of("Asia/Tokyo"))

        assertEquals(
            "最終取得: 7/25(土) 09:30",
            NewArrivalsLastFetchedTextBuilder.build(fetchedAt.toInstant().toEpochMilli()),
        )
    }
}
