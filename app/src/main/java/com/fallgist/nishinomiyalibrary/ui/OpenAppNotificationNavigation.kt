package com.fallgist.nishinomiyalibrary.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 通知タップでアプリを開くだけの共通PendingIntent。
 * 画面遷移は行わない（actionを設定しないため、HomeNavigationCommandStoreによる
 * HOME強制遷移は発生しない）。
 */
object OpenAppNotificationNavigation {
    fun pendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
