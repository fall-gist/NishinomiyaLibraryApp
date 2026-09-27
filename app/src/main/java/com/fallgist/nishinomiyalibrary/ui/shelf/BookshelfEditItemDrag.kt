package com.fallgist.nishinomiyalibrary.ui.shelf

import com.fallgist.nishinomiyalibrary.ui.settings.AutoReservationRuleDrag

/**
 * 本棚編集のドラッグ並べ替え(docs/design/bookshelf-order.md §4.5)で、
 * 「ドラッグを開始した位置」と「指の移動量の累積」から、移動先の位置(0始まり、範囲内にクランプ済み)を
 * 計算する純関数。[AutoReservationRuleDrag]と同じ考え方(移動量÷しきい値=移動する行数)だが、
 * 1行ずつ隣と入れ替えるのではなく、開始位置に移動する行数を直接足すことで、
 * 何件もまたいで一度に動かせるようにする。
 *
 * この関数自体は一覧の「現在の並び」を保持しない(呼び出し側が[itemCount]として都度渡す)。
 * これは、Composeの`pointerInput`のように一度始まると再起動しない長寿命のコルーチンから
 * 呼ぶ場合に、`dialog.items`のような値を直接キャプチャすると開始時点の古い並びに固定されてしまう
 * (`rememberUpdatedState`を使わない限り更新されない、という既知の落とし穴)ことを避けるため、
 * BookshelfScreen側で必ず最新の一覧から件数を取り出して渡す設計にしている。
 */
object BookshelfEditItemDrag {
    fun targetIndex(dragStartIndex: Int, accumulatedPx: Float, rowHeightPx: Float, itemCount: Int): Int {
        if (itemCount <= 0) return 0
        val steps = AutoReservationRuleDrag.steps(accumulatedPx, rowHeightPx)
        return (dragStartIndex + steps).coerceIn(0, itemCount - 1)
    }
}
