package com.fallgist.nishinomiyalibrary.ui.reservations

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.ui.components.EmptyNote
import com.fallgist.nishinomiyalibrary.ui.components.MemberDot
import com.fallgist.nishinomiyalibrary.ui.components.MemberFilterRow
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

@Composable
fun ReservationsScreen(
    state: ReservationsUiState,
    onSelectMember: (Long?) -> Unit,
    onOpenMenu: () -> Unit,
    onOpenDetail: (tilcod: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Column(modifier = modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "予約中", onOpenMenu = onOpenMenu)
        MemberFilterRow(
            members = state.members,
            selectedMemberId = state.selectedMemberId,
            onSelect = onSelectMember,
            countByMemberId = state.countByMemberId,
            totalCount = state.totalCount,
        )
        if (state.rows.isEmpty()) {
            EmptyNote("予約中の本はありません")
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
            ) {
                items(state.rows) { row ->
                    ReservationRowView(row, onClick = { onOpenDetail(row.tilcod, row.title) })
                }
            }
        }
    }
}

@Composable
private fun ReservationRowView(row: ReservationRow, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (row.isReady) colors.greenBg else colors.card)
            .border(
                1.dp,
                if (row.isReady) colors.green.copy(alpha = 0.3f) else colors.line,
                RoundedCornerShape(14.dp),
            )
            .clickable(enabled = row.tilcod.isNotBlank(), onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = row.statusLabel,
                color = if (row.isReady) colors.greenInk else colors.ink2,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MemberDot(row.memberColorHex)
                Text(text = row.memberName, color = colors.ink2, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = row.title,
            color = colors.ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "受取館 ${row.pickupLabel}", color = colors.ink2, fontSize = 11.sp)
            row.queueLabel?.let { Text(text = it, color = colors.ink2, fontSize = 11.sp) }
        }
        row.holdExpiryLabel?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                text = it,
                color = if (row.isReady) colors.alert else colors.ink2,
                fontSize = 11.sp,
                fontWeight = if (row.isReady) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
