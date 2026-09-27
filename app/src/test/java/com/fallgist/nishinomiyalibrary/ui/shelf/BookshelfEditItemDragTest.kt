package com.fallgist.nishinomiyalibrary.ui.shelf

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ドラッグの目標位置計算(`docs/design/bookshelf-order.md` §4.8)。
 * §4.5時点は指の移動量÷行の高さで移動量を決めていたが、開始時点のタイルの位置と周りの行の位置を
 * 見ていなかったため、タイルが直下の書誌にほぼ重なっていてもさらに1冊分動かさないと入れ替わらない
 * 不具合があった。ここでは画面上の位置関係(掴んだタイルの中心yと他の行の中心yの大小関係)だけで
 * 落とす先を決める純関数として固定する。
 */
class BookshelfEditItemDragTest {
    @Test
    fun `先頭へ動かすと0を返す`() {
        // 他の行の中心が[100, 200, 300, 400]のとき、掴んだタイルの中心が先頭より上(50)なら0。
        assertEquals(0, BookshelfEditItemDrag.targetIndexByPosition(draggedCenterY = 50f, otherRowCenters = listOf(100f, 200f, 300f, 400f)))
    }

    @Test
    fun `末尾へ動かすと他の行の数を返す`() {
        // 全ての行より下(450)なら、他の行の数(4)がそのまま移動先になる。
        assertEquals(4, BookshelfEditItemDrag.targetIndexByPosition(draggedCenterY = 450f, otherRowCenters = listOf(100f, 200f, 300f, 400f)))
    }

    @Test
    fun `ちょうど中心のとき隣の行の中心を越えた数だけ進む`() {
        // 100と200の間(150)なら1。200と300の間(250)なら2。
        assertEquals(1, BookshelfEditItemDrag.targetIndexByPosition(draggedCenterY = 150f, otherRowCenters = listOf(100f, 200f, 300f, 400f)))
        assertEquals(2, BookshelfEditItemDrag.targetIndexByPosition(draggedCenterY = 250f, otherRowCenters = listOf(100f, 200f, 300f, 400f)))
    }

    @Test
    fun `他の行が無ければ常に0を返す`() {
        assertEquals(0, BookshelfEditItemDrag.targetIndexByPosition(draggedCenterY = 123f, otherRowCenters = emptyList()))
    }

    /**
     * 入れ替え後の配置で再計算しても同じ結果になる(行ったり来たりしない)ことの回帰確認。
     * 各行の中心yは(順序に関わらず)一覧上の固定スロットの位置であり、`targetIndexByPosition`は
     * 単なるしきい値による数え上げなので、[otherRowCenters]の並び順(=どの資料がどのスロットにいるか)を
     * 変えても、同じ集合であれば同じ結果になるはずである。
     */
    @Test
    fun `入れ替え後の並び順で再計算しても同じ結果になる`() {
        val centers = listOf(100f, 200f, 300f, 400f)
        val shuffled = listOf(300f, 100f, 400f, 200f)
        val draggedCenterY = 250f
        val before = BookshelfEditItemDrag.targetIndexByPosition(draggedCenterY, centers)
        val after = BookshelfEditItemDrag.targetIndexByPosition(draggedCenterY, shuffled)
        assertEquals(before, after)
    }
}
