package com.fallgist.nishinomiyalibrary.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoReservationRuleDragTest {
    @Test fun `閾値未満は移動しない`() = assertEquals(0, AutoReservationRuleDrag.steps(47f, 48f))
    @Test fun `正負と複数閾値を移動数へ変換する`() {
        assertEquals(1, AutoReservationRuleDrag.steps(48f, 48f))
        assertEquals(-1, AutoReservationRuleDrag.steps(-48f, 48f))
        assertEquals(2, AutoReservationRuleDrag.steps(120f, 48f))
    }
}
