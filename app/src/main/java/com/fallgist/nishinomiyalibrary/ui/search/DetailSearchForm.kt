package com.fallgist.nishinomiyalibrary.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fallgist.nishinomiyalibrary.domain.model.MaterialKind
import com.fallgist.nishinomiyalibrary.domain.model.PublishedRange
import com.fallgist.nishinomiyalibrary.domain.model.SearchQuery
import com.fallgist.nishinomiyalibrary.domain.model.StockFilter
import com.fallgist.nishinomiyalibrary.ui.theme.LocalAppColors

/** [DetailSearchForm]のComposeテスト用タグ。 */
object DetailSearchFormTestTags {
    const val HEADER = "detail_search_header"
    const val SUBMIT = "detail_search_submit"
    const val CLEAR = "detail_search_clear"
    const val BACK = "detail_search_back"
    const val FIRST_FIELD = "detail_search_first_field"
}

/**
 * 詳細検索の入力欄(`docs/design/search-sort-filter.md` §3.4)。蔵書検索画面の中の全面表示
 * (別Windowの Dialog ではない。Dialogだと実機で下端が画面外にはみ出したため)。
 *
 * 操作ボタン(戻る・クリア・検索)は画面上部の見出しの行に置く。キーボードが出ていても、
 * 小さい画面でも、常に押せるようにするため。検証エラーは見出しの直下に出す。
 * 各入力欄のキーボードの実行キー(ImeAction.Search)でも検索する。
 *
 * [initial] は欄の初期値。「絞り込み」では今の検索条件を渡す。並べ替えの指定(`initial.sort`)は
 * 欄にはなく、検索するときそのまま引き継ぐ。条件の誤り([SearchQuery.validate])は検索を
 * 試みたあとに日本語で出し、検索しない。
 */
@Composable
fun DetailSearchForm(
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

    fun submit() {
        attempted = true
        val query = build()
        if (query.validate().isEmpty()) onSearch(query)
    }

    fun clear() {
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
    }

    // 検索を試みたあとは、入力のたびに誤りを再計算して出す(直せば消える)。
    val problems = if (attempted) build().validate() else emptyList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper)
            // 下にある蔵書検索画面へタップが抜けないようにする。
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag(DetailSearchFormTestTags.HEADER),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(DetailSearchFormTestTags.BACK)) { Text("戻る") }
            Text(
                text = "詳細検索",
                color = colors.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            TextButton(onClick = ::clear, modifier = Modifier.testTag(DetailSearchFormTestTags.CLEAR)) { Text("クリア") }
            Button(onClick = ::submit, modifier = Modifier.testTag(DetailSearchFormTestTags.SUBMIT)) { Text("検索") }
        }
        // 検証エラーは見出しの直下(スクロール欄の外)に出す。
        problems.forEach { problem ->
            Text(
                text = SearchContentBuilder.problemMessage(problem),
                color = colors.alert,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val onSearchKey = ::submit
            TextField("キーワード(全項目)", keyword, onSearchKey, Modifier.testTag(DetailSearchFormTestTags.FIRST_FIELD)) { keyword = it }
            TextField("書名", title, onSearchKey) { title = it }
            TextField("著者", author, onSearchKey) { author = it }
            TextField("出版者", publisher, onSearchKey) { publisher = it }
            TextField("分類", classification, onSearchKey) { classification = it }

            SectionLabel("出版年月")
            YearMonthRow("から", fromYear, fromMonth, onSearchKey, { fromYear = it }, { fromMonth = it })
            YearMonthRow("まで", toYear, toMonth, onSearchKey, { toYear = it }, { toMonth = it })

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
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TextField(
    label: String,
    value: String,
    onSearchKey: () -> Unit,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearchKey() }),
        modifier = modifier.fillMaxWidth(),
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
    onSearchKey: () -> Unit,
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchKey() }),
            modifier = Modifier.weight(2f),
        )
        OutlinedTextField(
            value = month,
            onValueChange = onMonthChange,
            label = { Text("月", fontSize = 11.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchKey() }),
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
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.green else colors.chipBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
