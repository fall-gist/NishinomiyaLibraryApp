# 本棚編集機能 技術設計

最終更新: 2026-08-09
状態: **段階3（Repository・共有ゲート・即時反映）実装完了・段階4未着手**
機能要件の正本: `docs/spec.md` §3.13
実サイト通信の正本: `docs/site-research.md` §13

## 1. 目的とスコープ

次の6操作を、既存の家族メンバー、書誌詳細、本棚表示、Room同期へ整合させて実装する。

1. 書誌詳細から資料を本棚へ追加（任意メモ付き）
2. 本棚から資料を1件削除
3. 資料メモを編集
4. 本棚を新規作成
5. 本棚名を変更
6. 本棚を削除

全操作は利用者の明示操作とアプリ内の最終確認からだけ開始する。バックグラウンド同期や画面表示を
契機に書込みを行わない。状態変更POSTは各段階につき1回だけ送信し、POST開始後に成否を確認できない
場合は再送せず`Unknown`とする。サイトのalert文言は成否判定に使わず、操作後に取得した本棚全体と
操作前状態を比較して判定する。

本設計に含めないものは、本棚メモの編集、資料の並べ替え、資料の全削除、既定本棚の保存である。
サイトフォーム上に該当項目が存在しても、今回のUIからは操作させない。ただし既存値とDOM順を必ず
保持して送信し、意図しない変更を防ぐ。

## 2. 確認済みの通信契約

| 操作 | 1段階目 | 段階 | 確認コード |
|---|---|---:|---|
| 本棚作成 | `WOpacSdiBookListExecAction.do` | 2 | `OPACSDI017` |
| 資料追加 | `WOpacTifDetailAddBookListAction.do` | 1 | なし |
| 本棚更新（名称・資料メモ） | `WOpacSdiBookListUpdateAction.do?` | 2 | `OPACSDI011` |
| 資料削除 | `WOpacSdiBookDelAction.do?flg=1` | 2 | `OPACSDI033` |
| 本棚削除 | `WOpacSdiBookListDelAction.do?delflg=1` | 2 | `OPACSDI010` |

- 2段階目は1段階目と同じactionからクエリを除いたURLへ、応答中の`prevRequestForm`の
  successful controlsをDOM順・重複込みで送り、末尾に実ページから抽出した名前の`okCodes`を追加する。
- 資料追加は1段階で完了する。応答中に`prevRequestForm`があっても2段階とは判定しない。
  `createConfirmDialog`の厳密な構造と操作種別の固定段階数を検証する。
- 追加フォームは書誌詳細の`LBForm`全29項目を送る。`booklist`だけを対象本棚番号、`commnt`を
  利用者入力（未入力なら明示的な空文字）へ置換する。`0`、`998`、末尾空白付き`999 `は送信先にしない。
- 更新・資料削除は編集画面の`LBForm`全体を送る。資料1件につき
  `bookcmnt, eachcmnt, sortno, eachsortno`がDOM順に繰り返され、資料IDは行ごとに存在しない。
- 本棚削除は一覧画面の`LBForm`全6項目を送る。
- 成功alert、失敗alert、`prevRequestForm`の存在だけでは成否を判断しない。

## 3. 採用方針と代替案

### 3.1 採用: 本棚専用のRepositoryとGateway

`BookshelfRepository`と`BookshelfGateway`を新設し、認証、送信直前の対象固定、フォーム解析、
1回限りのPOST、再取得照合、Room反映を本棚機能内に閉じ込める。予約用の
`ReservationOperationGate`には参加しない。本棚編集は予約データを変更せず、予約書込み世代の意味を
持たないためである。

代替案として既存`LibraryGateway`へ書込みメソッドを追加する案は不採用とする。現在の
`LibraryGateway.fetchUserData`は読取りと全体同期の境界であり、書込みの段階数、Exactly-once、
`Unknown`を混ぜると安全条件が読みにくくなる。

### 3.2 採用: 通常同期と編集で共有する本棚状態ゲート

`BookshelfStateGate`（Singletonの`Mutex`）を新設し、次の区間を直列化する。

- `StatusRepositoryImpl.syncMember`: 当該メンバーのサイト取得開始から`replaceMemberSnapshot`完了まで
- `BookshelfRepositoryImpl`: 認証前から操作後スナップショットのRoom反映完了まで

ロック順は常に次の順に固定する。

`StatusRepository.syncMutex`（同期だけ）→ `BookshelfStateGate` → `LicsXpSession`のrequest limiter → Room

これにより、編集成功後に、編集前から走っていた同期が古い本棚スナップショットをRoomへ書き戻す競合を
防ぐ。家庭内の少数メンバー用途であり、全メンバー共通の単一ゲートによる待ち時間増加を、単純で検証可能な
整合性のために受け入れる。

代替案の「編集成功後にRoomを更新せず次回同期を待つ」は、成功直後の表示要件を満たさないため不採用。
世代番号で古い本棚部分だけを捨てる案は、現行の`replaceMemberSnapshot`が全データを一括置換するため
変更範囲と分岐が大きく、不採用とする。

### 3.3 採用: サイト全体再取得による成否判定と即時反映

各操作のGatewayは同じ排他リクエスト列の中で、操作前と操作後の全本棚・全資料を取得する。成功を
証明できた場合、その操作後スナップショットをRepositoryがRoomへ原子的に全置換する。これにより空の
本棚、サイトが正規化した名称・メモ、作成時に採番された本棚番号を推測せず反映できる。

代替案の「操作対象行だけRoomへ差分更新」は、作成本棚番号、サイト側の文字列正規化、他端末との競合を
ローカルで推定する必要があるため不採用とする。

## 4. ドメイン契約

### 4.1 表示モデル

空の本棚を表示できるよう、本棚を資料から逆算しない。

```kotlin
data class BookshelfContent(
    val memberId: Long,
    val shelfNo: Int,
    val name: String,
    val items: List<ShelfItem>,
)
```

`BookshelfRepository.observeShelves(memberId)`は`ShelfDao.observeForMember`と
`ShelfItemDao.observeForMember`を`combine`し、本棚番号順で`BookshelfContent`を返す。資料ゼロ件の
`ShelfEntity`も空の`items`として残す。現行`StatusRepository.shelf(memberId)`は移行中の互換性のため
残し、本棚画面だけを新APIへ移す。別機能の一括改修は行わない。

### 4.2 操作要求

```kotlin
sealed interface BookshelfMutation {
    data class AddItem(val memberId: Long, val shelfNo: Int, val tilcod: String, val memo: String) : BookshelfMutation
    data class DeleteItem(val memberId: Long, val shelfNo: Int, val tilcod: String) : BookshelfMutation
    data class UpdateItemMemo(val memberId: Long, val shelfNo: Int, val tilcod: String, val memo: String) : BookshelfMutation
    data class CreateShelf(val memberId: Long, val name: String) : BookshelfMutation
    data class RenameShelf(val memberId: Long, val shelfNo: Int, val name: String) : BookshelfMutation
    data class DeleteShelf(val memberId: Long, val shelfNo: Int) : BookshelfMutation
}
```

UI表示用の書名、本棚名、資料件数は確認文言にだけ使い、Repositoryの対象固定には使わない。書込み対象は
`memberId + shelfNo`、資料操作ではさらに`tilcod`で固定する。

### 4.3 結果型

```kotlin
sealed interface BookshelfMutationOutcome {
    data class Applied(val localRefreshRequired: Boolean = false) : BookshelfMutationOutcome
    data class AlreadyRegistered(val localRefreshRequired: Boolean = false) : BookshelfMutationOutcome
    data object Unknown : BookshelfMutationOutcome
    data class Failure(val reason: FailureReason) : BookshelfMutationOutcome
}
```

- `Applied`: 操作後の再取得で目的状態を確認済み。
- `AlreadyRegistered`: 追加だけで使用。操作前に同一`tilcod`が対象本棚に存在した、またはPOST後に
  存在を確認したが新規追加と証明できなかった場合。利用者にはエラーではなく「登録済み」と表示する。
- `Unknown`: 状態変更POST開始後に目的状態を証明できない。自動再送しない。
- `Failure`: POST開始前の認証・通信・フォーム不一致、または状態変更POSTを送っていないことが確実な
  経路での失敗。
- `localRefreshRequired`: サイト側成功は確認したがRoom全置換だけに失敗した場合。成功を失敗へ
  読み替えず、表示更新のため手動同期を案内する。

`FailureReason`は既存値を再利用する。入力不正はUIとRepository入口で送信前に拒否し、例外ではなく
`SITE_RESPONSE_CHANGED`へ潰さず、Kotlinの`require`を公開API境界に置かない。UIへ返す入力エラーは
Controllerの状態として扱う。

## 5. Gateway設計

### 5.1 セッション境界

```kotlin
interface BookshelfGateway {
    suspend fun openAuthenticatedSession(cardNumber: String, password: String): BookshelfSession
}

interface BookshelfSession {
    suspend fun mutate(mutation: RemoteBookshelfMutation): RemoteBookshelfOutcome
    fun close()
}
```

メンバーごとに`rootSession.newIsolatedSession()`でCookieを隔離する。`mutate`全体を
`withExclusiveRequestSequence`で囲み、操作前スナップショット取得、編集画面遷移、POST、操作後
スナップショット取得の間に同じセッションの別リクエストを混ぜない。

Gatewayへ`memberId`や表示名は渡さず、認証済みセッションとサイト識別子だけを扱わせる。認証情報、
`hash`、フォーム値、HTML本文を通常ログへ出さない。診断を追加する場合も件数・画面分類・固定enumのみとし、
メモ、書名、カード番号、トークンを記録しない。

### 5.2 フォームパーサ

次を別型として実装する。操作ごとの安全条件が読めることを優先し、予約系パーサの型は流用しない。

- `BookshelfDetailAddFormParser`: 詳細`LBForm`を一意に特定し、全29項目の名前・順序・重複を検証する。
- `BookshelfCreateFormParser`: 新規作成フォームを解析し、`listname`と`commnt`だけを置換する。
- `BookshelfEditFormParser`: 編集フォーム、本棚番号・名称・本棚メモ、資料行の反復4項目を解析する。
- `BookshelfDeleteFormParser`: 一覧フォーム全6項目と対象`otherbook`を検証する。
- `BookshelfConfirmationFormParser`: `prevRequestForm`、`OK_CODES_NAME`、`createConfirmDialog`、actionを
  fail-closedで解析し、操作種別ごとの確認コードとクエリ無しactionを照合する。

全パーサは以下を満たさない場合にPOST前停止する。

- 対象formがちょうど1件である。
- 必須controlの個数、型、DOM順が実測契約と一致する。
- 同名controlの重複をリストのまま保持する。
- `hash`、`gamenid`、`returnid`、`otherbook`、`tilcod`が現在画面・要求対象と一致する。
- actionが同一originかつ許可した固定pathである。リダイレクト先やページ内の任意URLを採用しない。
- 2段階操作では、1段階目のqueryとbodyを合わせた多重集合が`prevRequestForm`と一致する。
- `OK_CODES_NAME`と確認コード候補が一意で、操作ごとの固定値と一致する。

### 5.3 DOM順でしか識別できない資料行

メモ更新と資料削除では、次の処理を一つの`withExclusiveRequestSequence`内で行う。

1. 対象本棚の表示ページを取得し、`ShelfParser`で資料をDOM順に得る。
2. `tilcod`一致行がちょうど1件であることを確認し、その位置を固定する。
3. 同じ表示ページの「このリストの編集」導線から編集ページへ遷移する。
4. `BookshelfEditFormParser`の反復行数が表示ページの資料数と一致することを確認する。
5. メモ更新は対象位置の`eachcmnt`だけを置換する。`bookcmnt`、全`sortno`、全`eachsortno`、
   他資料の`eachcmnt`、本棚の`listname`・`commnt`・`disp_chk`をそのまま保持する。
6. 資料削除はサイト実測どおりhidden `tilcod`へ対象を設定し、反復行を含むフォーム全体を保持する。

Roomの表示順や登録日から送信順を復元してはならない。送信する全行は必ず直前のサイトDOMから作る。
同一`tilcod`が対象本棚に複数ある、行数が合わない、途中で本棚番号が変わる場合は送信しない。

### 5.4 操作別シーケンス

共通の前処理は、ログイン、本棚全体の操作前取得、対象本棚の存在確認である。

#### 資料追加

1. 操作前に対象本棚へ同一`tilcod`があれば、POSTせず`AlreadyRegistered`。
2. 予約確定でも実測済みの`WOpacMsgNewListToTifTilDetailAction.do?urlNotFlag=1&tilcod=...`から
   認証済みの通常書誌詳細を取得し、要求`tilcod`と詳細フォームの`tilcod`を一致確認する。
3. `booklist`を対象本棚番号、`commnt`を入力値（空なら`""`）へ置換し、1段階POSTを1回送る。
4. 全本棚を再取得し、対象本棚に同一`tilcod`が1件存在することを確認する。
5. 新規出現しメモが正規化後一致すれば`Applied`。存在するが新規追加と証明できなければ
   `AlreadyRegistered`。存在しない、または再取得不能なら`Unknown`。

#### 本棚作成

1. 新規作成画面を取得し、名称を置換、本棚メモは空文字で送る。
2. `OPACSDI017`の二段階POSTを送る。
3. 操作前後の本棚番号集合の差がちょうど1件で、その本棚名が要求値なら`Applied`。
4. 差が0件または複数なら、POST後のため`Unknown`。UIは同一名称を入力時点で拒否しない
   （サイトが同名棚を許すか未確認であり、名前を識別子にしない）。

#### 本棚名変更

1. 対象本棚の編集フォームを取得し、`listname`だけを置換する。
2. `OPACSDI011`の二段階POSTを送る。
3. 同じ本棚番号が残り、名称が要求値へ変わり、資料集合が保たれていれば`Applied`。

#### 資料メモ更新

1. §5.3どおり対象位置の`eachcmnt`だけを置換する。
2. `OPACSDI011`の二段階POSTを送る。
3. 対象`memberId + shelfNo + tilcod`のメモが要求値へ変わり、他の資料集合が保たれていれば
   `Applied`。

#### 資料削除

1. §5.3どおり対象を一意固定し、hidden `tilcod`だけを対象へ設定する。
2. `OPACSDI033`の二段階POSTを送る。
3. 対象本棚から対象`tilcod`だけが消え、他の資料集合が保たれていれば`Applied`。
4. 操作前に対象が無い場合は状態変更POSTを送らず`Failure(SITE_RESPONSE_CHANGED)`。

#### 本棚削除

1. 一覧フォームの`otherbook`が対象本棚番号と一致することを確認する。
2. `OPACSDI010`の二段階POSTを送る。
3. 対象本棚番号が消え、他の本棚と資料集合が保たれていれば`Applied`。
4. 操作前に対象が無い場合は状態変更POSTを送らず`Failure(SITE_RESPONSE_CHANGED)`。

### 5.5 POST境界と成否裁定

- `postExactlyOnce`を使い、OkHttpの自動再試行に依存しない。
- 2段階操作の1段階目は実測上副作用を持たない。ただし1段階目応答の解析に失敗した場合も、念のため
  本棚を再取得する。目的状態なら`Applied`、完全に不変なら`Failure(SITE_RESPONSE_CHANGED)`、
  比較不能なら`Unknown`。
- 2段階目、または資料追加の1段階POSTを開始した後は、通信エラー、セッション切れ、メンテナンス、
  応答解析失敗があっても再送しない。再取得で目的状態を証明できた場合だけ成功とし、それ以外は
  `Unknown`とする。
- POST前の通信失敗は`Failure(NETWORK)`、認証失敗は`Failure(AUTH)`、メンテナンスは
  `Failure(SITE_MAINTENANCE)`、フォーム契約差異は`Failure(SITE_RESPONSE_CHANGED)`。
- 改行は比較時だけCRLFとLFをLFへ正規化する。前後空白を勝手にtrimせず、サイト表示パーサが行う
  HTML正規化以上の同一視をしない。

## 6. Roomと通常同期

### 6.1 原子的な本棚スナップショット置換

`AppDatabase.replaceShelfSnapshot(memberId, shelves, shelfItems)`を追加する。

1. 全Entityの`memberId`一致、本棚番号の一意性、全資料の親本棚存在をトランザクション前に検証する。
2. トランザクション内で対象メンバーの資料→本棚の順に削除する。
3. 本棚→資料の順に挿入する。
4. 既存`user_summaries`行があれば`shelfCount = shelves.size`だけを更新する。サマリが無い場合は
   他件数を推測した新規行を作らない。

DB schemaは変更せずRoom version 9を維持できる。Gatewayの送信用情報（本棚メモ、DOM位置、sortno、
hash）は短命な通信モデルに閉じ込め、Roomへ保存しない。

### 6.2 ローカル反映規則

- `Applied`と`AlreadyRegistered`で操作後の完全スナップショットがある場合だけRoomへ置換する。
- `Unknown`と`Failure`ではRoomを書き換えない。
- Room置換失敗はリモート結果を変えず、`localRefreshRequired=true`として返す。
- 本棚画面はRoomのFlowを観測するため、置換成功後は追加・名称・メモ・削除・空棚が即時反映される。

## 7. UI設計

### 7.1 Controller分離

`BookshelfScreenController`は本棚表示とメンバーフィルタを担当し続ける。書込みは新設する
`BookshelfEditingUiController`へ集約し、書誌詳細と本棚画面の双方から同じ状態・Repositoryを使う。
`BookDetailController`へ本棚通信を混ぜない。

編集Controllerは、開いているダイアログ、入力値、最終確認対象、送信中操作、結果通知をStateFlowで持つ。
同時に送れる操作は1件とし、送信中はすべての本棚編集入口を無効化する。画面回転後もPOSTを重ねないよう、
ControllerのCoroutineScopeをActivity内の既存Controllerと同じ寿命で保持する。

### 7.2 資料追加

書誌詳細に「本棚へ追加」ボタンを常時表示する（`tilcod`が有効でメンバーが存在する場合）。押下時に
モーダルを開き、次を毎回選ばせる。

1. メンバー（初期値なし）
2. 選択したメンバーの本棚（初期値なし）
3. 任意メモ（空文字を許可）

メンバー変更時は本棚選択を必ず解除する。前回のメンバー・本棚を既定値として記憶しない。本棚が0件なら
追加確定を無効化し、「先に本棚を作成してください」と表示する。入力内容を示す最終確認ダイアログで
「この本棚に追加」を押したときだけRepositoryを呼ぶ。

### 7.3 本棚画面の編集入口

- 画面上部に「本棚を作成」アクションを置く。「みんな」表示中でも作成ダイアログ内でメンバーを選ぶ。
- 各本棚ヘッダーのオーバーフローメニューに「名前を変更」「本棚を削除」を置く。
- 各資料カードのオーバーフローメニューに「メモを編集」「本棚から削除」を置く。カード本体のタップは
  従来どおり書誌詳細を開く。
- 空の本棚も列として表示し、列内に「登録資料はありません」を表示する。

本棚削除は他操作より重い確認とし、本文に正確な本棚名と消える資料件数を表示する。確定ボタンも
「本棚とN件を削除」とし、破壊色を使う。名称入力による再確認は要求されていないため採用しない。
資料削除は書名と本棚名を示す通常の破壊確認とする。

### 7.4 入力制約とメッセージ

- 本棚名: 空白だけを不可、最大50文字。送信値の前後空白は勝手に削除せず、空白だけかの判定にのみ
  `isBlank`を使う。
- 資料メモ: サイトの編集フォームに合わせ最大1000文字。
- 追加メモ: 実サイトで`maxlength`未確認のため、初回実装では資料メモと共通の1000文字上限を暫定採用する。
  Gatewayがサイトフォームからより短い`maxlength`を取得した場合はその要求を送信前に拒否し、
  実サイト確認後にUI上限を確認値へ固定する。
- 本棚メモ: UIに公開せず、既存値を完全保持する。作成時は空文字。

結果表示は次の意味を崩さない。

- `Applied`: 「本棚へ反映しました」
- `AlreadyRegistered`: 「この資料はすでに選択した本棚に登録されています」
- `Unknown`: 「処理結果を確認できません。自動では再送しません。本棚を更新して確認してください」
- `Failure`: 原因別の既存日本語表現。`SITE_RESPONSE_CHANGED`はサイト変更の可能性を示し、再試行を促さない。
- `localRefreshRequired`: 成功文に「表示更新に失敗しました。画面を更新してください」を付記する。

## 8. セキュリティ・障害・境界条件

- 認証情報は既存`CredentialStore`から操作直前に取得し、UI state、Room、ログへ複製しない。
- `hash`等の短命トークンは認証セッションとパーサ内だけで保持する。
- actionは同一origin・固定allowlist・HTTPS相当の既存base URL制約を通す。
- 対象本棚が削除済み、名称変更済み、資料が移動・削除済みなら送信前に停止する。
- 同一`tilcod`が対象本棚内で複数なら一意に操作できないため送信しない。
- アプリ終了・Coroutine cancellationがPOST開始後に発生しても再送しない。次回表示は通常同期で実サイトを正本に戻す。
- `Unknown`を成功・失敗へ推測変換しない。利用者の再操作も自動化せず、再取得後の明示判断に委ねる。
- 既存本棚メモ、既定表示チェック、資料順序を今回のUIが変更しないことを回帰条件とする。

## 9. テスト戦略

### 9.1 パーサ単体テスト

実測HTMLを機密情報除去済みfixtureとして追加し、各正常系に加えて次を検証する。

- form欠落・複数、control欠落・余分・順序変更・重複数変更
- actionの別origin・別path・クエリ残存
- `createConfirmDialog`欠落・複数・コメント/文字列内の偽署名
- 確認コード不一致（5操作を相互に入れ替えた場合も拒否）
- `booklist`の`0`、`998`、`999 `拒否
- 資料0件、1件、複数件で反復4項目を保持
- 改行、空メモ、50文字の本棚名、1000文字の資料メモ

### 9.2 Gatewayテスト

`MockWebServer`で要求path、query、POST回数、フォームのDOM順・重複を検証する。

- 6操作の正常系と操作後比較
- 追加済みはPOST 0回で`AlreadyRegistered`
- 一段階追加はPOST 1回、他操作は正常時POST 2回
- stage1解析失敗ではstage2を送らない
- stage2または追加POSTの通信断後に再送0回
- POST後の再取得で成功を証明、変化なし、部分変化、再取得不能
- 対象不在・複数・本棚切替不一致・DOM行数不一致は状態変更POST 0回
- 他の本棚、他の資料、順序、本棚メモが意図せず変化した場合は成功にしない

### 9.3 Repository・排他・Roomテスト

- 認証・Parse・Maintenance・Networkの結果変換
- `Applied`/`AlreadyRegistered`だけがRoomを置換し、`Unknown`/`Failure`は不変
- 空棚を含む全置換、棚削除、サマリ`shelfCount`更新、トランザクション失敗時の原子性
- 通常同期中の編集と編集中の通常同期が`BookshelfStateGate`で直列化され、古いスナップショットが
  成功後へ上書きされない
- ロック順に逆転がなく、キャンセル後にゲートが解放される
- Room失敗時にリモート成功を`Failure`へ変えず`localRefreshRequired=true`

### 9.4 UI・純関数テスト

- 空棚を含む列構築、メンバー順・本棚番号順
- 追加ダイアログは毎回メンバー・本棚未選択、メンバー変更で本棚解除
- 送信中の二重タップ防止
- 6操作の確認対象とRepository要求の対応
- 本棚削除確認に正確な名称・件数、資料削除確認に書名・本棚名
- `AlreadyRegistered`、`Unknown`、`localRefreshRequired`の表示
- 既存のカードタップによる書誌詳細遷移、メンバーフィルタ、プル更新の回帰

### 9.5 実サイト確認

実装・単体テスト・独立レビュー後にのみ、専用テスト棚で1操作ずつ行う。操作前後の全本棚状態を記録し、
既存棚へ触れない。各状態変更POSTは1回だけとし、成否不明ならその場で再試行しない。

確認順は、作成→追加（空メモ）→追加（メモあり、別資料）→重複追加→メモ更新→名称変更→資料削除→
本棚削除とする。最後に本棚数と既存棚の名称・件数が開始時と一致することを確認する。

## 10. 実装分割

### 段階1: 読取りモデルとRoom反映基盤

**完了（2026-08-09）**: `BookshelfContent`と読取り専用`BookshelfRepository`、空棚を保持する
Flow結合、`replaceShelfSnapshot`、既存サマリの`shelfCount`更新、空棚列表示を実装した。
対象テストと全単体テストは成功し、5.6-Sol（low）の独立レビューで重大・中程度の指摘はなかった。

- `BookshelfContent`、`BookshelfRepository`観測API
- `ShelfDao`と`ShelfItemDao`のFlow結合
- `replaceShelfSnapshot`、`UserSummaryDao.updateShelfCount`
- 空棚を含む`BookshelfScreenController`表示

受入条件: 書込みなしで既存同期結果を同じ順序で表示し、空棚も表示できる。全既存単体テストが通る。

### 段階2: 通信パーサとGateway

**完了（2026-08-09）**: 6操作の専用Gateway、4種の送信フォームパーサ、確認フォームパーサ、
Exactly-once送信、操作前後の全本棚比較を実装した。実測済み共通`createConfirmDialog`のforループ構造を
縮約した合成fixtureとMockWebServerで、DOM順、確認コード、stage2抑止、通信断非再送、比較3分岐を検証した。
全単体テストは成功し、最終5.6-Sol（low）レビューで重大・中程度の指摘はなかった。実サイト通信は未実施。

- 4種の送信フォームパーサと確認フォームパーサ
- 本棚全体の前後取得、6操作、Exactly-once、比較裁定
- fixtureとMockWebServerテスト

受入条件: 全失敗系で規定POST回数を超えず、DOM順・重複・固定コードを検証できる。実サイトへはまだ送らない。

### 段階3: Repository、共有ゲート、即時反映

**完了（2026-08-09）**: 6種の公開mutation契約、`BookshelfStateGate`、認証・Gateway結果変換、
成功スナップショットのRoom即時反映を実装した。通常同期もサイト取得開始からRoom反映完了まで同じ
ゲートへ参加させ、同期→編集・編集→同期の競合を統合テストで検証した。全単体テストは成功し、
修正後の5.6-Sol（low）レビューで重大・中程度の指摘はなかった。実サイト通信は未実施。

- `BookshelfStateGate`
- `BookshelfRepositoryImpl`の認証・結果変換・Room置換
- `StatusRepositoryImpl.syncMember`の共有ゲート参加
- 排他・Room・結果変換テスト

受入条件: 同期競合テストで古い本棚状態へ戻らず、`Unknown`時にローカル状態を変更しない。

### 段階4: 本棚画面の編集UI

**完了（2026-08-09）**: `BookshelfEditingUiController`へ5操作の入力・最終確認・送信中排他・
結果表示を集約し、本棚画面、`LibraryApp`、`MainActivity`、DIへ配線した。本棚名・資料メモの
境界値、明示確認前の通信抑止、5操作のmutation対応、削除対象、二重送信防止、結果文言を単体テストし、
Android instrumented Composeテストでカード全体の詳細タップ、overflow非伝播、処理中入口無効化、
空棚表示を回帰条件化した。instrumentedテストはコンパイル成功、端末実行は未実施である。対象単体テストと
全単体テストは成功し、修正後の5.6-Sol（low）レビューで重大・中程度の指摘はなかった。実サイト通信は未実施。

- 作成、名称変更、メモ編集、資料削除、本棚削除
- 確認・処理中・結果表示
- ControllerとComposeテスト

受入条件: 明示確認なしのRepository呼出しがなく、本棚削除確認に名称・件数が表示される。

### 段階5: 書誌詳細の追加UI

**完了（2026-08-09）**: 共有`BookshelfEditingUiController`へ資料追加の入力・棚Flow購読・最終確認を
追加し、全画面とダイアログの書誌詳細へ同じ入口を配線した。毎回メンバー・本棚未選択、空メモから開始し、
メンバー切替時の棚解除、棚読込み中と0件の区別、最大長、確認中のメンバー・棚消失/名称変更、二重送信を
単体テストした。Android instrumented Composeテストで追加入口、処理中/メンバーなしの無効化、入力確認の
無効条件、最終確認対象を回帰条件化し、テストはコンパイル成功、端末実行は未実施である。全単体テストは成功し、
修正後の5.6-Sol（low）レビューで重大・中程度の指摘はなかった。実サイト通信は未実施。

- 詳細画面の追加入口
- 毎回のメンバー・本棚選択、任意メモ、最終確認
- 全詳細表示形態（全画面・ダイアログ）の配線とテスト

受入条件: 前回選択を引き継がず、対象メンバー・本棚・資料が最終確認と要求で一致する。

### 段階6: 回帰・独立レビュー・実サイト確認

**状態（2026-08-09）**: 主担当監査、対象テスト、全単体テスト731件、`assembleDebug`、
`compileDebugAndroidTestKotlin`、秘密情報監査、5.6-Sol（low）最終再レビュー（重大・中程度の指摘なし）は完了。
独立レビューで発見した「状態変更POST開始後のcancelは`Unknown`とする決定的境界テスト」と、
「確認スナップショットを必須化し、実サイト`before`不一致時は状態変更POST 0回で停止する照合」を修正済み。
実サイトでの6操作・重複・`Unknown`非再送の確認、およびinstrumentedテストの端末実行は未実施のため、
段階6全体は未完了である。

- 主担当の受入条件対応表、`git diff --check`、対象テスト、全単体テスト、秘密情報監査
- 5.6-Sol（low）による独立コードレビュー1系統
- 重大・中程度の指摘を一括修正し、必要時だけ再レビュー
- 所有者ログイン済みセッションで§9.5の実サイト確認

受入条件: 既存棚の開始時状態を復元し、6操作、重複、Unknown非再送を確認して記録する。

## 11. 実装前チェックリスト（Terraへの初回作業票に含める）

### 正常系

- [ ] 6操作それぞれの対象固定、確認、POST回数、再取得比較が定義どおり
- [ ] 空棚、サイト採番、本棚名・メモのサイト正規化をスナップショットで反映
- [ ] `AlreadyRegistered`をエラー扱いしない

### 失敗系

- [ ] POST前の認証・通信・メンテナンス・Parseを`Failure`へ分類
- [ ] POST開始後の比較不能を`Unknown`とし、自動再送しない
- [ ] stage1不一致時にstage2を送らない
- [ ] Room失敗でリモート成功を失敗へ変えない

### 境界条件

- [ ] 資料0/1/複数、空メモ、最大長、同一`tilcod`、対象消失、フォーム重複
- [ ] sentinel本棚番号と末尾空白付き`999 `を拒否
- [ ] CRLF/LF以外の文字列変形を勝手に行わない

### 永続化

- [ ] 本棚・資料・サマリ件数を単一Room transactionで反映
- [ ] `Unknown`/`Failure`時はRoom無変更
- [ ] 空棚を内部結合で失わない

### 排他

- [ ] 通常同期と編集が`BookshelfStateGate`を共有
- [ ] ロック順が設計どおりで逆順取得なし
- [ ] Cookie分離と`withExclusiveRequestSequence`を維持

### セキュリティ

- [ ] カード番号、パスワード、hash、メモ、HTML本文をログへ出さない
- [ ] 同一origin・固定path以外へPOSTしない
- [ ] UI表示値ではなくサイト再取得値で送信対象を固定

### 回帰

- [ ] 既存同期、予約、取消、貸出延長、書誌詳細、プル更新に影響なし
- [ ] 既存本棚メモ、既定表示、資料順を変えない
- [ ] 全単体テストをコミット直前に1回実行

## 12. 設計時点の未検証事項

次は設計を止める疑問ではなく、fixture/MockWebServerと最終実サイト確認で検証する実装条件である。

- 追加フォームの`commnt`の実際の最大長。初回UIは資料メモと共通の1000文字を暫定値とし、
  フォームがより短い上限を示す要求は送信前に拒否する。
- 作成・更新後にサイトが名称・メモの前後空白を正規化するか。
- 他端末が全く同時に同じ本棚を変更した場合の応答。目的状態と非対象状態の双方が一致しない限り
  成功にしない。
- 本棚更新・資料削除の完了alert文言。成否判定に使わないため実装のブロッカーではない。

現時点で所有者判断を要する未決事項はない。
