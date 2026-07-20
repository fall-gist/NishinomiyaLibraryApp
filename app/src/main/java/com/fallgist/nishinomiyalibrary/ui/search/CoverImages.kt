package com.fallgist.nishinomiyalibrary.ui.search

import android.content.Context
import coil.ImageLoader

/**
 * 表紙画像用のImageLoader。spec §3.8のとおりディスクキャッシュは持たず、
 * メモリキャッシュのみ(アプリ終了で消える)。アプリ内で1つを共有する。
 */
object CoverImageLoaderHolder {
    @Volatile
    private var loader: ImageLoader? = null

    fun get(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .diskCache(null)
            .crossfade(true)
            .build()
            .also { loader = it }
    }
}
