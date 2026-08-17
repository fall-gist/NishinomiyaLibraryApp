package com.fallgist.nishinomiyalibrary.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedTextField
import com.fallgist.nishinomiyalibrary.data.backup.BackupImportResult
import com.fallgist.nishinomiyalibrary.data.backup.passwordRestoreMessage
import com.fallgist.nishinomiyalibrary.ui.member.MemberRegistrationResult
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationErrors
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationForm
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors
import java.io.IOException
import kotlinx.coroutines.launch
import android.graphics.Color as AndroidColor

private val PresetColors = listOf(
    "#3D6DB5", "#C25278", "#D98E2B", "#5FA05A", "#8A64B8",
)

/**
 * 家族アカウントが1つも無いときにトップ画面へ表示する初期登録フォーム。
 * 他画面へ遷移せず、その場で登録が完了する。秘密情報は画面ローカルにのみ保持する。
 */
@Composable
fun MemberRegistrationForm(
    onRegister: suspend (RegistrationForm) -> MemberRegistrationResult,
    onImportBackup: suspend (String) -> BackupImportResult,
    onImportSucceeded: (requestNotificationPermission: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // rememberSaveableは使わない: カード番号・パスワードを端末の保存状態に残さないため。
    var name by remember { mutableStateOf("") }
    var colorHex by remember { mutableStateOf(PresetColors.first()) }
    var cardNumber by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf(RegistrationErrors()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // バックアップからの復元(§5.1)。メンバー0人なので破棄されるものが無く、確認ダイアログは出さない。
    var importBusy by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importIsError by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) {
            // SAFがキャンセルされた場合。onClickで立てたフラグをここで解除しないと
            // ボタンが二度と押せなくなる(docs/design/settings-export-import.md §5.1)。
            importBusy = false
            return@rememberLauncherForActivityResult
        }
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
                importIsError = true
                importMessage = "ファイルを読み込めませんでした。保存先の権限を確認してください"
                importBusy = false
                return@launch
            }
            // 結果の3値・パスワード復元失敗の案内は設定画面(BackupSection)と同じ文言にする
            // (docs/design/settings-export-import.md §5.1: 同じ処理の結果を画面ごとに別の言葉で説明しない)。
            when (val result = onImportBackup(jsonText)) {
                is BackupImportResult.Success -> {
                    importIsError = result.passwordRestoreFailedCount > 0
                    val passwordNote = passwordRestoreMessage(result.passwordRestoreFailedCount)
                    importMessage = if (passwordNote != null) {
                        "${result.importedMemberCount}人分のメンバーと設定を読み込みました。$passwordNote"
                    } else {
                        "${result.importedMemberCount}人分のメンバーと設定、パスワードを読み込みました"
                    }
                    onImportSucceeded(result.requestNotificationPermission)
                }

                is BackupImportResult.AppliedWithWarning -> {
                    importIsError = true
                    val passwordNote = passwordRestoreMessage(result.passwordRestoreFailedCount)
                    importMessage = if (passwordNote != null) "${result.message}\n$passwordNote" else result.message
                    onImportSucceeded(result.requestNotificationPermission)
                }

                is BackupImportResult.Rejected -> {
                    importIsError = true
                    importMessage = result.message
                }
            }
            importBusy = false
        }
    }

    Column(
        modifier = modifier
            .background(colors.paper)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("西宮市立図書館", color = colors.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "はじめに、家族のアカウントを1人登録してください。",
            color = colors.ink2,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("表示名") },
            singleLine = true,
            isError = errors.name != null,
            supportingText = errors.name?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = cardNumber,
            onValueChange = { cardNumber = it },
            label = { Text("図書館カード番号") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = errors.cardNumber != null,
            supportingText = errors.cardNumber?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("パスワード") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = errors.password != null,
            supportingText = errors.password?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        Text("識別色", color = colors.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PresetColors.forEach { preset ->
                ColorSwatch(
                    hex = preset,
                    selected = colorHex.equals(preset, ignoreCase = true),
                    onClick = { colorHex = preset },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = colorHex,
            onValueChange = { colorHex = it },
            label = { Text("識別色 (#RRGGBB)") },
            singleLine = true,
            isError = errors.colorHex != null,
            supportingText = errors.colorHex?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                if (busy) return@Button
                busy = true
                message = null
                scope.launch {
                    when (val result = onRegister(RegistrationForm(name, colorHex, cardNumber, password))) {
                        MemberRegistrationResult.Saved -> {
                            errors = RegistrationErrors()
                            cardNumber = ""
                            password = ""
                            message = "登録しました"
                        }

                        is MemberRegistrationResult.Invalid -> {
                            errors = result.errors
                            message = "入力内容を確認してください"
                        }

                        MemberRegistrationResult.Failed -> {
                            message = "登録に失敗しました。時間をおいて再試行してください"
                        }
                    }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "登録中…" else "登録する")
        }

        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = colors.ink2, fontSize = 13.sp)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "認証情報は端末内に暗号化して保存され、図書館の公式サイト以外へは送信されません。",
            color = colors.ink2,
            fontSize = 11.sp,
        )

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = colors.line)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "以前の端末のバックアップファイルをお持ちの場合",
            color = colors.ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "設定は既定値からファイルの内容に置き換わります。",
            color = colors.ink2,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                if (importBusy) return@OutlinedButton
                // 「登録する」ボタンと同様、onClick内で同期的にフラグを立てる。SAFのピッカーが
                // 前面に出るまでの間も連打を防ぐ(docs/design/settings-export-import.md §5.1)。
                importBusy = true
                importMessage = null
                importLauncher.launch(arrayOf("application/json"))
            },
            enabled = !importBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (importBusy) "読み込み中…" else "バックアップから復元")
        }
        importMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                color = if (importIsError) colors.alert else colors.ink2,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ColorSwatch(hex: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val swatchColor = runCatching { Color(AndroidColor.parseColor(hex)) }.getOrDefault(colors.ink2)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(swatchColor)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) colors.ink else colors.line,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}
