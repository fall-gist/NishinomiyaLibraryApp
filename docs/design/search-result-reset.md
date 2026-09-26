# 蔵書検索: 検索結果と一時表示のリセット 設計書

作成日: 2026-09-20
状態: **完了（versionName 1.7、2026-09-26 実機確認済み）**。蔵書検索: `ddec794`・`391a76d`・`6de0837`（CI run 35459216355 成功）。新着資料(§8): `4637748`・`a9565cf`（CI run 35476593583 成功）
報告: 所有者（2026-09-20）。「複数カート追加の『○件をカートへ追加しました』が一度表示されると、
別キーワードで検索しなおしても表示され続ける」

本件は3段階の改修（1. 本件、2. 長押しによる選択モード、3. 読書記録の複数選択）の第1段階である。

## 1. 原因（確認済み）

- 結果メッセージは `SearchUiState.bulkCartAdditionResultMessage`（`SearchScreenController.kt:72`）に保持される
- `executeSearch`（同 `:438`）は `errorMessage` だけを消し、一斉操作の結果・エラーメッセージを消さない
- `SearchScreenController` は `@Singleton` でアプリ生存中ずっと残るため、他画面へ移って戻っても残る
- 付随（確認済み）: 警告設定がオフのとき、`search()` → `executeSearch()` は**選択を解除しない**。
  前の検索の選択キーが見えないまま残り、後の検索で同じ書誌が出ると選択済みで現れる。
  警告文言（「選択は解除されます」、`bulk-selection-followup.md` §6.2）とも食い違う

## 2. 所有者による確定事項（2026-09-20）

1. 新しい検索の実行時、および蔵書検索から他画面へ移ったときに、検索結果・選択・一時表示をリセットする
2. 書誌詳細はポップアップであり「他画面」に含めない。詳細を開閉しても何もリセットしない（現状どおり）
3. オーバーレイ化は行わない。画面構成は変えない

## 3. 設計

### 3.1 リセット規則

| 項目 | 新しい検索 | 他画面へ移る |
|---|---|---|
| 検索結果（`executedQuery`/`totalCount`/`results`/`hasNext`/`loadingMore`/`searching`/`errorMessage`/`suggestions`） | 新しい結果で置換（現状どおり） | 初期状態へ |
| 選択 `selectedCartTilcods` | **常に空にする**（警告オフでも。§1付随の修正） | 空にする |
| `bulkCartAdditionResultMessage` / `bulkCartAdditionErrorMessage` | 消す | 消す |
| 確認ダイアログ（`bulkCartAdditionConfirmation`/`bulkDirectReservationConfirmation`/`pendingSearchKeyword`） | 変更しない | null |
| **`bulkDirectReservationResults` / `bulkDirectReservationErrorMessage`** | **消さない** | **消さない** |
| 処理中フラグ（`bulkCartAdditionProcessing`/`bulkDirectReservationProcessing`） | 変更しない | **変更しない** |
| `members`/`libraries`/`defaultPickupLibraryCode`/`warnBeforeClearingSelection` | 変更しない | 変更しない |

**直接予約の結果・エラーを消さない理由**: 実サイトへの予約POSTの成否である。件ごとの成否を利用者が
見る前に消すと、どれが予約されたか分からなくなる。利用者がタップで閉じるまで残す
（カート追加はRoomのみの操作なので消してよい）。通知タップ（`homeNavigationCommandId`）は
結果ダイアログ表示中でも画面を移せるため、この区別は実際に効く。

### 3.2 実行中の処理

- 他画面へ移るとき、`searchJob`・`lendableJob`・`autocompleteJob` を**キャンセルする**
  （戻ったときに古い結果が遅れて現れるのを防ぐ）。`currentPage = 1` に戻す
- **一斉カート追加・一斉直接予約のコルーチンはキャンセルしない**。予約POSTを途中で切らない。
  完了時の結果は、画面へ戻ったときに表示される（利用者自身の操作の結果であり、正しい）
- 一斉操作の対象の絞り込み（`confirmBulkCartAddition`/`confirmBulkDirectReservation` 内の
  `currentTilcods`）は、現状 `scope.launch` の**内側**で `state.value.results` を読んでいる。
  確定直後に画面を移ると、リセット後の空の一覧で絞り込まれ、何も処理されない競合があり得る。
  **`currentTilcods` の取得を `launch` の前（確定操作と同じ同期区間）へ移す**。
  「確認待ちの間に一覧から消えたキーは無視する」（`bulk-selection.md` §4.3）は、確定時点の一覧で
  判定しても満たされる（確認待ちの間の変化は確定時点に反映済み）
- **世代番号による保護**: `scope` は `Dispatchers.Default`（マルチスレッド）であり、`cancel()` は
  協調的にしか止まらない。検索・追加読み込みのコルーチンが最後のsuspend点を過ぎたあとに
  `resetOnLeave()`や次の`executeSearch()`が割り込むと、「リセット（または新しい検索）の書き込み」→
  「古い検索の書き込み」の順になり、古い結果が後から書き戻る窓がある。`executeSearch`・
  `resetOnLeave`の冒頭で`searchGeneration`（`AtomicInteger`）を進め、結果を書く`_state.update`の
  直前に世代が一致するかを確認して不一致なら書き込みを丸ごと捨てることでこれを塞ぐ。
  `loadMore`は新しい世代を作らず、launch前に読んだ世代で同様に守る

### 3.3 「他画面へ移る」の検出位置

`LibraryApp.kt` で `currentName` を書き換えている6箇所（157, 211, 251, 319, 512, 554行目付近）を、
1つのローカル関数 `navigateTo(dest: Destination)` 経由に揃え、そこで
`current == SEARCH && dest != SEARCH` のときだけ `searchController.resetOnLeave()` を呼ぶ。

**`DisposableEffect` の `onDispose` は使わない。** 本アプリは画面回転でActivityが再生成され
（`AndroidManifest.xml` に `configChanges`/`screenOrientation` 指定なし）、`onDispose` が回転でも走るため、
回転で検索結果が消えてしまう。遷移操作の側で検出すれば回転の影響を受けない。

なお検索欄の入力文字列は `SearchScreen` 内の `remember` であり、画面を離れると既に消えている（現状どおり）。

### 3.4 スコープ外

- 新着資料画面は §8 で扱う（所有者の指示、2026-09-20）
- 書誌詳細ポップアップの開閉ではリセットしない（§2-2）
- 画面回転で検索欄の文字列が消える既存挙動は変更しない

## 4. 変更対象

- `ui/search/SearchScreenController.kt`: `executeSearch` の初期化項目追加、`resetOnLeave()` 新設、
  2つの `confirm*` の `currentTilcods` 取得位置の移動
- `ui/app/LibraryApp.kt`: `navigateTo` への集約と `resetOnLeave` 呼び出し
- `SearchScreenControllerTest.kt`: §5のテスト追加

## 5. テスト計画（決定論的。`StandardTestDispatcher` + `runTest`）

1. カート追加の結果メッセージが出た後に新しい検索をすると、結果メッセージが null になる
2. カート追加のエラーメッセージも同様に null になる
3. **警告設定オフで選択が残ったまま新しい検索をすると、選択が空になる**（§1付随の回帰防止）
4. 新しい検索では `bulkDirectReservationResults` と `bulkDirectReservationErrorMessage` が**残る**
5. `resetOnLeave()` で検索結果・選択・`executedQuery`・カート追加のメッセージが初期状態になる
6. `resetOnLeave()` で `bulkDirectReservationResults`/`bulkDirectReservationErrorMessage` が**残る**
7. `resetOnLeave()` で `members`・`warnBeforeClearingSelection` が保たれる
8. 検索の応答待ち（Fakeを `CompletableDeferred` 等で止める）の間に `resetOnLeave()` すると、
   その後応答が返っても `results` は空・`executedQuery` は null のまま
9. `confirmBulkCartAddition()` の直後（`advanceUntilIdle` 前）に `resetOnLeave()` しても、
   確定時点の候補がカートへ追加される（§3.2の競合の固定）
10. 同じく `confirmBulkDirectReservation()` の直後に `resetOnLeave()` しても予約が実行され、
    完了後に `bulkDirectReservationResults` が設定される。処理中フラグは完了まで true のまま

既存テスト（`設定オフでは選択が残っていても確認を出さず即座に検索する` など）は期待値を変えずに通るはず。
**通らなくなった場合は、テストを書き換える前に実装側を疑うこと。**

## 6. 手動確認（CIのAPKで実機）

1. 検索→複数選択→カートへ追加→結果メッセージ表示→別キーワードで検索 → メッセージが消える
2. 検索結果がある状態で他画面へ移り、蔵書検索へ戻る → 結果も選択も空
3. 検索結果から書誌詳細を開いて閉じる → 結果・選択はそのまま
4. 検索結果がある状態で画面を回転 → 結果は残る
5. 警告設定オフで選択したまま再検索 → 新しい結果に選択が残っていない

## 7. 未解決・既知のリスク

1. 一斉直接予約の処理中に画面を移ると、戻るまで結果が見えない。POSTは継続し、結果は保持される
2. 新着資料の一覧は画面を移っても残る（§8.1）。蔵書検索と挙動が異なるが、通信の性質が違うため意図的である

## 8. 新着資料画面（2026-09-20 追加、第1段階の続き）

所有者の指示により、新着資料画面にも同じ趣旨の対応を行う。ただし**蔵書検索と同じにはしない**。

### 8.1 一覧は消さない（蔵書検索との違い）

新着資料の一覧（`rows`）は Room のキャッシュと絞り込み語から `combine` で作られる派生値であり、
蔵書検索の結果のようにその場の通信で得た一時データではない。画面を移るたびに消すと、戻るたびに
巡回（公式サイトへの通信）が必要になる。**`rows`・`totalCount`・`lastFetchedAtEpochMillis`・
`refreshing`・`refreshFailed`・`updatePhase` には触れない。**

### 8.2 リセット規則

| 項目 | 巡回（`refresh`とその確認経由） | 他画面へ移る |
|---|---|---|
| `selectedCartTilcods` | **常に空にする**（警告オフでも） | 空にする |
| `bulkCartAdditionResultMessage` / `bulkCartAdditionErrorMessage` | 消す | 消す |
| 絞り込み語 `query` | 変更しない | 空にする |
| 確認ダイアログ（`bulkCartAdditionConfirmation`/`bulkDirectReservationConfirmation`/`pendingRefreshConfirmation`） | 変更しない | null / false |
| `bulkDirectReservationResults` / `bulkDirectReservationErrorMessage` | **消さない** | **消さない** |
| 一覧・取得状態（§8.1） | 現状どおり | **変更しない** |
| 処理中フラグ・`members`/`libraries`/`defaultPickupLibraryCode`/`warnBeforeClearingSelection`/`autoReservationEnabled` | 変更しない | 変更しない |

- 絞り込み語を変えただけではメッセージを消さない。1文字ごとに表示が消えるのはかえって落ち着かない
- 絞り込み語を画面離脱で空にするのは、`NewArrivalsScreen` の入力欄が `remember { mutableStateOf(state.query) }`
  で初期化されるため、戻ったときに入力欄と状態が食い違わないようにするためである
- 巡回で選択を常に解除するのは、蔵書検索の§1付随と同じ不具合（警告設定オフのとき解除されない）の修正である
- 巡回中のコルーチン（`performRefresh`）はキャンセルしない。`displayRefreshMutex` と
  `refreshedThisSession` の整合を壊さないため、`resetOnLeave` は巡回に一切触れない。
  したがって蔵書検索のような世代番号は不要である

### 8.3 検出位置

`LibraryApp.navigateTo` に、`current == NEW_ARRIVALS && dest != NEW_ARRIVALS` のときに
`newArrivalsController.resetOnLeave()` を呼ぶ分岐を足す。蔵書検索の分岐と並べる。

`onScreenLaunched` の `refreshedThisSession` は変更しない（セッション中1回の自動巡回のまま）。

### 8.4 テスト計画

1. カート追加の結果メッセージが出た後に `refresh()` すると、結果メッセージが null になる
2. エラーメッセージも同様
3. 警告設定オフで選択が残ったまま `refresh()` すると、選択が空になる
4. `refresh()` では `bulkDirectReservationResults`/`bulkDirectReservationErrorMessage` が残る
5. `resetOnLeave()` で選択・カート追加のメッセージ・`query` が初期状態になる
6. `resetOnLeave()` で `bulkDirectReservationResults`/`bulkDirectReservationErrorMessage` が残る
7. **`resetOnLeave()` で `rows`・`totalCount`・`lastFetchedAtEpochMillis` が保たれる**（§8.1の回帰防止）
8. 巡回中に `resetOnLeave()` しても巡回は続き、完了後に `refreshing` が false になる

### 8.5 手動確認

**3項目とも所有者が実機で確認済み（2026-09-20）。**

1. 新着で複数選択→カートへ追加→結果メッセージ→他画面へ移って戻る → メッセージも選択も無い（確認済み）
2. 新着で絞り込み→他画面へ移って戻る → 絞り込みが解除され、一覧は残っている（再取得が走らない）（確認済み）
3. 選択したまま「更新」 → 選択が残らない（確認済み）

蔵書検索側（§6）は未確認である。
