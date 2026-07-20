package com.fallgist.nishinomiyalibrary.ui.detail

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fallgist.nishinomiyalibrary.domain.model.Holding
import com.fallgist.nishinomiyalibrary.ui.components.EmptyNote
import com.fallgist.nishinomiyalibrary.ui.components.MemberDot
import com.fallgist.nishinomiyalibrary.ui.search.CoverImageLoaderHolder
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

/** tilcodを持つ全画面から開ける、共通の書誌詳細ビュー。戻る操作で閉じる。 */
@Composable
fun BookDetailView(
    detail: BookDetailUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    BackHandler(enabled = true, onBack = onBack)
    Column(modifier = modifier.fillMaxSize().background(colors.paper)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, colors.line, RoundedCornerShape(9.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text("←", color = colors.ink, fontSize = 17.sp)
            }
            Text("書誌詳細", color = colors.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        when {
            detail.loading -> EmptyNote("書誌詳細を読み込んでいます…")

            detail.errorMessage != null -> EmptyNote(detail.errorMessage)

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CoverImage(detail.coverUrl, detail.title)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = detail.title,
                            color = colors.ink,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        detail.lendable?.let { LendPill(it) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (detail.fields.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.card)
                            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        detail.fields.forEach { (label, value) ->
                            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = label,
                                    color = colors.ink2,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.width(88.dp),
                                )
                                Text(value, color = colors.ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                if (detail.readRows.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("既読情報", color = colors.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.card)
                            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        detail.readRows.forEach { row ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(vertical = 5.dp),
                            ) {
                                MemberDot(row.memberColorHex)
                                Text(row.memberName, color = colors.ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                Text(row.description, color = colors.ink2, fontSize = 12.5.sp)
                            }
                        }
                    }
                }

                if (detail.holdings.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("所蔵一覧", color = colors.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    HoldingsTable(detail.holdings)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun CoverImage(coverUrl: String?, title: String) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(width = 96.dp, height = 136.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.chipBg),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                imageLoader = CoverImageLoaderHolder.get(context),
                contentDescription = "$title の表紙",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("🖼", color = colors.ink2, fontSize = 30.sp)
        }
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

@Composable
private fun HoldingsTable(holdings: List<Holding>) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.chipBg)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            HoldingHeaderCell("館", 0.28f)
            HoldingHeaderCell("請求記号", 0.22f)
            HoldingHeaderCell("配架場所", 0.3f)
            HoldingHeaderCell("在庫状態", 0.2f)
        }
        holdings.forEachIndexed { index, holding ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HoldingCell(holding.library, 0.28f)
                HoldingCell(holding.callNumber, 0.22f)
                HoldingCell(holding.location, 0.3f)
                HoldingStatusCell(holding.status, 0.2f)
            }
            if (index != holdings.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.line),
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HoldingHeaderCell(text: String, weight: Float) {
    val colors = LocalAppColors.current
    Text(
        text = text,
        color = colors.ink2,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HoldingCell(text: String, weight: Float) {
    val colors = LocalAppColors.current
    Text(
        text = text,
        color = colors.ink,
        fontSize = 11.5.sp,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HoldingStatusCell(status: String, weight: Float) {
    val colors = LocalAppColors.current
    val positive = status.contains("在庫") || status == "貸出可"
    Text(
        text = status,
        color = if (positive) colors.greenInk else colors.alert,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.weight(weight),
    )
}
