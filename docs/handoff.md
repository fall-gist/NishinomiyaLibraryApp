# 実装引き継ぎ書(バックエンド)

> 2026-07-22追記: 即時予約・カート予約が確認画面解析時に停止する不具合を修正した。実サイト型の`LBForm`は
> `action`が空でJavaScriptにより送信先を設定するため、固定`action`必須の判定を廃止し、確認画面固有のhidden値と
> 受取館selectで安全に一意特定する。`Parse`・`Maintenance`・`Network`は個別理由で表示する。
> action無しLBFormと確定POST一回はユニットテストで確認するが、実機での再予約成立は未検証。
>
> 2026-07-22追加ライブ検証: 同一確認フォームで時間を空けて確定するとログイン画面へ戻り不成立、
> 再ログイン直後に確認から確定まで連続送信すると予約件数は17件から18件へ増加した。確認GETから
> 確定POSTまでは共有`RequestRateLimiter`の排他シーケンスに置き、同期・別セッション要求を
> 割り込ませない。500ms開始間隔と確定POST一回の規則は維持する。

このドキュメントは、バックエンド実装を担当するAIエージェント/開発者への
引き継ぎ資料です。**会話の文脈なしで読めるように書かれています。**

## まず読むもの(この順で)

1. [docs/spec.md](spec.md) — 確定済みのアプリ仕様。**ここに書かれた決定は変更しない**
2. [docs/backend-design.md](backend-design.md) — 本実装の設計書。モジュール構成・API・データモデルはこれに従う
3. [docs/site-research.md](site-research.md) — 図書館サイトの実地調査結果(エンドポイント・パラメータ・HTML構造の一次情報)

## スコープ

本書は、既存データ層の保守と予約UI実装を引き継ぐ。M0〜M5は当初バックエンドを
構築した際のマイルストーンであり、現在の実装状況は後段の追記を正とする。
予約機能はバックエンドを実装済みであり、直接予約通信、Roomローカルカート、
`ReservationCartRepository`を利用できる。予約カート画面・書誌詳細からの導線も実装済みである。

## 実装順序(マイルストーン)

各マイルストーンごとにコミットし、`./gradlew :app:testDebugUnitTest` が
通る状態を保つこと。

### M0: プロジェクト土台
- 空のAndroidプロジェクトを作成(設定値は backend-design.md §1 の表に従う)
- Version Catalog(`gradle/libs.versions.toml`)で依存を宣言
- GitHub Actions: push時に `testDebugUnitTest` + `assembleDebug` を実行し
  APKをartifactとしてアップロードするワークフロー(`.github/workflows/build.yml`)
- **受入条件**: CIがグリーン

### M1: パーサ(最重要・独立して作れる)
- `HashExtractor` と7パーサ(backend-design.md §3.4)を実装
- フィクスチャHTML(下記「フィクスチャ」参照)による全パーサのユニットテスト
- **受入条件**: 正常系・0件・不正HTML(ParseException)の3系統のテストが全パーサにある

### M2: LicsXpClient + openBD
- `LicsXpSession`(CookieJar + hash/gamenid保持)と `LibraryGateway` 実装
- MockWebServerで「ログイン→メニュー→usrlend/usrrsv/mybooklist」のフロー検証
- openBDクライアント(`coverUrl`)
- **受入条件**: MockWebServerテストで正常系+認証失敗+パース失敗が検証されている

### M3: Room + CredentialStore + 設定
- 全エンティティ/DAO/DB、EncryptedSharedPreferencesラッパー、DataStore設定
- **受入条件**: DAOのCRUDテスト(Robolectric)。パスワードがDB・平文prefsに存在しないこと

### M4: リポジトリ + 同期 + 通知
- 4リポジトリ(backend-design.md §6)、SyncWorker、Notifier
- 通知判定はClock注入でテスト(前日判定・READY遷移・重複防止)
- **受入条件**: 「同期→DB更新→通知判定」がユニットテストで一気通貫している

### M5: デバッグ画面(最小限)
- 1画面のみ: メンバー登録フォーム+「同期」ボタン+取得結果(貸出/予約/本棚/サマリ)の素のリスト表示+SyncLog表示
- 見た目は作り込まない(バックエンドの動作確認が目的)
- **受入条件**: 実機/エミュレータで実アカウントによる同期が成功し結果が表示される

### M6: 予約機能(UI・バックエンド完了)

- `ReservationGateway` / `ReservationSession`、Room v5→v6、`ReservationCartRepository`、
  匿名化フィクスチャとMockWebServerのバックエンドテストは実装済み
- 予約カート画面、ハンバーガーメニュー、書誌詳細からの追加・即時予約導線、受取館選択・
  最終確認・結果表示は実装済み。`ReservationUiController`が受取館・確認待ち状態・結果を共有する。
  Repository呼出しは最終確認の肯定操作だけに置く

## フィクスチャ(テスト用HTML)

- 取得スクリプト: [scripts/fetch-fixtures.sh](../scripts/fetch-fixtures.sh)
  (認証情報は環境変数 `LIB_CARD` / `LIB_PASS` で渡す。**スクリプトや
  リポジトリに直書きしない**)
- 保存先: `app/src/test/resources/fixtures/`
- 実行環境からサイトに接続できない場合は、リポジトリ所有者に実行を依頼すること

## 禁止事項(厳守)

1. **書き込みの許可範囲を厳守**: ユーザーが明示操作し最終確認した**直接予約確定**だけを、
   専用`ReservationGateway`経由で送信できる。自動予約、延長、予約取消、登録変更、
   公式サイトカートへの追加・削除など、それ以外のサイト書き込みは実装・送信禁止
2. **認証情報の露出禁止**: カード番号・パスワードをコード・コミット・ログ・
   例外メッセージ・テストコードに含めない
3. **アクセス頻度**: 自動同期は1日1回。リクエスト間500ms以上。テストで実サイトを
   叩くのは疎通スモークテスト(手動実行)のみ
4. **仕様の勝手な変更禁止**: spec.mdと異なる判断が必要になったら、実装せずに
   リポジトリ所有者に確認する

## リポジトリ運用

- 開発ブランチ: `claude/original-android-app-0kpre7`(このブランチにpush)
- コミットメッセージは日本語で簡潔に
- CI(GitHub Actions)がグリーンであることを常に維持

## 既知のハマりどころ

- ボット系User-Agentは**403**になる。ブラウザ相当のUA必須
- `j_username` はカード番号の前に**ゼロ16個**を前置する
- 画面遷移POSTは直前ページの `hash`/`gamenid` がないとエラー画面に飛ぶ
- 検索POSTの前に検索フォームのGETが必要(セッション確立)
- 日付形式が画面ごとに違う(`yyyy/MM/dd`, `yy/MM/dd`, `yyyy-MM-dd`)
- 予約一覧の「受取館」セルは割当前だと全館名が並ぶ(→割当前は空として扱う)

## 読書記録（2026-07-19 追記）

### 実装状況

- Room v3に`reading_records`を追加。主キーは`memberId + tilcod + loanDate`で、同期時には削除せずupsertだけを行う。
- `loans.tilcod`はv2→v3移行で`NOT NULL DEFAULT ''`として追加。現在貸出はタイトルコードを取得できたものだけ読書記録へ併合する。
- 差分停止用の既知キーは`reading_history_checkpoints`（サイト読書履歴を実取得した行のみ）から同期層が読み、`LibraryGateway.fetchUserData`へ値の`Set`として渡す。現在貸出の併合ではチェックポイントを作らないため、履歴を後から有効化しても全履歴を取得できる。通信クライアントはRoomに依存しない。
- `UsrReadListParser`は`table[summary=読書履歴一覧表]`をJsoupで読み、閉じ`tr`や`tbody`の不揃いを許容する。削除列・削除URLは参照しない。
- 初回同期は100件単位で全ページ、差分同期は新しい順に最初の既知行の直前までを取得する。ページの循環リンクはクライアント側で停止する。
- **2026-07-19修正(Claude)**: 表示件数はクエリ`pagingMax`では変わらないことが実サイトで判明したため、`usrread`を開いた後に`WOpacUsrReadListAction.do`へ`rowsPerPage=100`をフォームPOSTする方式に変更(以降のページ送りGETはセッションが100件を記憶)。実HTMLフィクスチャ`usrread_live.html`とそのパーサテストも追加。
- 読書記録の一覧・メンバー絞り込み・正規化検索・タイトルコードによる既読情報は`ReadingRecordRepository`で公開済み。正規化はUnicode NFKC、空白除去、小文字化（`Locale.ROOT`）。
- 最小デバッグ画面に読書記録件数・検索欄・結果表示を追加済み。認証情報は表示しない。

### 検証状況・未検証事項

- 確認済み(2026-07-19、最終コミット1af373d・Windowsローカル環境): `testDebugUnitTest` 82件成功・失敗0、`assembleDebug` 成功。チェックポイント修正後の再実行確認は完了。
- 確認済み(2026-07-19、CI run 29699710732・commit 2851c9f): CIグリーン(`testDebugUnitTest` + `assembleDebug`)。
- `fixtures/usrread.html` は匿名の合成HTML(実サイト由来ではない)であることを確認済み。
- 確認済み(2026-07-19、リポジトリ所有者による実機検証・APK): 実サイトの読書履歴取得・初回同期で約600件を正しく取得できることを確認。実機/エミュレータでの読書記録の同期・表示も確認済み。
- 確認済み(2026-07-22): root環境の`testDebugUnitTest`は82件成功（失敗・エラー・スキップなし）、`assembleDebug`も成功済み。読書履歴は実機で実サイトからの取得・表示を確認済みで、正しく動作した。
- `scripts/fetch-fixtures.sh`には実サイトの読書履歴初回取得処理を追加済み（実行時は個人情報のマスキング確認が必要）。

## 新着資料(2026-07-20 追記)

### スコープ

- 公式サイトの「新着資料」(認証不要の公開ページ)を、**ジャンルを保持せず**全ジャンル横断で
  tilcodにより名寄せした1本の書誌リストとして扱う(spec §3.9)。

### 実装状況

- Room v4に`new_arrivals`テーブルを追加(主キー=`tilcod`)。取得のたびに **全置換** で入れ替える
  (`AppDatabase.replaceNewArrivals` を1トランザクションで実行)。
- `NewArrivalRepository`(`newArrivals(): Flow<List<NewArrival>>` / `refresh()`)を公開。
  自動同期(`SyncWorker`)には**載せない**。画面表示時に1回+手動「更新」で `refresh()` を呼ぶ設計。
- `LibraryGateway.newArrivals()` は
  1. `GET WOpacMsgNewMenuDispAction.do?moveToGamenId=msgnewmenu`(ジャンル一覧、hash不要)
  2. 各ジャンルコード(`newMenuCode=01..28`)を順に `GET WOpacMsgNewMenuToMsgNewListAction.do?newMenuCode=NN`
  3. tilcodで先勝ちの重複排除
  というGET列だけで完結(hash/gamenidの受け渡し不要)。レート制御は既存 `LicsXpSession` の
  500ms間隔がそのまま効く。
- パーサ:
  - `NewArrivalMenuParser.parseGenreCodes(html)` — ジャンルリンクから `newMenuCode` を抽出。
  - `NewArrivalListParser.parse(html)` — `table.list` の見出し行で結果テーブルを特定
    (summary属性は当てにならない)。列名は「書名 / 巻次 / 著者 / 出版者 / **出版年月** / 分類 / 貸出」。
    **`出版` は `出版者` に先マッチするため厳密に `出版年月` を指定**すること。
    貸出セルは `○` → true / `×` → false / 空 → null にマップ。
    書名セルの `a[href*=tilcod]` または `a[onclick*=infoNext]` からタイトルコードを取り出す。
- テスト: パーサ2本(`ParsersTest`)+DAOラウンドトリップ+v3→v4マイグレーション+
  表示ビルダー(`NewArrivalsContentBuilderTest`)を追加。

### 検証状況

- 確認済み(2026-07-20、実サイトへの直接HTTP): 全28ジャンルをGETで取得できること、
  各ジャンルはページングなし1ページで数十〜数百件返ること、`newMenuCode` 空はエラー画面。
  ジャンル22(日本の小説)= 364件・ジャンル27(絵本・紙芝居)= 203件・ジャンル01(総記)= 68件。
- 確認済み(2026-07-20、CI run 29750452916・commit a660f39): `testDebugUnitTest` + `assembleDebug` グリーン。

## 予約のタイトルコード追加(2026-07-20 追記)

### 背景

- 予約一覧の資料名セルにも書誌詳細リンク(`?hTilcod=...` / `onclick=toTilInfoDetail('...')`)が
  あるため、UIの共通「書誌詳細オーバーレイ」から予約中の行を開けるようタイトルコードを保持する。

### 実装状況

- `Reservation` ドメインと `ReservationEntity` に `tilcod: String`(既定 `""`)を追加。
- Room v4 → v5 マイグレーション(`ALTER TABLE reservations ADD COLUMN tilcod TEXT NOT NULL DEFAULT ''`)。
  既存インストールでは次回同期で実値が入る。
- `ReservationListParser` は行内の `a[href*=hTilcod], a[onclick*=toTilInfoDetail]` から
  `hTilcod=|toTilInfoDetail\('(\d+)'\)` の正規表現でタイトルコードを取り出す
  (`LoanListParser` と同じ流儀)。
- 通知の重複判定キー(`ReservationNotificationKey` = memberId+title+materialType+reservedDate)は
  tilcod非依存のまま(挙動は変わらない)。
- テスト: パーサテストの `assertEquals("1000001898886", result.first().tilcod)` と
  全行 `\d{13}` チェックを追加。v4→v5マイグレーション、ビルダーテスト(`ReservationsContentBuilderTest`)も追加。

### 検証状況

- 確認済み(2026-07-20、CI run 29754260046・commit 2f7a41c): `testDebugUnitTest` + `assembleDebug` グリーン。
- 実サイト由来の `fixtures/usrrsv.html` 全19行に `hTilcod` リンクがあり、13桁数字であることを確認済み。
## 次期機能の引き継ぎ事項（2026-07-22追記）

### カレンダー: 返却期限日の特別表示

- 返却期限に到達する貸出がある日を、カレンダー上で通常日と区別できる特別表示にする。
- バックエンドには`Loan.dueDate`と`StatusRepository.loans()`が既にあるため、現時点では通信・DBの追加は不要。カレンダー画面側で貸出一覧を日付別に集約し、該当日の表示状態（件数やメンバー別情報を含むか）を設計する。
- 休館日表示（`CalendarRepository.closedDays()`）との重なりを想定し、同一日に複数の状態がある場合の優先順位をフロントエンド設計で決める。

### 予約機能: 実装済みバックエンドとUI実装引き継ぎ(2026-07-22)

#### 状態

- **確認済み**: 予約確定のライブ導線・重複・セッション切れを検証済み。一次情報は
  [site-research.md](site-research.md) §6、実装可能な詳細設計は
  [backend-design.md](backend-design.md) §11が正である
- **実装済み**: 直接予約のHTTP、`ReservationGateway`、`ReservationCartRepository`、
  Roomの`reservation_cart_items`。現行`AppDatabase`はv6である
- **実装済み**: 予約カート画面、ハンバーガーメニュー、書誌詳細からのカート追加・即時予約、
  受取館選択・最終確認・結果表示。カート追加はRoom更新だけで通信しない。
- **未検証**: 予約上限、利用制限、予約不可資料、無効受取館のサイト応答、メンテナンス中の
  予約挙動、成功alertの正確な文言
- **UI実装の未検証**: 実サイト予約POSTはUI実装中に自動実行していない。実機では最終確認を
  肯定したときだけ送信されるため、実データを変更する試験は利用者の明示操作で行う。
- **設計上の通信トレードオフ**: 成功応答の対象資料構造が未取得のため、POST後不明の各資料は
  予約一覧を即時読取して`tilcod`を確認できた場合だけ次資料へ進む。確認不能時は再送せず停止する

#### 実装時に変更してよい範囲

- 公式サイトカートは使わない。アプリ独自のRoomカートへの追加・削除はネットワーク通信なしで
  行い、項目には追加時に決めた`memberId`を保持する
- 受取館は確定時に1館選び、`SettingsStore.defaultCalendarLibrary`をアプリ共通の「既定館」として
  初期値にする。DataStore保存キー`default_calendar_library`は変更しない。メンバー別既定館は
  作らない
- 連絡方法はEmail固定。確認POSTは`contact=4` / `contactweb=4`で送り、UI・ドメインに
  選択肢を作らない
- 既存参照用`LibraryGateway`に書き込みAPIを追加せず、`ReservationGateway`と
  メンバーごとの一時`ReservationSession`を追加する。Cookieや認証情報をメンバー間・
  実行間で共有しない

#### 確定処理の不変条件

1. UIの明示操作と最終確認後だけ、カート確定または即時予約を開始する。即時予約は
   共通バッチ処理へ一時項目1件として渡す
2. 項目を`memberId`ごとにグループ化し、メンバーごとにログイン/分離セッションを1回だけ
   確立する。その中で資料を順次直接予約し、既存の500ms間隔を維持する
3. メンバーごとにログインを1回だけ行い、各資料では通常書誌詳細GET →
   `WOpacTifDirectYoyDispAction.do?tilcod=...`への詳細LBForm POST → fields抽出 →
   `WOpacTifDirectYoyExecAction.do?tilcod=...`を同一セッションで連続実行する。確認画面の
   `receivename`候補に選択館がなければ、POSTせずエラーにする
   - この確認GET・解析・確定POSTは、共有のサイト全体レート制御mutexを保持した排他区間とする。
     パース失敗・館不一致・例外時も必ず解放し、別セッションの同期やログイン要求を間に入れない
4. POST後不明の各項目は予約一覧を即時1回取得して確認できた場合だけ次資料へ進む。即時照合済みは
   末尾照合対象外で、`Submitted`/重複など未照合項目が残る場合だけ処理終了時に最大1回取得し、
   `tilcod`で照合する。成功alertに依存しない
5. `Success`と`AlreadyReserved`だけをローカルカートから削除する。`Failure`と`Unknown`は残し、
   メンバー別・資料別の部分成功結果をUIへ返す
6. 認証失敗はそのメンバーの残件だけを中止し、他メンバーを続行する。Parse・サイト変更・
   予期しない応答ではそのメンバーの残件を中止する
7. **POST前の明確なセッション切れだけ**新セッションで1回再試行できる。POST後の通信断など
   成否不明時は再送せず、予約一覧照合で`Success` / `AlreadyReserved` / `Unknown`を解決する

#### ライブ検証済みの重要点

- ログアウト導線は`OpacInitLoginAction.do?...yoycartflg=WYoyConfirm&tilcod=...`、ログイン後は通常書誌詳細の
  LBFormを`WOpacTifDirectYoyDispAction.do?tilcod=...`へ送信し、確定POSTは
  `WOpacTifDirectYoyExecAction.do?tilcod=...`
- 確認フォームの`gamenid=tiles.WYoyConfirm`、`tilcod`、`receivename`、`contact`、
  `contactweb=4`を扱う。確認画面を開いたまま待つとセッション切れでログインフォームが返り、
  予約不成立になる
- 重複時のalertは「予約済の書誌があります。予約できません。」であり、
  `AlreadyReserved`として解決済み扱いにする

#### 禁止事項の再確認

予約確定以外のサイト書き込み(自動予約、延長、予約取消、登録変更、公式サイトカートの
追加・削除)は禁止のままである。予約確定を含め、ライブPOSTをテストコード・CI・
バックグラウンド処理から自動実行してはならない。

## ライブ予約診断（2026-07-22追加）

実サイトへの予約 POST を、アプリの `LicsXpReservationGateway` / `LicsXpSession` で再現して差異を調べる専用タスクを追加した。通常の `:app:testDebugUnitTest` と CI はこの診断クラスを除外しており、外部通信・予約は絶対に実行しない。

実行は明示的な環境変数が全て揃った場合に限る。**このコマンドは本番サイトに予約を1回送信する副作用がある。** 既に対象資料が予約一覧にあれば POST を送らずに停止する。

```powershell
$env:LICSXP_LIVE_RESERVATION = 'YES_I_UNDERSTAND'
$env:LICSXP_LIVE_RESERVATION_CONFIRM = 'RESERVE_ON_PRODUCTION'
$env:LICSXP_CARD_NUMBER = 'カード番号'
$env:LICSXP_PASSWORD = 'パスワード'
$env:LICSXP_TILCOD = '予約対象の資料コード'
$env:LICSXP_PICKUP_LIBRARY = '106' # 受取館コード
.\gradlew.bat :app:liveReservationDiagnostic
```

診断はログイン、予約前一覧、確認 GET、確定 POST（既存のexactly-once処理）、予約後一覧を順に実行する。ログには HTTP メソッド・パス・ステータス・リダイレクト・フォームの名前と安全な制御値・画面分類だけを出す。`j_username`、`j_password`、`hash`、Cookie、レスポンス本文は出力・保存しない。認証情報をコード、fixture、Gradleプロパティ、コミットへ入れてはならない。

### 予約確定の追加確認（2026-07-22）

ライブ診断で、確認GETとフォーム項目がブラウザ実測に一致しているにもかかわらず、確定POSTの
200本文が詳細検索フォームとなり、予約後一覧で対象資料を確認できない事象が出た。HTTP 200は
予約成立を意味しない。`Referer`/`Origin`不足の仮説は、確認GET完全URLの`Referer`とサイトoriginの
`Origin`を確定POSTだけへ付与した2回目のライブ診断でも解消しなかったため反証済みである。ただし
ブラウザ等価性としてヘッダ付与は残す。確認GET→確定POSTの排他・500ms間隔・一回限りPOSTは維持する。

次の仮説はログインPOSTがブラウザのsuccessful controlsを欠いていること。実フォームには
`hash`、`gamenid=tiles.WMnuTop`、`username`、`j_username`、`h_username`、`j_password`があり、
従来の実装は後者2項目しか送っていなかった。共通の`LoginFormParser`がフォームを一意特定し、
hidden項目をDOM順で保持して3つの認証制御項目を上書きする。未知フィールドはhiddenだけを許可し、
無名・disabled・button・未知の非hidden項目は送らない。`LicsXpClient`と`LicsXpReservationGateway`の
両方が同じビルダーを使用する。認証値を例外・ログ・fixtureへ残してはならない。

予約確定ボタンの`exec(tilcod)`は、送信抑止フラグと`LBForm.action`を設定して`submit()`するだけで、
hidden値を変更しないことを確認済み。`DirectReservationConfirmationPage`もログインと同様に
successful controlsをDOM順で保持し、`receivename`・`contact`・`contactweb`だけを元位置で一度だけ
上書きする。旧実装の`contactweb`末尾追加はブラウザ送信順と異なるため廃止した。期待制御項目は
`receivename`/`contact`がselect、`contactweb`がhiddenで一意に存在しなければfail-closedで停止する。

### 通常書誌コンテキストへの予約導線変更（2026-07-22）

`EsTif`系の直接予約は検索セッション状態を必要とし、アプリ独自カートからは確認画面へ進まないことを
実サイトで確認した。予約処理は、通常書誌詳細の
`GET WOpacTifTilListToTifTilDetailAction.do?urlNotFlag=1&tilcod=...`、詳細LBFormのDOM順successful controlsを
`POST WOpacTifDirectYoyDispAction.do?tilcod=...`、`gamenid=tiles.WYoyConfirm`の確認フォームを
`POST WOpacTifDirectYoyExecAction.do?tilcod=...`で確定する順へ変更した。表示POSTは読み取り表示遷移で、
最終確定POSTだけをexactly-once/noRetryとする。詳細フォームは`LBForm`、`tiles.WTifTilDetail`、対象`tilcod`、
`kensakuFlg`、`kensaku`をhiddenかつ一意に要求してfail-closedにする。

診断の予約確認分類は、HTMLに現れる共通JavaScriptの文字列ではなく、同一フォームの
`gamenid=tiles.WYoyConfirm`、`tilcod`、`select[name=receivename]`で判定する。詳細検索フォームは
`tiles.WEsSchCmpd`または`condition1Text`で明示的に`search-form`と扱う。
