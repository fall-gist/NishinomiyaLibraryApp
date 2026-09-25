package com.fallgist.nishinomiyalibrary.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * 通常時の「長押しで複数選択」の案内、および選択モード中の「選択解除」を描く小さな帯
 * (`docs/design/selection-mode.md` §3.4、2026-09-20 実機確認を受けて改訂)。
 * 両者は同じ見た目(11sp・ink2・chipBg の角丸チップ)で揃える。[onClick]を渡すと押せるようになり、
 * 「選択解除」はこれで [onClick] に選択モードを抜ける処理を渡す。案内文言だけのときは渡さない。
 */
@Composable
fun SelectionHintChip(
    text: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Text(
        text = text,
        color = colors.ink2,
        fontSize = 11.sp,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.chipBg)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * 一斉操作バーの「⋯」メニューに並べる1項目(`docs/design/bulk-bookshelf-add.md` §5.1)。
 * [label]はメニュー項目の文言、[enabled]は処理中等での無効化、[onClick]はタップ時の通知。
 * 件数は付けない(主ボタンにだけ付ける、既存の副ボタンと同じ規則)。
 */
data class BulkOverflowAction(val label: String, val enabled: Boolean, val onClick: () -> Unit)

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
 * [overflowActions]を指定すると、主ボタンの右に「⋯」(`IconButton`)を出し、タップでメニューを開く
 * (`docs/design/bulk-bookshelf-add.md` §5.1、検索・新着の「カートへ追加」「本棚へ追加」)。
 * **空リスト(既定値)のときは「⋯」自体を描画せず、主ボタンだけの現行の見た目から1ピクセルも変わらない**
 * (予約中の一斉取消・予約カートの一括削除・貸出中の一斉延長はこれを指定しない)。
 *
 * [leadingContent]を指定すると、ボタン列の左に並べて描く(2026-09-20 実機確認を受けて追加。
 * `docs/design/selection-mode.md` §3.4、選択モード中の「選択解除」をボタンと同じ行の左端に置くため)。
 * **既定値(空)のときは左側に何も描かず、Rowの見た目は変更前と1ピクセルも変わらない**
 * (SpaceBetweenでも空のBoxは幅0のため、ボタン列は変更前どおり右端に寄る)。
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
    overflowActions: List<BulkOverflowAction> = emptyList(),
    leadingContent: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box { leadingContent() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.End)) {
            Button(
                onClick = onClick,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
            ) {
                Text(if (selectedCount > 0) "$actionLabel（${selectedCount}件）" else actionLabel)
            }
            if (overflowActions.isNotEmpty()) {
                var overflowExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Text("⋯", fontSize = 20.sp, color = LocalAppColors.current.ink2)
                    }
                    DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                        overflowActions.forEach { action ->
                            DropdownMenuItem(
                                text = { Text(action.label) },
                                enabled = action.enabled,
                                onClick = {
                                    overflowExpanded = false
                                    action.onClick()
                                },
                            )
                        }
                    }
                }
            }
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
