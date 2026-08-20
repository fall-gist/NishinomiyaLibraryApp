# 開館カレンダーへの返却期限表示 設計書

作成日: 2026-08-20
状態: 所有者承認済み（表現・遷移方式）・実装未着手
対応する構想: `docs/handoff.md`「次期機能の引き継ぎ事項（2026-07-22追記）> カレンダー: 返却期限日の特別表示」
モック: `docs/mockups/calendar-due-dates.html`
既存のカレンダー設計: `docs/ui-design.md`「開館カレンダー」（案B＝3ヶ月縦スクロール）

## 1. 目的と成果物

**目的**: 返却期限に到達する貸出がある日を、開館カレンダー上で通常日と区別できるようにする。

**成果物**:

1. 開館カレンダーの各マス下端に、その日が返却期限となる貸出の**メンバー色ドット**が並ぶ。
2. ドットのあるマスをタップすると**貸出中画面へ移動し、その日の行までスクロールして枠を強調**する。

**スコープ外**（今回は手を出さない）:

- 予約の取置期限（`Reservation.holdExpiryDate`）のカレンダー表示。同じ仕組みで載せられるが別依頼とする
- カレンダー画面へのメンバー絞り込みの追加（ホーム・貸出中に既にある機能の重複）
- 貸出中画面への日付区切り見出しの追加（モックの図中表現であり、実装対象ではない）
- 通信・DB・パーサの変更（`Loan.dueDate` と `StatusRepository.loans()` で足りる）
- `docs/handoff.md`「残課題C」に挙がる他5ファイルの lost update 修正
  （本設計は `CalendarScreenController` の3箇所だけを、触るついでではなく**必要**として直す。§4.5参照）

## 2. 所有者による確定事項（2026-08-19〜20）

1. マーカー表現は**メンバー色ドット**とする（件数バッジ案・月下リスト案は不採用）
2. タップ時は**貸出中画面へ遷移する**（カレンダー内でのボトムシート展開は不採用）
3. 遷移方式は**案2**（対象日までスクロール＋行の枠強調）とする
4. 遷移先に「カレンダーの○/○から移動しました」等の**説明バナーは出さない**。日付見出しも出さない。
   手掛かりはスクロール位置と枠の強調だけとする

## 3. 前提として確認済みの事実

- `Loan` は `memberId` と `dueDate: LocalDate` を持つ（`domain/model/Models.kt`）。
  `StatusRepository.loans(): Flow<List<Loan>>` は全メンバー分を返す（`data/repository/StatusRepositoryImpl.kt:59`）
- 日付ごとの集約は `HomeState.kt:209` に前例がある（`loans.groupBy { it.dueDate }`）。
  同ファイルは**在籍メンバーのIDに無い貸出を表示から除外**しており（`activeIds`）、本設計もこれに倣う
- `CalendarContentBuilder.months()` は Android 非依存の純関数で、テスト済み（`CalendarContentBuilderTest`、3件）
- 現行の `CalendarDayCell` は `dayOfMonth: Int?` しか持たず、**セルから日付を復元できない**。
  タップで日付を渡すには `LocalDate` をセルに載せる必要がある
- 貸出中画面のリストは `LazyColumn` の `items(state.rows)` 単独で、ヘッダ項目が無い（`ui/loans/LoansScreen.kt:95`）。
  よって **`state.rows` の添字がそのまま LazyColumn の index になる**
- `state.rows` は返却期限の昇順（`LoansContentBuilder`）。画面上部には `MemberFilterRow` があるが
  `LazyColumn` の外なので index に影響しない
- 画面間遷移は `LibraryApp` の `currentName = Destination.X.name` による単純な切替である（`ui/app/LibraryApp.kt:313` に前例）
- カレンダーの表示範囲は当月から3ヶ月（`DEFAULT_MONTH_COUNT = 3`）。貸出期間は通常2週間のため
  期限日はこの範囲に収まる。範囲外の期限は単に描画されない（異常ではない）

## 4. 設計

### 4.1 データの流れ

通信もDBも増やさない。`CalendarScreenController` に既存の2リポジトリを追加注入するだけである。

```
CalendarRepository.closedDays(code) ─┐
StatusRepository.loans() ────────────┼─ combine ─→ CalendarContentBuilder.months(...) ─→ CalendarUiState
FamilyRepository.members() ──────────┘
```

館の選択（`closedDays`）と返却期限（`loans`）は**独立**である。返却期限は館に依存しないため、
館を切り替えてもドットは変わらない。誤解を避けるため画面の注記に一文を足す（§4.4）。

**実装時の注記（2026-08-20追記）**: `combine`は全ソースが最低1回発行するまで何も発行しない。
そのため`CalendarUiState.initialized`は、従来の`closedDays`だけでなく`loans()`・`members()`の
初回発行にも依存するようになる。実害は薄いと判断した。理由は、`loans()`・`members()`とも
Roomの`observeAll()`系クエリを土台にしており、対象データが0件でも(初回同期前の新規インストール
直後でも)空リストを即時発行するため、これらのFlowが「発行しないまま止まる」実装ではないためである。

### 4.2 ドットの意味と上限

- **ドット1個＝1人**。同じメンバーが同じ日に3冊借りていてもドットは1個
  （冊数を表す設計にしない。マスが小さく、数の比較に耐えないため）
- 並び順は `Member.sortOrder`
- **最大3個**まで描画し、4人以上の場合は3個＋「＋」を出す
- 延滞日（過去日で未返却）にも同じドットを出す。**赤にしない**。
  延滞の強調はホームの「期限切れ」セクションが担っており、ここで赤くすると休館日の `alert` と紛らわしい

### 4.3 休館日・きょうとの重なり

`docs/handoff.md` の引き継ぎは「同一日に複数の状態がある場合の優先順位を決めること」を求めているが、
**本設計は優先順位を持たない**。3つの状態は描画層が異なり、競合しないためである。

| 状態 | 描画層 |
|---|---|
| 休館日 | セル背景の塗り（`colors.alertBg`） |
| きょう | セルの枠線（`colors.green`） |
| 返却期限 | セル下端のドット行 |

3つ同時に成立しても破綻しない（モックの「マス単体の拡大」で確認済み）。
なお実データでは休館日が返却期限になることは稀であり、これは成立性の確認に過ぎない。

### 4.4 変更点1: カレンダー画面

`ui/calendar/CalendarScreenController.kt`

```kotlin
data class CalendarDayCell(
    val dayOfMonth: Int?,
    /** 月内に日付があるマスの実日付。タップで貸出中へ渡す。空セルは null。 */
    val date: LocalDate? = null,
    val isClosed: Boolean = false,
    val isToday: Boolean = false,
    val isSunday: Boolean = false,
    val isSaturday: Boolean = false,
    /** この日が返却期限となる貸出を持つメンバーの色。sortOrder順・重複なし。 */
    val dueMemberColors: List<String> = emptyList(),
)
```

`CalendarContentBuilder` には引数をデフォルト値つきで足す。**既存の3テストは無改造で通る**。

```kotlin
fun months(
    today: LocalDate,
    closedDays: Set<LocalDate>,
    dueMemberColorsByDate: Map<LocalDate, List<String>> = emptyMap(),
    monthCount: Int = DEFAULT_MONTH_COUNT,
): List<CalendarMonthUi>

/**
 * 貸出を返却期限日ごとにまとめ、メンバー色の並びへ変換する。
 * 在籍メンバーに無い memberId の貸出は除外する(HomeState.kt の activeIds と同じ扱い)。
 * 同一メンバーの複数冊は1色へ畳む。
 */
fun dueMemberColorsByDate(loans: List<Loan>, members: List<Member>): Map<LocalDate, List<String>>
```

色の解決は既存の `parseMemberColor(colorHex, fallback)`（`ui/components/CommonUi.kt:123` 周辺の `MemberDot` と同じ）
を描画側で使う。`colorHex` が空のメンバーは `HomeState` と同じ扱いでフォールバック色にする。

`ui/calendar/CalendarScreen.kt`

- `onSelectDueDate: (LocalDate) -> Unit` を引数に追加
- `DayCell` に、`cell.date != null && cell.dueMemberColors.isNotEmpty()` のときだけ `Modifier.clickable` を付ける
  （期限の無い日はタップ無効。押しても何も起きない）
- 既存の `Box` の中にドット行を重ねる。数字は上へ2〜3dp寄せる
- 凡例に「返却期限（色＝だれの本か）」を1項目追加する。メンバー名と色の対応は凡例の下に小さく並べる
  （実装メモ・2026-08-20追記: このためのメンバー一覧受け渡し手段として `CalendarUiState` へ `members: List<Member>`（在籍メンバー、sortOrder順）を追加した）
- 既存の注記文へ次の一文を足す:
  「返却期限のしるしは館の選択に関係なく、家族全員ぶんを表示します。」

### 4.5 変更点2: `CalendarScreenController` の lost update 修正（必須）

`docs/handoff.md`「3回目の再発（2026-08-18 CI・ae83b63）と原因候補の特定」で
`SettingsScreenController` に対して行った修正と同じものを、本ファイルにも適用する。

現行は `_state.value = _state.value.copy(...)` が3箇所（`collect` 内・`refreshOnce` の成功時・失敗時）にあり、
**購読コルーチンと `refreshOnce` の別コルーチンが排他なしで read-modify-write している**。
今回 `combine` のソースが1本から3本へ増え、`loans()` は同期のたびに発行するため、
`refreshFailed` の書き込みが取りこぼされる窓は現在より広がる。**ついでの改善ではなく前提条件**として直す。

- 3箇所すべてを `MutableStateFlow.update {}`（CASループ）へ置き換える
- `_state.value = ...` を新たに書かない旨のコメントを `_state` 宣言の直下に置く（`SettingsScreenController` と同じ体裁）
- `refreshedCodes: MutableSet<String>` も購読コルーチンと `refreshOnce` の両方から触られる。
  同一 dispatcher 上とはいえ保証を書き下せないため、`update {}` 化にあわせて扱いを見直す
  （最小の対応は `refreshOnce` の呼び出し箇所を1つに保つこと。挙動は変えない）

`docs/handoff.md`「残課題C」の表から `CalendarScreenController.kt`（3箇所）を消し、本設計で対応した旨を追記する。
他5ファイルは引き続き未対応のまま残す。

### 4.6 変更点3: 遷移（案2）

**注目日は `LoansScreenController` に持たせない。** `LibraryApp` のローカル状態にして
`LoansScreen` へ引数で渡す。Controller は無改造、追加の永続状態もゼロである。

`ui/app/LibraryApp.kt`

```kotlin
// LocalDate は rememberSaveable が直接保存できないため epochDay で持つ。
var loansFocusDueDateEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
```

- `Destination.CALENDAR` の `CalendarScreen` へ
  `onSelectDueDate = { date -> loansFocusDueDateEpochDay = date.toEpochDay(); currentName = Destination.LOANS.name }`
- `Destination.LOANS` の分岐で `LoansScreen(focusDueDate = loansFocusDueDateEpochDay?.let(LocalDate::ofEpochDay), ...)`
- 同じ分岐に `DisposableEffect(Unit) { onDispose { loansFocusDueDateEpochDay = null } }` を置き、
  **画面を離れたら強調を消す**（タイマーは持たない）

`ui/loans/LoansScreen.kt`

```kotlin
val listState = rememberLazyListState()
// rows がまだ空(初期化前)の間は index が決まらない。rows の到着で再計算されるよう remember のキーに入れる。
val focusIndex = remember(focusDueDate, state.rows) {
    focusDueDate?.let { date -> state.rows.indexOfFirst { it.dueDate == date } }?.takeIf { it >= 0 }
}
// 1回のタップにつき1回だけスクロールする(メンバー絞り込みの操作で再スクロールしないため)。
var scrolledFor by remember { mutableStateOf<LocalDate?>(null) }
LaunchedEffect(focusIndex) {
    val index = focusIndex ?: return@LaunchedEffect
    if (scrolledFor == focusDueDate) return@LaunchedEffect
    listState.animateScrollToItem(index)
    scrolledFor = focusDueDate
}
```

- `LazyColumn(state = listState, ...)` にする
- `LoanRowView` に `highlighted: Boolean` を足し、`row.dueDate == focusDueDate` のとき枠を `colors.green` にする。
  強調は index ではなく**日付の一致**で決めるため、絞り込みで行が動いてもずれない
- 該当行が0件の場合（タップ直後に同期でその本が返却済みになった等）は、スクロールも強調も起きない。
  画面は通常の貸出中一覧として表示される。**エラーも案内も出さない**（バナーを出さない確定事項に従う）

## 5. テスト計画

**方針**: 追加するのは決定論的なテストだけにする。`docs/handoff.md`「既知のフレーキーテスト」のとおり
`SettingsScreenControllerTest` が未解決である以上、確率的なストレステストをこのスイートへ持ち込まない。

### 5.1 `CalendarContentBuilderTest` への追加（純関数・最優先）

既存3件はデフォルト引数により無改造で通る。以下を追加する。

1. 期限日のセルにだけ `dueMemberColors` が入り、他の日は空である
2. 同一メンバーが同じ日に複数冊借りていても色は1個に畳まれる
3. 複数メンバーの色が `sortOrder` 順に並ぶ
4. 在籍メンバーに無い `memberId` の貸出は無視される
5. 表示3ヶ月の範囲外の期限日は、どのマスにも現れない
6. 日付のあるマスに `date` が入り、空セルは `date == null` である
7. 休館日・きょう・返却期限が同一日でも3つのフラグ／値が同時に立つ（§4.3の裏付け）

### 5.2 `CalendarScreenControllerTest`（新規）

現在このControllerにテストは無い。`combine` の3ソース結線を確かめる最小限を足す。

- `HomeScreenControllerTest:223` の `FakeStatusRepository` と同じ流儀で、テストファイル内 private なフェイクを作る
- `CalendarScreenController` は既に `dispatcher` を引数で受けるので、`StandardTestDispatcher` を渡して
  `runTest` で駆動する（実時間待ちを入れない）
- 見るのは「貸出が流れてきたら該当月のセルに色が入る」「メンバーが空なら色が入らない」の2点に絞る

### 5.3 手動確認（CIのAPKで実機）

`docs/handoff.md` の方針どおり、ローカルにエミュレータを用意せずCIの成果物で確認する。

1. ドットの視認性（5dp径×3個＋「＋」）。潰れて見えるなら径・間隔・上限個数を調整する
2. ライト／ダーク両テーマでの見え方
3. 期限のある日をタップ → 貸出中へ移動し、該当行が画面内に来て枠が強調されること
4. 期限の無い日・空セルをタップしても何も起きないこと
5. 貸出中から離れて戻ると強調が消えていること
6. 「＋」の縦位置（実装メモ・2026-08-20追記）: 「＋」は8sp、ドットは5dpで文字の方が背が高い。
   ドット行は`BottomCenter`+`padding(bottom = 3.dp)`で置いているため、4人以上の日だけドットの
   縦位置がわずかにずれる可能性がある。ずれて見えるなら「＋」のフォントサイズか`Row`の
   `verticalAlignment`を調整する
7. 凡例の折り返し（実装メモ・2026-08-20追記）: 凡例が「休館」「きょう」「返却期限（色＝だれの本か）」の
   3項目になり、1行の`Row`（折り返しなし）に並ぶ。360dp幅では収まる計算だが、320dp幅の端末では
   見切れる恐れがある。見切れるなら横スクロールか2行化を検討する

## 6. 影響範囲と非互換

- **DBスキーマ・通信・パーサは無変更**。マイグレーション不要
- `CalendarDayCell` / `CalendarContentBuilder.months()` はデフォルト引数で拡張するため、既存の呼び出しは壊れない
- `LoansScreen` は引数が1つ増える（呼び出しは `LibraryApp` の1箇所のみ）
- `LoansScreenController`・`StatusRepository`・DIの提供内容は無変更。
  ただし `CalendarScreenController` の provider（`ui/di/DebugUiModule.kt:164`）に2依存を足す

## 7. 未解決・既知のリスク

1. **ドットの視認性は未検証**。実機で潰れる可能性があり、その場合は§5.3-1のとおり調整する
2. **`refreshedCodes` の扱い**は§4.5で最小限に留めており、根本的な排他設計は行わない。
   挙動を変えないことを優先する
3. 休館日が返却期限になるケースの実データ有無は**未確認**。表示は成立するため実装上の対応は不要
4. `docs/handoff.md`「残課題C」の他5ファイルは未対応のまま残る

## 8. 実装順序

1. `CalendarContentBuilder` / `CalendarDayCell` の拡張とテスト（§5.1）— ここまでUI非依存で完結する
2. `CalendarScreenController` の依存追加と `update {}` 化（§4.5）＋ テスト（§5.2）
3. `CalendarScreen` のドット描画・凡例・注記・タップ
4. `LibraryApp` と `LoansScreen` の遷移（§4.6）
5. `docs/handoff.md` の更新（引き継ぎ事項の消化、残課題Cの表から本ファイルを削除）
6. `docs/ui-design.md`「開館カレンダー」節へ返却期限マーカーの記述を追加

各段階でコミットし、1〜2の時点でCIを1回通してから3以降へ進む。
