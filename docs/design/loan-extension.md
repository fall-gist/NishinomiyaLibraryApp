# 貸出延長 技術設計

最終更新: 2026-08-04
状態: **設計確定・実装未着手**（§9の論点3件は2026-08-04に所有者が裁定済み）
機能要件の正本: `docs/spec.md` §3.12
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

**表示用の可否フラグと、送信用のコードを分離する。**

```kotlin
data class Loan(
    ...
    val extendable: Boolean = false,  // 同期時に延長ボタンの有無から算出。Roomへ保存する
)
```

- `extendable`（**Roomへ保存する**）: 貸出中一覧で延長ボタンを出し分けるためだけに使う。
  `LoanListParser`が延長ボタンの有無から算出し、同期のたびに更新される。
  Room v8→v9マイグレーション（`ALTER TABLE loans ADD COLUMN extendable INTEGER NOT NULL DEFAULT 0`）を
  追加する。既存インストールでは次回同期で実値が入る（`tilcod`追加時と同じ流儀）。
- `renewalCode`（**Roomへ保存しない**）: 実際の送信に使う`extend(mngcod)`の引数。
  延長実行時に取得し直した貸出状況一覧からのみ得る。`Loan`ドメインモデルにも持たせず、
  §4.2の延長専用パーサが返す`LoanExtensionRow`に閉じ込める。

分離する理由は、**古いコードで送信する事故を構造的に防ぐ**ためである。`renewalCode`をRoomへ
保存すると、同期から時間が経ってサイト側の状態が変わった後でも、アプリは古いコードを送信できて
しまう。可否フラグだけを保存し、送信コードは常に直前取得のものを使うことで、この経路自体を無くす。

`extendable`が`true`でも実行時に`renewalCode`を取得できない場合は、送信前に停止する
（フェイルクローズ）。同期後にサイト側で延長不可へ変わった場合に起こり得る。

### 4.2 パーサの分担

- `LoanListParser`（既存を拡張）: 通常同期で使う。行に延長ボタンがあるかだけを見て
  `Loan.extendable`を算出する。`renewalCode`の値は出力しない。
- `LoanExtensionListParser`（新規）: 延長実行時にだけ使う。各行の`tilcod`・返却期限日・
  `renewalCode`を持つ`LoanExtensionRow`のリストを返す。
  抽出は完全一致の`extend\("(\d+)"\)`のみを許可し、空値・複数候補・未知構文はパース失敗として
  停止する（`ReservationHideFormParser`の`hideCodeRegex`と同じ方針）。
  引数がダブルクォートであることは`docs/site-research.md`§10とフィクスチャで実測済み。

  **`renewalCode`は`String?`（nullable）とし、延長ボタンの無い行も結果に含めること。**
  延長に成功すると対象行はボタンを失う（`docs/site-research.md`§10「延長成功時の一覧の変化」で
  実測）。ボタンのある行だけを返すと、**成功したときに限って送信後の照合で対象行を見失い、
  常に成否不明になる**。既存の`Reservation.cancelCode`が取消不可の行で空になりつつ行自体は
  保持されているのと同じ形にする。送信対象の選択時だけ`renewalCode != null`で絞り込む。

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
  **`prevRequestForm`の項目の多重集合は、1段階目の「query + body」と一致する**
  （`mngFlg1_handan`はqueryで送るためbodyには無いが`prevRequestForm`には含まれる。
  `docs/site-research.md`§10で実測・訂正済み）。bodyだけと突き合わせると必ず不一致になる。
- `OK_CODES_NAME`変数と確認コード値（実測`OPACUSR005`）は、予約取消の`OK_CODES_NAME`抽出と同じ
  字句走査で都度抽出する。値をハードコードせず、抽出できない場合は送信前に停止する
  （`docs/handoff.md`の「未確認事項を推測で補完して実サイトへPOSTしてはならない」を踏襲）。
- 2段階目: `prevRequestForm`のDOM順controls＋末尾に`OK_CODES_NAME`＝抽出した確認コードを追加して
  送信する。
  **送信先はHTMLの`action`属性からは取得できない。** 実サイトの`prevRequestForm`に`action`属性は
  存在せず、ページ内スクリプトが`document.prevRequestForm.action = "..."`と実行時に代入する
  （`docs/site-research.md`§10で実測・訂正済み）。したがって送信先は`OK_CODES_NAME`と同じ字句走査で
  **このJS代入から抽出**し、固定origin・許可path（`/licsxp-opac/WOpacUsrLendListExtendAction.do`、
  クエリ無し）と一致する場合だけ送信する。一致しない・抽出できない場合は送信前に停止する。
  **`action`属性の存在を必須条件にしてはならない**（実サイトで必ず停止するため。予約取消の
  10回目ライブ診断で同じ誤りを踏んでいる）。
- 各POSTは自動再試行しない`noRetryClient`を使い、既存のリクエスト間500ms制御を維持する。

### 5.2 成否照合

`<div id="messages">`が空でメッセージに頼れないため、次の2つを組み合わせて判定する。

1. **主判定**: 送信直前に取得した対象行の返却期限日と、送信後に再取得した同じ`tilcod`行の
   返却期限日を比較する。**日付が送信前より後へ変化していれば`Extended`**とする。
   実データで機能することを確認済み（`docs/site-research.md`§10）。
2. **補助情報**: 送信後の対象行に延長ボタンが残っているか（`renewalCode`の有無）を記録する。
   実測では成功時にボタンは消えるが、これを判定条件にはしない（拒否時の挙動が未実測のため）。

**照合時の必須事項**（いずれも実測に基づく。`docs/site-research.md`§10参照）:

- 対象行は**必ず`tilcod`で探す。行の位置で追ってはならない**。延長すると一覧の並び順が変わり、
  対象行が末尾へ移動することを実測している。
- 送信後の一覧は**延長ボタンの有無に関わらず全行を保持して**照合する。成功すると対象行は
  ボタンを失うため、ボタンのある行だけに絞ると成功時に限って対象を見失う。

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

この型は画面に依存しない。表示文言はUI層で組み立てる（§9.1）。

### UI（§9.1で所有者が承認した範囲）

- 貸出中一覧の各行のうち、延長可能と判定できた資料にだけ「延長」ボタンを表示する。
- タップでアプリ独自の確認ダイアログ（資料名・現在の返却期限・「延長しますか？」）を表示する。
  サイトの確認文言は転記しない（文言未確認のため、かつ既存方針との整合のため）。
- 確定後、結果を次のように表示する。
  - `Extended`: 「返却期限を延長しました（新しい期限: yyyy/MM/dd）」
  - `Unknown`: 「延長できたか確認できません。しばらくしてから貸出状況をご確認ください」
  - `Failure`: 既存の予約系と同じ文言方針（認証失敗、通信失敗等）
- 一斉延長・複数選択・書誌詳細からの導線は今回のスコープ外。ただし§9.1のとおり、**後から
  追加できる層構造を保つことが実装上の必須要件**であり、「スコープ外だから作り込む」ことも
  「スコープ外だからUIへ通信ロジックを寄せる」ことも認めない。

ボタンの出し分けには`Loan.extendable`（Room保存、§4.1）を使う。UI層は`renewalCode`を一切持たない。

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

## 9. 所有者の裁定（2026-08-04、確定）

3件とも所有者が裁定した。以下を実装の前提とする。

### 9.1 UI導線は貸出中一覧の各行・1件ずつのみ（承認）

**所有者の判断**: 「ひとまず良いとする。バックエンドの通信部分とUIの延長ボタンを分けておけば、
後で追加したくなっても容易なはず。」

したがって、これは**単なるスコープ限定ではなく、構造上の要件**である。

- `LoanExtensionGateway` / `LoanExtensionRepository` は、**呼出し元が貸出中一覧であることを前提に
  しない**。引数は「延長対象1件を特定する情報（`memberId` と対象資料）」だけを受け取り、
  UI経路・画面種別に依存する引数や分岐を持たせない
- 対象の`renewalCode`はRepository層より内側で毎回取得する。UI層が`renewalCode`を保持して
  渡す設計にしない（UI層が通信内部値を知ると、経路追加のたびに取得責務が複製される）
- 結果型`LoanExtensionOutcome`も画面非依存とし、表示文言はUI層で組み立てる
- 将来「書誌詳細から延長」「複数選択で一斉延長」を追加する場合、**変更はUI層と、複数件を順に
  回すRepository側のループ追加だけで済む**状態を保つ。Gateway（1件の二段階POSTと照合）は不変とする

### 9.2 拒否理由を区別しない初期実装で進める（承認）

`Extended` / `Unknown` / `Failure`（POST前に確定した失敗のみ）の3値とし、サイトが延長を拒否した
場合の細分類は設けない。拒否例のHARまたは診断が採取できた時点で`FailureReason`を追加する。
**未実測の拒否理由を推測して分類を作らない。**

### 9.3 `ReservationOperationGate`へは参加させない（承認）

貸出延長は予約データに触れず、手動予約・自動予約・予約取消との業務上の競合が無いため、
共通書込ゲートには参加させない。二重タップ防止のための専用`Mutex`のみを持つ。

**ただし将来の注意点として記録する**: 「貸出延長も自動化する」構想が出た場合、および
「延長すると予約待ちの資料に影響する」ことが判明した場合は、この判断を再評価すること。
現時点でその根拠は無い。

## 10. 実装時のテスト計画

- Room: `MIGRATION_8_9`（既存データ保持、`extendable`既定`false`）、`LoanEntity`往復。
- パーサ: `LoanListParser`の`extendable`算出（ボタン有無）、`LoanExtensionListParser`の
  `renewalCode`正常抽出・0件・重複・未知構文・行と`tilcod`の対応。
  既存フィクスチャ`app/src/test/resources/fixtures/usrlend.html`に延長ボタン有無の両方の行が
  含まれていることを確認済みであり、そのまま使える。
  **ボタンの無い行も`renewalCode=null`として結果に含まれることを必ずテストで固定する。**
- **2段階目の確認ページのテストは、実サイト構造に忠実なフィクスチャで行う。** 自作HTMLだけで
  テストすると、`action`属性の有無のような実サイトとの差異を検出できない（実際に初回実装で
  この見落としが起きた）。フィクスチャは値をマスクし、`action`属性を持たない`<form>`タグ、
  JS代入によるaction設定、`OK_CODES_NAME`の定義、18項目のhiddenを実構造どおりに含めること。
- フォーム: 1段階目LBFormのDOM順・重複込み送信、`para`だけの上書き、2段階目`prevRequestForm`の
  DOM順保持、`OK_CODES_NAME`抽出、確認コード追加、action検証（固定origin・固定path・クエリ無し）。
- 通信: 実測どおりのpath/query/body/Referer/Origin、POST 2回、no-retry、500ms制御。
- 成否: 返却期限が進んだ場合の`Extended`、対象消失・複数化・解析不能時の`Unknown`、
  POST前失敗の`Failure`。
- 層分離（§9.1）: Gateway/Repositoryが画面種別に依存する引数・分岐を持たないこと。
  UI層が`renewalCode`を保持しないこと。
- 除外: 延長ボタンの無い行に対して延長操作を起動できないこと。`extendable=true`でも実行時に
  `renewalCode`を取得できなければ送信前に停止すること。
- セキュリティ: 診断ログに認証情報・個人情報・コード値・HTML本文が含まれないこと。
- CIからの実サイトPOSTは行わない。ライブ確認は所有者承認のうえ、対象資料を指定して1回だけ行う。

## 11. 実装順序

段階ごとにコミットし、各段階で`:app:testDebugUnitTest`と`:app:assembleDebug`が通る状態を保つ。

1. Room v8→v9（`MIGRATION_8_9`、`LoanEntity.extendable`）と`Loan`ドメイン、`LoanListParser`の
   `extendable`算出。**この段階では延長機能そのものは無く、フラグが同期で入るだけ。**
2. `LoanExtensionListParser`（`renewalCode`抽出）と、1段階目/2段階目のフォームパーサ。
   純粋なパース層のみで、通信は行わない。
3. `LoanExtensionGateway`（二段階POST＋返却期限照合）。MockWebServerでプロトコルを固定する。
   **実サイトへは一切送らない。**
4. `LoanExtensionRepository`と結果型。§9.1の層分離要件を満たすことをテストで固定する。
5. UI（貸出中一覧の延長ボタン、確認ダイアログ、結果表示）。
6. ライブ診断タスク`liveLoanExtensionDiagnostic`（通常テスト・CIから除外、専用同意フラグ
   `LICSXP_LIVE_EXTEND_CONFIRM`を要求）。既存の`liveReservationHideDiagnostic`と同じ流儀。
7. §12の実サイト一連確認（所有者承認のうえ1件）。

段階1は既存の同期処理に触れるため、単独コミットとし、既存の貸出同期テストが回帰しないことを
確認してから段階2へ進む（`ReservationSubmissionResolver`抽出時と同じ関門の考え方）。

## 12. 実サイト一連確認の受入条件（実装後）

`docs/design/reservation-cancel-auto-hide.md` §9と同型。所有者が延長してよい貸出資料1件を指定し、
アプリの通常操作（貸出中一覧の「延長」ボタン→アプリの確認ダイアログ→確定）を1回実行し、
延長後に実サイトの貸出状況一覧で返却期限が実際に延びたことを確認する。反証条件（対象が
一意特定できない、想定外のaction、返却期限が変化しない）に該当した場合は追加POSTをせず、
設計を改訂する。
