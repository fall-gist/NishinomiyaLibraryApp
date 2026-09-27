package com.fallgist.nishinomiyalibrary.ui.shelf

/**
 * 本棚編集のドラッグ並べ替え(docs/design/bookshelf-order.md §4.8)で、
 * 「掴んだ書誌の画面上の中心y」と「他の行の画面上の中心yの一覧」から、移動先の位置(0始まり)を
 * 計算する純関数。
 *
 * §4.5時点では指の移動量の累積を行の高さで割って移動量を決めていたが、開始時点のタイルの位置と
 * 周りの行の位置を見ていなかったため、タイルが直下の書誌にほぼ重なっていてもさらに1冊分動かさないと
 * 入れ替わらない不具合があった(§4.8)。ここでは指の移動量ではなく、常に画面上の位置関係から
 * 落とす先を決め直す。
 *
 * この関数自体は一覧の「現在の並び」を保持しない(呼び出し側が[otherRowCenters]として都度渡す)。
 * これは、Composeの`pointerInput`のように一度始まると再起動しない長寿命のコルーチンから
 * 呼ぶ場合に、`dialog.items`のような値を直接キャプチャすると開始時点の古い並びに固定されてしまう
 * (`rememberUpdatedState`を使わない限り更新されない、という既知の落とし穴)ことを避けるため、
 * BookshelfScreen側で必ず最新の一覧から中心yの一覧を取り出して渡す設計にしている。
 */
object BookshelfEditItemDrag {
    /**
     * @param draggedCenterY 掴んだ書誌(タイル)の画面上の中心y
     * @param otherRowCenters 掴んだ書誌を除いた、現在の並び順における他の行の画面上の中心yの一覧
     *   (順序は問わない。純粋なしきい値による数え上げのため)
     * @return 掴んだ書誌の移動先の位置(0始まり)。[otherRowCenters]のうち[draggedCenterY]より
     *   小さい(=画面上でより上にある)ものの数。隣の行の中心を越えた時点で1つ進み、
     *   越えた行が反対側へ移るため、入れ替え後に同じ状態で再計算しても行ったり来たりしない。
     */
    fun targetIndexByPosition(draggedCenterY: Float, otherRowCenters: List<Float>): Int =
        otherRowCenters.count { it < draggedCenterY }
}
