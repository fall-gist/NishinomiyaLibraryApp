package com.fallgist.nishinomiyalibrary.ui.diagnostics

import android.content.Intent
import java.io.File
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.fallgist.nishinomiyalibrary.ui.components.EmptyNote
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors
import kotlinx.coroutines.delay

/**
 * 通信診断ログの閲覧画面。検索・カテゴリ絞り込み・行タップコピー・表示中一括コピー・
 * ファイル共有・消去を提供する。戻る操作で呼び出し元(設定画面)へ戻る。
 */
@Composable
fun DiagnosticLogScreen(
    state: DiagnosticLogUiState,
    onQueryChange: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onCopyVisible: () -> String,
    onClear: () -> Unit,
    onExportFile: () -> File?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var copiedFeedback by remember { mutableStateOf(false) }
    var shareFailedFeedback by remember { mutableStateOf(false) }
    // TextFieldの値をController経由の非同期StateFlow往復にするとIMEの変換合成が崩れるため、
    // 入力値は画面ローカルに保持し、Controllerへは通知のみ行う
    var queryText by remember { mutableStateOf(state.query) }

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
            Text("診断ログ", color = colors.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        OutlinedTextField(
            value = queryText,
            onValueChange = {
                queryText = it
                onQueryChange(it)
            },
            label = { Text("検索(メッセージ・カテゴリ)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
        )
        Spacer(Modifier.height(8.dp))

        if (state.availableCategories.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 18.dp),
            ) {
                items(state.availableCategories) { category ->
                    CategoryChip(
                        label = category,
                        selected = category in state.selectedCategories,
                        onClick = { onToggleCategory(category) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${state.rows.size}/${state.totalCount}件 保存量 ${state.persistedBytesLabel}",
                color = colors.ink2,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(onCopyVisible()))
                    copiedFeedback = true
                },
            ) { Text("表示中をコピー") }
            TextButton(
                onClick = {
                    val file = onExportFile()
                    if (file == null) {
                        shareFailedFeedback = true
                    } else {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "診断ログを共有"))
                    }
                },
            ) { Text("ファイルを共有") }
            TextButton(onClick = onClear) { Text("消去") }
        }
        if (copiedFeedback) {
            LaunchedEffect(Unit) {
                delay(2000)
                copiedFeedback = false
            }
            Text("コピーしました", color = colors.green, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp))
        }
        if (shareFailedFeedback) {
            LaunchedEffect(Unit) {
                delay(2000)
                shareFailedFeedback = false
            }
            Text("共有できるファイルがありません", color = colors.alert, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp))
        }
        Spacer(Modifier.height(4.dp))

        if (state.rows.isEmpty()) {
            EmptyNote(if (state.totalCount == 0) "記録がありません" else "条件に一致する記録がありません")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.rows) { row ->
                    DiagnosticLogRowView(
                        row = row,
                        onTap = { clipboardManager.setText(AnnotatedString(row.copyText)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.green else colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) colors.paper else colors.ink,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DiagnosticLogRowView(row: DiagnosticLogRow, onTap: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 18.dp, vertical = 6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(row.timeLabel, color = colors.ink2, fontSize = 10.5.sp)
            Text(
                text = row.category,
                color = colors.ink,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.chipBg)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Text(
            text = row.message,
            color = colors.ink,
            fontSize = 12.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
