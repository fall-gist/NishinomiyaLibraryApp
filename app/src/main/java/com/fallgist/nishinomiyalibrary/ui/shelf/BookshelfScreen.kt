package com.fallgist.nishinomiyalibrary.ui.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
fun BookshelfScreen(
    state: BookshelfUiState,
    onSelectMember: (Long?) -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Column(modifier = modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "本棚", onOpenMenu = onOpenMenu)
        MemberFilterRow(
            members = state.members,
            selectedMemberId = state.selectedMemberId,
            onSelect = onSelectMember,
        )
        if (state.columns.isEmpty()) {
            EmptyNote("マイ本棚の登録はありません")
        } else {
            // 全員分の本棚を横並びで一覧できるようにする(確定仕様)。
            LazyRow(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.columns) { column -> ShelfColumnView(column) }
            }
        }
    }
}

@Composable
private fun ShelfColumnView(column: ShelfColumn) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(14.dp)),
    ) {
        // 本棚タイトル: 頭にメンバー識別色。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            MemberDot(column.memberColorHex, size = 11.dp)
            Text(
                text = column.shelfName,
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(text = column.memberName, color = colors.ink2, fontSize = 11.sp)
        }
        HorizontalDivider(color = colors.line)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
        ) {
            items(column.books) { book -> ShelfBookView(book) }
        }
    }
}

@Composable
private fun ShelfBookView(book: ShelfBook) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.paper)
            .border(1.dp, colors.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = book.title,
            color = colors.ink,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (book.memo.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(text = book.memo, color = colors.ink2, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(2.dp))
        Text(text = "登録 ${book.registeredDateLabel}", color = colors.ink2, fontSize = 10.sp)
    }
}
