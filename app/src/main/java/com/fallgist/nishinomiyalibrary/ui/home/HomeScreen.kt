package com.fallgist.nishinomiyalibrary.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fallgist.nishinomiyalibrary.ui.components.ScreenTopBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.ui.components.HamburgerButton
import com.fallgist.nishinomiyalibrary.ui.member.MemberRegistrationResult
import com.fallgist.nishinomiyalibrary.ui.member.RegistrationForm
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors
import android.graphics.Color as AndroidColor
import com.fallgist.nishinomiyalibrary.ui.autoreservation.AutoReservationRunView

/** メンバー識別色の16進文字列を安全にComposeのColorへ変換する。 */
private fun parseMemberColor(hex: String, fallback: Color): Color = runCatching {
    Color(AndroidColor.parseColor(hex))
}.getOrDefault(fallback)

@Composable
fun HomeScreen(
    state: HomeUiState,
    isRefreshing: Boolean,
    onSelectMember: (Long?) -> Unit,
    onManualSync: () -> Unit,
    onRefresh: () -> Unit,
    onRegister: suspend (RegistrationForm) -> MemberRegistrationResult,
    onOpenMenu: () -> Unit,
    onOpenDetail: (tilcod: String, title: String) -> Unit,
    onAcknowledgeAutoReservation: (Long) -> Unit,
    onOpenReservations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    when {
        // 初回ロード前のちらつきを避ける。
        !state.initialized -> Box(modifier.background(colors.paper))
        // 認証済みメンバーが1人もいなければ、その場で完結する登録フォームだけを出す。
        state.members.isEmpty() -> MemberRegistrationForm(onRegister = onRegister, modifier = modifier)
        else -> HomeContent(state, isRefreshing, onSelectMember, onManualSync, onRefresh, onOpenMenu, onOpenDetail, onAcknowledgeAutoReservation, onOpenReservations, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    state: HomeUiState,
    isRefreshing: Boolean,
    onSelectMember: (Long?) -> Unit,
    onManualSync: () -> Unit,
    onRefresh: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenDetail: (tilcod: String, title: String) -> Unit,
    onAcknowledgeAutoReservation: (Long) -> Unit,
    onOpenReservations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    var historyOpen by remember { mutableStateOf(false) }
    Column(modifier = modifier.background(colors.paper)) {
        // AppBarとメンバー絞り込みはプル対象の外に固定する。一緒に動くと落ち着かない見え方になるため。
        AppBar(
            lastSyncText = state.lastSyncText,
            lastSyncFailed = state.lastSyncFailed,
            isSyncing = isRefreshing,
            onManualSync = onManualSync,
            onOpenMenu = onOpenMenu,
        )
        MemberFilter(
            members = state.members,
            selectedMemberId = state.selectedMemberId,
            onSelectMember = onSelectMember,
        )
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
            ) {
                AutoReservationSummary(state.latestAutoReservationRun, onOpenHistory = { historyOpen = true })
                if (state.readyGroups.isNotEmpty()) {
                    SectionHeader("うけとれる予約")
                    Column(modifier = Modifier.padding(horizontal = 18.dp)) {
                        state.readyGroups.forEach { ReadyGroupView(it, onOpenDetail) }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                SectionHeader("返す本")
                if (state.dueGroups.isEmpty()) {
                    EmptyNote("借りている本はありません")
                } else {
                    Column(modifier = Modifier.padding(horizontal = 18.dp)) {
                        state.dueGroups.forEach { DueGroupView(it, onOpenDetail) }
                    }
                }
            }
        }
    }
    if (state.showAutoReservationDialog) {
        val run = state.latestAutoReservationRun
        AutoReservationHistoryDialog(
            run = run,
            onDismiss = { run?.runId?.let(onAcknowledgeAutoReservation) },
            onOpenReservations = {
                run?.runId?.let(onAcknowledgeAutoReservation)
                onOpenReservations()
            },
            onOpenHistory = {
                run?.runId?.let(onAcknowledgeAutoReservation)
                historyOpen = true
            },
        )
    }
    if (historyOpen) {
        AutoReservationHistoryOverlay(
            run = state.latestAutoReservationRun,
            onDismiss = { historyOpen = false },
            onOpenReservations = { historyOpen = false; onOpenReservations() },
        )
    }
}

@Composable
private fun AutoReservationSummary(run: AutoReservationRunView?, onOpenHistory: () -> Unit) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
        SectionHeader("新着資料の自動予約")
        if (run == null) {
            Text("まだ履歴はありません", color = colors.ink2, fontSize = 11.sp)
            Text("最新履歴を見る", color = colors.ink2, fontSize = 12.sp, modifier = Modifier.padding(vertical = 5.dp))
        } else {
            Text("最終試行: ${run.completedAtText}", color = colors.ink2, fontSize = 11.sp)
            Text(run.summaryText, color = colors.ink, fontSize = 12.sp)
            Text("最新履歴を見る", color = colors.green, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onOpenHistory).padding(vertical = 5.dp))
        }
    }
}

@Composable
private fun AutoReservationHistoryDialog(
    run: AutoReservationRunView?,
    onDismiss: () -> Unit,
    onOpenReservations: () -> Unit,
    onOpenHistory: () -> Unit,
    historyOnly: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (historyOnly) "最新の自動予約履歴" else "自動予約の結果") },
        text = {
            Column(modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
                if (run == null) Text("履歴はありません") else {
                    Text("${run.completedAtText}\n${run.summaryText}")
                    run.items.forEach { item ->
                        Spacer(Modifier.height(8.dp))
                        Text(item.title, fontWeight = FontWeight.SemiBold)
                        Text(item.outcomeText)
                        Text(item.matchedTermsText, fontSize = 11.sp)
                        Text(item.attemptsText, fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onOpenReservations) { Text("予約一覧を見る") } },
        dismissButton = {
            Row {
                if (!historyOnly) TextButton(onClick = onOpenHistory) { Text("最新履歴を見る") }
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
        },
    )
}

@Composable
private fun AutoReservationHistoryOverlay(run: AutoReservationRunView?, onDismiss: () -> Unit, onOpenReservations: () -> Unit) {
    val colors = LocalAppColors.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
            ScreenTopBar(title = "最新の自動予約履歴", onOpenMenu = onDismiss)
            TextButton(
                onClick = onOpenReservations,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            ) {
                Text("予約一覧を見る")
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
            ) {
                if (run == null) Text("まだ履歴はありません") else {
                    Text("${run.completedAtText}\n${run.summaryText}")
                    run.items.forEach { item ->
                        Spacer(Modifier.height(12.dp)); Text(item.title, fontWeight = FontWeight.SemiBold)
                        Text(item.outcomeText); Text(item.matchedTermsText, fontSize = 11.sp); Text(item.attemptsText, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppBar(
    lastSyncText: String,
    lastSyncFailed: Boolean,
    isSyncing: Boolean,
    onManualSync: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "西宮市立図書館",
                color = colors.ink,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            HamburgerButton(onClick = onOpenMenu)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = lastSyncText,
                color = if (lastSyncFailed) colors.alert else colors.ink2,
                fontSize = 11.sp,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, colors.line, RoundedCornerShape(999.dp))
                    .clickable(enabled = !isSyncing, onClick = onManualSync)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    text = if (isSyncing) "同期中…" else "↻ いますぐ同期",
                    color = if (isSyncing) colors.ink2 else colors.ink,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun MemberFilter(
    members: List<Member>,
    selectedMemberId: Long?,
    onSelectMember: (Long?) -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MemberChip(
            label = "みんな",
            dotColor = null,
            selected = selectedMemberId == null,
            onClick = { onSelectMember(null) },
        )
        members.forEach { member ->
            MemberChip(
                label = member.name,
                dotColor = parseMemberColor(member.colorHex, colors.ink2),
                selected = selectedMemberId == member.id,
                onClick = { onSelectMember(member.id) },
            )
        }
    }
}

@Composable
private fun MemberChip(
    label: String,
    dotColor: Color?,
    selected: Boolean,
    onClick: () -> Unit,
) {
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
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        Text(
            text = label,
            color = if (selected) Color.White else colors.ink2,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    val colors = LocalAppColors.current
    Text(
        text = title,
        color = colors.ink2,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun EmptyNote(text: String) {
    val colors = LocalAppColors.current
    Text(
        text = text,
        color = colors.ink2,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
    )
}

@Composable
private fun ReadyGroupView(
    group: ReadyGroup,
    onOpenDetail: (tilcod: String, title: String) -> Unit,
) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Row(
            modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (group.undated) group.headerLabel else "◗ ${group.headerLabel}",
                color = if (group.undated) colors.ink2 else colors.greenInk,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(text = "${group.items.size}冊", color = colors.ink2, fontSize = 12.sp)
        }
        group.items.forEach { item ->
            ReadyRow(item, onClick = { onOpenDetail(item.tilcod, item.title) })
        }
    }
}

@Composable
private fun ReadyRow(item: ReadyItem, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.greenBg)
            .border(1.dp, colors.green.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .clickable(enabled = item.tilcod.isNotBlank(), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(parseMemberColor(item.memberColorHex, colors.ink2)),
        )
        Text(
            text = item.title,
            color = colors.ink,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(text = item.memberName, color = colors.ink2, fontSize = 11.sp)
    }
}

@Composable
private fun DueGroupView(
    group: DueGroup,
    onOpenDetail: (tilcod: String, title: String) -> Unit,
) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Row(
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (group.overdue) "⚠ ${group.headerLabel}" else group.headerLabel,
                color = if (group.overdue) colors.alert else colors.ink,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(text = "${group.count}冊", color = colors.ink2, fontSize = 12.sp)
        }
        if (group.expanded) {
            group.books.forEach { book ->
                BookRow(book, onClick = { onOpenDetail(book.tilcod, book.title) })
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.card)
                    .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(text = "▸ ${group.foldedSummary}", color = colors.ink2, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun BookRow(book: HomeBook, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (book.overdue) colors.alertBg else colors.card)
            .border(
                1.dp,
                if (book.overdue) colors.alert.copy(alpha = 0.45f) else colors.line,
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = book.tilcod.isNotBlank(), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MemberTag(book.memberName, parseMemberColor(book.memberColorHex, colors.ink2), minWidth = 44.dp)
        Text(
            text = book.title,
            color = colors.ink,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(text = book.library, color = colors.ink2, fontSize = 11.sp)
    }
}

@Composable
private fun MemberTag(name: String, dotColor: Color, minWidth: androidx.compose.ui.unit.Dp = 0.dp) {
    val colors = LocalAppColors.current
    Row(
        modifier = if (minWidth > 0.dp) Modifier.width(minWidth) else Modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(text = name, color = colors.ink2, fontSize = 11.sp)
    }
}
