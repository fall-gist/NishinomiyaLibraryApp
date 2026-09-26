package com.fallgist.nishinomiyalibrary.ui.components

import com.fallgist.nishinomiyalibrary.ui.loans.LoanExtensionBulkProgress
import com.fallgist.nishinomiyalibrary.ui.shelf.BookshelfBulkAddProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 画面下の帯の文言を決める純関数のテスト(`docs/design/operation-progress-banner.md` §4)。
 * UI(帯の位置・消えるまでの時間)はここでは固定しない。実機確認で担保する(設計書§4末尾)。
 */
class OperationProgressContentBuilderTest {

    /** 引数を毎回書かずに済むよう、全て「進行していない」既定値を持つヘルパー。 */
    private fun banner(
        bookshelfBulkAdd: BookshelfBulkAddProgress? = null,
        loanBulkExtend: LoanExtensionBulkProgress? = null,
        cancelProcessing: Boolean = false,
        cartAddProcessing: Boolean = false,
        directReservationProcessing: Boolean = false,
        cartConfirmProcessing: Boolean = false,
        bookshelfMutationProcessing: Boolean = false,
        syncing: Boolean = false,
    ) = OperationProgressContentBuilder.banner(
        bookshelfBulkAdd = bookshelfBulkAdd,
        loanBulkExtend = loanBulkExtend,
        cancelProcessing = cancelProcessing,
        cartAddProcessing = cartAddProcessing,
        directReservationProcessing = directReservationProcessing,
        cartConfirmProcessing = cartConfirmProcessing,
        bookshelfMutationProcessing = bookshelfMutationProcessing,
        syncing = syncing,
    )

    // 1. 何も進行していなければnull
    @Test
    fun banner_returnsNull_whenNothingIsInProgress() {
        assertNull(banner())
    }

    // 2. 一斉本棚追加の進捗
    @Test
    fun banner_bookshelfBulkAdd_showsCompletedPlusOneOverTotal() {
        val result = banner(bookshelfBulkAdd = BookshelfBulkAddProgress(completed = 2, total = 5))
        assertEquals("本棚へ追加しています　3件目/5件", result?.text)
        assertEquals(OperationKind.BOOKSHELF_BULK_ADD, result?.kind)
    }

    // 3. 件数が総数を超えない(completed + 1 > total のとき総数で止まる)
    @Test
    fun banner_bookshelfBulkAdd_doesNotExceedTotalWhenLastItemJustCompleted() {
        val result = banner(bookshelfBulkAdd = BookshelfBulkAddProgress(completed = 5, total = 5))
        assertEquals("本棚へ追加しています　5件目/5件", result?.text)
    }

    // 4. 一斉延長の進捗
    @Test
    fun banner_loanBulkExtend_showsCompletedPlusOneOverTotal() {
        val result = banner(loanBulkExtend = LoanExtensionBulkProgress(completed = 1, total = 4))
        assertEquals("延長しています　2件目/4件", result?.text)
        assertEquals(OperationKind.LOAN_BULK_EXTEND, result?.kind)
    }

    @Test
    fun banner_loanBulkExtend_doesNotExceedTotal() {
        val result = banner(loanBulkExtend = LoanExtensionBulkProgress(completed = 4, total = 4))
        assertEquals("延長しています　4件目/4件", result?.text)
    }

    // 5. 一斉取消・一斉カート追加・一斉直接予約・カート確定・単件の本棚編集、それぞれの文言
    @Test
    fun banner_reservationCancel_hasFixedText() {
        val result = banner(cancelProcessing = true)
        assertEquals("予約を取り消しています", result?.text)
        assertEquals(OperationKind.RESERVATION_CANCEL, result?.kind)
    }

    @Test
    fun banner_cartAddition_hasFixedText() {
        val result = banner(cartAddProcessing = true)
        assertEquals("カートへ追加しています", result?.text)
        assertEquals(OperationKind.CART_ADDITION, result?.kind)
    }

    @Test
    fun banner_directReservation_hasFixedText() {
        val result = banner(directReservationProcessing = true)
        assertEquals("予約しています", result?.text)
        assertEquals(OperationKind.DIRECT_RESERVATION, result?.kind)
    }

    @Test
    fun banner_cartConfirm_hasFixedText() {
        val result = banner(cartConfirmProcessing = true)
        assertEquals("予約しています", result?.text)
        assertEquals(OperationKind.CART_CONFIRM, result?.kind)
    }

    @Test
    fun banner_bookshelfMutation_hasFixedText() {
        val result = banner(bookshelfMutationProcessing = true)
        assertEquals("本棚を変更しています", result?.text)
        assertEquals(OperationKind.BOOKSHELF_MUTATION, result?.kind)
    }

    // 6. 複数が同時に真のとき、優先順位の高い1つだけが選ばれる(組み合わせを数通り固定する)
    @Test
    fun banner_prioritizesBookshelfBulkAdd_overEverythingElse() {
        val result = banner(
            bookshelfBulkAdd = BookshelfBulkAddProgress(0, 3),
            loanBulkExtend = LoanExtensionBulkProgress(0, 2),
            cancelProcessing = true,
            cartAddProcessing = true,
            directReservationProcessing = true,
            cartConfirmProcessing = true,
            bookshelfMutationProcessing = true,
            syncing = true,
        )
        assertEquals(OperationKind.BOOKSHELF_BULK_ADD, result?.kind)
    }

    @Test
    fun banner_prioritizesLoanBulkExtend_overCancelAndBelow() {
        val result = banner(
            loanBulkExtend = LoanExtensionBulkProgress(0, 2),
            cancelProcessing = true,
            cartAddProcessing = true,
            directReservationProcessing = true,
            cartConfirmProcessing = true,
            bookshelfMutationProcessing = true,
            syncing = true,
        )
        assertEquals(OperationKind.LOAN_BULK_EXTEND, result?.kind)
    }

    @Test
    fun banner_prioritizesCancel_overCartAdditionAndBelow() {
        val result = banner(
            cancelProcessing = true,
            cartAddProcessing = true,
            directReservationProcessing = true,
            cartConfirmProcessing = true,
            bookshelfMutationProcessing = true,
            syncing = true,
        )
        assertEquals(OperationKind.RESERVATION_CANCEL, result?.kind)
    }

    @Test
    fun banner_prioritizesCartAddition_overDirectReservationAndBelow() {
        val result = banner(
            cartAddProcessing = true,
            directReservationProcessing = true,
            cartConfirmProcessing = true,
            bookshelfMutationProcessing = true,
            syncing = true,
        )
        assertEquals(OperationKind.CART_ADDITION, result?.kind)
    }

    @Test
    fun banner_prioritizesBookshelfMutation_overSync() {
        val result = banner(bookshelfMutationProcessing = true, syncing = true)
        assertEquals(OperationKind.BOOKSHELF_MUTATION, result?.kind)
    }

    // 7. 手動同期だけが進行しているとき「同期しています」。書き込みの操作と同時なら書き込み側が選ばれる
    @Test
    fun banner_syncOnly_showsSyncingText() {
        val result = banner(syncing = true)
        assertEquals("同期しています", result?.text)
        assertEquals(OperationKind.SYNC, result?.kind)
    }

    @Test
    fun banner_syncWithWriteOperation_prioritizesWriteOperation() {
        val result = banner(cartConfirmProcessing = true, syncing = true)
        assertEquals(OperationKind.CART_CONFIRM, result?.kind)
    }

    // 8. completionText: 操作の種類ごとに§2.7の文言を返す。手動同期はnullを返す(§2.8)
    @Test
    fun completionText_returnsExpectedTextForEachKind_andNullForSync() {
        assertEquals("本棚への追加が終わりました", OperationProgressContentBuilder.completionText(OperationKind.BOOKSHELF_BULK_ADD))
        assertEquals("延長が終わりました", OperationProgressContentBuilder.completionText(OperationKind.LOAN_BULK_EXTEND))
        assertEquals("予約の取消が終わりました", OperationProgressContentBuilder.completionText(OperationKind.RESERVATION_CANCEL))
        assertEquals("カートへの追加が終わりました", OperationProgressContentBuilder.completionText(OperationKind.CART_ADDITION))
        assertEquals("予約が終わりました", OperationProgressContentBuilder.completionText(OperationKind.DIRECT_RESERVATION))
        assertEquals("予約が終わりました", OperationProgressContentBuilder.completionText(OperationKind.CART_CONFIRM))
        assertEquals("本棚の変更が終わりました", OperationProgressContentBuilder.completionText(OperationKind.BOOKSHELF_MUTATION))
        assertNull(OperationProgressContentBuilder.completionText(OperationKind.SYNC))
    }
}
