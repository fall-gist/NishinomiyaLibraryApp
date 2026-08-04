# 貸出延長 技術設計

最終更新: 2026-08-04
状態: 設計中（所有者確認待ちの論点あり）
機能要件の正本: `docs/spec.md` §3.12（本設計と同時に追加）
一次情報: `docs/site-research.md` §10（所有者提供HARによる実測）

## 1. 目的と設計原則

貸出中の資料のうち、サイトが延長可能と示している資料について、利用者が貸出中一覧から明示操作で
返却期限を延ばせるようにする。既存の予約確定・予約取消と同じ「明示操作＋最終確認」「専用ゲートウェイへの
隔離」「送信は一回限り、成否不明は再送しない」という不変条件を踏襲する。

延長は取消のような「自動で後続処理を続ける」機能ではなく、予約直接確定・予約取消と同じ**単発の
明示操作**である。設計もその2つに倣う。

## 2. 実サイトについて確認済みの事実（`docs/site-research.md` §10 参照）

- 延長可能な資料にだけ「延長」ボタンが表示される（`extend(mngcod)`、`mngcod`は`tilcod`とは別の
  数値コード）。
- 二段階POSTである。1段階目`WOpacUsrLendListExtendAction.do?mngFlg1_handan=1`は貸出状況一覧の
  共通`LBForm`をそのまま送る（`para`に対象`mngcod`を設定）。応答は同じ`tiles.WUsrLendList`画面の
  再描画で、読込時に確認ダイアログを表示する。
- 2段階目は、1段階目応答内の`<form name="prevRequestForm">`（1段階目送信bodyとDOM順・重複数まで
  完全一致する隠しフィールド）へ`okCodes=OPACUSR005`を追加し、**明示的に**
  `action="/licsxp-opac/WOpacUsrLendListExtendAction.do"`（クエリ無し、絶対パス）を設定して送信する。
  予約非表示のような「action省略時は現在ドキュメントURL」という曖昧さは無い。
- 両段階とも`<div id="messages">`は空で、成功・失敗メッセージは得られない。
- 確認ダイアログの正確な文言、拒否時の応答構造、延長後に再度延長できるか、`para`の値が
  安定するかは**未確認**（HARが成功例1件のみ、かつ文字コード破損で日本語文言が読めないため）。

これらは予約取消・予約非表示で確立した「`prevRequestForm` + `OK_CODES_NAME` + 固定確認コード」という
共通の確認ダイアログ機構と同一パターンであり、確認GET・確定POSTの二段階排他区間、DOM順・重複込みの
successful controls送信、fail-closedなフォーム一意特定といった既存実装をそのまま踏襲できる。

## 3. 採用方針と代替案

### 採用: 予約取消と同型の「専用Gateway + 明示確認 + 二段階POST」

`LoanExtensionGateway`（仮称）を新設し、貸出状況一覧の取得→対象行の`mngcod`固定→二段階POST→
延長後の貸出状況一覧再取得による成否照合、を1つの操作境界とする。既存`ReservationGateway`とは
独立させる（貸出と予約はサイト側の別画面・別アクションであり、認証セッションの使い回し以外に
共有する状態がない）。

### 不採用: 予約取消の二段階実装をそのまま流用（`ReservationCancelConfirmationFormParser`の型を共用）

フィールド名（`para` vs `yoycod`）、確認コード（`OPACUSR005` vs 予約取消の値）、gamenid
（`tiles.WUsrLendList` vs `tiles.WYoyConfirm`系）が異なり、対象の一意性判定条件も別物
（予約は同一`tilcod`重複が実運用で起きるが、貸出は同一資料の同時貸出が実質起こらない）。
共通化すると条件分岐が増え、各アクションの安全条件を独立に読めなくなる。**構造的なパターン
（DOM順successful controls保持、`OK_CODES_NAME`抽出、fail-closed）は流用するが、型は分離する。**

### 不採用: サイトの確認ダイアログ文言をそのままアプリへ転記する

文言が未確認（文字コード破損）であることに加え、既存の予約系機能もサイトのJS/ダイアログを
実行・転記せず、アプリ独自の確認文言を表示する方針を一貫して採っている。今回も同様に、
アプリは対象資料名・現在の返却期限を示した独自の確認ダイアログを表示する。

## 4. 対象の固定とパース

### 4.1 `Loan`domain modelの拡張

```kotlin
data class Loan(
    ...
    val renewalCode: String = "",  // extend(mngcod) の mngcod。延長ボタンが無い行は空文字列
)
```

Room `LoanEntity`・DAO・DBバージョンは変更しない。`renewalCode`は`cancelCode`と同じ扱いとし、
**通信内部でだけ使い、Roomへ永続化しない**（延長可否は毎回の同期・取得で変わり得るため、
古い値を保存する意味がない。既存の`cancelCode`が同様の理由でRoom保存されているのとは事情が異なる
点に注意 — `cancelCode`は予約一覧画面と同じ通信経路で完結するため保存されているが、`renewalCode`は
延長専用の分離取得（後述）でのみ必要なため、`LoanListParser`の通常経路には出力せず、
延長専用のパーサ・スナップショットに閉じ込める）。

### 4.2 `LoanExtensionListParser`（新規）

貸出状況一覧HTMLから、各行の`tilcod`・返却期限日・`renewalCode`（`extend(mngcod)`のonclickから
正規表現で抽出）を持つ`LoanExtensionRow`のリストを返す。抽出は完全一致の
`extend\('(\d+)'\)`のみを許可し、空値・複数候補・未知構文はパース失敗として停止する
（`ReservationHideFormParser`の`hideCodeRegex`と同じ方針）。

`para`が延長以外の用途（`toDetail(tilcod)`）にも使われる共通フィールドであるため、対象行の
`LBForm`固定は、延長ボタンのonclick文字列から直接`mngcod`を取り出す方式とし、`LBForm`のPOST時点の
`para`値をこの`mngcod`で上書きする。

### 4.3 対象の一意性

延長対象は`tilcod`のみでは固定しない。同一`tilcod`が貸出状況一覧に複数行存在することは
通常運用では起こらない（同じ物理資料を同時に複数貸出することはできない）ため、予約のような
重複対応は不要と見込むが、**送信直前に取得した一覧で対象`tilcod`の行が1件でなければ送信前に停止する**
フェイルクローズは維持する。

## 5. 通信境界と成否照合

### 5.1 二段階POST

- 1段階目: `POST WOpacUsrLendListExtendAction.do?mngFlg1_handan=1`。貸出状況一覧の`LBForm`の
  successful controlsをDOM順・重複込みで送信し、`para`だけを対象`renewalCode`へ上書きする。
  既存の`BookDetailReservationFormParser`と同じ「ホワイトリストにしない・DOM順全送信」方針を踏襲する。
- 1段階目応答から`prevRequestForm`を一意に特定し、`gamenid=tiles.WUsrLendList`・
  `para==対象renewalCode`であることを確認する。
- `OK_CODES_NAME`変数と確認コード値（実測`OPACUSR005`）は、予約取消の`OK_CODES_NAME`抽出と同じ
  字句走査で都度抽出する。値をハードコードせず、抽出できない場合は送信前に停止する
  （`docs/handoff.md`の「未確認事項を推測で補完して実サイトへPOSTしてはならない」を踏襲）。
- 2段階目: `prevRequestForm`のDOM順controls＋末尾に`OK_CODES_NAME`＝抽出した確認コードを追加して
  送信する。actionは`prevRequestForm`側の明示指定（`WOpacUsrLendListExtendAction.do`、クエリ無し、
  固定origin）だけを許可し、それ以外のactionが指定されていた場合は送信前に停止する。
- 各POSTは自動再試行しない`noRetryClient`を使い、既存のリクエスト間500ms制御を維持する。

### 5.2 成否照合

`<div id="messages">`が空でメッセージに頼れないため、次の2つを組み合わせて判定する。

1. **主判定**: 送信直前に取得した対象行の返却期限日と、送信後に再取得した同じ`tilcod`行の
   返却期限日を比較する。**日付が送信前より後へ変化していれば`Extended`**とする。
2. **補助情報**: 送信後の対象行に延長ボタンが残っているか（`renewalCode`の有無）を記録する。
   判定には使わないが、延長後に再度延長可能かの実態が未確認であるため、診断・履歴として残す。

送信後に対象`tilcod`の行が1件でない（消えた、増えた）場合や、返却期限日を解析できない場合は
`Unknown`とする。明示的な拒否応答の構造が未確認であるため、**初期実装では`NotExtended(reason)`の
ような詳細な拒否理由は設けず、`Extended`と`Unknown`の2値**とする。拒否時の応答が実測でき次第、
`FailureReason`を追加する（新着自動予約の`RESERVATION_LIMIT_EXCEEDED`が事後追加されたのと同じ順序）。

POST後不明は自動再送しない。同一操作内での再試行もしない。

## 6. 結果モデルとUI

```kotlin
sealed interface LoanExtensionOutcome {
    data class Extended(val newDueDate: LocalDate) : LoanExtensionOutcome
    data object Unknown : LoanExtensionOutcome
    // POST前に確定した失敗（対象不在、フォーム不一致、認証失敗等）は既存のFailureReason系を再利用する
    data class Failure(val reason: FailureReason) : LoanExtensionOutcome
}
```

### UI（案。§9で所有者確認を求める点あり）

- 貸出中一覧の各行のうち、`renewalCode`が取得できた資料にだけ「延長」ボタンを表示する。
- タップでアプリ独自の確認ダイアログ（資料名・現在の返却期限・「延長しますか？」）を表示する。
  サイトの確認文言は転記しない（文言未確認のため、かつ既存方針との整合のため）。
- 確定後、結果を次のように表示する。
  - `Extended`: 「返却期限を延長しました（新しい期限: yyyy/MM/dd）」
  - `Unknown`: 「延長できたか確認できません。しばらくしてから貸出状況をご確認ください」
  - `Failure`: 既存の予約系と同じ文言方針（認証失敗、通信失敗等）
- 一斉延長・複数選択は設けない（1件ずつの明示操作。予約の「一斉取消」に相当する機能は今回スコープ外）。
- 書誌詳細オーバーレイからの延長導線は設けない（貸出中一覧からの1経路のみ。予約取消が複数経路を
  持つのは後から追加されたためであり、初期スコープは最小にする）。

## 7. 失敗・境界・排他・永続化

- 貸出状況一覧取得、対象固定、二段階POST、延長後照合までを1つの排他区間とし、既存の
  `RequestRateLimiter`/500ms間隔を維持する。
- **共通書込ゲート（`ReservationOperationGate`）には参加させない。** 貸出延長は予約データに触れず、
  手動予約・自動予約・予約取消との間に業務上の競合が無い。参加させると、無関係な自動予約の
  実行中に延長操作が待たされる新たなUX劣化を生む。貸出延長専用の軽量な`submissionMutex`
  （二重タップ防止のみが目的）を設ける。
- POST前に確定した認証・通信・パース失敗は即座に失敗として終了する（再試行しない）。
- 再起動後の再開・保留状態の永続化は行わない（単発操作であり、`docs/design/reservation-cancel-auto-hide.md`
  の非表示処理と同じ理由でRoomへ状態を残さない）。

## 8. セキュリティ・診断条件

- カード番号、パスワード、Cookie、`hash`実値、対象コード（`mngcod`/`para`）実値、資料名、HTML本文を
  ログへ出さない。
- 診断はPOST回数、段階名、対象一意性の成否、返却期限の変化有無（値そのものではなく「変化した/しない」）、
  一覧完全性だけを記録する。
- 固定origin・許可path（`WOpacUsrLendListExtendAction.do`、クエリ有無ごとに区別）だけを許可する。

## 9. 所有者確認が必要な論点

設計を実装に進める前に、次を確認したい。

1. **UI導線はこの案（貸出中一覧の各行、1件ずつ、他経路なし）でよいか。** 予約取消は後から
   複数経路・一斉取消が追加された経緯があるため、最初から複数経路を求めるなら設計を広げる。
2. **拒否理由を`Failure`ではなく暫定的に`Unknown`へ丸める初期実装でよいか。** 拒否時の実サイト応答が
   未実測のため、区別を持たせると未検証の推測実装になる。区別が必要な場合は、拒否例のHAR/診断を
   先に採取する。
3. **`ReservationOperationGate`へ参加させない判断でよいか。** 現状、貸出と予約データは独立しており
   競合が無いと判断したが、将来「貸出延長も自動化する」構想が既にある場合はゲート統一を検討したほうが
   よい可能性がある。

## 10. 実装時のテスト計画

- パーサ: `renewalCode`の正常抽出、0件、重複、未知構文、行と`tilcod`の対応。
- フォーム: 1段階目LBFormのDOM順・重複込み送信、`para`だけの上書き、2段階目`prevRequestForm`の
  DOM順保持、`OK_CODES_NAME`抽出、確認コード追加、action検証（固定origin・固定path・クエリ無し）。
- 通信: 実測どおりのpath/query/body/Referer/Origin、POST 2回、no-retry、500ms制御。
- 成否: 返却期限が進んだ場合の`Extended`、対象消失・複数化・解析不能時の`Unknown`、
  POST前失敗の`Failure`。
- 除外: 延長ボタンの無い行に対して延長操作を起動できないこと。
- セキュリティ: 診断ログに認証情報・個人情報・コード値・HTML本文が含まれないこと。
- CIからの実サイトPOSTは行わない。ライブ確認は所有者承認のうえ、対象資料を指定して1回だけ行う。

## 11. 実サイト一連確認の受入条件（実装後）

`docs/design/reservation-cancel-auto-hide.md` §9と同型。所有者が延長してよい貸出資料1件を指定し、
アプリの通常操作（貸出中一覧の「延長」ボタン→アプリの確認ダイアログ→確定）を1回実行し、
延長後に実サイトの貸出状況一覧で返却期限が実際に延びたことを確認する。反証条件（対象が
一意特定できない、想定外のaction、返却期限が変化しない）に該当した場合は追加POSTをせず、
設計を改訂する。
