package com.fallgist.nishinomiyalibrary.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.data.local.RETURN_REMINDER_DAYS_RANGE
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.ui.components.MemberDot
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import com.fallgist.nishinomiyalibrary.ui.components.parseMemberColor
import com.fallgist.nishinomiyalibrary.ui.member.MemberRegistrationResult
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationErrors
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationForm
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

private val PresetColors = listOf(
    "#3D6DB5", "#C25278", "#D98E2B", "#5FA05A", "#8A64B8",
)

/** メンバーフォームの編集対象。nullなら閉じている。 */
private data class EditorTarget(val member: Member?)

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onSaveMember: suspend (editingMemberId: Long?, RegistrationForm) -> MemberRegistrationResult,
    onMoveMemberUp: (Long) -> Unit,
    onMoveMemberDown: (Long) -> Unit,
    onRemoveMember: (Long) -> Unit,
    onUpdateSyncTime: (hour: Int, minute: Int) -> Unit,
    onSetNotifyReturnReminder: (Boolean) -> Unit,
    onSetNotifyPickupReady: (Boolean) -> Unit,
    onSetReturnReminderDaysBefore: (Int) -> Unit,
    onSetDefaultCalendarLibrary: (String) -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    var editorTarget by remember { mutableStateOf<EditorTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<Member?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.paper)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(title = "設定", onOpenMenu = onOpenMenu)

        SectionTitle("メンバー管理")
        SectionCard {
            if (state.memberRows.isEmpty()) {
                Text(
                    text = "メンバーが登録されていません",
                    color = colors.ink2,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            state.memberRows.forEachIndexed { index, row ->
                MemberRowView(
                    row = row,
                    onMoveUp = { onMoveMemberUp(row.member.id) },
                    onMoveDown = { onMoveMemberDown(row.member.id) },
                    onEdit = { editorTarget = EditorTarget(row.member) },
                    onDelete = { deleteTarget = row.member },
                )
                if (index != state.memberRows.lastIndex) {
                    DividerLine()
                }
            }
        }
        if (state.canAddMember && editorTarget == null) {
            Text(
                text = "+ メンバーを追加",
                color = colors.green,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(horizontal = 18.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, colors.line, RoundedCornerShape(10.dp))
                    .clickable { editorTarget = EditorTarget(member = null) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Text(
            text = "${state.memberRows.size}/${state.memberLimit}人 登録済み",
            color = colors.ink2,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
        )

        editorTarget?.let { target ->
            MemberEditorCard(
                editing = target.member,
                onSave = onSaveMember,
                onClose = { editorTarget = null },
            )
        }

        SectionTitle("同期")
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自動同期時刻", color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("1日1回、この時刻に自動で同期します", color = colors.ink2, fontSize = 11.sp)
                }
                SyncTimePicker(
                    hour = state.settings.syncHour,
                    minute = state.settings.syncMinute,
                    onUpdate = onUpdateSyncTime,
                )
            }
            DividerLine()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("最終同期", color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = state.lastSyncText,
                    color = if (state.lastSyncFailed) colors.alert else colors.ink,
                    fontSize = 12.sp,
                )
            }
        }

        SectionTitle("通知")
        Text(
            text = "種類ごとにオン/オフを切り替えられます。該当する通知は家族分をまとめて1通で届きます。",
            color = colors.ink2,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
        Spacer(Modifier.height(6.dp))
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("返却期限リマインダー", color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("設定した日数前にまとめて通知(既定: 前日)", color = colors.ink2, fontSize = 11.sp)
                }
                Switch(
                    checked = state.settings.notifyReturnReminder,
                    onCheckedChange = onSetNotifyReturnReminder,
                )
            }
            if (state.settings.notifyReturnReminder) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("通知タイミング", color = colors.ink2, fontSize = 12.sp)
                    SimpleDropdown(
                        current = reminderDaysLabel(state.settings.returnReminderDaysBefore),
                        options = RETURN_REMINDER_DAYS_RANGE.map { reminderDaysLabel(it) to it },
                        onSelect = onSetReturnReminderDaysBefore,
                    )
                }
            }
            DividerLine()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("予約受取可能", color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("受取可を検知した同期時にまとめて通知", color = colors.ink2, fontSize = 11.sp)
                }
                Switch(
                    checked = state.settings.notifyPickupReady,
                    onCheckedChange = onSetNotifyPickupReady,
                )
            }
        }

        SectionTitle("カレンダー")
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("既定表示館", color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("開館カレンダーを開いたとき最初に表示する館", color = colors.ink2, fontSize = 11.sp)
                }
                SimpleDropdown(
                    current = state.libraries.find { it.code == state.settings.defaultCalendarLibrary }?.name
                        ?: state.settings.defaultCalendarLibrary,
                    options = state.libraries.map { it.name to it.code },
                    onSelect = onSetDefaultCalendarLibrary,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }

    deleteTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("${member.name} を削除しますか?") },
            text = { Text("このメンバーの認証情報と取得済みデータを端末から削除します。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveMember(member.id)
                        deleteTarget = null
                    },
                ) { Text("削除する", color = colors.alert) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("キャンセル") }
            },
        )
    }
}

private fun reminderDaysLabel(days: Int): String = if (days == 1) "1日前(前日)" else "${days}日前"

@Composable
private fun SectionTitle(title: String) {
    val colors = LocalAppColors.current
    Text(
        text = title,
        color = colors.ink2,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        content()
    }
}

@Composable
private fun DividerLine() {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(1.dp)
            .background(colors.line),
    )
}

@Composable
private fun MemberRowView(
    row: SettingsMemberRow,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MemberDot(row.member.colorHex, size = 11.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(row.member.name, color = colors.ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text("カード番号 ${row.maskedCardNumber}", color = colors.ink2, fontSize = 11.sp)
        }
        IconButtonBox("↑", enabled = row.canMoveUp, onClick = onMoveUp)
        IconButtonBox("↓", enabled = row.canMoveDown, onClick = onMoveDown)
        IconButtonBox("✎", enabled = true, onClick = onEdit)
        IconButtonBox("✕", enabled = true, onClick = onDelete, danger = true)
    }
}

@Composable
private fun IconButtonBox(label: String, enabled: Boolean, onClick: () -> Unit, danger: Boolean = false) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.line, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> colors.line
                danger -> colors.alert
                else -> colors.ink
            },
            fontSize = 13.sp,
        )
    }
}

/**
 * メンバー追加/編集のインラインフォーム。初期登録フォームと同一項目・同一検証。
 * カード番号・パスワードは rememberSaveable を使わず画面ローカルにのみ保持する。
 */
@Composable
private fun MemberEditorCard(
    editing: Member?,
    onSave: suspend (editingMemberId: Long?, RegistrationForm) -> MemberRegistrationResult,
    onClose: () -> Unit,
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()

    var name by remember(editing?.id) { mutableStateOf(editing?.name ?: "") }
    var colorHex by remember(editing?.id) { mutableStateOf(editing?.colorHex ?: PresetColors.first()) }
    var cardNumber by remember(editing?.id) { mutableStateOf(editing?.cardNumber ?: "") }
    var password by remember(editing?.id) { mutableStateOf("") }
    var errors by remember(editing?.id) { mutableStateOf(RegistrationErrors()) }
    var message by remember(editing?.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(
            text = if (editing == null) "メンバーを追加" else "${editing.name} を編集",
            color = colors.ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("表示名") },
            singleLine = true,
            isError = errors.name != null,
            supportingText = errors.name?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
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
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("パスワード") },
            placeholder = {
                if (editing != null) Text("変更しない場合は空欄", fontSize = 12.sp)
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = errors.password != null,
            supportingText = errors.password?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        Text("識別色", color = colors.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PresetColors.forEach { preset ->
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(parseMemberColor(preset, colors.ink2))
                        .border(
                            width = if (colorHex.equals(preset, ignoreCase = true)) 3.dp else 1.dp,
                            color = if (colorHex.equals(preset, ignoreCase = true)) colors.ink else colors.line,
                            shape = CircleShape,
                        )
                        .clickable { colorHex = preset },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = colorHex,
            onValueChange = { colorHex = it },
            label = { Text("識別色 (#RRGGBB)") },
            singleLine = true,
            isError = errors.colorHex != null,
            supportingText = errors.colorHex?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onClose, enabled = !busy) { Text("キャンセル") }
            Button(
                onClick = {
                    if (busy) return@Button
                    busy = true
                    message = null
                    scope.launch {
                        when (val result = onSave(editing?.id, RegistrationForm(name, colorHex, cardNumber, password))) {
                            MemberRegistrationResult.Saved -> {
                                errors = RegistrationErrors()
                                onClose()
                            }

                            is MemberRegistrationResult.Invalid -> {
                                errors = result.errors
                                message = "入力内容を確認してください"
                            }

                            MemberRegistrationResult.Failed -> {
                                message = "保存に失敗しました。時間をおいて再試行してください"
                            }
                        }
                        busy = false
                    }
                },
                enabled = !busy,
            ) {
                Text(if (busy) "保存中…" else "保存")
            }
        }

        message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = colors.ink2, fontSize = 12.sp)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "🔒 認証情報は端末内に暗号化保存されます。図書館の公式サイト以外へは送信されません。",
            color = colors.ink2,
            fontSize = 10.5.sp,
        )
    }
}

/** 時と分(5分刻み)の2つのドロップダウンで同期時刻を選ぶ。 */
@Composable
private fun SyncTimePicker(hour: Int, minute: Int, onUpdate: (Int, Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        SimpleDropdown(
            current = "%d時".format(hour),
            options = (0..23).map { "%d時".format(it) to it },
            onSelect = { newHour -> onUpdate(newHour, minute) },
        )
        SimpleDropdown(
            current = "%02d分".format(minute),
            options = ((0..55 step 5).toSet() + minute).sorted().map { "%02d分".format(it) to it },
            onSelect = { newMinute -> onUpdate(hour, newMinute) },
        )
    }
}

@Composable
private fun <T> SimpleDropdown(
    current: String,
    options: List<Pair<String, T>>,
    onSelect: (T) -> Unit,
) {
    val colors = LocalAppColors.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(colors.paper)
                .border(1.dp, colors.line, RoundedCornerShape(9.dp))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(current, color = colors.ink, fontSize = 12.5.sp)
            Text("▾", color = colors.ink2, fontSize = 11.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(label, fontSize = 13.sp) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}
