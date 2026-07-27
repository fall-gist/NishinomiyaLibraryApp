# 実装引き継ぎ書(バックエンド)

> **現在の予約機能（2026-07-27、確認済み）**: 確定POSTだけが`JSESSIONID`を送らない不具合を
> 修正済みで、即時予約・アプリ内カート予約の双方が実サイトで成立し、予約状況一覧への反映も確認済み。
> 確定POSTの再送禁止、送信後の予約一覧照合、500ms開始間隔を維持すること。詳細と調査履歴は
> 「予約機能: 実装済みバックエンドとUI実装引き継ぎ」以降を参照する。

このドキュメントは、バックエンド実装を担当するAIエージェント/開発者への
引き継ぎ資料です。**会話の文脈なしで読めるように書かれています。**

## まず読むもの(この順で)

1. [docs/spec.md](spec.md) — 確定済みのアプリ仕様。**ここに書かれた決定は変更しない**
2. [docs/backend-design.md](backend-design.md) — 本実装の設計書。モジュール構成・API・データモデルはこれに従う
3. [docs/site-research.md](site-research.md) — 図書館サイトの実地調査結果(エンドポイント・パラメータ・HTML構造の一次情報)

## 主担当AIへの進行指示（2026-07-27レビュー反映）

以下は予約障害だけに限らず、外部サイト連携の実装・検証で守ること。

1. **送ったつもりではなく、実際に送られた通信を最初に確認すること。**
   クライアントが組み立てたRequest、ネットワークへ出たRequest、受信Response、処理後のサイト状態を
   分けて観測する。CookieJar・interceptor・redirect適用後の通信を確認せず、フォーム値やヘッダの
   仮説へ進まない。
2. **ブラウザとアプリを同じ粒度で突き合わせること。**
   問題を再現できた最短フローについて、各要求のmethod・URL・body・Cookieを含むheaders・responseの
   status/headers・redirect・順序を比較する。`.do`のパス列だけ、確定POSTのbodyだけ、といった
   部分比較で一致を断定しない。
3. **直前の成功要求と失敗要求を先に比較すること。**
   今回なら確認画面表示POSTと確定POSTのwireログを比較すれば、後者だけ`JSESSIONID`と観測ログ自体が
   欠けることから、別クライアント経路へ早く絞れた。外部HARの取得を待たず、アプリ内部で得られる
   隣接要求の差分を先に取る。
4. **仮説は予測・反証条件・差分を一つずつ定めて検証すること。**
   複数の推定変更を積み上げない。各実験で「正しければ何が変わるか」を先に書き、変化がなければ
   revertして基準状態へ戻す。事実、推定、未検証を同じ段落で混同しない。
5. **状態を持つ通信は、設定継承までテストすること。**
   別OkHttpClientを使うexactly-once経路は、methodや送信回数だけでなく、直前Responseの
   `Set-Cookie`を次のPOSTが送ること、必要なinterceptor/headerが通常経路と一致することを
   MockWebServerで検証する。
6. **実機検証のビルド元を毎回固定・記録すること。**
   `BuildConfig.GIT_SHA`を検証前に確認し、結果にはSHA・操作・対象・期待値・実測値を残す。
   SHAが一致しない検証結果を現行コードの根拠に使わない。
7. **成功実績と必要性を区別すること。**
   ある変更を含む構成で予約が成立しても、その変更が「必要」「無害」「今後も影響しない」とは
   証明されない。必要性を単独検証していない変更は、そのまま未検証と記録する。
8. **調査記録と現行仕様を分けて保守すること。**
   `site-research.md`は観測事実、`backend-design.md`は現行の規範、`handoff.md`は現在状態・判断・
   未解決事項を主とする。反証済み仮説は履歴として残しても、冒頭の現状説明へ混ぜない。
9. **状態変更の可能性がある要求を、画面取得・観測目的で送らないこと。**
   POSTやサイト内の実行アクションは、結果が未解明なら書き込みとして扱う。「1段階目は確認表示だけ」
   と実測する前に非破壊と決めつけず、状態変更エンドポイントを`fetch`等で直接呼んでHTMLを読むことも
   しない。ブラウザの正規操作とHAR／DevToolsの記録で観測する。
10. **取消・確定などの書き込み機能は、ブラウザの成功通信を採取してからプロトコルを実装すること。**
    少なくともmethod・URL・query・body（順序と同名重複を含む）・Cookie・Referer/Origin・応答・
    操作後状態を1組取得する。ドメインやDBの先行実装は可能だが、未実測の送信手順を本番へ試行しながら
    完成させない。
11. **書き込み成功は、安定した業務IDと完全な一覧で照合すること。**
    実行ボタン由来の一時的なコードやボタンの消失だけを、対象レコードの消失と同一視しない。
    対象の`tilcod`等を送信前に固定し、送信後一覧が完全と確認できた場合だけ成功／不成立を確定する。
    一覧が不完全、または同一レコードが残っている可能性があれば成否不明へ倒す。
12. **ブラウザのネイティブダイアログを含む検証は、操作前に採取手順を決めること。**
    Networkログを保持し、所有者がダイアログを操作し、直後にRequest payloadを保存する。
    本番セッションで`window.confirm`／`window.alert`を書き換える場合は、ページ遷移後には引き継がれず、
    サイトの重要な文言や分岐を消し得ることを前提にし、必要性と影響を明示して承認を得る。

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

1. **書き込みの許可範囲**: **2026-07-26にリポジトリ所有者の判断で、従来の全面禁止を撤回した。**
   従来は「ユーザーが明示操作し最終確認した直接予約確定だけ」を許可し、自動予約・延長・予約取消・
   登録変更・公式サイトカート操作を実装・送信禁止としていた。撤回後の運用は次のとおり:
   - 新しい書き込み操作は、**機能ごとに設計を起こし所有者の承認を得てから**実装する。
     承認なしに書き込み経路を増やしてはならない
   - 書き込みは引き続き専用ゲートウェイに隔離し、exactly-once・再送禁止・失敗時の照合という
     既存の不変条件を守る
   - `docs/spec.md` も2026-07-26に同時改定済み（§1・§3.10・§4・§5）。撤回は実装を約束するもの
     ではなく、機能ごとに設計と承認を経て spec.md へ追記してから実装する
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

### 自動巡回の鮮度抑止(2026-07-27 追記)

- 画面表示時の自動巡回(28ジャンル、500ms間隔で約15秒)はアプリ起動ごとに1回走るが、
  実測で予約処理と並行して走っていたため、**最終取得から12時間以内なら画面表示時の
  自動巡回をスキップ**するようにした(`NewArrivalsScreenController.shouldAutoRefresh`、
  しきい値は`AUTO_REFRESH_FRESHNESS_THRESHOLD_MILLIS`)。
- 最終取得時刻(epoch millis)は`SettingsStore`(DataStore)に専用キーで保存する
  (`AppSettings`には含めない。利用者設定ではなく内部状態のため)。`NewArrivalRepository.refresh()`
  が全置換に**成功したときだけ**更新し、失敗時は前回の時刻を維持する。
- 新着資料が1件も保持されていない(初回起動など)場合は経過時間を問わず巡回する。
- 「更新」ボタン等の**明示操作(`refresh()`)は鮮度に関わらず常に巡回**する(抑止しない)。
- 新着資料画面に最終取得時刻を小さく表示する(`NewArrivalsLastFetchedTextBuilder`、未取得時は「未取得」)。

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
- 連絡方法はEmail固定。確認POSTは`contact=4`で送り、UI・ドメインに選択肢を作らない。
  `contactdirectweb`はサイト発行値のまま送り、値を上書きしない(誤って`contactweb=4`と
  記載していたのは訂正済み。詳細は「確認フォームの実コントロール判明」節を参照)
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
  `contactdirectweb`を扱う。確認画面を開いたまま待つとセッション切れでログインフォームが返り、
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

### dry-run診断（予約確認画面の構造調査、書き込み副作用なし）（2026-07-25追加）

予約確認画面(`tiles.WYoyConfirm`)の実HTML構造を、**確定POSTを一切行わずに**調べるための dry-run モードを追加した。`WOpacTifDirectYoyExecAction.do` へのPOSTは発行しない（`directReserve`を呼ばない）ため、本番サイトへの書き込み副作用はゼロである。通常の `:app:testDebugUnitTest` と CI はこの診断クラス（`LiveReservationInspectionTest`）も除外している。

実行は次の環境変数が全て揃った場合に限る。**確定フラグ(`LICSXP_LIVE_RESERVATION_CONFIRM`)は不要であり、指定されていても dry-run が優先されるため書き込みは起きない。**

```powershell
$env:LICSXP_LIVE_RESERVATION = 'YES_I_UNDERSTAND'
$env:LICSXP_LIVE_RESERVATION_DRY_RUN = 'INSPECT_ONLY'
$env:LICSXP_CARD_NUMBER = 'カード番号'
$env:LICSXP_PASSWORD = 'パスワード'
$env:LICSXP_TILCOD = '対象資料コード'
$env:LICSXP_PICKUP_LIBRARY = '106'
.\gradlew.bat :app:liveReservationInspect
```

出力にはログイン、確認画面到達までの遷移、確認画面の画面分類・input type 付き form fingerprint、確定POSTで送るはずの送信項目名（DOM順、値は含まない）、受取館候補コード一覧、要求した受取館が候補に含まれるかを出す。**フォームの値・Cookie・認証情報は一切出力されない。** フィールド名・input type・画面分類・HTTPメソッド/パス/ステータスまでがログに出る範囲であり、既存の`diagnosticValue`による秘匿化方針を継続する。

### 取消診断（`liveReservationCancelDiagnostic`、2026-07-27追加）

`ReservationSession.cancelReservation`（実サイト未検証）をライブ検証するための専用タスク。既存の予約診断・dry-run診断と同じ流儀で、通常の `:app:testDebugUnitTest` と CI は診断クラス（`LiveReservationCancelDiagnosticTest`）を除外している。

**このコマンドは本番サイトで予約取消を1回実行する不可逆な副作用がある。** そのため通常の予約診断とは別の専用同意フラグ(`LICSXP_LIVE_CANCEL_CONFIRM`)を要求する。

```powershell
$env:LICSXP_LIVE_RESERVATION = 'YES_I_UNDERSTAND'
$env:LICSXP_LIVE_CANCEL_CONFIRM = 'CANCEL_ON_PRODUCTION'
$env:LICSXP_CARD_NUMBER = 'カード番号'
$env:LICSXP_PASSWORD = 'パスワード'
$env:LICSXP_CANCEL_TILCOD = '取り消したい予約の資料コード'
.\gradlew.bat :app:liveReservationCancelDiagnostic
```

安全弁（すべて`check`による停止で、取消POSTを送らない）:

- 対象tilcodが予約一覧に無ければ何もせず停止する（「対象の予約が一覧にありません」）。
- 対象tilcodが複数件一致した場合も、どれを取り消すか決められないため停止する。
- 一致した行の取消コード(`cancelCode`)が空（取消非対応行）なら停止する。
- 一意に特定できた場合だけ`cancelReservation`を**1回だけ**呼ぶ。ループや複数件取消は実装していない。

ログには一致件数・取消結果・取消後一覧に対象が残っているかを出す。資料名・取消コードそのものはログへ出さない（`tilcod`は既存診断でも出しているため出してよい）。

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
successful controlsをDOM順で保持し、`receivename`・`contact`だけを元位置で一度だけ上書きする。
`contactdirectweb`はサイト発行値のまま送る(2026-07-25の実測により`contactweb`という
フィールドは実在しないと判明したため、対象から外した。詳細は後述)。期待制御項目は
`receivename`/`contact`がselect、`contactdirectweb`がhiddenで一意に存在しなければ
fail-closedで停止する。

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

### 確認画面ホワイトリスト実装の是正（2026-07-25、事実）

- 予約確認画面(`tiles.WYoyConfirm`)の実HTMLは一度も取得されていない。
  `app/src/test/resources/fixtures/reservation_confirm.html`は合成fixtureであり、確認画面の
  コントロール構成(hidden以外のtext/radio/checkbox/textarea/他のselectの有無)は未確定である。
- `DirectReservationConfirmParser`が「hidden以外の入力欄を送らない」ホワイトリスト実装に
  なっていたため、ブラウザの`document.LBForm.submit()`と乖離しうる問題を是正した。
  `BookDetailReservationFormParser`と同じ規則で、確認フォームのsuccessful controlsを
  全てDOM順で送るよう修正した(hidden限定のホワイトリストは廃止)。fail-closedの検証
  (フォーム一意特定、`gamenid=tiles.WYoyConfirm`、`tilcod`一致、`contactdirectweb`が
  hiddenで一意存在、`receivename`/`contact`がselectで一意、受取館候補抽出)は
  2026-07-25の是正まで維持した(是正内容は次節)。
- 書誌詳細画面のフォーム構成・ログインフォームの項目は2026-07-25に実サイトの生HTML
  (未ログイン状態)で照合済みで、現行実装と一致することを確認した(確認済み)。予約ボタンは
  `type=button`であり`submit()`では送信されないため、「送信にボタン要素が無いことが原因」
  という仮説は**書誌詳細画面については反証済み**である。ただし確認画面のボタン構成は未検証。
- `LiveReservationDiagnostic`の事前重複チェックは使い捨ての別セッションで行うよう変更し、
  予約実行本体はログイン直後に確定POSTまで連続実行する新しいセッションで行うよう変更した。
  実測済みなのは「ログイン→確認→確定を同一セッションで連続実行する必要がある」ことまでで、
  「予約前一覧の取得が確定を妨げる」ことは**未検証の推定**である。診断をブラウザで成功した
  列と一致させるための設計判断として分離した。

### 確認フォームの実コントロール判明と`contactweb`誤りの訂正（2026-07-25、事実）

- 2026-07-25のライブdry-run診断で、予約確認画面(`tiles.WYoyConfirm`)フォームの実コントロールを
  input type付きfingerprintで実測した。全コントロールは以下のみ(名前とDOM順は実測、
  **各値は未取得**):
  - 確定POSTのDOM順: `gamenFlag` → `hash` → `returnid` → `gamenid` → `tilcod` →
    `loginshuflag` → `contactdirectweb` → `receivenameFocus` → `watsptcodFocus` →
    `contactFocus` → `returnValue` → `bmtime_hide` → select `receivename` → select `contact`
  - text/textarea/radio/checkbox/buttonは存在しない
  - fixture 2件の並び順もこの実測順に一致させた(値のみ合成)。詳細は site-research.md §6.6
- **`contactweb`というフィールドは実在しない。正しくは`contactdirectweb`である。** 従来の
  `DirectReservationConfirmParser`は`contactweb`がhiddenで一意に存在し値が`4`であることを
  フォーム特定条件にしていたため、確認フォームを一意に特定できず`ParseException`で停止していた。
  これにより確定POSTは一度も送信されていなかった。
- `contactdirectweb`の値は未取得である。値を推測して埋め込まず、確定POSTでは
  `contactdirectweb`をサイト発行値のまま送る。上書きするのは`receivename`と`contact`の
  2項目のみに変更した。フォーム特定条件は`gamenid`/`tilcod`/`contactdirectweb`(hidden、
  一意存在のみを確認、値は問わない)、`receivename`/`contact`(select、一意存在)とした。
  値の検証は`gamenid == tiles.WYoyConfirm`と`tilcod`一致のみ残し、`contactweb`の値検証は
  実在しないフィールドの検証だったため削除した。
- 「hidden以外のコントロールを送っていないことが原因」という仮説は、実フォームにhidden以外の
  入力欄が存在しなかったため**反証された**。ただし2026-07-25(前節)で導入したDOM順successful
  controls全送信の実装はブラウザ等価性として維持する。
- **未検証事項**: `receivenameFocus` / `watsptcodFocus` / `contactFocus` / `bmtime_hide` /
  `returnValue` / `gamenFlag` / `loginshuflag`の各値の意味、および受取館selectを変更した際に
  ブラウザがこれらの値を書き換えるかどうかは不明である。`contactdirectweb`修正後も確定POSTが
  なお失敗する場合、次の調査対象はこれらの値の挙動である。

### ブラウザでの確定POST実測とhash補完・再表示POST撤去（2026-07-26、事実）

- 2026-07-26、ブラウザで確定POSTを実際に送って本文を実測した(`hash`の実値は秘匿ポリシーにより
  記載しない)。DOM順・各値は site-research.md §6.7 参照。要点:
  - `contactdirectweb`はブラウザでも**空**のまま確定している。前節の直前のコミット`0ce645a`で
    入れた「確認画面の`contactdirectweb`を`4`へ上書きして`WOpacTifDirectYoyDispAction.do?webrak=1`
    へ再表示POSTしてから確定する」処理は**誤りだったため撤去した**。確定POSTは、確認画面の
    successful controlsをDOM順のまま(`receivename`と`contact`だけ上書き、`contactdirectweb`は
    サイト発行値のまま)送る形へ戻した。dry-run診断側の`contactDirectWebValue`引数・
    `ContactSelectionRetry`・環境変数`LICSXP_CONTACT_DIRECT_WEB`・`buildFormWithContactDirectWeb`は、
    確定POSTを送らない調査専用の経路として残してある。
  - `hash`はブラウザでは**非空**、アプリは書誌詳細をURL直接GETで開くため書誌詳細・確認画面とも
    hash**空**で描画される(dry-run診断で実測済み)。ログイン直後メニュー画面には有効な`hash`が
    あり、`LicsXpSession.updateTokens(menu)`で保持している。これを`session.requireTokens().hash`
    として、`BookDetailReservationForm.buildForm(hashOverride)` / 
    `DirectReservationConfirmationPage.buildForm(pickupLibraryCode, hashOverride)`の両方へ渡し、
    **ページの`hash`が空のときだけ**元DOM位置のままhashOverrideへ上書きするようにした。
    ページが非空の`hash`を発行していれば絶対に上書きしない。
  - `returnid`の値の差(ブラウザ`tiles.WTifTilDetail2`／アプリ従来`tiles.WTifTilDetail`)は
    アプリの到達経路の違いによるもので、実装側で値を作ってはならない。**今回は変更しておらず、
    未検証事項として残る。**
  - ブラウザが送るヘッダのうちアプリが送っていないもの(`Accept`系、`Cache-Control`、`Pragma`、
    `Upgrade-Insecure-Requests`、`Sec-Fetch-*`、`sec-ch-ua*`、`DNT`)は**未検証事項**として残す。
    Referer/Originはアプリも一致した値を送っている。
  - これで予約が成立するかどうかは、この時点でもなお**未検証**である。次にライブdry-run/実予約で
    確認すべきは、hash補完後に確定POSTがサイト側で受理されるかどうかである。

### 予約導線の書誌詳細入口アクション修正・hash補完撤去（2026-07-26追記、事実）

- 前節で「未検証事項」とした`returnid`差(ブラウザ`tiles.WTifTilDetail2`／アプリ従来
  `tiles.WTifTilDetail`)の原因を、匿名アクセスとブラウザDevToolsの実測で特定した。
  **書誌詳細への入口アクションの違い**である。詳細な実測表はsite-research.md §6.8参照。
  - `WOpacTifTilListToTifTilDetailAction.do?urlNotFlag=1&tilcod=…`(アプリが従来使用)は
    `tiles.WTifTilDetail`をhash**空**で描画し、確定POSTが詳細検索画面へ差し戻される。
  - `WOpacMsgNewListToTifTilDetailAction.do?urlNotFlag=1&tilcod=…`は`tiles.WTifTilDetail2`を
    hash**非空**で描画し、ブラウザの予約成立と同じ経路になる。複数`tilcod`で確認済み。
- **対応**: `ReservationGateway.kt`の`directReserve`・`inspectDirectReservationConfirmation`両方の
  書誌詳細GETを`WOpacMsgNewListToTifTilDetailAction.do`へ差し替えた(クエリは`urlNotFlag=1`と
  `tilcod`のまま)。読み取り専用の書誌詳細取得(`LicsXpClient`)は対象外で、予約導線だけを変更した。
- `BookDetailReservationFormParser`が要求する`gamenid`を`tiles.WTifTilDetail`から
  `tiles.WTifTilDetail2`へ変更した。両方を許すのではなく、ブラウザで予約成立が確認されている
  画面だけを受け入れるfail-closedとした。`WOpacTifTilListToTifTilDetailAction.do`経由の実HTML
  (gamenid=`tiles.WTifTilDetail`)を渡すと`ParseException`になることをテストで固定した。
- **hash補完の撤去**: 前節で入れた「ページの`hash`が空ならログイン後メニューのセッション
  トークンで補う」処理(`BookDetailReservationForm.buildForm(hashOverride)` /
  `DirectReservationConfirmationPage.buildForm(pickupLibraryCode, hashOverride)`の
  `hashOverride`引数、`ReservationGateway.requireSessionHash()`)を削除した。入口アクションを
  直したことで、ページ自身が非空の`hash`を発行するため不要になったため。ブラウザとの完全一致を
  優先し、`hash`はサイト発行値の素通しに戻した(実値は記載しない)。
- テストは`ReservationGatewayTest.kt`・`ParsersTest.kt`の両方で、実HTMLフィクスチャ
  `book_detail.html`自体は書き換えず、テスト内で`gamenid`を`tiles.WTifTilDetail2`・`hash`を
  非空値へ置換した文字列を生成して使うよう更新した。確定POSTが1回だけであること・
  Referer/Originが従来どおりであることも既存テストのまま維持されている。
- 確認済み(2026-07-26、ローカル): `./gradlew :app:testDebugUnitTest :app:assembleDebug`が
  両方成功。これで予約が成立するかどうかは、この時点でもなお**未検証**であり、次はライブ
  dry-run/実予約での確認が必要である。

### 予約導線のログイン手順修正(2026-07-26追記、事実)

- ブラウザDevToolsで予約成立時の遷移列を実測した(詳細はsite-research.md §6.9参照):

  ```
  WOpacInitLoginActiontemp.do
  OpacLoginAction.do
  WPwdLoginCheckAction.do
  WOpacMnuTopInitAction.do?WebLinkFlag=1&moveToGamenId=msgnewmenu
  WOpacMsgNewMenuToMsgNewListAction.do
  WOpacMsgNewListToTifTilDetailAction.do?urlNotFlag=1
  WOpacTifDirectYoyDispAction.do?tilcod=...
  ```

  アプリの予約導線(`LicsXpReservationGateway.openAuthenticatedSession`)は従来
  `OpacInitLoginAction.do?subSystemFlag=0`からログインフォームを取得しており、
  `WPwdLoginCheckAction.do`に到達していなかった。認証後の戻り先はログインフォームの
  入口によって決まるため、これがアプリが認証後にポータルのトップへ戻されていた原因である。
- **対応**: `ReservationGateway.kt`の`openAuthenticatedSession`のみ、ログインフォーム取得を
  `WOpacInitLoginActiontemp.do`(クエリなし)へ変更し、ログインPOST直後に
  `WPwdLoginCheckAction.do`(クエリなし)を1回GETするようにした。未認証時は200・空ボディを
  返すため、内容は解析せずメンテナンス判定のみ行う。`j_security_check?subSystemFlag=0`への
  送信内容・`WOpacMnuTopInitAction.do?WebLinkFlag=1`取得・`classifyLoginMenu`・
  `updateTokens`は変更していない。読み取り用(`LicsXpClient.fetchUserData`)の
  `OpacInitLoginAction.do`は意図的に変更していない。
- テストは`ReservationGatewayTest.kt`の全ケースで、j_security_checkへのPOST直後に
  `WPwdLoginCheckAction.do`へのGETが1回入るよう、enqueue順序とリクエストインデックスの
  アサーションを更新した。空ボディでも例外にならないことも確認している。
  `LicsXpClientTest`の読み取り経路のテストは変更していない。
- 確認済み(2026-07-26、ローカル): `./gradlew :app:testDebugUnitTest :app:assembleDebug`が
  両方成功。
- **未検証事項**:
  - この修正で予約が成立するかは未検証である。
  - ブラウザは書誌詳細の前に新着ジャンル一覧(`WOpacMsgNewMenuToMsgNewListAction.do`)を
    経ているが、アプリは書誌詳細へ直接入っている。この差の予約成否への影響は未検証である。

### 観測点をネットワーク層へ移し、推測実装を撤去(2026-07-26追記)

- **観測点の移動**: 予約確定POSTが200を返しつつ詳細検索画面へ差し戻される問題を追うため、
  `LicsXpSession`の`observeRequest(request)`が`client.newCall(request).execute()`の**前**に
  呼ばれていた点を洗い出した。ここではアプリが組み立てた`Request`オブジェクトしか見えず、
  `addInterceptor`が足すヘッダや`CookieJar`が積む`Cookie`は記録に現れない。実際に送信された
  内容と、アプリが送ったつもりの内容を比較できるよう、`addNetworkInterceptor`を追加し、
  `LicsXpDiagnosticObserver.onWireRequest`でネットワークへ実際に出た時点のヘッダ・protocol・
  Cookie名・Set-Cookie名(+属性名)を記録できるようにした。`observeRequest`によるアプリ組立て
  時点の記録は変更せず両方を残している。値を持つのはヘッダのみで、`Cookie`ヘッダは名前だけ
  `cookieNames`へ分離し、`Set-Cookie`は値を持たず名前と属性名だけを記録する。`enabled`が
  falseの間は文字列生成・解析を一切行わない。
- **推測実装の撤去**: `ad17e2c`(新着ジャンル一覧経由)と`89c9854`(ブラウザ実測ヘッダの
  一律付与)は、いずれも「これが予約成立に必要かどうかは未検証」と明記していたとおり効果が
  確認できておらず、`git revert`でベースラインへ戻した。`directReserve`/
  `inspectDirectReservationConfirmation`は書誌詳細GETから始まる元の流れに戻り、
  `requestNewArrivalsListContext`と`NEW_ARRIVALS_GENRE_CODE_FOR_LIST_CONTEXT`は削除した。
  `LicsXpSession`のヘッダは`Accept`、`Upgrade-Insecure-Requests`、`Sec-Fetch-*`、
  `sec-ch-ua*`を撤去し、`Accept-Language`(ブラウザでは遷移種別によらず一定の値)と
  `User-Agent`だけを残した。`contactdirectweb`の扱い、書誌詳細の入口
  `WOpacMsgNewListToTifTilDetailAction.do`、`gamenid=tiles.WTifTilDetail2`の要求、
  ログイン入口`WOpacInitLoginActiontemp.do`、`WPwdLoginCheckAction.do`のGETは実測に基づく
  変更のため維持している。
- 確認済み(2026-07-26、ローカル): `./gradlew :app:testDebugUnitTest :app:assembleDebug`が
  両方成功。
- **未検証事項**: ネットワーク層の観測で実際の送信内容を比較できるようになったが、それに
  よって予約成立の原因がヘッダかCookieか他の要因かはまだ特定できていない。次はライブ
  dry-run診断で`onWireRequest`のログを取得し、ブラウザ実測との差分を突き合わせる必要がある。

### 予約が成立しなかった真因（2026-07-26、確認済み）

予約確定POSTだけがセッションCookieを送っていなかった。原因はKotlinの変数シャドーイングである。

```kotlin
class LicsXpSession private constructor(
    client: OkHttpClient,                       // コンストラクタ引数
) {
    private val sourceClient = client
    private val client: OkHttpClient = sourceClient.newBuilder()
        .cookieJar(cookieJar)                   // Cookieとヘッダはここで付く
        .addInterceptor { ... }
        .build()
    private val noRetryClient = client.newBuilder()  // この client はコンストラクタ引数
        .retryOnConnectionFailure(false)
        .build()
}
```

プロパティ初期化子では同名のコンストラクタ引数がプロパティより優先される。そのため
`noRetryClient` はCookieJarもインターセプタも持たない素のクライアントから作られていた。
確定POSTだけがこのクライアントを使うため、サイトから見れば未知のセッションからのPOSTとなり、
業務エラーではなく入口画面（詳細検索）へ差し戻されていた。観測された「200・拒否理由の
メッセージなし・詳細検索画面」と完全に一致する。

コンストラクタ引数を `httpClient` へ改名してシャドーイングを解消した。回帰試験は
`ExactlyOncePostClientTest`（確定専用POSTが通常要求と同じCookieとUser-Agentを送ること）。

発見の経緯: 送信時点の観測を `addNetworkInterceptor` へ移したところ、確定POSTだけ `wire` の
記録が出なかった。他の全リクエストには記録があり、直前のDisp POSTにもあった。確定POSTだけが
別のOkHttpClientを使っていることが手がかりになった。

#### この真因の判明によって未検証に戻った事項

次の変更はいずれもブラウザ実測との差分を埋める目的で入れたが、**予約成立に必要かどうかは
未検証**である。真因が別にあった以上、必要性は再評価の余地がある。現在の構成で予約が成立する
こととの両立は確認済みだが、各変更が無害であることや将来も必要であることまでは確認していない。

- 予約導線のログイン入口を `WOpacInitLoginActiontemp.do` にしたこと
- 認証直後の `WPwdLoginCheckAction.do` のGET
- 書誌詳細の入口を `WOpacMsgNewListToTifTilDetailAction.do` にしたこと
  （`gamenid=tiles.WTifTilDetail2` の要求も含む）

一方、`contactweb` → `contactdirectweb` の修正は、これが無ければ確認フォームを特定できず
確定POST自体が送られないため、真因とは独立した実バグである。

### 予約成立の確認（2026-07-26、確認済み）

リポジトリ所有者による実機検証で、**カート経由・即時予約の両方で予約が成立し、実サイトの予約状況
一覧への反映も確認できた**。長期にわたって成立しなかった不具合は解決である。

真因は直前の節に記した「確定POSTだけがセッションCookieを送っていなかった」ことである。

#### 必要性が未検証のまま残っている変更

原因調査の過程で、ブラウザ実測との差分を埋める目的で次を入れた。真因が別にあったため、**予約成立に
必要かどうかは検証されていない**。現在の実機成功と両立しているため直ちに外す理由はないが、
無害性が証明されたわけではない。現状維持とし、変更する場合だけ一項目ずつ対照実験する。

- 予約導線のログイン入口を `WOpacInitLoginActiontemp.do` にしたこと
- 認証直後の `WPwdLoginCheckAction.do` のGET
- 書誌詳細の入口を `WOpacMsgNewListToTifTilDetailAction.do` にし、`gamenid=tiles.WTifTilDetail2` を
  要求するようにしたこと

これらを外す場合は、**必ず実機で予約成立を再確認すること**。ユニットテストでは検証できない。

`contactweb` → `contactdirectweb` の修正は、これが無ければ確認フォームを特定できず確定POSTが
送られないため、真因とは独立した実バグである。維持が必須。

#### ブラウザとアプリで依然として異なる点（現在の成功構成と両立することを確認済み）

- ブラウザは `WPwdLoginCheckAction.do`、新着一覧、書誌詳細への遷移をいずれもPOSTで行う。
  アプリはGETで代用している
- ブラウザは書誌詳細の前に新着ジャンル一覧を経由する。アプリは経由しない
- ブラウザは `Accept` / `Upgrade-Insecure-Requests` / `Sec-Fetch-*` / `sec-ch-ua*` を送る。
  アプリは `User-Agent` と `Accept-Language` のみ
- ブラウザは `v=PC` とWAF系のCookieを保持する。アプリは `JSESSIONID` のみ

#### この調査から得られた教訓

- 送信内容の観測は、アプリが組み立てた時点ではなく**実際にネットワークへ出た時点**で行う必要がある。
  組立て時点の観測を信用したため、Cookieが欠落している事実を長期間見落とした
- 実機検証では、**実行中のAPKがどのコミットのものか**を毎回確認する。古いAPKでの検証を3回行った。
  現在は `BuildConfig.GIT_SHA` を診断ログと設定画面に出しているので、これを確認すること

### 予約機能実装・失敗検証レビュー（2026-07-27）

#### 結論

- **確認済み**: 予約機能の境界設計と安全策は概ね妥当で、現在の主要障害は解消済みである。
  `ReservationGateway`への書き込み分離、アプリ内カート、最終確認後だけの送信、exactly-once、
  成否不明時の再送禁止と予約一覧照合は維持すべきである。
- **確認済み**: 失敗調査は安全性、記録、仮説の反証という点では良好だったが、観測と比較の順序が
  非効率だった。特に`contactdirectweb`修正後、確定POSTが実際に送られるようになった最初の失敗時点で、
  確認画面表示POSTと確定POSTのwire上のCookie・クライアント経路を比較すべきだった。
- **確認済み**: 「もっと早く全通信を突き合わせるべきだった」という指摘は妥当である。ただし、
  `contactweb`誤認中は確定POST自体が送られていなかったため、その段階ではフォーム特定の修正が先でよい。
  非効率が明確になったのは、`contactdirectweb`修正後も実予約が失敗した時点からである。
- **確認済み**: 真因はサイト固有のフォーム・順序・待ち時間ではなく、確定専用`noRetryClient`だけが
  設定済みCookieJar/interceptorを継承しなかった実装バグである。`b66c006`で修正し、
  `ExactlyOncePostClientTest`で`Set-Cookie`受領後の確定POSTに`JSESSIONID`が付くことを回帰試験している。

#### 実装に関する評価

- **良かった点（確認済み）**
  - 公式サイトのカートを操作せず、Roomのアプリ内カートと直接予約通信を分離したため、カート追加時の
    不要な通信とアカウント切替を避けられている。
  - 確定POST専用経路を一般リトライから外し、送信後は予約一覧で照合するため、通信断時の二重予約を
    防ぐ設計になっている。
  - 確認フォームは実DOMのsuccessful controlsを順序どおり引き継ぎ、アプリが上書きする値を
    `receivename`と`contact`へ限定している。`contactweb`から`contactdirectweb`への訂正は、
    真因とは別に必要だった実バグ修正である。
  - カート経由と即時予約の双方で実サイトへの成立・予約一覧反映を確認している。
- **改善済みの問題（確認済み）**
  - 通常経路とexactly-once経路でOkHttpClient設定が分岐し得るのに、当初のテストは送信回数や
    画面遷移を主に検証し、Cookieの状態継承を保証していなかった。`noRetryClient`を設定済み
    `client`から派生させ、Cookie/headerを検証する回帰試験を追加した現状は妥当である。
- **残る設計上の注意（未検証）**
  - ログイン入口、`WPwdLoginCheckAction.do`、書誌詳細入口と`gamenid`の変更は、現在の成功構成とは
    両立しているが、予約成立への必要性は単独検証していない。削除も「必須」とする断定もせず、
    次に触る必要が生じたときだけ一項目ずつ実機対照実験する。

#### 失敗検証の進め方に関する評価

- **良かった点（確認済み）**
  - ライブ診断を通常テスト・CIから除外し、明示的な資格情報と対象資料がある場合だけ動かした。
  - 確定POSTを送らないdry-run、重複確認、exactly-once、ログの秘密情報マスキングにより、
    調査による誤予約・認証情報漏えいのリスクを抑えた。
  - 各仮説と反証をGit・`site-research.md`・本書へ残し、効果が確認できない新着一覧経由や
    ブラウザ風ヘッダをrevertしてベースラインへ戻した。
  - wire観測へ切り替えた後は、確定POSTだけ観測されない差から別クライアントを特定し、真因と
    症状を説明できるところまで到達した。
- **非効率だった点（確認済み）**
  - ブラウザとアプリの比較が、確定POST本文、ヘッダ、`.do`パス列など部分ごとに進み、全要求を
    同じ粒度で並べる基準表が遅かった。そのため、メール選択往復、hash、入口アクション、ヘッダ、
    新着一覧コンテキストなど、真因でない仮説の検証が先行した。
  - prepared requestのログを実送信ログとして扱い、CookieJar/interceptor適用後を見ていなかった。
    「確定POSTだけ別のexactly-onceクライアント」というコード上の境界を、調査開始時の比較軸に
    入れていなかった。
  - 古いAPKで3回検証し、現行コミットに対する反証にならない結果を得た。現在のSHA表示は妥当な是正。

#### 現状に対して行うべき変更

1. **必須・文書**: `backend-design.md`の「予約UI未実装」「実サイト予約未検証」など、現在と異なる
   記述を現行状態へ更新する。`site-research.md`と本書の調査履歴は削らず、規範と履歴を分離する。
2. **必須・文書**: 「サイトは拒否理由を一切表示しない」は観測範囲を超えるため、
   「確定POSTの直後応答と現行パーサでは理由を取得できていない」と記す。ブラウザではダイアログが
   観測され、その後の`WOpacCommonBackAction.do`も存在するため、サイト全体に理由が無いとは断定しない。
3. **推奨・コード**: `StayedOnConfirmation`後に対象`tilcod`が一覧に無いことを
   `REJECTED_BY_SITE`へ確定する前に、取得した一覧が完全であることを確認できるガードを検討する。
   「上限20件・20件でも1ページ」は現在の実測事実だが、恒久的なサイト仕様とは未確認である。
   サマリ件数と解析行数の不一致、または次ページの兆候がある場合は拒否と断定せず
   `VERIFICATION_UNAVAILABLE`相当に倒す案が安全である。
4. **推奨・テスト**: `ExactlyOncePostClientTest`は今回の回帰を直接防ぐため維持する。将来
   OkHttpClient生成を変更するときは、通常要求で得たCookieが確定POSTへ渡る一連のテストを
   受入条件に含める。実サイト固有の成功をMockWebServerだけで保証したとは表現しない。
5. **任意・調査**: 拒否理由の取得がUI要件になった場合だけ、ブラウザHARでダイアログ表示から
   `WOpacCommonBackAction.do`までを調べる。これは現行の予約成立障害の修正には不要であり、
   書き込みアクションの可能性を確認して所有者の承認を得るまでアプリから送信しない。

現時点で、予約成立のために追加のコード変更が必要という証拠はない。上記3は失敗判定の堅牢化、
上記1・2は文書の現行化、上記5は必要になった場合だけ行う別調査である。

## 今後の構想（2026-07-26時点。未着手・未設計）

リポジトリ所有者が挙げた要望を記録する。**具体的な設計・実装には着手していない。**
着手する際は、それぞれ設計から始めること。

### 1. 予約中画面に現在の予約数を表示する

- 参照系のみで実現できる。サイトのログイン後共通ヘッダに利用状況サマリ（予約中N件）があり、
  予約状況一覧の行数からも数えられる（site-research.md §4）。
- 予約上限との対比を出すなら、上限値の取得手段が必要。**予約上限は未検証**（§6.4）。

### 2. 予約の削除機能

- **現行の「禁止事項」と衝突する。** 本書の禁止事項1で、予約取消はサイト書き込みとして禁止している。
  実装するなら、まず禁止事項の側を所有者の判断で改めること。
- サイト側の削除導線・確認画面・応答は未調査。読み取り専用ポリシーのもとで削除ボタンには
  触れていない（§4.2の読書履歴でも同様）。

### 3. 本棚（マイ本棚）の編集機能

- **現行の「禁止事項」と衝突する。** 禁止事項1の「登録変更」に該当する。同上。
- 本棚の切り替え（表示状態の変更）だけは実装済みで、読み取り専用ポリシーの範囲内としている（§4.1）。
  追加・削除・メモ編集は未調査。

### 5. 貸出中画面からの貸出延長（2026-07-27追記。未着手・未設計）

貸出中の資料の返却期限を延長する操作を、貸出中画面から行えるようにする。

- **サイト側の導線は未調査。** 貸出状況一覧（`gamen=usrlend`）の画面内アクションを、予約状況一覧に
  対して行ったのと同じ方法（診断ログの画面スクリプト抽出）で調べるところから始める。
  §9に予約状況一覧の例がある
- 延長には図書館ごとの制約があるのが一般的で、次はいずれも未確認である。延長可能な回数、
  予約が入っている資料の可否、延長後の返却期限の決まり方、延長不可のときのサイト応答
- 書き込み操作であるため、機能ごとに設計と所有者の承認を経てから実装する（§禁止事項1）。
  予約確定・予約取消と同じく、専用ゲートウェイへの隔離、送信一回限り、成否不明時は再送せず
  照合で解決、という不変条件を満たすこと
- 延長の成否は、貸出状況一覧の返却期限が変わったことで照合できる見込み（未検証）

### 4. 新着資料からキーワード一致で自動予約する

- **現行の「禁止事項」と最も強く衝突する。** 禁止事項1は「ユーザーが明示操作し最終確認した直接予約確定
  だけ」を許可しており、自動予約を名指しで禁止している。spec.md の決定にも関わるため、所有者の明示的な
  方針変更なしに着手してはならない。
- 設計上決める必要がある事項:
  - **予約上限との関係**。上限に達した状態で自動予約が走るとどうなるかは未検証（§6.4）
  - **優先順位の決め方**。キーワードの優先度、メンバー間の配分、上限枠の割り当て
  - 誤検出時の取消手段（2と同じ問題に行き着く）
  - 実行タイミングと頻度（現行のアクセス頻度方針は自動同期1日1回・要求間500ms以上）
  - ユーザーへの事前・事後通知
- 新着資料の取得自体は実装済み（認証不要のGETのみ、§5b）。

## 未検証事項（2026-07-26追加）

### 複数メンバー分をカートに入れた状態での一括予約

本Aをアカウントa、本Bをアカウントbに割り当ててカートに入れ、一度の確定操作で両方を予約できるか
**実機で未検証**である。

- 実装上は対応している。`ReservationCartRepositoryImpl.execute` が対象を `memberId` でグループ化し、
  メンバーごとに分離セッションでログインして順に処理する。認証失敗や中断は当該メンバーの残件だけを
  止め、他メンバーは続行する設計で、ユニットテストもある。
- 実機で確認できていない理由は、検証用アカウントの一方が予約上限に達しており、失敗した場合に
  **上限によるものかアプリの不具合かを切り分けられない**ため。
- 検証するには、両アカウントとも予約枠に余裕がある状態を作る必要がある。
- 関連する事項（解決済み、2026-07-27）: 予約上限に達したときのサイト応答は実測済みで
  （site-research.md §6.4）、`FailureReason.RESERVATION_LIMIT_EXCEEDED`に落ちるようになった。
  ただし複数メンバー分の一括予約自体の実機検証はまだ行っていない。

## 新着キーワード自動予約のロードマップ（2026-07-26。設計以前の整理）

「今後の構想」4番の実装に向けた前段の整理である。**まだ設計ではない。** 未決定事項が残っており、
所有者の回答を得てから詳細設計に入る。

### 大枠の流れ

1. **キーワード登録** — 監視キーワードをRoomに保存。追加・削除・有効/無効切替のUIを新設する
2. **照合** — 新着資料を取得し、各資料をキーワードと突き合わせる
3. **除外** — 予約中・貸出中・読書記録にある・過去に自動予約した資料は対象外にする
4. **枠の割当** — 予約上限を超えないよう、候補を優先順位で並べて枠の分だけ選ぶ
5. **実行** — 既存の `ReservationCartRepository` の予約確定経路をそのまま使う
6. **通知と記録** — 結果を通知し、判定履歴をRoomに残す（再処理防止と後追い確認のため）

### 流用できる既存資産

- 新着資料の取得（実装済み。認証不要のGETのみ、28ジャンル巡回、500ms間隔。§5b）
- 予約確定通信（2026-07-26に実機で成立を確認済み）
- 通知基盤（`NotificationPlanner` / `NotificationService`）
- 正規化照合（読書記録で使用。Unicode NFKC・空白除去・小文字化）
- `SyncWorker`（日次実行の器）

### 新規に必要なもの

- 監視キーワードのテーブルと管理画面
- 自動予約の判定履歴テーブル（再処理防止）
- 枠管理と優先順位のロジック
- 日次実行への組み込み

### 予約上限の扱い（方針確定）

**所有者の判断（2026-07-26）**: 予約数やカテゴリを事前に数えて送信可否を判断することは**しない**。
**予約を試みて失敗したら予約しない、それだけで足りる。** したがって上限値の取得やカテゴリ判定を
実装に埋め込む必要はない。上限の値は site-research.md §6.4 に参考情報として記録した。

**2026-07-27追記（解決済み）**: 実際に確定POSTを送るライブ診断で上限超過時のサイト応答を実測した
（site-research.md §6.4）。成功時も上限超過時も応答本文は同じ「予約確認」画面であり、違いはページ内
スクリプトのダイアログ文言だけである。この文言（`予約制限を`＋`越えています`の包含）を検出したときは
`FailureReason.RESERVATION_LIMIT_EXCEEDED`として、予約一覧との照合を行わずに拒否確定する。詳細は
後述の「上限超過・成功ダイアログ文言の解決（2026-07-27実装）」を参照。

以下は上記実測より前の記述であり、historical context として残す。当時は確定POSTが成立しなかった
事例を観測していたが理由不明のまま、確認画面の再表示と予約一覧照合だけで拒否を推定していた
（`FailureReason.REJECTED_BY_SITE`。今も文言が検出できない場合の残余経路として使われる）。

### 未決定事項（所有者の判断待ち）

| # | 論点 | 選択肢 | 提案 |
|---|---|---|---|
| 1 | 確認モデル | (a)完全自動 / (b)通知して1タップ確定 / (c)候補通知のみ | (b) |
| 2 | 予約枠の管理 | (a)自動予約の上限冊数をメンバーごとに設定 / (b)手動用に残す枠を設定 / (c)使い切ってよい | (a) |
| 3 | キーワードとメンバーの対応 | (a)キーワードごとに1人指定 / (b)共通＋予約先1人 / (c)複数メンバー同時 | (a) |
| 4 | 実行契機 | (a)日次同期 / (b)手動のみ / (c)両方 | (c) |

提案の理由:

- 1は誤検出時の被害（不要な予約が枠を埋め、取消手段が現状ない）が実質的であるため。ただし
  「就寝中に人気新刊を確保する」ことが主目的なら(a)でなければ意味がない。目的次第で変わる
- 2は分かりやすく、予約上限値が未検証でも安全側に倒せるため
- 3は(c)だと同じ本を家族で重複予約することになり実用性が低いため
- 4は新着巡回が28ジャンル×500msで約15秒であり、日次に載せても負荷が許容範囲であるため

### 既定案（異論がなければこれで設計する）

**照合の仕様**

- 対象フィールド: 書名・巻次・著者・出版者（分類は含めない）
- 一致方式: 正規化後の部分一致（NFKC・空白除去・小文字化。読書記録検索と同じ）
- 複数語: スペース区切りでAND
- 除外語: `-word` の形で対応する
- 大文字小文字・全角半角は区別しない

**優先順位（枠が足りないとき）**

キーワードの登録順（上が優先）→ 同一キーワード内では貸出可の資料を優先 → 出版年月の新しい順。
「キーワードごとに優先度を数値で持つ」案もある。

### 関連する依存

**予約の削除機能（構想2番）は自動予約と実質セットである。** 誤検出した予約を取り消せないと運用が
苦しい。確認モデルで(a)完全自動を選ぶ場合は、削除機能を先に実装することを強く推奨する。

### 受け付けられなかった確定POSTの解決（2026-07-27実装）

理由は不明ながら確定POSTが成立しなかった事例を観測した（site-research.md §6.4）。その直後応答は
現行パーサが取得できる理由を含まず、予約確認画面を再表示していた。また予約状況一覧は、利用状況
サマリの件数と解析行数が一致する範囲ではページングされないと実測した（同 §9）。現行サイト・
検証アカウントでは、1ページ目の一覧照合で対象を見落とさないことを確認済みである。ただし、これを
恒久仕様として保証する資料はなく、将来のサイト変更時にも偽陰性が起きないとは断定しない。

これを受けて、確定POST後の応答が確認画面のまま（`DirectReservationResponseParser.Result` /
`DirectReservationAttempt` の `StayedOnConfirmation`）だった場合を、従来の「成否不明
（`IndeterminateAfterPost`）」から区別した。`ReservationCartRepositoryImpl` は
`StayedOnConfirmation` を受けると予約一覧を1回だけ取得して照合し、対象`tilcod`が無ければ
`FailureReason.REJECTED_BY_SITE`（拒否）として確定し、**同一メンバーの次の項目は中止せず続行する**
（資料種別ごとの上限などで次の資料は成功し得るため）。一覧にあれば防御的に成功扱いにする。
一覧取得自体に失敗した場合だけ`Unknown(VERIFICATION_UNAVAILABLE)`とし、残り項目は従来どおり中止する。
セッション切れ後の再試行経路にも同じ分岐を入れてある。UI文言（`ReservationUiController`）も
「予約上限に達しているなどの理由が考えられます」と、断定しない表現に改めた。

**一覧の完全性ガード（2026-07-27追加）**: 上記の「拒否」断定は「予約上限20件・一覧が非ページング」
という現時点の実測に依存していた。これはレビューで指摘されたとおり恒久的なサイト仕様として
保証されたものではなく、将来サイトが変わり一覧がページングされると、成立しているのに1ページ目に
対象が無いだけで誤って拒否と断定してしまう恐れがある。そこで、取得した一覧が完全だと確認できた
ときだけ拒否と断定し、確認できない場合は成否不明へ倒すガードを追加した。

ログイン後の共通ヘッダには利用状況サマリがあり、既存の`SummaryParser`が予約中件数
（`UserSummary.reservationCount`）を解析できる。`LicsXpReservationSession.fetchReservations()`は
その先頭で`WOpacMnuTopInitAction.do?WebLinkFlag=1`を取得しており、このHTMLに既にサマリが
含まれているため、新たなリクエストを発生させずに件数を得られる。これを利用し、
`fetchReservationSnapshot()`（`internal ReservationSnapshotSource`）がサマリの予約中件数と
一覧の解析行数を突き合わせ、一致した場合だけ`ReservationListSnapshot.complete = true`を返す。
サマリが解析できない場合や件数が一致しない場合は`complete = false`とし、件数だけを
`noteDiagnostic`へ記録する（書名などは記録しない）。

`ReservationSession`は公開インターフェースであり、新規メソッド追加は既存実装（テストのフェイクを
含む）を破壊するため、この完全性判定機能だけを別の internal インターフェース
（`ReservationSnapshotSource`）に切り出し、`ReservationCartRepositoryImpl.resolveStayedOnConfirmation`
側で`as?`により任意に取得する形にした。実装していないセッションに対しては、完全性を確認できない
ものとして安全側（成否不明）に倒す。

`resolveStayedOnConfirmation`の判定は次のとおりになった。
- 対象`tilcod`が一覧に**ある** → 完全性に関わらず`VerifiedSuccess`（従来どおり）
- 対象が**無く**、かつ一覧が**完全** → `Failure(REJECTED_BY_SITE)`。残り項目は中止せず続行する（従来どおり）
- 対象が**無く**、一覧の完全性を**確認できない** → `Indeterminate(VERIFICATION_UNAVAILABLE)`。残り項目は中止する
- 一覧の取得自体が例外 → 従来どおり`Indeterminate(VERIFICATION_UNAVAILABLE)`、残り項目は中止

`ReservationGatewayTest`にサマリ件数と解析行数が一致／不一致／サマリ解析不能の3ケースを追加し、
`ReservationCartRepositoryTest`の既存拒否系テストは、フェイクセッションが`ReservationSnapshotSource`を
実装して`complete = true`を返すよう更新した上で、完全性を確認できない場合に拒否と断定せず
残件を中止する新規テストを追加した。


### 予約一覧の完全性ガードのライブ確認（2026-07-27、書き込みなし）

dry-run診断（`:app:liveReservationInspect`）に予約一覧の取得を追加し、実サイトで確認した結果は
`parsed=19 complete=true`。**利用状況サマリの予約中件数と、解析した一覧の行数が一致した。**
ガードは実アカウントで正しく通り、過剰に成否不明へ倒すことはない。

**2026-07-27追記（解決済み）**: この時点では上限超過を意図的に発生させる試験は未実施だったが、
後日実施し、上限超過時の応答文言を実測した（site-research.md §6.4、下記「上限超過・成功ダイアログ
文言の解決」参照）。

### 上限超過・成功ダイアログ文言の解決（2026-07-27実装）

ライブ診断で確定POSTを繰り返し実行し、上限超過時・成功時それぞれの応答本文を実測した
（site-research.md §6.4）。判明したのは次のとおり:

- 確定POST応答は**成功時も上限超過時も同じ「予約確認」画面**（`gamenid=tiles.WYoyConfirm`）である
- 唯一の違いはページ内スクリプトのダイアログ文言。成功時は
  `予約登録しました。確認したい場合は予約状況一覧で確認して下さい。`、上限超過時は
  `図書・雑誌は予約制限を1冊越えています。`（区分名・冊数は可変）

これを受けて`DirectReservationResponseParser`に、既存の`LicsXpSession.extractSiteMessages`を再利用した
文言判定を追加した。`予約制限を`と`越えています`の両方を含む文言があれば`Result.LimitExceeded(message)`、
`予約登録しました`を含めば`Result.Registered`とする。判定順序はログイン画面・既知重複alertが最優先、
次に`LimitExceeded`、`Registered`、メンテナンス、最後に従来の`StayedOnConfirmation`（文言なし）・
`IndeterminateAfterPost`とした。`ReservationGateway`側にも`DirectReservationAttempt.LimitExceeded`
（`message`を運ぶ）と`.Registered`を追加した。

`ReservationCartRepositoryImpl`の解決方針:

- `LimitExceeded`を受けたら、サイトが理由を明示しているため**予約一覧との照合を行わずに**
  `FailureReason.RESERVATION_LIMIT_EXCEEDED`として拒否確定する。資料区分ごとに上限が異なるため、
  同一メンバーの**次の項目は中止せず続行する**。サイトの文言は
  `ReservationOutcome.Failure.siteMessage`（新設の省略可能フィールド）に保持し、UIへ渡す
- `Registered`を受けても、**成否判定の最終根拠は従来どおり予約一覧照合のままにする**
  （`resolveStayedOnConfirmation`を`StayedOnConfirmation`と同様に呼ぶ）。実測できたのはこの1つの
  成功文言だけであり、サイトが将来別の文言を返す可能性を排除できないため、文言だけを信頼する
  設計へは変えなかった
- セッション切れ後の再試行経路にも同じ分岐を入れた

UI（`ReservationUiController`）は、`RESERVATION_LIMIT_EXCEEDED`のとき`siteMessage`があればそのまま
表示し、無ければ既定文言「予約できる冊数の上限に達しています」を使う。

### ライブ診断の実行体制（2026-07-27）

カード番号とパスワードは所有者がWindowsのユーザー環境変数へ設定済みで、担当AIは値を読み出さずに
実行時へ引き渡せる。PowerShellから次の形でユーザースコープを読み込む（値は出力しない）。

```
$env:LICSXP_CARD_NUMBER=[Environment]::GetEnvironmentVariable('LICSXP_CARD_NUMBER','User')
$env:LICSXP_PASSWORD=[Environment]::GetEnvironmentVariable('LICSXP_PASSWORD','User')
```

- **書き込みなしのdry-run（`liveReservationInspect`）は担当AIが自走してよい。** 仮説検証の往復を
  所有者に依存しない
- **書き込みを伴う診断（`liveReservationDiagnostic`）は都度、所有者の承認を得てから実行する。**

### UI改修: FLAG_SECUREの撤去とテキスト選択の許可（2026-07-27）

`MainActivity.onCreate`冒頭で設定していた`window.setFlags(FLAG_SECURE, FLAG_SECURE)`を削除した。
理由は次のとおり。
- 端末のスクリーンショットが真っ黒になり、不具合報告や調査で画面を共有できなかったため
- パスワード・カード番号をUIへ表示する箇所は無く（暗号化ストレージに保持し、画面には出していない）、
  表示されるのは家族の貸出・予約状況のみであること
- 仕様書・設計書（`docs/spec.md`等）に`FLAG_SECURE`を要求する記述は無いこと

再度スクリーンショット禁止に戻したい場合は、`MainActivity.onCreate`で上記の`window.setFlags`呼び出しを
書き戻せばよい（`android.view.WindowManager`のimportも合わせて要復元）。

あわせて`LibraryApp`の`Scaffold`内、`innerPadding`適用後の`Box`の内側全体を
`androidx.compose.foundation.text.selection.SelectionContainer`で包み、画面上のテキストを
長押しで選択・コピーできるようにした。書誌詳細・診断ログのオーバーレイやダイアログもこの`Box`の
内側にあるため、まとめて対象になる。ドロワー（`ModalDrawerSheet`）と下部ナビ（`Scaffold`の
`bottomBar`）はこの`Box`の外側にあるため対象外。

`SelectionContainer`は長押し起点のテキスト選択ジェスチャーのみを追加するもので、
`clickable`/`toggleable`によるタップ判定を妨げない。本リポジトリには`onLongClick`/
`combinedClickable`を使う箇所が無いことを確認しており、ボタン・スイッチ・一覧行のタップや
`OutlinedTextField`への入力は従来どおり機能する（`OutlinedTextField`は自身の選択状態を
独立して管理するため、外側の`SelectionContainer`と競合しない）。そのため`DisableSelection`による
個別除外は行っていない。

### 予約取消の1段階実装が未完成だった時点（履歴。後続の`0dbb0a0`で2段階目実装済み）

以下は`10513b1`時点の履歴である。実サイトへ
`WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1`をPOSTして初めて、当時の1段階実装では
予約取消を実際にサイトへ反映できないことが判明した。

- サイトの取消操作は「確認ダイアログ表示→OK→実際の取消送信」の**2段階**であり、アプリが送信する
  1回のPOSTは1段階目（確認ダイアログ`予約の取消を行います。よろしいですか？`を含む一覧画面が
  返るだけ）にしか到達しない。取消後の一覧に対象が残っていることを実測で確認済み。
- OK後に何を送信すれば実際に取り消されるかは**未特定**。共通JS `lbwebdialog.js`の`lbConfirm`は
  `confirm()`をラップして真偽値を返すだけで、OK後の遷移先はページ固有スクリプト側にある。
- 併せて、旧実装の拒否判定（「できません」「越えています」という一般語）は、全ページ共通で
  埋め込まれているJS定数（`仮パスワードでは利用できません。パスワード変更を行なってください。`）
  にも誤反応する欠陥があったため削除した。現在は実測した具体的な文言（`取消を行います`）だけで
  「確認画面が返った(未完了)」ことを判定する`ReservationCancelAttempt.ConfirmationRequired`を返す。
- 診断（`LicsXpSession.extractScreenScriptFieldAssignments`）を強化し、`confirm(`/`lbConfirm(`を
  含む関数の本体、およびトップレベルの`confirm`/`lbConfirm`呼び出し文を診断ログへ出力できるように
  したが、本実装時点ではOK後の送信内容の実測はまだ行っていない。

この課題は、所有者がブラウザの実Request payloadを採取した後、`0dbb0a0`で2段階目を実装して
コード上は解決した。現在は、1段階目の本文を保持し、先頭へ
`mngFlg2_handan=1`・`kbnchgflag=1`、末尾へ`okCodes=OPACUSR001`を加え、クエリ無しの
`WOpacUsrRsvCancelAction.do`へ2段階目を送る。MockWebServerでは順序・同名重複・Referer/Origin・
送信回数を検証済みである。一方、**このアプリ実装による実サイト取消成功は未検証**であり、
UIも未実装である。

### 予約取消の実装・検証レビュー（生ログ監査、2026-07-27）

#### レビュー指摘への対応（2026-07-27、実サイト未検証）

レビューで必須としたバックエンド堅牢化を実装した。UIは引き続き未接続であり、アプリ実装による
実サイト取消成功も未検証である。

- 取消依頼は`memberId`・`tilcod`・`cancelCode`の3値で固定する。送信前の予約一覧を
  `ReservationListParser`で再解析し、`cancelCode`一致行から一意・非空の`tilcod`を取得して、
  依頼時の`tilcod`と一致する場合だけ進む。対象特定不能・不一致は取消POST前に
  `LibraryError.Parse`で停止する。
- 取消後はメニューを再取得して最新予約件数サマリを取り、一覧解析行数と一致する完全一覧でのみ、
  固定した`tilcod`行の消失を`Cancelled`とする。取消ボタン（`cancelCode`）だけの消失、対象残存、
  サマリ/一覧の解析不能、件数不一致はすべて`IndeterminateAfterPost`である。
- 1段階目から2段階目へは、静的な文言ではなく、stage1応答を再解析して同じ`cancelCode`→同じ一意の
  `tilcod`を持つ予約行が残ること、さらに`src`無しかつ標準JavaScript MIMEの実測済み
  legacy script構造（外側の`if (document.all || IS_EXPLORER_11 || isEdge)`→
  `lbConfirm`/`lbConfirm1`/`window.confirm`→`okArray`へ`OPACUSR001`→`for`ループ内の
  `newHidden.type/name/value`→`document.prevRequestForm.appendChild(newHidden)`）全体に一致することを
  両方確認してから進む。外側if候補は、丸括弧・角括弧の外かつ同一blockの文頭ならblock深度を問わず抽出する。
  2段階目の本文は送信前に一意に解析済みの`cancelForm`を再利用するため、stage1応答のformは再解析しない。
  JavaScriptを一般解釈せず、これは「直前に利用者が指定した対象を残す応答が既知プロトコル署名を持つ」
  ことを確かめる複合ガードである。コメント・文字列・template literal・正規表現・非JavaScript script・
  関数/class/arrow関数・括弧不整合はすべて2段階目なしとして照合へ進む。2段階目後も文言だけでは判定せず、一覧照合する。
- 各取消POSTは「クライアントが自動再試行しない1回限りの試行」であり、ネットワーク上の厳密な
  exactly-once保証ではない。1段階目・2段階目の通信断は再送せず成否不明とする。

追加した回帰試験は、`cancelCode`だけ消えるケース、一覧不完全/サマリ解析不能、2段階目通信断、
完全一覧での`tilcod`消失、stage1での対象消失/`tilcod`不一致、コメント・文字列・template literal・
正規表現・`src`付き・template/json script・未呼出し関数・direct `if (false)`・分割代入関数・
トップレベル`return`・括弧不整合に埋め込まれた取消構造を扱う。

#### 初回アプリライブ取消診断と署名修正（2026-07-27）

- **確認済み**: 専用CLI診断の初回実行は、1段階目POSTのみHTTP 200で完了し、
  `IndeterminateAfterPost`を返した。取消後一覧には対象`tilcod`が残り、2段階目POSTは送信されていない。
  このため当該試行による実サイトの取消は不成立である。
- **確認済み**: 原因は、当時の`OBSERVED_LEGACY_CANCEL_CONFIRMATION`が内側の`if (0 != 1)`から始まる
  合成構造だけを許可していたこと。実サイトでは外側に
  `if (document.all || IS_EXPLORER_11 || isEdge)`があり、内側の非該当分岐には`lbConfirm1`も存在する。
  不一致時は2段階目を送らないフェイルクローズであり、余分な取消POSTは発生しなかった。
- **対応済み（再ライブ未実施）**: 署名を外側のブラウザ判別から、`rest`→`OPACUSR001`→
  `for`ループ内の`newHidden.type/name/value`→`document.prevRequestForm.appendChild(newHidden)`までの実測順へ
  限定した。旧来の内側断片だけ、tailだけが`if (false)`内、確認文言に前後の文字列がある場合は2段階目へ
  進まない回帰試験も追加した。ユニットテストが通っても、実サイトでの
  取消成立はまだ未確認である。次のライブ診断は所有者承認のある1件だけで実施し、POST段階数、結果、
  取消後一覧の対象有無を記録すること。

#### 2回目アプリライブ取消診断と巨大script対応（2026-07-28）

- **確認済み**: 初回対応後の再実行も1段階目POSTだけがHTTP 200で完了し、
  `IndeterminateAfterPost`、取消後一覧の対象`tilcod`残存、2段階目POST未送信となった。今回も取消は不成立である。
- **確認済み**: 実測確認構造は巨大な同一`script`内にあり、候補外のfunction/class/arrow関数/正規表現まで
  `sanitizeLegacyCancelScript`が拒否していた。これは安全側の停止であり、余分な取消POSTは発生していない。
- **対応済み（再ライブ未実施）**: raw script全体を緩和して受理するのではなく、コメント・文字列等を飛ばす
  字句走査でトップレベルの実測済み外側ifを探し、連続する「外側if/else」「if(rest)/else」「for(okArray)」の
  3文だけを括弧対応で切り出す。切り出した候補内部には従来のサニタイズと固定署名を厳格に適用する。
  template literal（backtick）が同一script内にあれば候補全体を拒否する。`/`はトークン文脈で正規表現開始を
  判定し、曖昧な場合を正規表現として扱うことで偽陽性より偽陰性を選ぶ。巨大script内の正例、候補内部の
  禁止構文、非トップレベル、`if (false)`内tail、template literal、`if`/`else`/arrow直後の正規表現内署名の
  負例をユニットテストで扱う。
  アプリによる取消成立は未検証のままであり、次のライブ診断は所有者承認の対象1件で実施する。

#### 3回目アプリライブ取消診断と安全な原因集計（2026-07-28）

- **確認済み**: 3回目も1段階目POSTだけがHTTP 200で完了し、`IndeterminateAfterPost`、取消後一覧の
  対象`tilcod`残存、2段階目POST未送信となった。今回も取消は不成立であり、推測による署名緩和・再送は行っていない。
- **対応済み（再ライブ未実施）**: 既存の`matched`判定と第2段階POST可否は変えず、
  `cancel-reservation-signature`診断へ無害な集計だけを記録する。項目はinline script数、完全一致確認文言数、
  外側if数、字句走査の完了/拒否理由別件数、candidate数、sanitize成功数、固定署名一致数、最終`matched`である。
  HTML・script本文・資料コード・取消コード・hash・認証情報はログに含めない。MockWebServerで通常正例、巨大script正例、
  template拒否例のsummaryと取消POST回数を検証した。次のライブ診断は、この集計を採取して停止原因を確認すること。

#### 4回目アプリライブ取消診断と構造抽出観測（2026-07-28）

- **確認済み**: 4回目も1段階目POSTだけがHTTP 200で完了し、`IndeterminateAfterPost`、取消後一覧の
  対象`tilcod`残存、2段階目POST未送信となった。集計は`matched=false`、inline script 11件、完全一致確認文言1件、
  外側if 1件、字句走査completed 11件、candidate 0件であり、停止点は構造抽出層まで絞られた。
- **対応済み（再ライブ未実施）**: 判定・第2段階POST可否を変えず、外側ifごとの匿名構造観測を追加した。
  集計項目はトップレベル/ネスト、statement-start条件、top-level `return`遮断、後続3文の切出し失敗段階
  （外側if構造、rest-if開始/rest-if構造、for開始/for構造）である。MockWebServerでトップレベル成功、
  外側block内、直前境界不一致、rest-if欠落、for欠落のsummaryと取消POST回数を検証した。次のライブ診断では
  この構造集計を採取し、候補0の理由を確認すること。アプリによる取消成立は未検証である。

#### 5回目アプリライブ取消診断と候補境界の確定（2026-07-28）

- **確認済み**: 第5回の構造集計は`outerIfDepth=top:0,nested:1`、他の抽出段階は0件だった。第2段階へ進まない
  原因は実測済み外側ifが外側block内にあり、従前のトップレベル制約が候補を除外していたことである。
- **対応済み（再ライブ未実施）**: 暫定の重複構造観測器を削除し、恒久診断は同一候補走査による匿名基本集計
  （`matched`、script数、確認文言数、外側if数、走査結果別数、candidate数、sanitize成功数、固定署名一致数）へ
  縮小した。候補はコメント・通常文字列・template literal・正規表現外で、丸括弧・角括弧の外かつ同一blockの
  文頭（先頭または`{`/`;`/`}`後）ならblock深度を問わず抽出する。候補内部は外側if→`if(rest)`→OK hiddenの
  `for`ループ固定署名に完全一致させる。
- **設計判断**: `if (false)`・未呼出し関数等の一般到達可能性は静的に証明しない。第2段階送信の安全境界は、
  一意な`cancelCode`、期待`tilcod`、固定署名の複合ガードである。コメント・文字列・template・
  正規表現内の偽署名、候補内部の禁止構文、文言改変、対象/form不一致は引き続き拒否する。外側block内の
  実ライブ相当正例で第2段階POSTが2回となる回帰試験を追加した。アプリによる取消成立は未検証であり、ライブ実行はしていない。

#### 6回目アプリライブ取消診断と複合ガード診断（2026-07-28）

- **確認済み**: 第6回も1段階目だけで停止し対象`tilcod`は残存したが、署名診断は
  `matched=true`、candidate 1件、sanitize成功1件、固定署名一致1件だった。従って第2段階を止めた条件は
  署名以外の複合ガードであると切り分けた。

#### 7回目アプリライブ取消診断とstage1 form再解析の除去（2026-07-28）

- **確認済み**: 第7回のstage診断は`targetStillPresent=true`、`signatureMatched=true`だった。stage1には
  元一覧formと`prevRequestForm`が併存し、使用しない取消form再解析が「対象form一意」の前提を満たさず停止していた。
- **対応済み（再ライブ未実施）**: stage1の取消form再解析を送信条件・診断から完全に除去した。2段階目は
  送信前に一意に解析済みの`cancelForm`を従前どおり再利用するため、payloadは不変である。複合ガードは
  `targetStillPresent && signatureMatched`、匿名診断はこの2値と最終`matched`だけである。MockWebServerで
  元一覧form+`prevRequestForm`併存でも第2段階へ進むこと、対象tilcod不一致・署名不一致では1段階目だけであること、
  取消POST回数を検証する。次のライブ診断では第2段階POSTと取消後照合を確認すること。ライブ実行はしていない。

#### 8回目アプリライブ取消診断とブラウザ再送フォーム化（2026-07-28）

- **確認済み**: 固定値再構成（hardcode）版による第8回は、第1・第2段階の計2回のPOSTがいずれもHTTP 200で完了したが、
  対象`tilcod`は取消後一覧に残り、取消は不成立だった。取消後のサマリは19件、一覧パーサは20行であり、
  完全一覧の照合にもならなかった。旧実装はaction、`okCodes`、本文順を固定値で再構成しており、ブラウザの
  `prevRequestForm`再送と一致する根拠が不足していた。
- **対応済み（本番第2段階成功は未検証）**: `ReservationCancelConfirmationFormParser`を追加する。stage1 HTMLの一意な
  `form[name=prevRequestForm]`からaction・successful controlsをDOM順/同名重複込みで取得し、字句安全に一意抽出した
  `OK_CODES_NAME`の実field名へ`OPACUSR001`を末尾追加する。既存controlsは`mngFlg2_handan=1`・`kbnchgflag=1`と
  送信前の`cancelForm`に対し名前・値・重複数で完全一致させるが、送信順はform DOM順を維持する。actionは同一originかつ
  `WOpacUsrRsvCancelAction.do`に限定する。不一致・複数form・曖昧定数・外部actionでは第2段階を送らない。
  `OK_CODES_NAME`は署名字句走査と同等の正規表現開始文脈で抽出し、コメント・文字列・template・正規表現内の偽代入、
  および既存control名との衝突を拒否する。
  次のライブ診断ではブラウザと第2段階のaction/本文を照合し、取消後一覧で結果を確認すること。ライブ実行はしていない。

#### 9回目アプリライブ取消診断と現行版の安全停止（2026-07-28）

- **確認済み**: `prevRequestForm`を実DOMから再送する現行版では、ライブ実行開始時点で対象`tilcod`は予約一覧に
  1件あったが、対応する`cancelCode`は空だった。送信前の安全弁が停止したため取消POSTは0回であり、
  本番の第2段階POSTは送信していない。
- **未確認**: `cancelCode`が空になった原因は未確認である。予約状態の自然変化、前回POSTの影響などを含め、
  この結果だけから原因を断定してはならない。
- **次の条件**: 現行`prevRequestForm`版の本番第2段階POST成功・取消成立は未検証である。次のライブ診断には、
  非空の`cancelCode`を持つ現在取消可能な別の`tilcod`を、所有者が明示承認して指定する必要がある。

#### 監査範囲と結論

所有者提供の`2026-07-27-reservation-cancel-investigation.md`全327行と、関連コミット
（`3b77933`〜`0dbb0a0`）、現行コード・テストを照合した。ログは担当AI自身の記録であり、
「全書き込みで所有者の承認を得た」という点はログ内の自己申告として扱う。会話上の各承認までは
この資料だけでは独立確認できない。

- **確認済み**: ドメイン・DB・パーサ・書き込み境界の基礎設計は概ね妥当である。取消ボタンの有無で
  取消可能性を判定し、同名重複をDOM順のまま保持し、自動リトライを禁止して一覧照合する方針は維持する。
- **確認済み**: 調査の進め方は、直前の予約障害レビューで定めた指示を十分に守れていない。
  観測範囲不足に気付いた後も観測手段を直さず、推測したPOSTを本番へ送り、最後は所有者が採取した
  実Request payloadで決着した。前回と同じ「見えていない通信を推測で補う」構図を繰り返している。
- **確認済み**: `0dbb0a0`の2段階実装はブラウザ実測payloadとMockWebServerテストに基づくが、
  アプリから実サイトへ2段階目を送って取消が成立することは未検証である。
- **推定**: 現状の取消バックエンドをそのままUIへ接続して完成扱いにしてはならない。
  後述の成功照合を修正し、追加テストを通してから、所有者承認の1件だけでライブ確認する。

#### 良かった点

- **確認済み**: fixtureから`yoykCancel`と`yoykcode`の同名重複を先に確認し、状態文字列ではなく
  取消ボタンの有無を採用した。提供可能行を誤って取消対象にしない設計になっている。
- **確認済み**: 取消を専用Gateway／Repositoryへ隔離し、対象なし・複数一致・取消コード空で停止する
  ライブ診断と専用同意フラグを用意した。
- **確認済み**: 一般語による拒否誤検出を実測後に除去し、効果のなかった2段階目の推定実装と
  汎用定数抽出をrevertして基準へ戻した。
- **確認済み**: 最終実装では、1段階目のqueryを2段階目のbody先頭へ移し、元bodyの順序・同名重複を
  保ち、`okCodes=OPACUSR001`を末尾へ加えることをテストしている。各POSTは自動再送しない。
- **確認済み**: Room v6→v7マイグレーション、パーサ、Gateway、Repositoryのテストを追加し、
  生ログ上では最終時点の`testDebugUnitTest`と`assembleDebug`成功を記録している。

#### 進め方の問題

1. **重大・確認済み: 成功通信を採取する前に送信プロトコルを実装した。**
   fixtureと`yoykCancel`関数から「1回POSTすれば取消完了」と仮定し、最初のバックエンドを作った。
   取消は不可逆な書き込みであるため、正規ブラウザ操作のmethod・URL・全payload・応答・状態差分を
   先に1組採取すべきだった。ドメイン・DB・表示用パーサの先行実装は問題ないが、送信部分は
   未実測のまま完成扱いにしてはならない。
2. **重大・確認済み: 状態変更POSTを「非破壊の画面取得」と扱った。**
   生ログ6-2は1段階目を非破壊と呼び、7-3では確認HTMLを読む目的で取消アクションへ`fetch(...POST...)`
   を試みている。結果的に拡張機能が403で遮断したが、遮断されなければサイト状態を変え得る。
   「確認表示だけ」は実測後に分かった事実であり、それ以前のPOSTは書き込みとして扱う必要がある。
3. **重大・確認済み: payloadを見ないまま推測した2段階目を本番へ複数回送った。**
   URLだけのaction一覧から本文を推測し、別機能のHARで見た
   `CONFIRM_DIALOG_SEND_REDIRECT=true`まで流用した。所有者が対象を承認していても、未知のpayloadを
   本番へ送る技術的根拠にはならない。推測実験はMockWebServerまでに留め、本番ではDevTools/HARの
   実測値だけを再現する。
4. **重要・確認済み: 観測出力が300文字で切れていると認識しながら、抽出窓を広げなかった。**
   ここで1500文字へ広げていれば`prevRequestForm`と`OPACUSR001`へ早期到達でき、少なくとも
   4回の推測的ライブ試行を避けられた。「欠けた出力から推論しない」を明示的な停止条件にする。
5. **重要・確認済み: JS関数の局所だけから画面全体の挙動を断定した。**
   `yoykCancel`本体にconfirmが無いことから「クライアント側確認は無い」と結論したが、確認処理は
   1段階目応答のトップレベルスクリプトにあった。関数、呼出元、応答ページ、遷移後処理を一組で見る。
6. **重要・確認済み: 共通JS定数を含む文字列集合へ一般的な拒否語を適用した。**
   既知の落とし穴をサブエージェントへの指示へ反映せず、`できません`で無関係な定数を拒否と誤判定した。
   メッセージは文字列だけでなく、DOM表示、実際の`alert`／`lbConfirm`呼出し、静的定数など
   出所を区別して解析する。
7. **中・確認済み: ブラウザ検証の採取計画が後手だった。**
   ネイティブconfirmがページをブロックすること、ページ遷移で`window.confirm`の差し替えが消えることを
   事前に織り込めず、1回はセッション切れになった。最初からNetworkログ保持、所有者によるOK、
   Request payloadの保存までを1手順として準備すべきだった。
8. **中・未確認: 各ライブ結果とGit SHAの対応が生ログに残っていない。**
   コミット単位の説明はあるが、各実行の直前にHEAD／ビルド元SHAを記録した証跡はない。また、
   AGENTS.mdで要求する実装後の別セッションレビューを行った記録も、このログからは確認できない。

#### 内容・現行コードの問題

1. **必須・確認済み: 取消成功を`cancelCode`の消失だけで判定している。**
   `resolveCancelByListDiff`は取消後一覧に同じ`cancelCode`が無ければ`Cancelled`とする。しかし、
   対象予約が「提供可能」等へ遷移して取消ボタンだけ消えた場合、予約行自体は残っていても
   `cancelCode`は空になり、取消成功と誤判定する。送信前一覧で対象の`tilcod`を固定し、送信後は
   その予約行自体が消えたことを確認する。`cancelCode`は操作対象選択に使い、成功の業務IDにしない。
2. **必須・確認済み: 取消後一覧の完全性を確認していない。**
   直接予約の成立照合にはサマリ件数との一致を見る`ReservationListSnapshot.complete`を導入済みだが、
   取消の`resolveCancelByListDiff`は1ページ分の`ReservationListParser`だけを見る。対象が別ページへ
   移動した場合も成功と誤判定し得るため、同じ完全性ガードを再利用し、完全でなければ
   `IndeterminateAfterPost`へ倒す。
3. **推奨・推定: 確認ダイアログ判定も静的文言へ誤反応する余地がある。**
   現行は`extractSiteMessages`内に`取消を行います`があれば2段階目を送る。この抽出器が共通JS定数も
   収集することは確認済みであり、将来この文言が実行されない静的定数として別画面へ入る可能性は
   排除できない。実際のトップレベル`lbConfirm`呼出し、`prevRequestForm`、メッセージコードの組合せで
   構造的に判定するか、少なくとも「静的定数だけ」の回帰テストを追加する。
4. **推奨・確認済み: 2段階目の通信断テストが不足している。**
   現在の接続断テストは1段階目が対象である。2段階目で接続断した場合も2段階目を再送せず
   `IndeterminateAfterPost`となり、取消POST総数が2回を超えないことを明示的に検証する。
5. **文言是正・確認済み**: `postReservationExactlyOnce`はネットワーク上の成立をexactly-onceに
   保証するものではなく、「クライアントが自動再試行しない1回限りの試行」である。設計書・コメントでは
   成否不明が残ることと併記し、トランザクション的なexactly-once保証と誤読させない。

#### 次に行うこと

1. UI実装と次のライブ取消より前に、取消対象の安定ID（`tilcod`）保持と一覧完全性ガードを実装する。
2. 「取消ボタンだけ消えて行は残る」「一覧不完全」「2段階目接続断」「静的確認文言だけ」のテストを追加する。
3. `backend-design.md`の1段階仕様と、本書以外に残る「取消未完成」の記述を2段階仕様へ更新する。
4. ローカルの`testDebugUnitTest`／`assembleDebug`とCIを確認した後、所有者承認の指定1件だけで
   アプリ実装のライブ取消を検証する。実行前後のHEAD SHA、対象`tilcod`、一覧件数、対象行の有無を残す。
5. ライブ成功を確認するまで、予約取消は「バックエンド実装中・UI未接続・実サイト成功未検証」と扱う。
