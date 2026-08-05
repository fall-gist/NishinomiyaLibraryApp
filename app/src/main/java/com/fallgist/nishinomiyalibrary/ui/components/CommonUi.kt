package com.fallgist.nishinomiyalibrary.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.fallgist.nishinomiyalibrary.domain.model.Member
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
    Column(
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

/** [MemberDot]の既定直径。 */
private val MemberDotDefaultSize = 9.dp

/**
 * 一覧行で書誌名とメンバードットの間に空ける間隔(行レイアウト追い込み第3次・項目10、2026-08-06)。
 * 貸出中・予約中・読書記録の3画面共通。
 */
val MemberDotGap = 8.dp

/**
 * 書誌名より下の行(館名・返却期限・予約順位・取置期限)に与える`start` padding(同・項目11)。
 * ドット径([MemberDotDefaultSize])＋ドットと書誌名の間隔([MemberDotGap])。
 * [MemberDot]の既定サイズを変えた場合、3画面のインデントが自動で追随する。
 */
val MemberDotIndent = MemberDotDefaultSize + MemberDotGap

/** メンバー識別色のドット。 */
@Composable
fun MemberDot(colorHex: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = MemberDotDefaultSize) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(parseMemberColor(colorHex, colors.ink2)),
    )
}

/**
 * 横スクロールするメンバー絞り込みチップ行。[includeEveryone] が true なら先頭に「みんな」を出す。
 * 選択中は null=みんな。
 */
@Composable
fun MemberFilterRow(
    members: List<Member>,
    selectedMemberId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    includeEveryone: Boolean = true,
    countByMemberId: Map<Long, Int> = emptyMap(),
    totalCount: Int? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (includeEveryone) {
            MemberChip(
                label = totalCount?.let { "みんな $it" } ?: "みんな",
                colorHex = null,
                selected = selectedMemberId == null,
                onClick = { onSelect(null) },
            )
        }
        members.forEach { member ->
            MemberChip(
                label = countByMemberId[member.id]?.let { "${member.name} $it" } ?: member.name,
                colorHex = member.colorHex,
                selected = selectedMemberId == member.id,
                onClick = { onSelect(member.id) },
            )
        }
    }
}

@Composable
private fun MemberChip(label: String, colorHex: String?, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.green else colors.chipBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (colorHex != null) {
            MemberDot(colorHex)
        }
        Text(
            text = label,
            color = if (selected) Color.White else colors.ink2,
            fontSize = 13.sp,
        )
    }
}
