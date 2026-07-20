package com.fallgist.nishinomiyalibrary.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors
import android.graphics.Color as AndroidColor

/** メンバー識別色の16進文字列を安全にComposeのColorへ変換する共通関数。 */
fun parseMemberColor(hex: String, fallback: Color): Color = runCatching {
    Color(AndroidColor.parseColor(hex))
}.getOrDefault(fallback)

/** 右上のハンバーガーボタン(☰)。全画面共通でメニュードロワーを開く。 */
@Composable
fun HamburgerButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .border(1.dp, colors.line, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "☰", color = colors.ink, fontSize = 17.sp)
    }
}

/**
 * 画面上部の共通バー: 左に画面名、右上に☰。
 * [below] を渡すと、タイトル行の下に任意の内容(ホームの同期行など)を差し込める。
 */
@Composable
fun ScreenTopBar(
    title: String,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
    below: (@Composable () -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, color = colors.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            HamburgerButton(onClick = onOpenMenu)
        }
        below?.invoke()
    }
}

/** セクションの小見出し(補助テキスト色・太字)。 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Text(
        text = title,
        color = colors.ink2,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 8.dp),
    )
}

/** データが無いときの控えめな案内文。 */
@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Text(
        text = text,
        color = colors.ink2,
        fontSize = 13.sp,
        modifier = modifier.padding(horizontal = 18.dp, vertical = 8.dp),
    )
}
