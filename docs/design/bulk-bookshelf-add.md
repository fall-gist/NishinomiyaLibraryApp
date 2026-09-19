# 検索・新着からの一斉本棚追加 設計書

作成日: 2026-09-18
状態: 所有者承認済み（期待値の扱い・対象画面・バー配置）・段階1・段階2実装済み・**所有者による実機確認済み（2026-09-20、§6.3）**
前提となる設計:
- `docs/design/bookshelf-editing.md`（本棚編集。単件の資料追加は実装済み）
- `docs/design/bulk-selection.md` / `docs/design/bulk-selection-followup.md`（一斉操作。実装済み）
発端: 所有者「検索結果から複数書誌を選択して、カート追加や予約はできるが、本棚追加はできない状態。追加して」

## 1. 目的と成果物

**成果物**: 蔵書検索・新着資料で選択した複数の書誌を、1人のメンバーの1つの本棚へまとめて追加できる。

**スコープ外**:

- 本棚の作成（本棚が0件のメンバーには追加できない。既存の単件追加と同じ扱い）
- 資料メモの入力（一斉追加ではメモは常に空文字）
- 複数メンバー・複数本棚への同時追加（1操作 = 1メンバー × 1本棚）
- `BookshelfGateway` の変更（§3.4。1件の追加手順と照合は不変とする）
- 本棚画面側の一斉削除（`bulk-selection.md` §1 で見送り済みのまま）

## 2. 所有者による確定事項（2026-09-18）

1. 確認時の期待値のうち**資料数だけを1件ごとに更新**し、それ以外（メンバー名・本棚の総数・対象本棚の
   番号と名前）は**確認時の値のまま照合**する（§4.2）
2. 対象画面は**蔵書検索と新着資料の両方**（カート追加・直接予約と揃える）
3. 一斉操作バーは**「予約する（N件）」の主ボタン＋「…」メニュー**とし、メニューに
   「カートへ追加」「本棚へ追加」を置く（§5.1）。現行の「カートへ追加」枠線ボタンはメニューへ移す

## 3. 前提として確認済みの事実

### 3.1 単件追加は既に存在する

- 書誌詳細から `BookshelfEditingUiController.requestAddItem(tilcod, title)`（`BookshelfEditingUiController.kt:164`）
- 流れ: メンバー選択（初期値なし）→ そのメンバーの本棚を `observeShelves(memberId)` で監視 → 本棚選択
  （初期値なし）→ メモ → 最終確認（`bookshelf-editing.md` §7.2）
- 本棚が0件なら追加確定を無効化し「先に本棚を作成してください」と出す

### 3.2 1件の追加手順（`bookshelf-editing.md` §5.4「資料追加」）

各追加で次を行う。**1件あたり4往復**で、500ms間隔の制御があるため件数に比例して時間がかかる。

1. 全本棚を取得（`fetchAllShelves`）し、期待値と照合（`BookshelfGateway.kt:115`）
2. 対象本棚に同一 `tilcod` があれば**POSTせず** `AlreadyRegistered`
3. 通常書誌詳細を取得し、`tilcod` を一致確認
4. 追加POSTを1回送る
5. 全本棚を再取得し、成否を裁定（`Applied` / `AlreadyRegistered` / `Unknown`）

### 3.3 期待値の照合項目（`BookshelfGateway.kt:443` `matches`）

| 項目 | 目的 |
|---|---|
| メンバー名 | 別メンバーへの誤操作を防ぐ |
| 本棚の総数 | 本棚の追加・削除による**番号のずれ**を検出する |
| 対象本棚の番号と名前 | **番号が別の本棚を指す**事態を検出する |
| 対象本棚の資料数 | 確認後に本棚の中身が変わったことを検出する |

この仕組みは「**対象と異なる本棚が削除される不具合**」の再発防止である（`docs/design/release-build.md` 1.2の項）。

**一斉追加の障害**: 1件追加すれば資料数が変わる。期待値を使い回すと**2件目以降がすべて
`PRECONDITION_MISMATCH` で止まる**。

### 3.4 `mutate` は1件ごとにログインする

`BookshelfRepositoryImpl.mutateLocked`（`BookshelfRepositoryImpl.kt:70`）は、呼ばれるたびに
メンバー取得 → パスワード取得 → `gateway.openAuthenticatedSession(...)`（**ログイン**）→ 1件処理 →
`session.close()` を行う。**`mutate` を単純にループすると N 件で N 回ログインする**。

### 3.5 成功時の結果は操作後の本棚を持つ

`RemoteBookshelfOutcome.Applied` / `AlreadyRegistered` は、手順5で再取得した**操作後の全本棚**
（`shelves`・`items`）を持つ。Repository はこれで Room を置換する（`persistSnapshot`）。

## 4. 設計: Repository

### 4.1 API

```kotlin
interface BookshelfRepository {
    fun observeShelves(memberId: Long): Flow<List<BookshelfContent>>        // 既存
    suspend fun mutate(mutation: BookshelfMutation): BookshelfMutationOutcome // 既存・変更しない

    /**
     * 1人のメンバーの1つの本棚へ、複数の資料を順に追加する。
     * 本棚状態ゲートとログインは一括処理全体で1回だけ取得する。
     * Unknown または Failure が出た時点で打ち切り、残りは NotAttempted とする。
     */
    suspend fun addItems(
        request: BookshelfBulkAddRequest,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): BookshelfBulkAddResult
}

data class BookshelfBulkAddRequest(
    val memberId: Long,
    val shelfNo: Int,
    val items: List<BookshelfBulkAddItem>,
    /** 最終確認に表示した状態。shelf は必須、item は null。 */
    val confirmed: BookshelfMutationExpectation,
)

data class BookshelfBulkAddItem(val tilcod: String, val title: String)

data class BookshelfBulkAddResult(
    val items: List<BookshelfBulkAddItemResult>,
    /** 途中の Room 置換が1回でも失敗したか。UIは「画面を更新してください」を付記する。 */
    val localRefreshRequired: Boolean,
)

data class BookshelfBulkAddItemResult(val item: BookshelfBulkAddItem, val outcome: BookshelfBulkAddItemOutcome)

sealed interface BookshelfBulkAddItemOutcome {
    data object Added : BookshelfBulkAddItemOutcome            // Applied
    data object AlreadyRegistered : BookshelfBulkAddItemOutcome
    data object Unknown : BookshelfBulkAddItemOutcome          // POST後に成否不明
    data class Failed(val reason: FailureReason) : BookshelfBulkAddItemOutcome
    data object NotAttempted : BookshelfBulkAddItemOutcome     // 打ち切りで未実行
}
```

`mutate` と `BookshelfMutation` の既存型は**変更しない**（書誌詳細からの単件追加・本棚画面の編集が使っている）。

### 4.2 期待値の更新規則（所有者確定事項1）

1件目は `request.confirmed` をそのまま使う。2件目以降は、**直前の結果が持つ操作後の本棚（§3.5）から
対象本棚の資料数だけ**を取り、それ以外は `request.confirmed` の値のまま使う。

```kotlin
next = request.confirmed.copy(
    shelf = request.confirmed.shelf!!.copy(itemCount = <直前の結果の items のうち shelfNo が一致する件数>),
)
```

**Room からは読まない。** Room 置換が失敗しても（`localRefreshRequired`）、サイトから取り直した
本棚が正本であり、期待値の根拠は崩れないためである。

この規則で**守れるもの**と**緩めるもの**:

| | 扱い |
|---|---|
| 本棚の削除・改名・番号のずれ | **従来どおり検出して止まる**（名前・本棚の総数は確認時の値で照合し続けるため） |
| 一斉追加の途中で、同じ本棚へ別経路から資料が足された | **検出しない**（資料数を直前の実数で更新するため）。利用者の意図は崩れないので許容する |

**資料数を「確認時＋追加成功数」と予測する案は採らない。** `AlreadyRegistered` は「元からあった
（POSTなし・数は不変）」と「POST後に在るが新規追加と証明できない（数は+1の可能性）」の両方を含み
（`bookshelf-editing.md` §5.4 手順5）、予測が曖昧になるためである。実数を取るならこの曖昧さは生じない。

**「直前の結果の本棚を使う」ことで、名前の変化を追随しないこと**が重要である。直前の結果の本棚では
対象本棚の名前が変わっていても、次の期待値の名前は `request.confirmed` のままにする。そうすれば
サイト側の照合で止まる。**名前や本棚の総数まで直前の結果から取ってはならない。**

### 4.3 打ち切り条件

| 1件の結果 | 次へ進むか | 理由 |
|---|---|---|
| `Applied` | 進む | 資料数は操作後の本棚から取れる |
| `AlreadyRegistered` | 進む | 同上 |
| `Unknown` | **打ち切る** | POSTの成否が不明。状態が分からないまま次を送らない |
| `Failure` | **打ち切る** | 認証・メンテナンス・通信・照合不一致のいずれでも、後続も同じ理由で失敗する見込みが高い |

打ち切った後の残りは `NotAttempted` として結果に含める（**黙って捨てない**）。

Room 置換の失敗（`localRefreshRequired`）は**打ち切り条件にしない**（§4.2 のとおり期待値の根拠が
Room に依存しないため）。結果の `localRefreshRequired` を真にして UI に伝える。

### 4.4 排他とログイン

- 本棚状態ゲート（`BookshelfStateGate`）を**一括処理全体で1回だけ**取得する。ロック順は既存どおり
  `BookshelfStateGate` → `LicsXpSession` の共有 limiter → Room（`BookshelfRepositoryImpl.kt:123` のコメント）
- メンバー取得・パスワード取得・ログイン（`openAuthenticatedSession`）も**全体で1回**。最後に必ず `close()`
- メンバー名の照合（`mutateLocked` 冒頭と同じ）は開始時に1回行う
- 1件ごとの処理は既存の `session.mutate(RemoteBookshelfMutation.AddItem(shelfNo, tilcod, memo = "", expected))`
  を使う。**Gateway は変更しない**
- 成功のたびに既存の `persistSnapshot` と同じ規則で Room を置換する
  （`bookshelf-editing.md` §6.2。`Unknown`・`Failure` では Room を書き換えない）

ゲートを全体で保持するため、**一括処理の間は通常同期の本棚部分が待たされる**。失敗ではなく待機であり、
許容する（本棚編集と同期を直列化するというゲートの本来の目的どおり）。

`mutateLocked` の前段（メンバー・パスワード・セッション取得）を共通化する小さな抽出は許可する。
**ただし `mutate` の挙動を変えてはならない。**

### 4.5 進捗

`onProgress(completed, total)` は1件終えるたびに呼ぶ（`completed` は1始まり）。打ち切った件でも呼ぶ。
`NotAttempted` の件では呼ばない。1件あたり4往復のため、進捗表示が無いと固まったように見える。

## 5. 設計: UI

### 5.1 一斉操作バー（所有者確定事項3）

蔵書検索・新着資料のバーを次の形に変える。

```
[█ 予約する（5件）█] [⋯]
                     ↓
              ┌──────────────┐
              │ カートへ追加 │
              │ 本棚へ追加   │
              └──────────────┘
```

- 主ボタン「予約する（N件）」は現行どおり（緑の塗り、件数付き）
- 「⋯」（`IconButton`）を押すとメニューが開き、「カートへ追加」「本棚へ追加」を選べる
- 実行中（カート追加・直接予約・本棚追加のいずれか）はメニューの項目も無効化する

`BulkActionBar` の副アクション（`secondaryActionLabel` / `secondaryEnabled` / `onSecondaryClick`）は
現在これら2画面でしか使っていないため、**メニュー項目のリストへ置き換える**。

```kotlin
data class BulkOverflowAction(val label: String, val enabled: Boolean, val onClick: () -> Unit)

fun BulkActionBar(
    selectedCount: Int,
    actionLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = LocalAppColors.current.alert,
    contentColor: Color = LocalAppColors.current.card,
    overflowActions: List<BulkOverflowAction> = emptyList(),   // 空なら「⋯」を出さない
)
```

**`overflowActions` が空のときの描画は現行と1ピクセルも変えないこと。** 予約中の一斉取消・予約カートの
一括削除・貸出中の一斉延長はこれを指定しない。

### 5.2 一斉本棚追加のダイアログ

`BookshelfEditingUiController` に、既存の単件追加ダイアログ（`BookshelfEditingDialog.AddItem`）と並ぶ
**一斉追加用のダイアログ**を足す。**メンバー選択と本棚一覧の監視（`selectAddItemMember` の仕組み）を
再利用する**こと。本棚監視のジョブ管理は既に実装されており、作り直すと取り消し漏れの危険がある。

- メンバー（初期値なし）→ そのメンバーの本棚（初期値なし）を選ぶ。**メモ欄は置かない**
- メンバー変更時は本棚選択を必ず解除する（既存と同じ）
- 本棚が0件なら確定を無効化し「先に本棚を作成してください」（既存と同じ文言）
- 最終確認の文言に件数・メンバー名・本棚名を明示する:「太郎の本棚『読みたい』へ5件を追加します」
- 確定時に `BookshelfBulkAddRequest.confirmed` を**その時点で表示している本棚の状態**から組み立てる
  （メンバー名・本棚の総数・対象本棚の番号・名前・資料数。`item = null`）

**ダイアログの描画位置**: 本棚編集ダイアログは既にアプリ全体で1か所に描画されている（`LibraryApp` の
`BookshelfEditingDialogs`）。一斉追加用もそこに足す。検索・新着の画面は Controller を呼ぶだけにする。

### 5.3 実行中と結果

- 実行中は「N件目/M件」の進捗を出す（`LoanExtensionUiController` の一斉延長と同じ形）
- 結果は件ごとの行で出す。文言は `bookshelf-editing.md` §7.4 に揃える

| 結果 | 表示 |
|---|---|
| `Added` | 追加しました |
| `AlreadyRegistered` | すでにこの本棚に登録されています |
| `Unknown` | 追加できたか確認できません。本棚画面でご確認ください |
| `Failed` | 既存の失敗文言（認証失敗・通信失敗など） |
| `NotAttempted` | 前の資料で処理を中断したため、追加していません |

- `localRefreshRequired` が真なら、結果の末尾に「表示更新に失敗しました。画面を更新してください」を付記する
  （`bookshelf-editing.md` §7.4 と同じ）

### 5.4 選択の解除と排他

- 一斉本棚追加が終わったら（成否を問わず）、検索・新着の選択を空にする（`bulk-selection.md` §4.3 と同じ）
- 選択は検索・新着の Controller が持ち、本棚追加は `BookshelfEditingUiController` が行う。
  完了を検索・新着へ伝える手段を用意すること（完了時コールバック等。方式は実装に任せる）
- 実行中は、検索・新着のチェックボックスとバーの全操作を無効化する。**`BookshelfEditingUiController` の
  処理中フラグも無効化の条件に含めること**（別 Controller の状態であり、見落としやすい）
- **確認待ちの間に検索・新着の一覧から書誌が消えても、対象からは外さない**（`bulk-selection.md` §4.3
  の「一覧から消えたキーは対象外にする」規則は、一斉本棚追加には適用しない。実装は`dialog.items`を
  そのまま送る）。理由: カート削除等の「キーが存在しなくなる」場合と異なり、検索・新着の一覧から
  書誌が消えても書誌そのもの（`tilcod`）は無効にならない。利用者が明示的に選んだ資料を追加するのが
  意図どおりであり、また選択・確認のダイアログはモーダルなので、利用者の操作によって一覧が入れ替わる
  ことはない（段階2実装時の判断、2026-09-18）

### 5.5 状態更新の規則

`BookshelfEditingUiController` は既に `_state.update {}` を使っている。追加部分も**必ず `update {}` を使う**
（`_state.value = ...` を書かない。`docs/handoff.md`「残課題C」の経緯）。一斉追加は数秒〜数十秒動き続け、
その間も UI は操作可能であるため。

## 6. テスト計画

**方針**: 決定論的なテストのみ（`docs/handoff.md`「既知のフレーキーテスト」のとおり、確率的なテストの追加は禁止）。
**実サイトへの POST・ライブ診断は一切行わない。**

### 6.1 Repository（段階1）

Gateway・セッションはフェイクで置き換え、**フェイクが受け取った期待値を検査する**。

1. 全件 `Applied` のとき、2件目以降の期待値の資料数が**直前の結果の本棚の実数**になっている
2. 2件目以降の期待値の**メンバー名・本棚の総数・本棚番号・本棚名が確認時の値のまま**である
3. 直前の結果で対象本棚の**名前が変わっていても**、次の期待値の名前は確認時のまま（追随しない）
4. `AlreadyRegistered` のとき次へ進み、資料数は直前の結果の実数を使う
5. `Unknown` で打ち切り、残りが `NotAttempted` になる
6. `Failure` で打ち切り、残りが `NotAttempted` になる
7. Room 置換が失敗しても打ち切らず、結果の `localRefreshRequired` が真になる
8. ログイン（`openAuthenticatedSession`）が**一括処理全体で1回**、`close()` も1回
9. メモが常に空文字で送られる
10. `onProgress` が件ごとに呼ばれ、打ち切った件でも呼ばれ、`NotAttempted` では呼ばれない
11. 空リストでは通信せず空の結果を返す
12. 開始時のメンバー名が確認時と異なれば、1件も送らず全件 `Failed` になる（§9で確定）

### 6.2 UI（段階2）

- バー: `overflowActions` が空のとき「⋯」が出ない（既存3画面の回帰）
- ダイアログ: メンバー未選択・本棚未選択・本棚0件では確定できない
- メンバー変更で本棚選択が解除される
- 確定時の `confirmed` が表示中の本棚の状態と一致する
- 完了で検索・新着の選択が空になる
- 実行中は検索・新着の操作が無効になる（本棚側の処理中フラグで）

### 6.3 手動確認（CIのAPKで実機。**実データを作る操作**）

| # | 項目 | 結果（2026-09-20、所有者がCIのAPKで実施） |
|---|---|---|
| 1 | 検索・新着それぞれから2〜3件を選び、1つの本棚へ追加。本棚画面とサイトで追加を確認する | **確認済み**（複数件を本棚へ一斉追加できた。検索・新着の別、サイト側での確認までは報告に無い） |
| 2 | 既に本棚にある書誌を含めて追加し、「すでに登録されています」と出て処理が続くこと | **確認済み**（最後の結果画面に表示され、後続の件も処理された。§4.3 どおり） |
| 3 | 本棚が0件のメンバーを選ぶと確定できないこと | **確認済み**（本棚を先に作るよう促す表示が出て確定できない） |
| 4 | 実行中の進捗表示 | **確認済み**（進捗が表示され、戻るキーでは閉じない） |
| 5 | 「⋯」メニューの開閉と、「カートへ追加」が従来どおり動くこと（配置変更の回帰確認） | **確認済み** |
| 6 | 予約中の一斉取消・予約カートの一括削除・貸出中の一斉延長のバーに「⋯」が出ないこと | **確認済み** |

**所有者の報告にあった範囲だけを確認済みとした。** 1は「複数件を追加できた」との報告で、
検索と新着の両方で試したか、公式サイト側でも反映を確かめたかは報告に含まれていない。

## 7. 実装段階

**原則1セッション1段階で進める。** 各段階でコミット・プッシュ・CI確認・`docs/handoff.md` 更新まで終えて区切る。

| 段階 | 内容 | 通信 |
|---|---|---|
| 1 | Repository の `addItems`（§4）とテスト（§6.1） | あり（ただしテストはフェイクのみ） |
| 2 | UI（§5）とテスト（§6.2） | — |

段階1を先に行うのは、**安全性の核心（期待値の更新規則と打ち切り）を UI から切り離して検証できる**ためである。

## 8. 既知のリスク

1. **所要時間**: 1件あたり4往復＋500ms間隔。10件で数十秒かかる見込み（**未実測**）
2. **ゲートの保持時間**: 一括処理の間、通常同期の本棚部分が待たされる。長時間になると同期の完了が遅れる
3. **サイトの本棚あたり資料数の上限**は**未確認**。上限に達した場合の応答は分からない。
   `Failure` か `Unknown` になって打ち切られる想定だが、実測していない
4. §4.2 で緩めた検出（途中で同じ本棚に別経路から資料が足される）は、この家族利用のアプリで起こる可能性は低いが、ゼロではない
5. **POST後に想定外の例外が漏れた場合の分類**。`session.mutate` から想定外の例外（NPE等の実装不具合。
   `LibraryError` 系と `CancellationException` は Gateway 内で捕捉済み）が漏れると、POST開始後であっても
   `Failed` に分類され、「確実に未送信」と読める表示になる。これは既存の単件経路（`mutateLocked`）と
   同じ扱いで、一斉化で新たに生じた不具合ではない。ただし**1件で起きる機会が一斉化でN件分に増える**。
   一括処理はその時点で打ち切られるため、後続が送られることはない（段階1独立レビュー、2026-09-18）

## 9. 実装時に決めた事項（段階1で確定）

1. §6.1-12: 開始時のメンバー名不一致（および同種の事前チェック失敗: メンバー不在・パスワード欠落・
   ログイン失敗）の扱いは、**1件も送らず全件を同じ理由の `Failed` にする**と決めた。
   全件 `NotAttempted` にする案は採らなかった。`NotAttempted` のUI文言「前の資料で処理を中断したため、
   追加していません」（§5.3）は「前の資料」の失敗を前提にしており、事前チェックの失敗にはその前提が
   ない（1件も送っていない）ため、文言と実態が食い違う。`Failed` なら理由（`SITE_RESPONSE_CHANGED` /
   `AUTH` / `NETWORK` 等）がそのまま件ごとの行に表示され、UIの説明として正確である。
   `onProgress` は打ち切り条件（§4.3）ではなく事前チェック失敗であるため、`NotAttempted` ではなく
   `Failed` の規則（打ち切った件でも呼ぶ）に従い、全件について呼ぶ。
