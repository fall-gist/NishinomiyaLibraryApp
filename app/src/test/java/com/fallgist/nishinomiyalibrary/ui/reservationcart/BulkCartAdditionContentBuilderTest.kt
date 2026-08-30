package com.fallgist.nishinomiyalibrary.ui.reservationcart

import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartAddSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BulkCartAdditionContentBuilderTest {
    @Test
    fun `結果文言はskippedが0なら件数のみ`() {
        val message = BulkCartAdditionContentBuilder.resultMessage(ReservationCartAddSummary(added = 5, skipped = 0))
        assertEquals("5件をカートへ追加しました", message)
    }

    @Test
    fun `結果文言はskippedがあれば内訳を添える(design §7,3)`() {
        val message = BulkCartAdditionContentBuilder.resultMessage(ReservationCartAddSummary(added = 3, skipped = 2))
        assertEquals("3件をカートへ追加しました（2件は既にカートにあります）", message)
    }

    @Test
    fun `targetsはメンバー未選択なら例外にする`() {
        val request = BulkCartAdditionConfirmationRequest(
            candidates = listOf(BulkCartAdditionCandidate("100", "資料A", null)),
        )
        assertThrows(IllegalArgumentException::class.java) { BulkCartAdditionContentBuilder.targets(request) }
    }

    @Test
    fun `targetsは選択メンバーを全候補に適用する`() {
        val request = BulkCartAdditionConfirmationRequest(
            candidates = listOf(
                BulkCartAdditionCandidate("100", "資料A", "著者A"),
                BulkCartAdditionCandidate("101", "資料B", null),
            ),
            selectedMemberId = 7,
        )

        val targets = BulkCartAdditionContentBuilder.targets(request)

        assertEquals(2, targets.size)
        assertEquals(7L, targets[0].memberId)
        assertEquals(null, targets[0].cartItemId)
        assertEquals("著者A", targets[0].writerLine)
        assertEquals(7L, targets[1].memberId)
    }

    @Test
    fun `canConfirmはメンバー未選択または候補0件でfalse(design §7,2)`() {
        assertEquals(false, BulkCartAdditionConfirmationRequest(emptyList(), selectedMemberId = 1).canConfirm)
        assertEquals(false, BulkCartAdditionConfirmationRequest(listOf(BulkCartAdditionCandidate("1", "a", null))).canConfirm)
        assertEquals(
            true,
            BulkCartAdditionConfirmationRequest(listOf(BulkCartAdditionCandidate("1", "a", null)), selectedMemberId = 1).canConfirm,
        )
    }
}
