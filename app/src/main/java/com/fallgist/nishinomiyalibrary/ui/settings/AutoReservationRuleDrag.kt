package com.fallgist.nishinomiyalibrary.ui.settings

/** 長押しドラッグの累積距離を、ルールの上下移動回数へ変換する純粋関数。 */
object AutoReservationRuleDrag {
    fun steps(accumulatedPixels: Float, thresholdPixels: Float): Int {
        if (thresholdPixels <= 0f) return 0
        return (accumulatedPixels / thresholdPixels).toInt()
    }
}
