package com.fallgist.nishinomiyalibrary.ui.newarrivals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.ui.components.EmptyNote
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors
import com.fallgist.nishinomiyalibrary.data.repository.NewArrivalUpdatePhase

@Composable
fun NewArrivalsScreen(
    state: NewArrivalsUiState,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenDetail: (tilcod: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    // IMEの変換合成が崩れるため、入力値は画面ローカルに保持しControllerへは通知のみ渡す
    var queryText by remember { mutableStateOf(state.query) }
    Column(modifier = modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(
            title = "新着資料",
            onOpenMenu = onOpenMenu,
            below = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when (state.updatePhase) {
                            NewArrivalUpdatePhase.FETCHING -> "新着資料を更新中"
                            NewArrivalUpdatePhase.AUTOMATIC_RESERVATION -> "自動予約を判定・実行中"
                            null -> "ジャンル横断でまとめた新着 ${state.totalCount}件"
                        },
                        color = colors.ink2,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = "↻ 更新",
                        color = colors.green,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(enabled = !state.refreshing, onClick = onRefresh)
                            .background(colors.greenBg)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            },
        )
        Text(
            text = NewArrivalsLastFetchedTextBuilder.build(state.lastFetchedAtEpochMillis),
            color = colors.ink2,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
        if (state.autoReservationEnabled) {
            Text(
                text = "自動予約がONです。更新後に自動予約を判定します",
                color = colors.greenInk,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 3.dp),
            )
        }
        OutlinedTextField(
            value = queryText,
            onValueChange = {
                queryText = it
                onQueryChange(it)
            },
            placeholder = { Text("書名・著者でしぼりこむ", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
        )
        when {
            !state.initialized -> EmptyNote("読み込んでいます…")

            state.rows.isEmpty() && state.query.isNotBlank() ->
                EmptyNote("「${state.query}」に一致する新着資料はありません")

            state.rows.isEmpty() && state.refreshFailed ->
                EmptyNote("新着資料を取得できませんでした。通信状況を確認して「更新」してください")

            state.rows.isEmpty() ->
                EmptyNote("新着資料はまだありません。「更新」で取得できます")

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
            ) {
                if (state.refreshFailed) {
                    item {
                        EmptyNote(
                            "最新の取得に失敗しました。表示は前回の取得内容です",
                            modifier = Modifier.padding(0.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
                items(state.rows) { row ->
                    NewArrivalRowView(row, onClick = { onOpenDetail(row.tilcod, row.title) })
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun NewArrivalRowView(row: NewArrivalRow, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .clickable(enabled = row.tilcod.isNotBlank(), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                color = colors.ink,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = row.subtitle,
                    color = colors.ink2,
                    fontSize = 11.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        row.lendable?.let { LendPill(it) }
    }
}

@Composable
private fun LendPill(lendable: Boolean) {
    val colors = LocalAppColors.current
    Text(
        text = if (lendable) "○貸出可" else "×貸出不可",
        color = if (lendable) colors.greenInk else colors.alert,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (lendable) colors.greenBg else colors.alertBg)
            .padding(horizontal = 9.dp, vertical = 2.dp),
    )
}
