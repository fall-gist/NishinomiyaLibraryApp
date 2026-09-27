package com.fallgist.nishinomiyalibrary.ui.shelf

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ドラッグの目標位置計算(`docs/design/bookshelf-order.md` §4.5)。
 * `BookshelfScreen`側の長寿命なpointerInputコルーチンから安全に呼べるよう、
 * 一覧を保持しない純関数として切り出した(レビュー指摘: 開始位置は常に呼び出し時点の最新の件数から計算する)。
 */
class BookshelfEditItemDragTest {
    @Test
    fun `移動量に応じて開始位置から複数行分を一度に移動できる`() {
        // 1行=48px。2.5行分下へ動かすと2行分(48*2=96px)だけ進む(端数は切り捨て)。
        assertEquals(2, BookshelfEditItemDrag.targetIndex(dragStartIndex = 0, accumulatedPx = 120f, rowHeightPx = 48f, itemCount = 5))
        assertEquals(0, BookshelfEditItemDrag.targetIndex(dragStartIndex = 2, accumulatedPx = -120f, rowHeightPx = 48f, itemCount = 5))
    }

    @Test
    fun `移動量が1行未満なら開始位置のまま変わらない`() {
        assertEquals(1, BookshelfEditItemDrag.targetIndex(dragStartIndex = 1, accumulatedPx = 30f, rowHeightPx = 48f, itemCount = 5))
        assertEquals(1, BookshelfEditItemDrag.targetIndex(dragStartIndex = 1, accumulatedPx = -30f, rowHeightPx = 48f, itemCount = 5))
    }

    @Test
    fun `範囲外へは進まず先頭または末尾でクランプする`() {
        assertEquals(0, BookshelfEditItemDrag.targetIndex(dragStartIndex = 0, accumulatedPx = -500f, rowHeightPx = 48f, itemCount = 5))
        assertEquals(4, BookshelfEditItemDrag.targetIndex(dragStartIndex = 0, accumulatedPx = 500f, rowHeightPx = 48f, itemCount = 5))
    }

    @Test
    fun `件数が0以下なら常に0を返す`() {
        assertEquals(0, BookshelfEditItemDrag.targetIndex(dragStartIndex = 0, accumulatedPx = 100f, rowHeightPx = 48f, itemCount = 0))
    }

    /**
     * 2回目以降のドラッグ(=呼び出し時点で開始位置と件数が変わっている状況)を模した回帰確認。
     * [A, B, C, D]でAを2行下へ動かした後の並び([B, C, A, D])から、
     * 新しいdragStartIndex(=2、最新の並びでのAの位置)を使って計算すれば、
     * 古い並び(dragStartIndex=0)を引きずって先頭へ飛ぶことはない。
     */
    @Test
    fun `2回目のドラッグは最新の開始位置を使えば古い並びへ飛ばない`() {
        // 古い並び(dragStartIndex=0)のまま1行上へ動かすと誤って0のまま(先頭)になってしまう例。
        val staleTarget = BookshelfEditItemDrag.targetIndex(dragStartIndex = 0, accumulatedPx = -48f, rowHeightPx = 48f, itemCount = 4)
        assertEquals(0, staleTarget)
        // 正しくは最新の並びでのAの位置(2)を開始位置として渡す。
        val correctTarget = BookshelfEditItemDrag.targetIndex(dragStartIndex = 2, accumulatedPx = -48f, rowHeightPx = 48f, itemCount = 4)
        assertEquals(1, correctTarget)
    }
}
