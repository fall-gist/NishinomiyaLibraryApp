package com.fallgist.nishinomiyalibrary.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fallgist.nishinomiyalibrary.domain.model.MaterialKind
import com.fallgist.nishinomiyalibrary.domain.model.PublishedRange
import com.fallgist.nishinomiyalibrary.domain.model.SearchQuery
import com.fallgist.nishinomiyalibrary.domain.model.StockFilter
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

/**
 * 詳細検索の入力欄(`docs/design/search-sort-filter.md` §3.4)。画面いっぱいのダイアログとして開き、
 * 縦にスクロールできる(狭い画面・ソフトキーボード表示中でも入力しやすいように)。
 *
 * [initial] は欄の初期値。「絞り込み」では今の検索条件を渡す。並べ替えの指定(`initial.sort`)は
 * 欄にはなく、検索するときそのまま引き継ぐ。条件の誤り([SearchQuery.validate])は検索ボタンを
 * 押したあと、欄の下に日本語で出し、検索しない。
 */
@Composable
fun DetailSearchDialog(
    initial: SearchQuery,
    onSearch: (SearchQuery) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    var keyword by remember { mutableStateOf(initial.keyword) }
    var title by remember { mutableStateOf(initial.title) }
    var author by remember { mutableStateOf(initial.author) }
    var publisher by remember { mutableStateOf(initial.publisher) }
    var classification by remember { mutableStateOf(initial.classification) }
    var fromYear by remember { mutableStateOf(initial.published.fromYear?.toString().orEmpty()) }
    var fromMonth by remember { mutableStateOf(initial.published.fromMonth?.toString().orEmpty()) }
    var toYear by remember { mutableStateOf(initial.published.toYear?.toString().orEmpty()) }
    var toMonth by remember { mutableStateOf(initial.published.toMonth?.toString().orEmpty()) }
    var kinds by remember { mutableStateOf(initial.materialKinds) }
    var stock by remember { mutableStateOf(initial.stock) }
    var attempted by remember { mutableStateOf(false) }

    fun build(): SearchQuery = initial.copy(
        keyword = keyword.trim(),
        title = title.trim(),
        author = author.trim(),
        publisher = publisher.trim(),
        classification = classification.trim(),
        published = PublishedRange(
            fromYear = SearchContentBuilder.parseNumberInput(fromYear),
            fromMonth = SearchContentBuilder.parseNumberInput(fromMonth),
            toYear = SearchContentBuilder.parseNumberInput(toYear),
            toMonth = SearchContentBuilder.parseNumberInput(toMonth),
        ),
        materialKinds = kinds,
        stock = stock,
    )

    // 検索ボタンを押したあとは、入力のたびに誤りを再計算して出す(直せば消える)。
    val problems = if (attempted) build().validate() else emptyList()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.paper)
                .statusBarsPadding()
                .imePadding(),
        ) {
            Text(
                text = "詳細検索",
                color = colors.ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField("キーワード(全項目)", keyword) { keyword = it }
                TextField("書名", title) { title = it }
                TextField("著者", author) { author = it }
                TextField("出版者", publisher) { publisher = it }
                TextField("分類", classification) { classification = it }

                SectionLabel("出版年月")
                YearMonthRow("から", fromYear, fromMonth, { fromYear = it }, { fromMonth = it })
                YearMonthRow("まで", toYear, toMonth, { toYear = it }, { toMonth = it })

                SectionLabel("書誌種別(複数選べます)")
                MaterialKind.entries.chunked(2).forEach { pair ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        pair.forEach { kind ->
                            KindCheck(
                                label = SearchContentBuilder.materialKindLabel(kind),
                                checked = kind in kinds,
                                onToggle = { kinds = if (kind in kinds) kinds - kind else kinds + kind },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                SectionLabel("在庫状況")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StockFilter.entries.forEach { filter ->
                        ChoiceChip(
                            label = SearchContentBuilder.stockFilterLabel(filter),
                            selected = stock == filter,
                            onClick = { stock = filter },
                        )
                    }
                }

                problems.forEach { problem ->
                    Text(
                        text = SearchContentBuilder.problemMessage(problem),
                        color = colors.alert,
                        fontSize = 12.5.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        attempted = true
                        val query = build()
                        if (query.validate().isEmpty()) onSearch(query)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("検索") }
                OutlinedButton(
                    onClick = {
                        keyword = ""
                        title = ""
                        author = ""
                        publisher = ""
                        classification = ""
                        fromYear = ""
                        fromMonth = ""
                        toYear = ""
                        toMonth = ""
                        kinds = emptySet()
                        stock = StockFilter.ALL
                        attempted = false
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("クリア") }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("戻る") }
            }
        }
    }
}

@Composable
private fun TextField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = LocalAppColors.current.ink2,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun YearMonthRow(
    label: String,
    year: String,
    month: String,
    onYearChange: (String) -> Unit,
    onMonthChange: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = colors.ink, fontSize = 13.sp, modifier = Modifier.padding(end = 2.dp))
        OutlinedTextField(
            value = year,
            onValueChange = onYearChange,
            label = { Text("年", fontSize = 11.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(2f),
        )
        OutlinedTextField(
            value = month,
            onValueChange = onMonthChange,
            label = { Text("月", fontSize = 11.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun KindCheck(label: String, checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(label, color = LocalAppColors.current.ink, fontSize = 13.sp)
    }
}

/** 選択中なら緑地に白文字のチップ(蔵書検索の並べ替え・在庫状況に共通)。 */
@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Text(
        text = label,
        color = if (selected) Color.White else colors.ink2,
        fontSize = 12.5.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.green else colors.chipBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
