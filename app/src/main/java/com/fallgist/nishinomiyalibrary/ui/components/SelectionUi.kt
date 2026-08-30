package com.fallgist.nishinomiyalibrary.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * (旧`BulkCancelBar`の「一斉取消」「一斉取消（N件）」と同じ規則)。
 * [containerColor]・[contentColor]は機能ごとに異なる強調色を使うための調整で、既定値は
 * 旧`BulkCancelBar`と同じ`colors.alert`/`colors.card`にしてあり、呼び出し側で何も指定しなければ
 * 予約中一覧は移行前と見た目が変わらない。
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
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
        ) {
            Text(if (selectedCount > 0) "$actionLabel（${selectedCount}件）" else actionLabel)
        }
    }
}
