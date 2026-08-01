package com.fallgist.nishinomiyalibrary.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 自動予約通知タップをHOME遷移へ変換するための専用契約。 */
object AutoReservationNotificationNavigation {
    const val ACTION_OPEN_HOME =
        "com.fallgist.nishinomiyalibrary.action.OPEN_HOME_FROM_AUTO_RESERVATION"

    fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val REQUEST_CODE = 1003
}

/** Intentのactionを一回限りのHOME遷移コマンドへ変換する。 */
class HomeNavigationCommandStore {
    private val mutableCommandId = MutableStateFlow<Long?>(null)
    val commandId: StateFlow<Long?> = mutableCommandId.asStateFlow()
    private var nextId = 0L

    fun accept(intent: Intent?): Boolean {
        if (intent?.action != AutoReservationNotificationNavigation.ACTION_OPEN_HOME) return false
        intent.action = null
        nextId += 1
        mutableCommandId.value = nextId
        return true
    }

    fun consume(commandId: Long) {
        if (mutableCommandId.value == commandId) {
            mutableCommandId.value = null
        }
    }
}
