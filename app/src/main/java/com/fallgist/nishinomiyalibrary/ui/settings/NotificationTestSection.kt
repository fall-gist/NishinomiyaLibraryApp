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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.data.sync.AndroidNotificationSink
import com.fallgist.nishinomiyalibrary.data.sync.MemberLoanItems
import com.fallgist.nishinomiyalibrary.data.sync.PickupReadyItem
import com.fallgist.nishinomiyalibrary.data.sync.PickupReadyPlan
import com.fallgist.nishinomiyalibrary.data.sync.ReturnReminderPlan
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * 通知テストボタン(一時的な検証用)。
 * 詳細は docs/design/notification-test-button.md を参照。確認が取れ次第撤去する。
 * 撤去手順に従い、このファイル自体と SettingsScreen.kt からの2行の呼び出しを削除すれば完結する。
 */
@Composable
fun NotificationTestSection() {
    val colors = LocalAppColors.current
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var resultText by remember { mutableStateOf<String?>(null) }
    var showOpenSettingsButton by remember { mutableStateOf(false) }

    fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun sendDummyNotifications() {
        scope.launch {
            val sink = AndroidNotificationSink(context)
            val returnReminderPlan = ReturnReminderPlan(
                itemsByMember = listOf(
                    MemberLoanItems(
                        memberId = -1L,
                        memberName = "テスト太郎",
                        titles = listOf("通知テスト用の本A", "通知テスト用の本B"),
                    ),
                    MemberLoanItems(
                        memberId = -2L,
                        memberName = "テスト花子",
                        titles = listOf("通知テスト用の本C"),
                    ),
                ),
                hasOverdue = false,
            )
            val pickupReadyPlan = PickupReadyPlan(
                items = listOf(
                    PickupReadyItem(
                        reservationId = -1L,
                        memberName = "テスト太郎",
                        title = "通知テスト用の予約本",
                        pickupLibrary = "テスト館",
                        holdExpiryDate = LocalDate.now().plusDays(7),
                    ),
                ),
            )
            val returnOk = sink.postReturnReminder(returnReminderPlan)
            val pickupOk = sink.postPickupReady(pickupReadyPlan)
            when {
                returnOk && pickupOk -> {
                    resultText = "通知を2件送信しました。通知領域を確認してください。"
                    showOpenSettingsButton = false
                }
                else -> {
                    resultText = "送信できませんでした(通知がオフ、またはチャンネルが無効です)。"
                    showOpenSettingsButton = true
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            sendDummyNotifications()
        } else {
            resultText = "通知の権限が許可されていません。"
            showOpenSettingsButton = true
        }
    }

    fun onTestButtonClick() {
        val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsRuntimePermission) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            sendDummyNotifications()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "通知テスト(検証用)",
            color = colors.ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "ダミーの内容で2件の通知を送信します。確認後は撤去予定です。",
            color = colors.ink2,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "通知テストを送信",
            color = colors.greenInk,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.greenBg)
                .clickable { onTestButtonClick() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        resultText?.let { message ->
            Text(
                text = message,
                color = colors.ink2,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (showOpenSettingsButton) {
            Text(
                text = "アプリの通知設定を開く",
                color = colors.green,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable { openAppNotificationSettings() }
                    .padding(top = 4.dp),
            )
        }
    }
}
