package com.fallgist.nishinomiyalibrary.ui.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.ui.components.EmptyNote
import com.fallgist.nishinomiyalibrary.ui.components.MemberDot
import com.fallgist.nishinomiyalibrary.ui.components.MemberFilterRow
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

@Composable
fun ReadingRecordsScreen(
    state: ReadingRecordsUiState,
    onSelectMember: (Long?) -> Unit,
    onQueryChange: (String) -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    // TextFieldの値をController経由の非同期StateFlow往復にするとIMEの変換合成が崩れるため、
    // 入力値は画面ローカルに保持し、Controllerへは通知のみ行う
    var queryText by remember { mutableStateOf(state.query) }
    Column(modifier = modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "読書記録", onOpenMenu = onOpenMenu)
        OutlinedTextField(
            value = queryText,
            onValueChange = {
                queryText = it
                onQueryChange(it)
            },
            placeholder = { Text("書名・著者でさがす", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
        )
        MemberFilterRow(
            members = state.members,
            selectedMemberId = state.selectedMemberId,
            onSelect = onSelectMember,
        )
        when {
            state.showActivationHint -> EmptyNote(
                "このメンバーの読書記録がまだありません。" +
                    "図書館サイト側で読書履歴が有効化されていない可能性があります。",
            )

            state.rows.isEmpty() && state.query.isNotBlank() ->
                EmptyNote("「${state.query}」に一致する記録はありません")

            state.rows.isEmpty() -> EmptyNote("読書記録はまだありません")

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
            ) {
                items(state.rows) { row -> ReadingRowView(row) }
            }
        }
    }
}

@Composable
private fun ReadingRowView(row: ReadingRow) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MemberDot(row.memberColorHex)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                color = colors.ink,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${row.loanDateLabel} · ${row.library} · ${row.memberName}",
                color = colors.ink2,
                fontSize = 11.sp,
            )
        }
    }
}
