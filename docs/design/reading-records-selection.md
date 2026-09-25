# 読書記録画面の複数選択 設計書

作成日: 2026-09-20
状態: **実装完了・実機確認待ち**（`4f81308`・`5242e5c`、CI run 36169420164 成功。versionName 1.7 に含む）
位置づけ: 3段階の改修の第3段階（1. 検索・新着のリセット=完了、2. 長押しによる選択モード=完了、3. 本件）
前提: `docs/design/selection-mode.md`（長押しによる選択モード。5画面に適用済み）

## 1. 目的と所有者確定事項（2026-09-20）

読書記録画面には複数選択の機能が無い。これを追加する。

- 一括操作は**カートへ追加・直接予約・本棚へ追加**の3つ（所有者確定）
- 方式は他画面と同じ**長押しによる選択モード**とする（不揃いを許容しない方針）

## 2. 現状（確認済み）

- `ReadingRecordsScreenController` は `selectedMemberId` と `query` から `rows` を組み立てるだけで、
  選択の概念も一斉操作も持たない
- `ReadingRow` は `memberColorHex` / `title` / `loanDateLabel` / `library` / `tilcod` を持つ。
  **`tilcod` は空のことがある**（古い記録。`ReadingRecordsScreen.kt:126` は空なら行タップを無効にしている）
- `ReadingRow` は著者名を持たない。一斉カート追加の候補（`BulkCartAdditionCandidate`）は
  `tilcod` / `title` / `writerLine?` を取り、`writerLine` は null 可
- `_state` の更新は `flatMapLatest` の単一のコレクタから `_state.value = newState` で全置換している。
  **書き手が増えるため、`update {}` へ直す必要がある**（`docs/handoff.md` の記録）

## 3. 設計

### 3.1 他画面の作りをそのまま使う

蔵書検索・新着資料と同じ部品・同じ規則を使う。

| 要素 | 使うもの |
|---|---|
| 選択モードの上部バー | `SelectionModeBar` |
| 一斉操作バー | `BulkActionBar`（主ボタン「予約する」＋「⋯」に「カートへ追加」「本棚へ追加」） |
| 確認ダイアログ | `BulkCartAdditionConfirmDialog` / `BulkDirectReservationConfirmDialog` |
| 結果表示 | `ReservationResultsDialog`、カート追加は件数の文言 |
| 本棚へ追加 | `BookshelfEditingUiController.requestBulkAddItems(items, onCompleted)` |
| 長押し・タップ | `combinedClickable` と `DisableSelection`（選択モードの設計 §3.2・§3.3） |

**蔵書検索の `SearchScreenController` の該当部分を、読書記録のコントローラへ写す**。
共通化のための抽象（基底クラスや共通コントローラ）は作らない。画面ごとに一覧の持ち方・選択キーの
意味が違い、過去にも画面ごとに書き下ろしてきた。抽象化は3画面目の重複を見てから判断する。

### 3.2 選択キーは資料番号（tilcod）とする

読書記録は**同じ資料が複数行に現れる**（家族の別の人が読んだ、同じ人が別の日に借りた）。
一斉操作はいずれも資料に対する操作であり、誰がいつ読んだかは関係ない。したがって:

- 選択キーは `tilcod`
- **同じ資料の行は連動する**。1行を選ぶと、一覧にある同じ資料の行すべてにチェックが付く
- 一斉操作へ渡す候補は `tilcod` で重複を除く。「3件を選択中」の件数も**資料の数**で数える
  （行の数ではない）
- `tilcod` が空の行は選択できない（長押しでも選択モードに入らない。選択モード中のタップも無効）

この連動は、利用者から見て「同じ本が2つ選ばれて2回予約される」ことを防ぐ。

### 3.3 選択モードの規則

`docs/design/selection-mode.md` §3.3〜§3.8 をそのまま適用する。要点のみ再掲する。

- 通常はチェックボックスを出さない。一覧の上に「長押しで複数選択」を出す
- 長押しで選択モードに入り、その資料を選択する。選択モード中のタップは選択の切り替え
- 上部バーに「N件を選択中」と「選択解除」。「すべて選択」は置かない
- 戻るキーで抜ける。選択が0件になっても抜けない
- 一斉操作の完了（成功・失敗のどちらでも）で抜ける。通信例外では抜けない
- **他画面へ移ったら抜ける**。読書記録には `resetOnLeave()` が無いので、`exitSelectionMode()` を
  `LibraryApp.navigateTo` から呼ぶ（貸出中・予約中と同じ形）
- 絞り込み（メンバーの切り替え、検索語の変更）では**選択モードから抜けない**。
  ただし表示されなくなった資料は「表示中の選択」から外れ、件数と操作対象から外れる
  （`docs/design/bulk-selection-followup.md` §6.1 の既存規則と同じ）

### 3.4 状態の持ち方

`ReadingRecordsUiState` に次を足す。蔵書検索の `SearchUiState` と同じ名前にする。

```kotlin
val selectionMode: Boolean = false,
val selectedTilcods: Set<String> = emptySet(),
val members: List<Member> = emptyList(),          // 既にある
val libraries: List<Library> = emptyList(),       // 直接予約の受取館選択に使う
val defaultPickupLibraryCode: String = "",
val bulkCartAdditionConfirmation: BulkCartAdditionConfirmationRequest? = null,
val bulkCartAdditionProcessing: Boolean = false,
val bulkCartAdditionResultMessage: String? = null,
val bulkCartAdditionErrorMessage: String? = null,
val bulkDirectReservationConfirmation: BulkDirectReservationConfirmationRequest? = null,
val bulkDirectReservationProcessing: Boolean = false,
val bulkDirectReservationResults: List<ReservationResultRow> = emptyList(),
val bulkDirectReservationErrorMessage: String? = null,
```

**`_state` の更新を `update {}` へ直す。** 現在の `flatMapLatest { ... }.collect { _state.value = it }`
は状態全体を置き換えるため、そのままでは選択が巡回の更新で消える（過去に4つのコントローラで
踏んだ lost update と同型）。コレクタ側は**組み立てた値のうち一覧に関する項目だけを差し込む**形に
変える。

```kotlin
.collect { built ->
    _state.update { current ->
        current.copy(
            initialized = true,
            members = built.members,
            selectedMemberId = built.selectedMemberId,
            query = built.query,
            rows = built.rows,
            showActivationHint = built.showActivationHint,
        )
    }
}
```

### 3.5 直接予約・カート追加・本棚追加の扱い

- `ReservationCartRepository` と `CalendarRepository`（受取館の一覧）、`SettingsStore` の既定館を
  読書記録のコントローラへ注入する。蔵書検索と同じ依存である
- **カートを経由しない直接予約は `reserveNow(targets, confirmation)` を使う**
  （`docs/design/bulk-selection-followup.md` §5.2）。`confirmCart` は使わない
- 候補の組み立ては、通信の直前ではなく**確定操作と同じ同期区間**で行う
  （`docs/design/search-result-reset.md` §3.2 の修正と同じ理由）
- 一覧に存在しなくなった資料は無視する（`docs/design/bulk-selection.md` §4.3）
- `writerLine` は読書記録が持たないため null を渡す
- 本棚追加は `BookshelfEditingUiController` に任せ、完了時のコールバックで選択モードを抜ける

### 3.6 スコープ外

- 読書記録の削除・編集は追加しない
- 書誌詳細の表示、履歴の同期、絞り込みの仕組みは変えない
- 予約カート画面の方式は変えない（チェックボックスのまま）

## 4. テスト計画（決定論的。`StandardTestDispatcher` + `runTest`）

コントローラ:

1. `enterSelectionMode(tilcod)` で選択モードに入り、その資料が選択される
2. `tilcod` が空、一覧に無い `tilcod` では選択モードに入らない
3. 選択モード中の `toggleSelection` で選択が増減する。0件でも選択モードは続く
4. `exitSelectionMode()` で選択が空になり、選択モードから抜ける
5. **同じ資料が複数行にあるとき、選択は1件として数えられ、候補も1件だけ渡る**（§3.2）
6. 一斉カート追加の完了（成功・失敗）で選択モードから抜ける
7. 一斉直接予約の完了（成功）で選択モードから抜ける。通信例外では抜けない
8. 処理中は `enterSelectionMode`・`toggleSelection` を受け付けない
9. 確定の直後に一覧が入れ替わっても、確定時点の候補が処理される（§3.5）
10. **絞り込みの更新（メンバー切り替え・検索語の変更）で選択が消えない**（§3.4 の lost update 対策）
11. 表示されなくなった資料は、件数と操作対象から外れる（§3.3）

## 5. 手動確認（CIのAPKで実機）

1. 読書記録で書誌を長押し → 選択モードに入る
2. 同じ本が複数行あるとき、1行を選ぶと同じ本の行すべてにチェックが付き、件数は1と数える
3. 資料番号が無い古い記録は長押ししても反応しない
4. 「予約する」→ メンバーと受取館を選んで実行 → 結果が出て、選択モードから抜ける
5. 「⋯」→「カートへ追加」→ 件数の文言が出る
6. 「⋯」→「本棚へ追加」→ 本棚を選んで実行できる
7. 検索語を変える・メンバーを切り替える → 選択モードのままで、選択も残っている
8. 他画面へ移って戻る → 選択モードから抜けている

## 6. 未解決・既知のリスク

1. 読書記録は「すでに読んだ本」の一覧であり、そこから予約する動機は限られる（続きを借りる、
   もう一度借りる）。使われ方が想定と違えば、一斉操作の並びは後で変えられる
2. 同じ資料の行の連動（§3.2）は、他画面には無い規則である。実機で違和感がないかを見る
3. 読書記録の一覧は件数が多くなりうる。選択の集合は資料番号の集合であり、行数には比例しない
