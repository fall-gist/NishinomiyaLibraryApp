package com.fallgist.nishinomiyalibrary.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.data.backup.BackupFileNaming
import com.fallgist.nishinomiyalibrary.data.backup.BackupImportResult
import com.fallgist.nishinomiyalibrary.data.backup.passwordRestoreMessage
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors
import java.io.IOException
import java.time.LocalDateTime
import kotlinx.coroutines.launch

/**
 * 設定のエクスポート/インポート(端末間移行)。docs/design/settings-export-import.md 参照。
 * SAFのURI取得(Android依存)はここで行い、Controllerへは文字列だけを渡す境界にする。
 * ファイルにはログインパスワードが暗号化(§4)されて含まれるため、取り扱いに注意する旨をUIに明記する。
 */
@Composable
fun BackupSection(
    memberCount: Int,
    onExport: suspend () -> BackupExportOutcome,
    onImport: suspend (String) -> BackupImportResult,
    onImportSucceeded: (requestNotificationPermission: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        statusMessage = null
        scope.launch {
            when (val outcome = onExport()) {
                is BackupExportOutcome.Success -> {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(outcome.json.toByteArray(Charsets.UTF_8))
                        } ?: throw IOException("出力先を開けませんでした")
                        statusIsError = false
                        statusMessage = "書き出しました"
                    } catch (exception: IOException) {
                        statusIsError = true
                        statusMessage = "書き出しに失敗しました。保存先の権限を確認してください"
                    } catch (exception: SecurityException) {
                        statusIsError = true
                        statusMessage = "書き出しに失敗しました。保存先の権限を確認してください"
                    }
                }

                is BackupExportOutcome.Failed -> {
                    statusIsError = true
                    statusMessage = outcome.message
                }
            }
            busy = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        statusMessage = null
        scope.launch {
            val jsonText = try {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
            } catch (exception: IOException) {
                null
            } catch (exception: SecurityException) {
                null
            }
            if (jsonText == null) {
                statusIsError = true
                statusMessage = "ファイルを読み込めませんでした。保存先の権限を確認してください"
                busy = false
                return@launch
            }
            when (val result = onImport(jsonText)) {
                is BackupImportResult.Success -> {
                    statusIsError = result.passwordRestoreFailedCount > 0
                    val passwordNote = passwordRestoreMessage(result.passwordRestoreFailedCount)
                    statusMessage = if (passwordNote != null) {
                        "${result.importedMemberCount}人分のメンバーと設定を読み込みました。$passwordNote"
                    } else {
                        "${result.importedMemberCount}人分のメンバーと設定、パスワードを読み込みました"
                    }
                    onImportSucceeded(result.requestNotificationPermission)
                }

                is BackupImportResult.AppliedWithWarning -> {
                    // データの取り込み自体は完了している(Roomトランザクション確定後の後処理失敗)。
                    statusIsError = true
                    val passwordNote = passwordRestoreMessage(result.passwordRestoreFailedCount)
                    statusMessage = if (passwordNote != null) "${result.message}\n$passwordNote" else result.message
                    onImportSucceeded(result.requestNotificationPermission)
                }

                is BackupImportResult.Rejected -> {
                    statusIsError = true
                    statusMessage = result.message
                }
            }
            busy = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "端末を買い替えるとき、この端末の内容を1つのファイルに書き出し、新しい端末で読み込めます。" +
                "このファイルにはログインパスワードが含まれます。取り扱いに注意してください。",
            color = colors.ink2,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    if (busy) return@Button
                    exportLauncher.launch(BackupFileNaming.suggestedFileName(LocalDateTime.now()))
                },
                enabled = !busy,
            ) { Text("書き出す") }
            Button(
                onClick = {
                    if (busy) return@Button
                    showImportConfirm = true
                },
                enabled = !busy,
            ) { Text("読み込む") }
        }
        statusMessage?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                color = if (statusIsError) colors.alert else colors.ink2,
                fontSize = 11.sp,
            )
        }
    }

    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text("設定を読み込みますか?") },
            text = {
                Text(
                    "現在のメンバー${memberCount}人と設定は破棄され、ファイルの内容に置き換わります。" +
                        "保存済みのパスワードもファイルの内容で上書きされます。この操作は取り消せません。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirm = false
                        importLauncher.launch(arrayOf("application/json"))
                    },
                ) { Text("読み込む", color = colors.alert) }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) { Text("キャンセル") }
            },
        )
    }
}
