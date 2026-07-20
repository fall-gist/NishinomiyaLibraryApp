package com.fallgist.nishinomiyalibrary.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.ui.components.EmptyNote
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

@Composable
fun CalendarScreen(
    state: CalendarUiState,
    onSelectLibrary: (String) -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Column(modifier = modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "開館カレンダー", onOpenMenu = onOpenMenu)
        LibraryPicker(state = state, onSelectLibrary = onSelectLibrary)
        Text(
            text = "一度に表示できるのは一館のみです。初期表示は設定の既定館です。",
            color = colors.ink2,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )
        when {
            !state.initialized -> EmptyNote("読み込んでいます…")

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
            ) {
                if (state.noClosedDayData) {
                    item {
                        EmptyNote(
                            if (state.refreshFailed) {
                                "休館日データを取得できませんでした。通信状況を確認して開き直してください"
                            } else {
                                "この館の休館日データはまだありません。取得中です…"
                            },
                            modifier = Modifier.padding(0.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                items(state.months) { month -> MonthView(month) }
                item { Legend() }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun LibraryPicker(state: CalendarUiState, onSelectLibrary: (String) -> Unit) {
    val colors = LocalAppColors.current
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(horizontal = 18.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.card)
                .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.selectedLibraryName.ifEmpty { "館を選択" },
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text("▾", color = colors.ink2, fontSize = 14.sp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            state.libraries.forEach { library ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = library.name,
                            fontSize = 13.sp,
                            fontWeight = if (library.code == state.selectedLibraryCode) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelectLibrary(library.code)
                    },
                )
            }
        }
    }
}

@Composable
private fun MonthView(month: CalendarMonthUi) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)) {
        Text(
            text = month.label,
            color = colors.ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("日", "月", "火", "水", "木", "金", "土").forEachIndexed { index, label ->
                Text(
                    text = label,
                    color = when (index) {
                        0 -> colors.alert
                        6 -> colors.green
                        else -> colors.ink2
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        month.weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { cell -> DayCell(cell, modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DayCell(cell: CalendarDayCell, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier
            .aspectRatio(1.1f)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (cell.dayOfMonth != null) {
            val background = if (cell.isClosed) colors.alertBg else Color.Transparent
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(background)
                    .then(
                        if (cell.isToday) {
                            Modifier.border(2.dp, colors.green, RoundedCornerShape(8.dp))
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = cell.dayOfMonth.toString(),
                    color = when {
                        cell.isClosed -> colors.alert
                        cell.isSunday -> colors.alert
                        cell.isSaturday -> colors.green
                        else -> colors.ink
                    },
                    fontSize = 12.sp,
                    fontWeight = if (cell.isToday || cell.isClosed) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun Legend() {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.alertBg)
                .border(1.dp, colors.alert, RoundedCornerShape(4.dp)),
        )
        Text("休館", color = colors.ink2, fontSize = 11.sp)
        Spacer(Modifier.size(10.dp))
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(2.dp, colors.green, RoundedCornerShape(4.dp)),
        )
        Text("きょう", color = colors.ink2, fontSize = 11.sp)
    }
}
