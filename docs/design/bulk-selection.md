# 複数選択による一斉操作の他画面展開 設計書

作成日: 2026-08-29
状態: 所有者承認済み（方式・メンバー指定）・実装未着手
発端: 所有者「予約中画面では複数書誌を選択して一斉取消ができる。同じような機能を他の画面でも追加したい」
既存の同種機能: 予約中の一斉取消（`docs/design/reservation-cancel-hardening.md`、`docs/ui-design.md`「経路2」）

## 1. 目的と成果物

**目的**: 一覧に対する繰り返し操作を、1件ずつではなくまとめて行えるようにする。

**成果物**: 次の3機能。いずれも予約中の一斉取消と**同じチェックボックス方式**で操作する。

| 記号 | 画面 | 操作 | 通信 |
|---|---|---|---|
| A | 貸出中 | 一斉延長 | **あり**（1件ずつ順次POST） |
| C | 予約カート | 一括削除＋「カートを空にする」 | なし（Roomのみ） |
| D | 蔵書検索・新着資料 | 一斉カート追加 | なし（Roomのみ） |

**スコープ外**:

- 本棚の資料一斉削除（所有者が本棚機能を積極的に使っていないため今回は見送り。候補としては有効）
- 読書記録・設定・ホーム・開館カレンダー（繰り返し操作が存在しない、または一斉化が危険）
- 書誌詳細オーバーレイからの導線追加
- 予約の一斉確定（`confirmCart` は既に全件確定であり、一部だけ確定する機能は今回作らない）
- `LoanExtensionGateway`（1件の二段階POSTと照合）の変更。**`loan-extension.md` §9.1 が不変を要求している**

## 2. 所有者による確定事項（2026-08-29）

1. 方式は**チェックボックスに統一**する。長押しによる選択モードは**採用しない**。
   画面ごとに方式を変える案（Dだけ長押し）も検討したが、**画面間で操作方法が揃わないことを許容しない**判断
2. 対象はA・C・Dの3機能。**Dが要望の原点**である
3. Cは「カートを空にする」と「一部の一括削除」の**両方**を作る。カートは最大5アカウント×各20件を
   保持しうるため、手で1件ずつ消すには大きすぎるという判断
4. Dの「誰の分か」は、**追加時の確認ダイアログでメンバーを選ぶ**（一覧上部に常設しない）

### 2.1 長押し方式を採らない理由（検討の記録）

`Modifier.combinedClickable` は Compose foundation 1.7.0 で `@ExperimentalFoundationApi` である
（実ソース `Clickable.kt:232` および `:325` で確認済み）。本プロジェクトは
`@OptIn(ExperimentalMaterial3Api)` 5箇所・`ExperimentalCoroutinesApi` 3箇所を既に使っており
実験的APIの使用自体に前例はあるが、`ExperimentalFoundationApi` は新規である。

加えて長押し方式は、選択モードの状態・行タップの意味の切替・`BackHandler` によるモード解除が必要で、
チェックボックス方式より確実に重い（一覧画面での `BackHandler` 使用は前例が無く、既存は書誌詳細と
診断ログの2箇所のみ）。

**決め手は既存方針との衝突である。** `docs/ui-design.md`「選択用チェックボックスは常時表示する」には
「選択モードへの切り替え操作は設けない」と明記されている。長押し方式はこれを覆すことになる。

機能ごとの比較では、Aはチェックボックスが明確に優位（`canExtend` の行にだけ出せるため、対象が一望できる）、
Dは長押しの利点が最も効く（カート追加はほぼ全行が対象で「対象が分かる」利点が効かない）という差があった。
それでも**統一を優先する**というのが所有者の判断である。

## 3. 前提として確認済みの事実

- 予約の一斉取消はサイトの一括APIではなく、**アプリが1件ずつ順次処理して結果をまとめる**方式である
  （`cancelReservations(targets: List<...>)` → 件ごとの成否を持つ `ReservationCancelBatchResult`）
- 予約中のチェックボックスは `if (row.cancellable)` のときだけ描画し、**非対象行では幅も確保しない**
  （`ReservationsScreen.kt:222`。行タップ領域とは別の `Row` に置いて領域を分離している）
- 貸出中の延長可否は `LoanRow.canExtend`（= `extendable && tilcod.isNotBlank()`）で既に判定できる
- **`loan-extension.md` §9.1 は一斉延長を予期しており、変更範囲を先に決めてある**:
  「変更はUI層と、**複数件を順に回すRepository側のループ追加だけ**で済む状態を保つ。Gateway
  （1件の二段階POSTと照合）は不変とする」。本設計はこれに従う
- `loan-extension.md` §9.3 により、貸出延長は `ReservationOperationGate` に**参加しない**
  （予約データに触れないため）。専用 `Mutex` のみを持つ。本設計でもゲートに参加させない
- `ReservationCartItem` は `memberId` を**必須**で持つ（`Models.kt:276`）。カート追加時に
  メンバーが決まっている必要がある
- `SearchResultRow` は `writerLine: String` を持つ（`SearchScreenController.kt:32`）。
  **詳細を開かずにカート項目を作れる**。`NewArrival` は `author`／`publisher` を持ち
  `writerLine` 相当は無いが、`ReservationCartItem.writerLine` は nullable である
- `ReservationCartRepositoryImpl.addToCart` は `insertIgnoreDuplicate` を使っており、
  **同一(memberId, tilcod)の重複追加は黙って無視される**（例外にならない）
- 対象4画面とも、行タップは既に書誌詳細オーバーレイに使われている
  （`SearchScreen.kt:144`、`NewArrivalsScreen.kt:134`、`ReservationCartScreen.kt:115`、貸出中も同様）

## 4. 共通設計

### 4.1 共通部品を切り出し、予約中も移行する

「操作方法を揃える」という確定事項を名実ともに満たすため、選択UIを共通部品として切り出し、
**既存の予約中画面もその部品を使うように移行する**。部品だけ作って予約中を放置すると、
見た目が揃う保証が無く「統一した」と言えなくなる。

新規: `ui/components/SelectionUi.kt`

- `SelectionCheckbox(checked, enabled, onToggle, checkedColor)`:
  現行 `ReservationsScreen` のチェックボックスをそのまま一般化したもの。**行タップ領域とは別の
  コンテナに置くこと**を前提とする（配置は各画面の責務）
- `BulkActionBar(selectedCount, actionLabel, enabled, onClick)`:
  現行 `BulkCancelBar`（`ReservationsScreen.kt:165`、private）を一般化したもの。
  一覧最上部に置き、選択件数と操作ボタンを出す

**予約中画面の移行は独立したコミットにする。** 動作中の画面に手を入れるため、問題があれば
この1コミットだけを戻せる状態を保つ。移行時に見た目を変えない（現行の `colors.alert` 系の
配色・レイアウトを維持する）。

### 4.2 選択状態は各画面のUiControllerが持つ

予約中と同じく、選択キーの集合は各画面のUiControllerが `Set<K>` で保持する。
共通の選択状態ホルダーは作らない（画面ごとにキーの型も対象条件も異なり、共通化しても
得るものが少ないため）。

| 機能 | 選択キー | 備考 |
|---|---|---|
| A | `LoanExtensionKey(memberId, tilcod)` | **既存の型をそのまま使う** |
| C | `Long`（`cartItemId`） | カート内で一意 |
| D | `String`（`tilcod`） | 一覧内で一意。空文字列の行は対象外 |

### 4.3 選択の解除タイミング（全機能共通）

- 操作の実行が完了したら選択を空にする（予約中の `confirmPending` と同じ）
- 一覧の内容が変わっても選択は自動で解除しない。ただし**一覧に存在しなくなったキーは、
  操作実行時に無視する**（同期で行が消える、他画面で削除される等）
- 画面を離れたときの扱いは各UiControllerの生存期間に従う（いずれもDIで `@Singleton`。
  予約中の現行挙動と同じにする）

## 5. 機能A: 貸出中の一斉延長

### 5.1 Repository

`LoanExtensionRepository` に複数件版を追加する。**`extendLoan(target)` は残す**（書誌詳細等からの
1件経路が将来増えても壊れないようにするため。§9.1の要件）。

```kotlin
interface LoanExtensionRepository {
    suspend fun extendLoan(target: LoanExtensionTarget): LoanExtensionOutcome
    /**
     * 複数件を順に延長する。1件が失敗しても後続を続行し、件ごとの結果を返す。
     * onProgressは1件終えるたびに呼ぶ(completedは1始まり)。UI側の進捗表示(§5.2)はこれで駆動する。
     * 既定値は何もしないラムダなので、進捗を使わない呼び出し元(テスト等)は指定しなくてよい。
     */
    suspend fun extendLoans(
        targets: List<LoanExtensionTarget>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): LoanExtensionBatchResult
}

data class LoanExtensionBatchResult(val items: List<LoanExtensionItemResult>)
data class LoanExtensionItemResult(val target: LoanExtensionTarget, val outcome: LoanExtensionOutcome)
```

実装は**既存 `extendLoan` を順に呼ぶループ**とする。Gatewayは不変。
`ReservationCancelRepository.cancelReservations` と同じ流儀で、1件の失敗が後続を止めない。
ループはRepository側に置く（§9.1の構造要件どおり、UI層は「複数件を順に回すRepository側の
ループ追加」の結果を使うだけにする。UI層で別のループを持たない）。

**注意**: 延長は1件ごとに「一覧を取得し直して `renewalCode` を得る」二段階POSTを行う。
したがって一斉延長は件数に比例して時間がかかる。**進捗表示が必要**（§5.2）。進捗表示は
`extendLoans` の `onProgress` コールバックで駆動する（実装時に追加。当初案ではUI層が
`extendLoan` を自前でループしていたが、それだとRepository側の`extendLoans`が本番コードから
一度も呼ばれず、テストと実挙動が乖離するため、コールバック付きに直した）。

### 5.2 UI

`LoanExtensionUiController` を拡張する。

- `selectedKeys: Set<LoanExtensionKey>` と `toggleSelection(key)` を追加
- 確認要求を、予約取消と同じく**単一/一斉を区別する型**にする
  （`ReservationCancelConfirmationRequest.Single` / `.Bulk` と同じ形）
- 結果は件ごとの行を持つダイアログで表示する。文言は既存の3値をそのまま使う
  （`Extended` / `Unknown` / `Failure`。`loan-extension.md` §6の文言を変えない）
- **処理中は進捗を出す**（「3件目/5件」程度）。1件あたり二段階POST＋一覧再取得のため、
  無表示だと固まったように見える

`LoansScreen`:

- `row.canExtend` の行にだけ `SelectionCheckbox` を置く（予約中と同じ「非対象行には出さない」方式）
- 一覧最上部に `BulkActionBar`（ラベル「一斉延長」）
- 既存の行内「延長」ボタンは**残す**（1件だけ延長したい場合の最短経路であり、現行の利用者体験を壊さない）

### 5.3 成功後のローカル反映

`loan-extension.md` §6.1 の規則を件ごとにそのまま適用する（`Extended` のときだけ当該行を
`dueDate` 更新・`extendable = false`。`Unknown`・`Failure` は一切書き換えない）。
**一斉化にあたって規則を変えない。** ループの各回で既存 `extendLoan` を呼ぶため、この挙動は自動的に維持される。

## 6. 機能C: 予約カートの一括削除と「カートを空にする」

### 6.1 Repository

```kotlin
interface ReservationCartRepository {
    suspend fun removeFromCart(cartItemId: Long)            // 既存・維持
    suspend fun removeFromCart(cartItemIds: List<Long>)     // 追加
    suspend fun clearCart()                                  // 追加(全削除)
}
```

いずれもRoom操作のみで通信しない。`clearCart()` を `removeFromCart(全件のid)` で代用せず独立させるのは、
**「空にする」が件数に依存しない単純な操作**であり、DAOの一括削除で足りるためである。

### 6.2 UI

`ReservationCartScreen`:

- 各行に `SelectionCheckbox`（カート項目は全件が削除対象なので、**全行に出す**）
- 一覧最上部に `BulkActionBar`（ラベル「選択した項目を削除」）
- 画面下部または上部に「カートを空にする」ボタンを別に置く。**確認ダイアログを必須**とする
  （取り返しがつかないため。文言に件数を入れる: 「カートの◯件をすべて削除します」）
- 既存の行内削除ボタンは**残す**

### 6.3 確認の要否

- 一括削除: **確認する**（複数件が一度に消えるため）
- 「カートを空にする」: **確認する**
- 1件削除（既存）: 現行どおり確認なし（挙動を変えない）

## 7. 機能D: 蔵書検索・新着資料からの一斉カート追加

### 7.1 Repository

```kotlin
interface ReservationCartRepository {
    suspend fun addToCart(target: ReservationTarget)                    // 既存・維持
    /** 複数件をまとめて追加する。既にカートにある(memberId, tilcod)は加算せず skipped に数える。 */
    suspend fun addToCart(targets: List<ReservationTarget>): ReservationCartAddSummary
}

data class ReservationCartAddSummary(val added: Int, val skipped: Int)
```

既存の `addToCart` は `insertIgnoreDuplicate` で重複を黙って無視する。**一斉追加では
「何件入ったか」を利用者に伝える必要がある**ため、追加された件数と無視された件数を返す。
`insertIgnoreDuplicate` の戻り値（無視時は -1）で判別できる。

### 7.2 メンバー指定（所有者確定事項4）

選択後に「カートへ追加」を押すと、**メンバー選択を兼ねた確認ダイアログ**を出す。

- メンバーのチップを並べ、1人選ぶ（書誌詳細の既存UIと同じ見た目。`MemberDot` + 名前）
- 選択中の件数を文言に出す: 「太郎の分として5件をカートへ追加します」
- **1回の操作につきメンバーは1人**とする。複数メンバーへ振り分けたい場合は操作を繰り返す
  （「まとめて拾う」という目的から遠のくため、1件ごとの割り当ては作らない）
- メンバー未選択では実行ボタンを無効にする
- **検索・新着の両画面で同じダイアログを使い回す**

初期選択は行わない（誤ったメンバーのカートへ入る事故を避けるため、明示的に選ばせる）。

### 7.3 UI

`SearchScreen` / `NewArrivalsScreen`:

- 各行に `SelectionCheckbox`。**`tilcod` が空の行には出さない**（既に行タップも無効にしている前例に合わせる）
- 一覧最上部に `BulkActionBar`（ラベル「カートへ追加」）
- 結果は簡潔に伝える。全件追加なら「5件をカートへ追加しました」、重複があれば
  「3件をカートへ追加しました（2件は既にカートにあります）」
- 既存の行タップ（書誌詳細を開く）は**変更しない**

### 7.4 カート項目の内容

| 画面 | `title` | `writerLine` |
|---|---|---|
| 蔵書検索 | `SearchResultRow.title` | `SearchResultRow.writerLine` |
| 新着資料 | `NewArrival.title` | `author` と `publisher` から組み立てる。空なら null |

**詳細（`bookDetail`）を取りに行かない。** 一覧が持つ情報だけでカート項目を作る。
一斉追加のたびに件数分の詳細取得を走らせるのは、通信を増やすうえに遅い。

## 8. テスト計画

**方針**: 決定論的なテストのみ。`docs/handoff.md`「既知のフレーキーテスト」のとおり
確率的なテストをこのスイートへ持ち込まない。

### 8.1 Repository（純粋・最優先）

- `extendLoans`: 1件が `Failure` でも後続が実行され、件ごとの結果が返る
- `extendLoans`: 空リストを渡すと通信せず空の結果を返す
- `removeFromCart(List)`: 指定したidだけが消える
- `clearCart()`: 全件消える
- `addToCart(List)`: 重複を含む入力で `added`／`skipped` が正しく数えられる
- `addToCart(List)`: 存在しない `memberId` を含む場合の挙動（既存 `addToCart` は `require` で
  例外。一斉版でどう扱うかを実装時に確定し、テストで固定すること。**§10-2 参照**）

### 8.2 UiController

- 選択のトグル、実行後に選択が空になること
- 一覧から消えたキーが実行時に無視されること（§4.3）
- Dのメンバー未選択時に実行できないこと

### 8.3 手動確認（CIのAPKで実機）

1. A: 2件以上を選んで一斉延長し、成功した行の期限が更新され延長ボタンが消えること
2. A: 処理中の進捗表示が出ること
3. C: 一部選択の削除、「カートを空にする」の両方
4. D: 検索・新着それぞれから複数追加し、カート画面に正しいメンバーで入ること
5. D: 既にカートにある書誌を含めて追加し、件数の文言が実態と合うこと
6. 予約中: 移行後も一斉取消が従来どおり動くこと（§4.1のリファクタの回帰確認）

## 9. 実装順序

1. **共通部品の切り出しと予約中の移行**（§4.1）。ここでCIを通す
2. **D: 一斉カート追加**（要望の原点。通信なしで最も安全）
3. **C: カートの一括削除と空にする**（通信なし。Dと同じカート周辺なので続けて行う）
4. **A: 一斉延長**（唯一の通信あり。最も慎重を要するため最後）

各段階でコミットし、CIを通してから次へ進む。1と2の間、3と4の間で一度実機確認を挟めると安全。

## 10. 未解決・既知のリスク

1. **一斉延長の所要時間**。1件ごとに一覧再取得＋二段階POSTのため、10件選ぶと相応に待たされる。
   進捗表示（§5.2）で対処するが、**実測していない**。実機確認で許容範囲か判断すること。
   耐えられない場合は選択件数の上限を設ける案がある（未採用）
2. **【決定・実装済み】一斉カート追加で存在しないメンバーを指定した場合の扱い**:
   **その件だけ飛ばし、`skipped`に数えて残りの対象の処理を継続する**（全体を失敗させない）。
   既存の単数版`addToCart`は`require`で例外を投げるが、一斉版では重複追加（既存の
   `insertIgnoreDuplicate`が黙って無視する挙動）と同じ「その件だけ結果に出さず続行する」流儀に
   揃えた。理由: 一斉操作は複数件をまとめて楽に処理するための機能であり、1件の不正（UI経由では
   在籍メンバーからしか選べないため実際には起こらないが、契約としては起こり得る）のために
   他の正当な対象まで巻き込んで全滅させるのは、一斉操作の目的に反する。tilcod・titleが空、
   またはcartItemIdを指定した対象が混在する場合は、UI経由では起こらない構造的な誤り呼び出しと
   判断し、従来どおり`require`で例外にする（`ReservationCartRepositoryImpl.addToCart(List)`参照）。
   テストは`ReservationCartRepositoryTest`に固定した。
3. `ReservationCartItem` の上限は設けていない。所有者の想定（5アカウント×20件=100件）でも
   Roomの一覧表示・一括削除は問題ないと考えるが、**実測していない**
4. 予約中画面の移行（§4.1）は動作中の画面への変更である。見た目を変えない前提だが、
   回帰確認（§8.3-6）を必ず行うこと
