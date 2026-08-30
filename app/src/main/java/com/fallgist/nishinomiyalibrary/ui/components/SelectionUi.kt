package com.fallgist.nishinomiyalibrary.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

/**
 * 一覧行の選択用チェックボックス(`docs/design/bulk-selection.md` §4.1)。
 * 予約中一覧のチェックボックス([com.fallgist.nishinomiyalibrary.ui.reservations.ReservationsScreen]
 * が元々持っていたもの)をそのまま一般化しただけで、見た目・挙動は変えていない。
 *
 * 行タップ領域(書誌詳細を開く等)とは別のコンテナに置くこと。配置(どのRow/Columnに乗せるか)は
 * 各画面側の責務とする(このコンポーザブルはチェックボックス単体だけを描画する)。
 */
@Composable
fun SelectionCheckbox(
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    checkedColor: Color,
    modifier: Modifier = Modifier,
) {
    Checkbox(
        checked = checked,
        onCheckedChange = { onToggle() },
        enabled = enabled,
        colors = CheckboxDefaults.colors(checkedColor = checkedColor),
        modifier = modifier,
    )
}

/**
 * 一覧最上部に置く、選択件数と一斉操作ボタンのバー(`docs/design/bulk-selection.md` §4.1)。
 * 予約中一覧の`BulkCancelBar`(旧`ReservationsScreen.kt`のprivate関数)をそのまま一般化したもの。
 *
 * ボタン文言は選択0件のとき[actionLabel]のみ、1件以上のとき「[actionLabel]（N件）」になる
 * (旧`BulkCancelBar`の「一斉取消」「一斉取消（N件）」と同じ規則)。件数は主ボタンにだけ付ける。
 * [containerColor]・[contentColor]は機能ごとに異なる強調色を使うための調整で、既定値は
 * 旧`BulkCancelBar`と同じ`colors.alert`/`colors.card`にしてあり、呼び出し側で何も指定しなければ
 * 予約中一覧は移行前と見た目が変わらない。
 *
 * [secondaryActionLabel]を指定すると、同じ行に副アクション(`OutlinedButton`)を並べる
 * (`docs/design/bulk-selection-followup.md` §5.3、検索・新着の「カートへ追加」と「予約する」)。
 * 指定しない(既定値のまま)場合は主ボタンだけの現行の見た目から1ピクセルも変わらない。
 * 副ボタンには件数を付けない。
 */
@Composable
fun BulkActionBar(
    selectedCount: Int,
    actionLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = LocalAppColors.current.alert,
    contentColor: Color = LocalAppColors.current.card,
    secondaryActionLabel: String? = null,
    secondaryEnabled: Boolean = false,
    onSecondaryClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.End),
    ) {
        if (secondaryActionLabel != null && onSecondaryClick != null) {
            OutlinedButton(
                onClick = onSecondaryClick,
                enabled = secondaryEnabled,
            ) {
                Text(secondaryActionLabel)
            }
        }
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
        ) {
            Text(if (selectedCount > 0) "$actionLabel（${selectedCount}件）" else actionLabel)
        }
    }
}

/**
 * 選択が残ったまま不可逆な一覧の入れ替えを行う前の確認ダイアログ
 * (`docs/design/bulk-selection-followup.md` §6.2)。蔵書検索の再検索・新着資料の巡回(「更新」)で使う。
 *
 * [operationLabel] は「検索」「更新」など、これから行う操作の名前。文言に差し込む。
 * [onDisableWarning] は「今後は表示しない」。設定をオフにしたうえで[onConfirm]と同じ続行を行う
 * (呼び出し側の責務。ここではクリックの通知だけを行う)。
 */
@Composable
fun ClearSelectionWarningDialog(
    operationLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onDisableWarning: () -> Unit,
) {
    val colors = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("選択を解除しますか？") },
        text = {
            Column {
                Text("チェックした資料はまだ予約されていません。${operationLabel}を行うと選択は解除されます。よろしいですか？")
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "今後は表示しない",
                    color = colors.green,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onDisableWarning),
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("続ける") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("戻る") } },
    )
}
