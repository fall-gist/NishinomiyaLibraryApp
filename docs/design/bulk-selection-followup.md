# 一斉操作の追補（延長可能ラベル・一斉直接予約・選択リセット警告） 設計書

作成日: 2026-08-30
状態: 所有者承認済み（対処範囲）・実装未着手
前提となる設計: `docs/design/bulk-selection.md`（A/C/D の一斉操作。実装済み）
発端: 所有者による Ver.1.5 実機確認時の指摘3件

## 1. 目的と成果物

| 記号 | 内容 | 通信 |
|---|---|---|
| **E** | 貸出中のチェックボックスの横に「延長可能」を添える | なし |
| **F** | 蔵書検索・新着資料の複数選択から、カート追加に加えて**直接予約**もできるようにする | **あり** |
| **G** | 選択が残ったまま一覧が入れ替わる問題への対処（警告ダイアログ＋設定項目、件数規則の統一） | なし |

**スコープ外**:

- 一斉操作そのものの方式変更（チェックボックス方式は維持）
- 予約中・予約カートの一斉操作（今回は触らない）
- 蔵書検索の検索状態そのものの保持・復元（画面遷移で結果が残る現行挙動は変えない）
- 複数アカウントへの同時予約（所有者が「やむを得ない」として1アカウントに限定）

## 2. 所有者による確定事項（2026-08-30）

1. 貸出中のチェックボックスが「浮いて見える」ため、予約中の「順番待ち」に相当する**「延長可能」テキストを横に添える**
2. 検索・新着の複数選択から**直接予約もできるようにする**。確認ダイアログでアカウントを選ぶ。
   **複数アカウントへは入れられないが、やむを得ない**
3. 再検索時に**注意喚起ダイアログ**を出す。ダイアログに「今後は表示しない」を置き、
   **設定画面で復活させられる項目**を設ける
4. 対処範囲は**蔵書検索と新着資料の両方**。「今後は表示しない」は両画面共通の設定1つにまとめる

## 3. 前提として確認済みの事実

### 3.1 選択と表示行の関係（当初の理解を訂正するもの）

- 選択は `tilcod`（新着は `selectedCartTilcods`、検索も同様）で保持される。**行が表示から消えても選択は残る**
- したがって**絞り込みは可逆**である。新着資料の絞り込み語を戻せばチェックは再び見える。
  当初「絞り込むとチェックを外せなくなる」と整理したが、これは誤りである
- **不可逆なのは次の2つ**。旧結果が戻らないため、その選択は永久に見えなくなる
  - 蔵書検索: 新しいキーワードでの検索実行
  - 新着資料: 巡回（`refresh()` / `onScreenLaunched()`）による一覧の入れ替え
- `loadMore`（検索の追加読み込み）は行を追加するだけで消さないため、対象外

### 3.2 件数のずれ（未報告の不具合）

| | 参照元 |
|---|---|
| `BulkActionBar` の表示件数 | `state.selectedCartTilcods.size`（**全選択件数**） |
| 実際の処理対象 | `cartAdditionCandidates(rows, selectedTilcods)`（**表示中の行のみ**） |

**5件選んでから絞り込むと、バーは「5件」のまま実際は3件しか追加されない。** 再検索後はバーが
「5件」でも追加は0件になる。所有者が報告した不便さの実体はこれである
（「チェックを外せない」というより「チェックが残っているのに何も起きない」）。

### 3.3 直接予約の基盤

- `ReservationCartRepositoryImpl.execute(targets: List<ReservationTarget>, confirmation, beforeWrite)` は
  **既に複数件を受け取り、`memberId` ごとにグループ化して処理する**
- `reserveNow(target, confirmation)` はそこへ `listOf(target)` を渡しているだけである
- `reserveNow` は `operationGate.withOperation(ReservationOperationType.MANUAL_RESERVATION)` の内側で動く
- `ReservationConfirmation` は `pickupLibraryCode` と `confirmedAtEpochMillis` を持つ。
  **直接予約には受取館の指定が要る**
- 予約上限・利用制限・予約不可資料のサイト応答は**未検証**（`docs/handoff.md` 記載）。
  一部成功は `ReservationBatchResult` の件ごとの結果で表現できる

### 3.4 設定の追加

- `AppSettings` はフィールド追加で拡張できる（`SettingsStore`、既定値つき）
- `BackupPayload` の設定はフィールドごとの写像である（`BackupExporter` / `BackupImporter`）。
  **新しいフィールドを写像に加えなければ、バックアップの形式は変わらない**

## 4. 機能E: 貸出中の「延長可能」ラベル

`LoansScreen.kt` の `if (row.canExtend)` ブロック（現状はチェックボックスだけを置く `Row`）に、
テキストを1つ加える。

```kotlin
if (row.canExtend) {
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionCheckbox(...)
        Text(
            text = "延長可能",
            color = colors.ink2,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
```

**体裁は予約中の `statusLabel` と完全に同じにする**（`fontSize = 11.sp`、`FontWeight.Bold`、`colors.ink2`）。
目的は「浮いて見える」ことの解消であり、目立たせることではない。緑などで強調しない。

`canExtend` でない行にはこの `Row` 自体が無いため、ラベルも出ない（現行の出し分けを変えない）。

## 5. 機能F: 検索・新着からの一斉直接予約

### 5.1 Repository

```kotlin
interface ReservationCartRepository {
    suspend fun reserveNow(target: ReservationTarget, confirmation: ReservationConfirmation): ReservationBatchResult
    /** 複数件をまとめて直接予約する。カートを経由しない。 */
    suspend fun reserveNow(targets: List<ReservationTarget>, confirmation: ReservationConfirmation): ReservationBatchResult
}
```

実装は既存 `reserveNow` と同型で、`execute(targets, confirmation, ::markWriteStarted)` へそのまま渡す。
`operationGate.withOperation(MANUAL_RESERVATION)` の内側で動かし、`validateConfirmation` と
各 target の `validateTarget(permitCartItemId = false)` を通す。`recordPickupSubmissions` も同様に呼ぶ。

**単数版は残す**（書誌詳細からの即時予約が使っているため）。

### 5.2 カートを経由しない

「カートへ追加してから確定する」方式は**採らない**。`confirmCart` はカート内の**全項目**を確定するため、
既にカートに入っている他の項目まで巻き込む。直接予約は独立した経路とする。

### 5.3 UI

`BulkActionBar` に2つ目のアクションを持たせる（省略可能とし、予約中・予約カートは1つのまま）。

- 検索・新着: 「カートへ追加」と「予約する」の2ボタン
- 「予約する」を押すと、**メンバー選択・受取館選択・件数を含む最終確認ダイアログ**を出す
  - メンバー選択は既存の `BulkCartAddition` と同じ見た目（`MemberDot` + 名前のチップ）
  - 受取館は既存の `PickupLibrarySelector` を使う。初期値は `SettingsStore.defaultCalendarLibrary`
    （アプリ共通の「既定館」。`docs/backend-design.md` の方針に従う）
  - 文言に件数・メンバー・受取館を明示する:「太郎の分として5件を中央図書館受取で予約します」
  - メンバーまたは受取館が未選択のときは実行ボタンを無効にする
- 結果は既存の予約結果表示を再利用する（件ごとの成否）

### 5.4 安全のための不変条件

`docs/backend-design.md` の予約に関する不変条件をそのまま守る。

1. **UIの明示操作と最終確認の後にだけ**開始する
2. `ReservationOperationGate` に参加する（自動予約・予約取消との競合を防ぐ）
3. 1回の操作につきメンバーは1人。複数アカウントへは同時に予約しない（所有者確定事項2）
4. 実行中は「予約する」「カートへ追加」とも無効化する

**この操作はサイトに実データを作る、取り返しのつかない操作である。** 一斉化にあたって
確認の厳しさを緩めない。

## 6. 機能G: 選択が残ったまま一覧が入れ替わる問題

### 6.1 件数の規則を統一する（§3.2の不具合への対処）

**「表示中の選択」だけを数え、処理する**に統一する。

- `BulkActionBar` の件数は、`selectedTilcods` のうち**現在の表示行に含まれるものの数**にする
- 実行対象も同じ集合（現行の `cartAdditionCandidates` と同じ規則）

これにより「見えているものが対象」という一貫した理解になり、バーの件数と実際の処理件数が
食い違わなくなる。絞り込みで隠れた選択は数から外れるが、**語を戻せば再び数に入る**（可逆）。

### 6.2 不可逆な入れ替えの前に警告する

次の操作を行おうとしたとき、**表示中でない分も含めた選択が1件以上あれば**確認ダイアログを出す。

| 画面 | 対象操作 |
|---|---|
| 蔵書検索 | 新しいキーワードでの検索実行 |
| 新着資料 | 巡回（`refresh()`。「更新」操作） |

- 文言: 「チェックした資料はまだ予約されていません。<操作>を行うと選択は解除されます。よろしいですか？」
- ボタン: 「続ける」「戻る」
- ダイアログ内に**「今後は表示しない」**を置く。押した場合は設定をオフにしたうえで続行する
- 「続ける」で選択をすべて解除し、本来の操作を実行する
- 「戻る」で何もしない（検索も巡回も実行しない）

**画面表示時の自動巡回（`onScreenLaunched`）は対象外**とする。画面を開いた直後で選択が空のため、
ダイアログを出す意味が無く、開くたびに邪魔になる。

**`loadMore` は対象外**（行が消えないため）。

### 6.3 設定

`AppSettings` に1つ追加する。

```kotlin
/** 一斉操作の選択が解除される前に確認ダイアログを出すか。既定はオン。 */
val warnBeforeClearingSelection: Boolean = DEFAULT_WARN_BEFORE_CLEARING_SELECTION  // true
```

- DataStoreキー: `warn_before_clearing_selection`
- **蔵書検索・新着資料で共通の1設定**とする（所有者確定事項4）
- 設定画面に切り替えを1つ足す。文言案:「選択が解除される前に確認する」、
  補足「蔵書検索や新着資料でチェックしたまま検索・更新するとき確認します」
- **バックアップの写像には加えない**（`BackupPayload` / `BackupExporter` / `BackupImporter` を変更しない）。
  端末ごとのUIの好みであり、バックアップ形式を変える価値が無い。インポート時は既定値（オン）になる

## 7. テスト計画

**方針**: 決定論的なテストのみ（`docs/design/bulk-selection.md` §8 と同じ）。

### 7.1 Repository

- `reserveNow(List)`: 複数件を渡すと `execute` に同じ並びで渡り、件ごとの結果が返る
- `reserveNow(List)`: 空リストの扱いを実装時に決め、テストで固定する（§9-2）
- `reserveNow(List)`: `cartItemId` を持つ target を含む場合は例外（既存 `validateTarget` の契約）

### 7.2 UiController / ContentBuilder

- 表示中の選択件数を返す純関数（§6.1）: 選択が表示行に含まれる場合・含まれない場合
- 警告ダイアログの発火条件: 選択が0件なら出ない、設定がオフなら出ない、両方満たすときだけ出る
- 「続ける」で選択が空になり、本来の操作が実行されること
- 「戻る」で選択が残り、本来の操作が実行されないこと
- 「今後は表示しない」で設定がオフになり、かつ本来の操作が実行されること
- Fの確認ダイアログ: メンバー未選択・受取館未選択では実行できないこと

### 7.3 手動確認（CIのAPKで実機）

1. E: 貸出中の「延長可能」ラベルが予約中の状態表示と揃って見えること
2. F: 検索・新着それぞれから複数選択して直接予約し、サイトに予約が入ること（**実データを作る操作**）
3. F: 受取館・メンバーが確認ダイアログの表示どおりに反映されること
4. G: 選択したまま再検索して警告が出ること、「戻る」で検索されないこと
5. G: 「今後は表示しない」の後は警告が出ないこと、設定画面から戻せること
6. G: 絞り込みで隠れた選択がバーの件数から外れ、語を戻すと戻ること

## 8. 実装順序

1. **E**（最小・UIのみ）
2. **G の §6.1**（件数規則の統一）— 警告より先に、ずれ自体を直す
3. **G の §6.2・§6.3**（警告ダイアログと設定）
4. **F**（唯一の通信あり・取り返しがつかない操作のため最後）

各段階でコミットし、CIを通してから次へ進む。

## 9. 未解決・既知のリスク

1. **一斉直接予約は予約上限・利用制限に触れうる**。サイトの応答は未検証である
   （`docs/handoff.md`）。5件選んで3件目で上限に達した場合の応答は分からない。
   件ごとの結果で「一部成功」を表現できる設計にはなっているが、**上限到達時の応答文言は未実測**である
2. **【決定・実装済み】`reserveNow(emptyList())` の扱い**: 通常の`execute`フローにそのまま渡す。
   対象が無いため`execute`内のグループ化も空になり、`ReservationGateway`への通信は一切発生せず
   `ReservationBatchResult(emptyList())`を返す。`validateConfirmation`(受取館コード・最終確認時刻の
   妥当性検証)は対象の有無に関わらず行う(確認情報自体の整合性は対象件数と独立した契約であるため)。
   UI経由では0件で「予約する」を実行できないため実際には到達しないが、契約として
   `ReservationCartRepositoryTest`に固定した(`ReservationCartRepositoryImpl.reserveNow(List)`参照)。
3. §6.1 の件数規則の変更は、**既存の一斉カート追加の見え方を変える**（バーの数字が減る場合がある）。
   これは不具合の修正であり意図した変更だが、所有者が「数が減った」と感じる可能性がある
4. `warnBeforeClearingSelection` をバックアップに含めないため、機種変更時は既定（オン）に戻る。
   意図した挙動である
