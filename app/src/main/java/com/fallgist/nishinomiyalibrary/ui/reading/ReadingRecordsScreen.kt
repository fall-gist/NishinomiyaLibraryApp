package com.fallgist.nishinomiyalibrary.ui.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.fallgist.nishinomiyalibrary.ui.components.MemberDotGap
import com.fallgist.nishinomiyalibrary.ui.components.MemberDotIndent
import com.fallgist.nishinomiyalibrary.ui.components.MemberFilterRow
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingRecordsScreen(
    state: ReadingRecordsUiState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onSelectMember: (Long?) -> Unit,
    onQueryChange: (String) -> Unit,
    onOpenMenu: () -> Unit,
    onOpenDetail: (tilcod: String, title: String) -> Unit,
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
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                // 空状態が3種類とも、スクロール可能なコンポーネントで包んでプルを受け取れるようにする。
                state.showActivationHint -> Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyNote(
                        "このメンバーの読書記録がまだありません。" +
                            "図書館サイト側で読書履歴が有効化されていない可能性があります。",
                    )
                }

                state.rows.isEmpty() && state.query.isNotBlank() ->
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        EmptyNote("「${state.query}」に一致する記録はありません")
                    }

                state.rows.isEmpty() ->
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        EmptyNote("読書記録はまだありません")
                    }

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp),
                ) {
                    items(state.rows) { row ->
                        ReadingRowView(row, onClick = { onOpenDetail(row.tilcod, row.title) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingRowView(row: ReadingRow, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    // レイアウト追い込み第3次(2026-08-06)項目10: ドットは書誌名と同じRow(CenterVertically)に置き、
    // 書誌名の縦中央で揃える。外側で「ドット｜Column」と横に並べる旧構造(ドットがColumn全体の
    // 縦中央に付いてしまう)をやめ、Column{ Row(ドット+書誌名) ; Row(下の行) }の形にする。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .clickable(enabled = row.tilcod.isNotBlank(), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MemberDotGap),
        ) {
            MemberDot(row.memberColorHex)
            Text(
                text = row.title,
                color = colors.ink,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        // 個別行にメンバー名は出さない(2026-08-05・ドットのみ)。絞り込み行での再掲を避ける。
        // 項目11: 書誌名より下の行にMemberDotIndentを与え、書誌名のインデントと揃える。
        Text(
            text = "${row.loanDateLabel} · ${row.library}",
            color = colors.ink2,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = MemberDotIndent),
        )
    }
}
