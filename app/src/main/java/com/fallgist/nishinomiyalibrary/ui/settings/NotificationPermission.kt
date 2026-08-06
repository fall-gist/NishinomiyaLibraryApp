package com.fallgist.nishinomiyalibrary.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

/** 通知権限(POST_NOTIFICATIONS)の状態。docs/design/notification-permission.md 参照。 */
enum class NotificationPermissionUiState {
    /** Android 12以下。権限不要。このセクションは表示しない。 */
    NotRequired,

    /** 許可済み。表示しない。 */
    Granted,

    /** 未許可。要求できる可能性がある。 */
    Missing,

    /** 要求したが拒否された(または設定アプリで明示的にオフにされた)。設定アプリへ誘導する。 */
    Denied,
}

/** 状態→警告文言・ボタン表示の判定。純Kotlin、Compose非依存でテスト対象。 */
object NotificationPermissionMessages {
    /** 警告行(と操作ボタン)を表示すべきか。NotRequired/Grantedでは表示しない。 */
    fun shouldShowNotice(state: NotificationPermissionUiState): Boolean = when (state) {
        NotificationPermissionUiState.NotRequired -> false
        NotificationPermissionUiState.Granted -> false
        NotificationPermissionUiState.Missing -> true
        NotificationPermissionUiState.Denied -> true
    }

    /** 警告本文。表示しない状態ではnull。 */
    fun message(state: NotificationPermissionUiState): String? = when (state) {
        NotificationPermissionUiState.NotRequired -> null
        NotificationPermissionUiState.Granted -> null
        NotificationPermissionUiState.Missing ->
            "通知が許可されていません。返却期限や受取可能のお知らせが届きません。"
        NotificationPermissionUiState.Denied ->
            "通知が許可されていません。端末の設定から通知をオンにしてください。"
    }

    /** 操作ボタンの文言。表示しない状態ではnull。 */
    fun buttonLabel(state: NotificationPermissionUiState): String? = when (state) {
        NotificationPermissionUiState.NotRequired -> null
        NotificationPermissionUiState.Granted -> null
        NotificationPermissionUiState.Missing -> "通知を許可する"
        NotificationPermissionUiState.Denied -> "アプリの通知設定を開く"
    }
}

/** [rememberNotificationPermissionController] が返す操作口。 */
class NotificationPermissionController(
    private val stateProvider: () -> NotificationPermissionUiState,
    private val onRequestOrOpenSettings: () -> Unit,
) {
    val state: NotificationPermissionUiState get() = stateProvider()

    /** Missingなら権限要求、Deniedなら設定アプリを開く。 */
    fun requestOrOpenSettings() = onRequestOrOpenSettings()
}

/**
 * 通知が実際に届く状態かどうか。
 *
 * `AndroidNotificationSink.canPost()`が投稿可否に使う条件のうち、**アプリ単位の2条件**
 * （ランタイム権限とアプリ全体の通知トグル）と揃える。権限だけを見ると、権限は許可のまま
 * アプリ全体の通知がオフにされた端末で「警告を出さないのに通知が届かない」状態になる。
 *
 * **チャンネル単位の無効化（`IMPORTANCE_NONE`）は見ていない。** `canPost()`はチャンネルごとに
 * 判定するため、特定チャンネルだけをオフにされた場合はここでは検出できない（既知の制約。
 * `docs/design/notification-permission.md`参照）。
 */
private fun canReceiveNotifications(context: android.content.Context): Boolean {
    val manager = context.getSystemService(android.app.NotificationManager::class.java)
    if (manager != null && !manager.areNotificationsEnabled()) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}

/**
 * 通知権限の状態を管理するController。
 * ON_RESUMEで権限状態を再評価する(設定アプリで許可して戻ってきたケースを拾うため)。
 */
@Composable
fun rememberNotificationPermissionController(): NotificationPermissionController {
    val context = LocalContext.current.applicationContext
    val notRequired = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    var state by remember {
        mutableStateOf(
            when {
                notRequired -> NotificationPermissionUiState.NotRequired
                canReceiveNotifications(context) -> NotificationPermissionUiState.Granted
                else -> NotificationPermissionUiState.Missing
            },
        )
    }

    fun reevaluate() {
        if (notRequired) return
        state = if (canReceiveNotifications(context)) {
            NotificationPermissionUiState.Granted
        } else if (state == NotificationPermissionUiState.Granted) {
            // 直前までGrantedだった場合(設定アプリでオフにされた)はMissingへ戻す。
            NotificationPermissionUiState.Missing
        } else {
            state
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 権限が取れても、アプリ全体の通知がオフなら通知は届かない。その場合はGrantedにせず
        // Deniedへ倒し、設定アプリへの導線を出す(権限だけを見るとここで警告が消えてしまう)。
        state = if (granted && canReceiveNotifications(context)) {
            NotificationPermissionUiState.Granted
        } else {
            NotificationPermissionUiState.Denied
        }
    }

    fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    // ON_RESUMEでの再評価: 設定アプリで許可して戻ってきたとき警告を消すために必須。
    val lifecycleOwner = LocalLifecycleOwner.current
    val reevaluateOnResume = rememberUpdatedState { reevaluate() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reevaluateOnResume.value()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return NotificationPermissionController(
        stateProvider = { state },
        onRequestOrOpenSettings = {
            when (state) {
                NotificationPermissionUiState.Missing ->
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                NotificationPermissionUiState.Denied -> openAppNotificationSettings()
                NotificationPermissionUiState.NotRequired,
                NotificationPermissionUiState.Granted,
                -> Unit
            }
        },
    )
}

/** 通知権限の警告行と操作ボタン。NotRequired/Grantedのときは何も描画しない。 */
@Composable
fun NotificationPermissionNotice(controller: NotificationPermissionController) {
    val state = controller.state
    if (!NotificationPermissionMessages.shouldShowNotice(state)) return
    val message = NotificationPermissionMessages.message(state) ?: return
    val buttonLabel = NotificationPermissionMessages.buttonLabel(state) ?: return

    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.alertBg)
            .padding(10.dp),
    ) {
        Text(
            text = message,
            color = colors.alert,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = buttonLabel,
            color = colors.alert,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.card)
                .clickable { controller.requestOrOpenSettings() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
