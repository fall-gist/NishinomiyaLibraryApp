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

## 主担当AIへの進行指示（2026-07-27レビュー反映、2026-08-05に項目13〜16を追加）

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
13. **サイト応答を解析するパーサの正常系テストは、実測由来のフィクスチャで固定すること。**
    （2026-08-05追加。貸出延長 段階2のレビューで、同じ失敗の3度目の再発を受けて明文化した）
    自作HTMLだけでテストすると、**実装者の理解が間違っていてもテストは通る**。自作HTMLは
    実装者の理解と同じ形をしているためである。実際に、貸出延長では「全テストが通るが実サイトでは
    必ず`ParseException`になる」実装が生まれた。異常系は自作HTMLでよいが、**正常系は必ず
    実サイト由来（HAR・実取得HTML）の構造をマスクしたフィクスチャで固定する**。
    既存例: `usrlend_extend_confirm.html`、`usrrsv.html`、`usrread_live.html`。
14. **実サイトに「存在すること」を要件にする前に、実物のHTMLでその要素を確認すること。**
    （2026-08-05追加）予約取消10回目（`prevRequestForm`のaction属性）と貸出延長段階2で
    同一の誤りを繰り返している。この系統のサイトの`prevRequestForm`・`LBForm`は
    **`action`属性を持たず、送信先はページ内スクリプトが実行時に代入する**。フォームの送信先を
    検証したい場合は、HTML属性ではなくJS代入を字句走査で読むこと。要素の不在を必須条件にした
    実装はフェイルクローズで安全側ではあるが、**機能が永久に動かない**という形で現れるため、
    テストが通っていても安心してはならない。
15. **状態変更POSTの前後で結果型の意味を変える場合、同じ応答に対する検証が全て同じ経路へ
    倒れているか確認すること。** （2026-08-05追加。貸出延長 段階3のレビューで判明）
    「送っていないと確定した失敗」と「送った後の成否不明」を別の型で表す設計では、**同じ応答に
    対する複数の検証のうち一部だけが例外を素通しして別の型になる**非対称が生まれやすい。
    実際に、貸出延長では1段階目POST直後のメンテナンス検知だけが`try`の外にあり、既に1回
    送信済みなのに「送っていない」意味の型を返していた。**個々の経路を個別に固定するテストでは
    この非対称は検出できない。** 対になる2ケース（例「送らずに停止し変化なし」と「送った後に
    変化なし」）を**並べたテストで固定**し、片方に寄せる変更で必ずどちらかが赤くなる状態を作ること。
16. **「コミットした」と「プッシュした」を区別し、必ず`origin`との差分で確認すること。**
    （2026-08-05追加。実際に見落とした）実装セッションがコミットのみで終わっていたにもかかわらず、
    管理セッションが`git log --oneline -1`（ローカルの先端）だけを見て「プッシュ済み」と報告した。
    所有者はCIのAPKでしか実機確認できないため、**未プッシュはそのまま「所有者が実機で確認できない」に
    直結する**。確認は必ず `git log origin/<branch>..HEAD --oneline` を使い、**出力が空であること**を
    確かめること。ローカルのコミットハッシュが進んでいることは、プッシュされた根拠にならない。

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

#### 書き込み制限の履歴と現行方針

この節の初版では予約確定以外のサイト書き込みを禁止していたが、2026-07-26に所有者が全面禁止を
撤回した。予約取消は実装・実サイト検証済みであり、新着キーワード自動予約は2026-07-29に
`docs/spec.md`§3.11として機能要件が承認された。自動テスト・CIから実サイトPOSTを送らないこと、
書き込みを専用ゲートウェイへ隔離すること、POST後不明を再送しないことは引き続き不変である。

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

### 非表示診断（`liveReservationHideDiagnostic`、2026-08-03追加・未実行）

予約取消後の自動非表示本体を実装する前に、実サイトの成功通信を1件だけ採取する専用タスク。
通常の`:app:testDebugUnitTest`とCIは`LiveReservationHideDiagnosticTest`を除外している。

**このコマンドは、本番サイトの取消済み予約1件を一覧から非表示にする副作用がある。**
所有者が非表示してよい対象を指定した場合だけ、次の5項目をすべて明示して実行する。

```powershell
$env:LICSXP_LIVE_RESERVATION = 'YES_I_UNDERSTAND'
$env:LICSXP_LIVE_HIDE_CONFIRM = 'HIDE_ON_PRODUCTION'
$env:LICSXP_CARD_NUMBER = 'カード番号'
$env:LICSXP_PASSWORD = 'パスワード'
$env:LICSXP_HIDE_TILCOD = '非表示にしてよい取消済み予約の資料コード'
.\gradlew.bat :app:liveReservationHideDiagnostic
```

安全弁:

- 完全な予約一覧で、対象`tilcod`の`CANCELLED`行と厳密な`yoykHihyoji('<数字>')`コードを
  それぞれ一意に確認できた場合だけPOSTする。
- 実測済みの限定LBForm構造だけをDOM順・同名重複のまま再送し、`yoycod`だけを対象コードへ置換する。
- 送信先は固定originの`WOpacUsrRsvHiddenAction.do`だけとする。stage1は固定query
  `mngFlg2_handan=1`、stage2はqueryなしで、各POSTを自動再試行せずそれぞれ1回だけ送る。
- 送信後は完全な一覧で対象取消行の消失を確認する。コードだけ消えた場合を成功にしない。
- ログはmethod/path、項目名、ヘッダ名、Cookieの有無、HTTP状態、画面分類、結果enumだけに限定し、
  認証情報・Cookie値・hash値・`yoycod`値・`tilcod`・資料名・HTML本文・画面本文を出さない。

実サイト診断はまだ一度も実行していない。所有者の対象指定と明示承認なしに実行してはならない。

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

## プルリフレッシュ（2026-07-28追記）

`docs/spec.md`§3.7に「手動同期: プルリフレッシュ」と書かれながら未実装だった項目を実装した。
設計書は`docs/design/pull-to-refresh.md`にある（所有者承認済み。決定事項と却下した代替案も同書）。

### 実装状況

- 対象はホーム・貸出中・予約中・本棚・読書記録の5画面。リスト領域を下へ引くと
  `StatusRepository.syncAll(SyncTrigger.MANUAL)`が走る。トップバーとメンバー絞り込み行は
  プル対象の外に固定してある
- 同期状態は新設の`ui/sync/SyncUiController`（`@Singleton`）へ集約した。ホームの
  「いますぐ同期」ボタンと各画面のプルが同じ`isSyncing`・同じ結果表示を共有する。
  `HomeUiState`からは`isSyncing`・`syncMessage`を削除した
- 結果表示は`LibraryApp`の`Scaffold`に置いた**Snackbar1つ**に統一した。ホームにあった
  常設テキスト行は廃止。開始時（「同期中です」）は流さず、完了・失敗時のみ出す
- Snackbarの再表示を防ぐため、メッセージは`SyncMessage(id, text)`とし、表示後に
  `consumeMessage(id)`でクリアする。表示中に新しいメッセージが来た場合は古いidでは消さない
- 空状態（貸出中・予約中・本棚・読書記録）は`verticalScroll`で包んだ。`PullToRefreshBox`は
  ネストスクロール経由でジェスチャを受けるため、スクロール可能な子が無いと空のとき引けない。
  「1冊も無いから同期したい」場面こそ必要なための対応である
- 使用APIは`androidx.compose.material3.pulltorefresh.PullToRefreshBox`
  （material3 1.3.0、`@ExperimentalMaterial3Api`）

### 検証状況

- `compileDebugKotlin`・`assembleDebug`成功、`testDebugUnitTest`452件すべて成功（2026-07-28実測）
- `SyncUiControllerTest`を新設（6件）。完了メッセージ、一部失敗の人数、例外時の失敗メッセージ、
  `consumeMessage`の一致・不一致、同期中の再入で`syncAll`が2回呼ばれないこと。
  再入テストは`CompletableDeferred`で1件目をsyncAll内に留めて実際のインターリーブを作っている
- **実機で動作確認済み（2026-07-28、所有者）。** 開発機にはAndroid端末もAVDも無いため、
  CI（`.github/workflows/build.yml`、`on: push`）が出す`app-debug-apk`アーティファクトを
  実機へ入れて確認した。**今後この種の確認はこの経路で行うこと。**
  - **本棚のプルは動く。** `LazyRow`内に`LazyColumn`が並ぶ構造でも縦プルが親へ届く。
    下方向プルは`onPostScroll`＝子が消費しなかった残りで拾う実装のためで、事前の読みどおりだった
  - ホーム・貸出中・予約中・読書記録でプルが効き、Snackbarで結果が出る
  - ホームの「いますぐ同期」中に他画面へ移ってもインジケータが回っている（状態集約が働いている）
  - 画面回転でSnackbarは二重表示にならない
  - **空状態だけ未確認。** 予約が0件になる状況を作れないため。他画面と同じ形であり実害は無い見込み
- 実機検証で**画面回転により同期が中断される不具合**が見つかり、修正済み（下記）

### 画面回転で同期が中断される不具合（2026-07-28、修正済み）

`MainActivity`が同期を`uiScope`で起動していたが、この`uiScope`は`onDestroy`で`cancel()`される。
画面回転はActivityの破棄・再生成なので、同期コルーチンごとキャンセルされていた。
`finally`は実行されるため`isSyncing=false`に戻りMutexも解放されるが、**同期は途中で止まる**。
メンバーが複数いる場合、2人目の途中で回転すると1人目だけ更新された状態で終わり、
`CancellationException`は再スローされるためSnackbarでの通知も出ない。

**本機能で入れた退行ではない。** 旧`HomeScreenController.requestManualSync`も同じ`uiScope`から
呼ばれており同じ問題を抱えていた。プルリフレッシュで手動同期の頻度が上がり顕在化した。

修正は、`SyncUiController`（`@Singleton`でActivityより長生き）に自前の
`CoroutineScope(SupervisorJob() + dispatcher)`を持たせ、`requestManualSync`を非suspendにして
その上で走らせる形にした。`MainActivity`は`syncUiController::requestManualSync`を渡すだけになる。
`close()`は設けない——同期を最後まで走らせることが目的のため、プロセスと寿命を共にする。

**修正後に実機で再確認し、回転しても中断されないことを確認済み（2026-07-29、所有者）。**

### 既知の弱点（意図した割り切り）

- 同期失敗はSnackbarが消えると追えなくなる。ホームだけはAppBarの`lastSyncFailed`で後追いできるが、
  他4画面にはその表示が無い。必要になったら「失敗時のみ再試行ボタン付きSnackbar」を足せる
- 書誌詳細を`Dialog`で開いている間（予約中・予約カート）は、Snackbarがダイアログの下に隠れる。
  「書誌詳細表示中の特別扱いは不要」という所有者判断の範囲内

### 開発環境の注意（2026-07-28に判明）

- このリポジトリには`local.properties`が無く、ローカルでGradleを回すには`JAVA_HOME`
  （Android Studio同梱の`jbr`で可）と`ANDROID_HOME`を明示指定する必要がある。指定しないと
  「SDK location not found」または「JAVA_HOME is not set」で失敗する。
- **実機検証はCIのアーティファクトから行う。** 開発機にAndroid端末は接続されておらず、AVDも
  1つも作成されていない。プッシュすれば`.github/workflows/build.yml`が単体テストと
  デバッグAPKビルドを回し、`app-debug-apk`として保存するので、そこから取って実機へ入れる。
  エミュレータを新規に用意する必要はない。

## 今後の構想（2026-07-26時点。未着手・未設計）

リポジトリ所有者が挙げた要望を記録する。**具体的な設計・実装には着手していない。**
着手する際は、それぞれ設計から始めること。

### 1. 予約中画面に現在の予約数を表示する（実装済み・確認済み）

**実装済み**（2026-08-04コードで確認）。共通コンポーネント`MemberFilterRow`（`ui/components/CommonUi.kt`）が
`totalCount`・`countByMemberId`を受け取り、「みんな N」「メンバー名 N」の形でチップに件数を表示する。
`ReservationsScreenController`が`ReservationsContentBuilder.countByMember`で算出して渡しており、
予約中画面で常時表示されている。所有者が確認済み。
予約上限との対比（上限値の取得）は引き続き未実装・**予約上限は未検証**のまま（§6.4）。

### 2. 予約の削除機能（実装済み・実サイト確認済み）

2026-07-26に所有者が全面禁止を撤回し、予約取消として実装・実サイト成立確認済み（本書「予約成立の確認」
「予約取消の実装・検証レビュー」各節）。取消後の自動非表示まで2026-08-04に実サイト確認済み
（本書「予約取消に続けて『非表示』まで自動で処理する」節）。この項目は完了。

### 3. 本棚（マイ本棚）の編集機能

- **現行の「禁止事項」と衝突する。** 禁止事項1の「登録変更」に該当する。同上。
- 本棚の切り替え（表示状態の変更）だけは実装済みで、読み取り専用ポリシーの範囲内としている（§4.1）。
  追加・削除・メモ編集は未調査。

### 5. 貸出中画面からの貸出延長（2026-08-05、全7段階完了・実サイト一連確認成功）

貸出中の資料の返却期限を延長する操作を、貸出中画面から行えるようにする。

- **確認済み**: 所有者提供のHAR（実サイトでの延長1回分）により、サイト側の導線・二段階POSTの
  構造を確認した。詳細は`docs/site-research.md`§10、技術設計は`docs/design/loan-extension.md`。
  予約取消・予約非表示と同型の「`prevRequestForm` + `OK_CODES_NAME` + 固定確認コード」機構であり、
  確認コードは`OPACUSR005`、対象識別は`extend(mngcod)`が設定する`para`フィールド（`tilcod`とは別）。
- **未確認のまま残る事項**: 確認ダイアログの正確な文言（HARの文字コード破損により未取得）、
  拒否時（延長回数上限・予約有り資料等）のサイト応答構造、延長後に再度延長可能か、`mngcod`の安定性。
  今回のHARは成功例1件のみで拒否例は無い
- `docs/spec.md`§3.12へ機能要件を追加した。技術設計§9の論点3件は2026-08-04に所有者が裁定済み:
  - UI導線は貸出中一覧の各行・1件ずつのみ。**ただし所有者の指示により、通信部分（Gateway/
    Repository）とUIを分離し、後から導線を追加できる層構造を保つことが実装上の必須要件**である
  - 拒否理由は区別せず`Extended`/`Unknown`/`Failure`の3値。拒否例を実測できた時点で分類を追加する
  - 予約系の共通書込ゲート(`ReservationOperationGate`)には参加させない（予約データに触れないため）
- **Room v8→v9が必要**: 延長ボタンの出し分けに`Loan.extendable`（Room保存）を使い、実際の送信に
  使う`renewalCode`は実行時に取得し直す。古いコードで送信する事故を構造的に防ぐための分離である
- 実装順序は技術設計§11にある（全7段階。段階1のRoom+同期変更は単独コミットで既存回帰を確認する）
- 書き込み操作であるため、専用ゲートウェイへの隔離、送信一回限り、成否不明時は再送せず照合で解決、
  という不変条件は他の書込機能と同じく維持する

#### 段階1〜2の実装とレビュー（2026-08-05）

- **完了**: 段階1（Room v8→v9、`LoanEntity.extendable`、`LoanListParser`の可否算出）＝`86ab939`、
  段階2（`LoanExtensionListParser`・1段階目/2段階目フォームパーサ）＝`12054a9`、
  レビュー指摘の修正＝`e42f1bb`。`testDebugUnitTest` 611件成功（失敗・エラー・スキップ0）、
  `assembleDebug`成功。実サイトへの通信は一切行っていない（段階1〜2は純粋なパーサとRoomのみ）。

##### レビューで見つかった欠陥（3件とも初回実装時点では全テストが通っていた）

`docs/handoff.md`の進行指示にある「見えていない通信を推測で補う」失敗の**3度目の再発**である。
1度目は予約確定、2度目は予約取消（10回目ライブ診断）、今回が3度目。

1. **`prevRequestForm`の`action`属性を必須にしていた。** 実サイトの同フォームに`action`属性は
   存在せず、ページ内スクリプトが実行時に代入する。実装は実サイトの応答で必ず`ParseException`と
   なり、2段階目は永久に送信されない状態だった。予約取消10回目と同一の誤り。
   JS代入からの字句走査抽出へ修正した。
2. **`LoanExtensionListParser`が延長ボタンの無い行を捨てていた。** 延長に成功すると対象行は
   ボタンを失う（行自体は残る）ため、**成功したときに限って**送信後の照合で対象を見失い、
   常に成否不明になる構造だった。`renewalCode`をnullableにし全行を保持する形へ修正した。
3. **`prevRequestForm`の項目多重集合の期待値が1段階目のbodyだけだった。** 実際は
   query+body（`mngFlg1_handan`の1件差）。この誤りは`docs/site-research.md`§10の初版記述にも
   あり、設計書がそれを踏襲していた。両方訂正済み。

##### 再発の原因と対策（重要）

**3件とも「テストは全部通るが実サイトでは動かない」状態だった。原因はテスト用HTMLを実装者が
自作したことである。** 自作HTMLは実装者の理解と同じ形をしているため、理解が間違っていても
テストは通る。

対策として、実サイトのHARから起こしたフィクスチャ
`app/src/test/resources/fixtures/usrlend_extend_confirm.html`（値はマスク済み）を追加し、
「実サイトの正常な応答をパースできること」を回帰テストで固定した。
**今後、実サイトの応答を解析するパーサを書くときは、実測由来のフィクスチャによる正常系テストを
必須とすること。** 異常系は自作HTMLでよいが、正常系を自作HTMLだけで済ませてはならない。

##### レビュー体制について（有効だった点）

管理セッションと独立レビューセッションの2系統でレビューし、**互いの見落としを補完した**。
上記1は両者が検出、2は管理セッションのみ、3は独立レビューのみが検出した。特に独立レビューは
2について「成否照合は実現可能」と誤った結論を出していた。単独レビューでは3件全ては拾えなかった。
- 延長の成否は返却期限日の変化で照合する設計とした（サイトの成功メッセージが空のため文言には
  頼れないことをHARで確認済み）

#### 段階3〜4の実装とレビュー（2026-08-05）

- **完了**: 段階3（`LoanExtensionGateway`、二段階POST＋返却期日照合）＝`8e18ab0`、
  段階4（`LoanExtensionRepository`と結果型、§9.1の層分離）＝`a671451`、
  所有者裁定の設計反映＝`4cefa56`、レビュー指摘の修正＝下記。
  `testDebugUnitTest` 640件成功（失敗・エラー・スキップ0）、`assembleDebug`成功。
  **実サイトへの通信は一切行っていない**（テストはMockWebServerのみ）。
- **段階5以降（UI・ライブ診断・実サイト一連確認）は未着手。**

##### レビューで見つかった欠陥（3件とも初回実装時点では全テストが通っていた）

1. **確認コード`OPACUSR005`がハードコードだった**（設計§5.1違反）。サイトが延長時に別の確認
   （例「この資料には予約が入っています。よろしいですか？」＝別コード）を出すよう変わった場合、
   アプリは**表示されていない別の問いに盲目的にOKを返す**。二段階確認という安全機構の意味が
   失われる。`okArray`への代入からの字句走査抽出＋想定値照合へ修正した。
2. **1段階目POST直後の検証の一部だけが例外を素通しして`Failure`になっていた。**
   `requireNotMaintenance(stage1Page.html)`が`try`の外にあり、`LibraryError.Maintenance`が
   Repositoryまで伝播して`Failure(SITE_MAINTENANCE)`＝「送っていない」意味の型になっていた。
   **すでに1回POSTを送った後**である。同じ`stage1`応答に対する直後の検証（確認フォーム解析失敗）は
   「既にネットワークへ1回送っている」というコメント付きで慎重に照合経路へ回しており、
   **実装者自身の記述と非対称**だった。
3. **2段階目を送らずに停止した場合も`Unknown`に倒れていた**（設計§6は「フォーム不一致」を
   明示的に`Failure`側に挙げている）。2段階目を送っていないと確定できる場合まで「確認できません」
   としか言えず、**サイト構造変更の検知が`Unknown`に埋もれて発見が遅れる**。

2・3の根因は、**§6の「POST前に確定した失敗」の「POST」がどちらの段階を指すか設計が曖昧だった**
ことである。所有者裁定により設計へ**§5.3を新設**し、1段階目POST後の全検証を3分岐
（期日が進んだ→`Extended` / 進んでいないと確定→`Failure` / 確定できない→`Unknown`）へ
一律で倒す形に統一した。**2段階目送信後の意味論（§5.2）は変えていない。**

##### 既知の課題（所有者裁定により据え置き）

独立レビューが、同型の問題が既存の**予約取消**にもあると報告した。**いずれも修正しない。**
稼働中かつ実サイト検証済みの経路へ手を入れる回帰リスクを避けるためである（2026-08-05所有者裁定）。

- `ReservationGateway.kt`の`requireNotMaintenance(stage1Html)`（1段階目POST後）が
  `ReservationCancelRepositoryImpl.kt`で`Failure(SITE_MAINTENANCE)`化される。上記2と同型。
- `ReservationCancelConfirmationFormParser`の確認コード`OPACUSR001`がハードコード。上記1と同型。

貸出延長の実サイト一連確認が済み、抽出方式が実サイトで問題なく動くことを確認できた後であれば、
予約取消側へ横展開してよい。**それ以前に触らないこと。**

> **2026-08-06追記**: 上記2件は所有者承認のうえ横展開済み（メンテナンス検知の是正は2段階目応答も
> 対象に含めた）。この節は据え置き当時の記録であり、現状は後掲
> 「予約取消側への横展開（2026-08-06実装済み・実サイト未確認）」を正とする。

##### 再発の原因と対策

段階1〜2の3件は「実サイトを見ずに推測で補った」ことが根因だったが、**段階3〜4の3件は種類が違う**。
実サイトの構造理解は正しく、**結果型の意味論（どの失敗をどの型で報告するか）が経路ごとに
食い違っていた**。テストは各経路を個別に固定していたため、**経路間の非対称そのものは
どのテストにも検出されなかった**。

対策として、修正後は「2段階目を**送らずに**停止し期日不変→`Failure`」と「2段階目を**送って**
期日不変→`Unknown`」を**並べたテストで固定**した。片方に寄せる変更を入れると必ずどちらかが赤くなる。
管理セッションが4種類の意図的破壊で実際に赤くなることを確認済み
（3分岐の潰し込み→3件失敗、2段階目後の意味論変更→1件失敗、メンテナンス例外の復活→1件失敗、
確認コードのハードコード復活→5件失敗）。

##### レビュー体制について

段階1〜2に続き、**2系統のレビューが互いの見落としを補完した**。上記1・3は管理セッションのみ、
2は独立レビューのみが検出した。**両者に共通して検出した指摘は無く、単独レビューでは
どちらも3件全ては拾えなかった。**

#### 段階5〜6の実装(2026-08-05)

- **完了**: 段階5(UI: 貸出中一覧の延長ボタン・確認ダイアログ・結果表示)、段階6
  (`liveLoanExtensionDiagnostic`タスクの登録のみ・未実行)。それぞれ別コミット。
  `testDebugUnitTest` 659件成功(失敗・エラー・スキップ0)、`assembleDebug`成功
  (658件は下記レビュー修正前の値)。
  **実サイトへの通信は一切行っていない**(UIテストはコントローラ単体、診断タスクは登録のみで未実行)。
- 段階7(実サイト一連確認)は2026-08-05に成功。詳細は後掲「段階7 実サイト一連確認」。

##### 実装内容

- `LoanRow`(`LoansScreenController.kt`)に`memberId`・`dueDate`・`extendable`を追加し、
  `canExtend`(`extendable && tilcod.isNotBlank()`)で延長ボタンの出し分け条件を一元化した
  (設計§10「除外」項目: `extendable=true`でも`tilcod`が空なら延長操作を起動できない)。
- UI状態は`ReservationCancelUiController`と同じ流儀で`LoanExtensionUiController`
  (`ui/loans/LoanExtensionUiController.kt`)へ分離した。`LoanExtensionRepository`だけを叩き、
  Gatewayは直接呼ばない。確認ダイアログを確定するまで通信を開始しない。処理中は対象行のキー
  (`processingTarget`)を保持し、行のボタン無効化・進行中表示に使う。結果文言(`Extended`/
  `Unknown`/`Failure`)は`LoanExtensionContentBuilder`(Android非依存の純関数)で組み立て、
  `succeeded`フラグをUnknown/成功の唯一の判定点にした(進行指示15と同じ考え方)。
- `LoanExtensionRepositoryImpl`に、`Extended`成功時だけ対象行(`memberId`+`tilcod`)のRoomを
  `dueDate`/`extendable=false`へ更新する処理を追加した(設計§6.1)。`LoanDao.applyExtensionResult`
  が対象行を`countByMemberAndTilcod`で数え、1件でなければ`updateAfterExtension`を呼ばずに
  `false`を返す(フェイルクローズ)。`Unknown`/`Failure`では呼び出し自体を行わない。ローカル
  反映の失敗(例外)は延長そのものの成否を変えない(サイト側では既に延長済みのため)。
- `liveLoanExtensionDiagnostic`タスク(`app/build.gradle.kts`)を既存4タスクと同じ`exclude`設定に
  加えた。診断本体(`LiveLoanExtensionDiagnostic.kt`)は既存の本番実装
  (`LicsXpLoanExtensionSession.extendLoan`)をそのまま1回だけ呼ぶだけで、独自の書込みロジックは
  持たない。ログはPOSTのmethod/path・段階名・`outcome`の種別(`Extended(dueDateChanged=true)`/
  `Unknown`/`Failure(reason)`)だけで、返却期限の実値・カード番号・パスワード・Cookie・hash実値・
  `para`/`mngcod`実値・資料名・HTML本文は一切出力しない。

##### 意図的破壊による検出確認(実施済み)

4件とも実際に赤くなることを確認してから元に戻した。

1. `LoanRow.canExtend`を`extendable`単独判定に変更 → `LoansContentBuilderTest`の
   `canExtend_isTrueOnlyWhenExtendableAndTilcodPresent`が失敗
2. `LoanExtensionContentBuilder.resultMessage`の`Unknown`分岐を`succeeded=true`に変更 →
   `LoanExtensionContentBuilderTest`・`LoanExtensionUiControllerTest`の対応するテストが失敗
3. `LoanExtensionRepositoryImpl`のローカル反映を`Extended`限定から無条件呼び出しに変更 →
   `LoanExtensionRepositoryTest`の`Unknownでは対象行を一切更新しない`が失敗
4. `LoanDao.applyExtensionResult`から件数チェックを除去 → `LoanExtensionRepositoryTest`の
   `対象行が1件でなければExtendedでも更新しない`が失敗

##### レビューで見つかった欠陥（1件・修正済み `c0dd950`）

**結果ダイアログが`Unknown`を「延長できませんでした」と断定していた。**
`LoansScreen.kt`の`LoanExtensionResultDialog`が`succeeded`（2値）でタイトルと色を出し分けており、
`Unknown`は`succeeded=false`のため**タイトル「延長できませんでした」・本文「延長できたか
確認できません」という自己矛盾した表示**になっていた。文字色もエラー色だった。

**実害**: `Unknown`の実体は「延長が成立している可能性がある」状態である。失敗と断定すれば
利用者は**もう一度延長ボタンを押す**。設計全体が守ってきた「送信は一回限り、成否不明なら
再送しない」が最後のUIで破られる。しかも`Unknown`では§6.1によりRoomを更新しないため、
**行は古い返却期限のまま・延長ボタンも残ったまま**で、画面上のすべてが再試行を促す状態だった。

**修正**: `LoanExtensionResultKind`（`EXTENDED`/`UNKNOWN`/`FAILED`）を導入し、タイトルも
`LoanExtensionContentBuilder`側（Android非依存）で組み立てる形にした。`succeeded`は
`kind == EXTENDED`から導出するプロパティにし、KDocへ「falseでも失敗の断定に使うな」と明記した。
Composableは組み立て済みの値を表示するだけになった。色は予約取消の`Unknown`と同じ既存の
`colors.cautionInk`を流用（新規色定数の追加なし）。

**再発の型と対策**: 段階3〜4と**同じ「3値を境界で2値へ潰す」失敗**である（進行指示15）。
テストが全緑で通った理由も同型で、**本文は`LoanExtensionContentBuilder`のテストで固定されて
いたのに、タイトルだけがComposable内で計算されテストの外にあった**。修正では
「3値のタイトルが互いに異なること」「`Unknown`のタイトルが失敗を断定する文言でないこと」を
並べて固定するテストを追加し、断定文言へ戻すと赤くなることを確認済み。

**レビュー体制**: 段階3〜4と異なり、今回は**両者が同じ箇所を検出したうえで深刻度の評価が
割れた**。独立レビューは「成功と誤認させない側への不一致であり実害はない」として参考扱いに
したが、**成功と誤認させないことと失敗を断定することは別**であり、管理セッションの判断で
必須修正とした。指摘の有無だけでなく**深刻度の評価も突き合わせる**必要がある。

#### 段階7 実サイト一連確認（2026-08-05、成功。所有者が実機で確認）

**確認済み（所有者の実機操作による）**。受入条件は`docs/design/loan-extension.md`§12。
CIのAPKをインストールし（アプリ内表記でコミット符号を照合済み）、アプリの通常操作
（貸出中一覧の「延長」→アプリの確認ダイアログ→確定）を実行した。

- **延長が実サイトで成立した。**
- **アプリの貸出中一覧で返却期限が新しい日付へ更新され、延長ボタンも消えた。**
  設計§6.1のローカル反映（`Extended`のときだけ`dueDate`更新・`extendable=false`）が
  実機で機能することを確認した。同期を待たずに反映される。
- 反証条件（対象が一意特定できない、想定外のaction、返却期限が変化しない）には該当しなかった。

これにより、二段階POST・確認コードと送信先の**抽出方式**・返却期限照合・ローカル反映が
実サイトで機能することが確認できた。**設計§11の全7段階が完了**である。

##### 実機確認で判明した2件

1. **延長ボタンが表示されなかった（原因: 同期未実施。コードの欠陥ではない）。**
   `Loan.extendable`は同期時（`deleteForMember`+`insertAll`）にしか書かれない。
   `MIGRATION_8_9`は既存行に`DEFAULT 0`を入れるため、**アプリ更新後に一度も同期していないと
   全行が延長不可のまま**になる。自動同期は1日1回であり、更新直後は前のビルドが書いた
   データを見ることになる。プルリフレッシュで解消した。
   - 切り分けの過程で**テストの穴**が見つかった。パーサ単体・UI組み立て単体はそれぞれ
     テストされていたが、**同期→Room往復→表示行の組み立て までを通したテストが無く、
     経路のどこかで`extendable`や`tilcod`が落ちても検出できない**状態だった。
     `LoanExtensionVisibilityChainTest`（`ed2a5c4`）で塞いだ。実フィクスチャ`usrlend.html`から
     12行中7行が`canExtend=true`になることを通しで固定している。
2. **延長ボタンの下1/3が見切れていた（修正済み `f8c57f0`）。**
   原因は2つ。(a) `Modifier.height(32.dp).padding(bottom = 10.dp)` — 32dpの箱の内側に10dpの
   余白を取るためボタン本体が22dpに潰れる。(b) ボタンを置いた行の縦余白が`0.dp`で、
   親`Column`の`clip(RoundedCornerShape(12.dp))`に下端が切り取られていた。
   予約取消ボタンと同じく**既存の情報行の右端に置き、修飾子は`Modifier.height(32.dp)`のみ**に
   揃えた。**実機での表示確認は未実施。**

##### 段階7完了後も未確認のまま残る事項

- 拒否時（延長回数上限・予約有り資料等）のサイト応答構造。今回も成功例しか得られていない。
  **拒否例が実測できるまで`FailureReason`の細分類を作らないこと**（設計§9.2）。
- 延長後に時間を置けば再度延長できるようになるのか。
- `mngcod`（`renewalCode`）の安定性。
- ライブ診断`liveLoanExtensionDiagnostic`は登録済み・**未実行のまま**。通常操作で確認できたため
  実行の必要は生じなかった。必要な環境変数は
  `LICSXP_LIVE_RESERVATION=YES_I_UNDERSTAND`・`LICSXP_LIVE_EXTEND_CONFIRM=EXTEND_ON_PRODUCTION`・
  `LICSXP_CARD_NUMBER`・`LICSXP_PASSWORD`・`LICSXP_TILCOD`。

##### 予約取消側への横展開（2026-08-06、**実サイト確認済み**）

据え置いていた同型2件を、所有者承認のうえ横展開した。設計・受入条件は
`docs/design/reservation-cancel-hardening.md`が正本。コミットは`301b7b2`（方針A）・`7d3954e`（方針B）。

- **方針A**: `ReservationCancelConfirmationFormParser`の確認コード`OPACUSR001`のハードコードを除去。
  `okArray[<添字>] = "…";`を貸出延長と同型の字句走査で都度抽出し、想定値と照合してfail-closeする。
- **方針B**: `ReservationGateway.cancelReservation`の1段階目POST後・2段階目POST後の
  メンテナンス検知を、例外送出（＝`Failure(SITE_MAINTENANCE)`＝「送っていない」意味）ではなく
  一覧照合経路（`resolveCancelAndHide`）へ倒す。所有者裁定により**2段階目応答も対象に含めた**
  （据え置き時に挙げていたのは1段階目だけだが、2段階目は状態変更POST送信後であり実害が大きい）。
  この変更後、`cancelReservation`が`LibraryError.Maintenance`を漏らすのは**stage1 POST前だけ**である。

`testDebugUnitTest` 678件成功（失敗・エラー・スキップ0）、`assembleDebug`成功。
管理セッションが4種類の意図的破壊で実際に赤くなることを確認した（stage1の是正revert→1件、
stage2の是正revert→1件、想定値照合の除去→1件、いずれも該当テストのみが赤）。独立レビューも
別途実施し、重大・中の新規指摘なし。

**実サイト確認（2026-08-06、確認済み）**: 所有者が実機（CIのAPK）でアプリから予約取消を実行し、
**取消が成立し、続く自動非表示まで働くこと**を確認した。実装時点で最大のリスクだった
「方針Aのfail-closeが実サイトで空振りし、全テストが緑のまま取消が成立しなくなる」（進行指示14の形）
は起きないことが、アウトカムとして確定した。方針Bで触れた2段階目POST後の経路
（`resolveCancelAndHide`→非表示）が従来どおり機能することも同時に確認できている。

**この確認の限界**: ビルド元コミット符号（`BuildConfig.GIT_SHA`）は記録していない（進行指示6が
求める記録項目のうちSHAだけが欠けている。対象コードは`7d3954e`が最終）。また、確定したのは
「実サイトで取消が成立した」というアウトカムであり、「stage1 HTML全体で`okArray`代入が1件」という
構造の一次情報は依然として未採取である。

**発見した弱点と、その解消（2026-08-06、確認済み）**: 当初、「抽出した確認コードが実際に送信される」
という不変条件がテストで守られておらず、`buildForm()`をハードコードに戻しても全テストが緑のままだった。
`parse`経由では想定値照合により確認コードが常に`OPACUSR001`になるため、抽出値を送っていても
リテラルを送っていても外部から区別できないことが原因である。安全性に穴はなかったが、将来この照合を
緩めたときにリテラルが誤った値を送り得る。`ReservationCancelConfirmationForm`を直接構築して
`buildForm()`の出力を固定するテスト1件（`buildFormは渡された確認コードをそのまま末尾へ付ける`）を
追加して塞いだ。同じ劣化を再度注入し、このテストだけが赤くなることを確認済み。

**この弱点は独立レビュー（「テストが実装を守れていない箇所は見つからなかった」と報告）では検出されず、
管理セッションの破壊検証のみが検出した。** 段階3〜4で確認された「2系統のレビューが互いの見落としを
補完する」構図の再現である。**テストが通ることと、変更が守られていることは別である**という点は、
劣化注入で確かめる以外に確認手段がない。

### 6. 予約取消に続けて「非表示」まで自動で処理する（2026-08-04、実サイト一連確認成功）

**所有者の想定は「UIに非表示ボタンを置く」ことではなく、予約取消が成立したあとアプリが続けて
非表示まで処理してしまうこと。** 仕様を`docs/spec.md`§3.10、詳細設計を
`docs/design/reservation-cancel-auto-hide.md`へ確定した。Stage 15 HARで確定した二段階通信を既存の
`cancelReservation`内へ接続し、2026-08-04に実サイトでの一連成功を所有者が確認した。

- 背景: 実サイトは取消しても対象行を一覧から消さず、`state=CANCELLED`・非表示ボタン(`yoykHihyoji`)の
  行として残す。利用者から見れば取り消したはずの本が一覧に残り続けるため、続けて非表示にできると自然。
  同一`tilcod`の重複（取消済み行を残したまま再予約）も、非表示まで済ませていれば起きない。
- サイト側の導線はHARで確認済み: `yoykHihyoji`は固定stage1 query付きPOST、検証済み
  `prevRequestForm`と固定確認署名を用いるstage2 POSTから成る。実装はこの二段階通信だけを使う。
- 現在の実装は非表示ボタンの**コード値（`yoykHihyoji('...')`の引数）を保持していない**。実装時は
  `ReservationListRow`の通信内部値として抽出する。ただし取消直後の同一処理内だけで使用し、Roomへは
  永続化しないため、想定していたv7→v8マイグレーションは不要と判断した。
- 確定方針: 取消成立を完全な一覧で確認できた場合だけ、今回増えた取消済み行を一意に固定して非表示POSTを
  1回だけ送る。過去の取消済み行は清掃しない。非表示失敗・成否不明でも取消成功は維持し、一覧整理の
  警告として分離表示する。POST後不明は再送せず、再起動後にも再開しない。
- 複数選択の一斉取消では、選択された各予約を順次「取消成立確認→その行の非表示→非表示後照合」まで
  処理する。正常系では取消成立した選択行すべてが非表示対象であり、先頭の1行だけには限定しない。

#### 実サイト一連確認（2026-08-04、確認済み）

**確認済み**: 所有者が実機（CI APKビルド元コミット`12b9613`。アプリ内表示ではSHAでなくコミット符号
`12b9613`が確認できた）で、新規に1件予約したのち取り消す操作を行った。アプリの結果表示は通常どおり
（一覧整理の警告文言は出なかった）。直後にブラウザで公式サイトの予約一覧を直接確認し、**対象行が
一覧から完全に消えている**ことを確認した。詳細設計§9が定める最終確認項目のうち、非表示成立の
決定的な証拠（送信後の対象行消失）を独立した経路（アプリ経由でなくブラウザでの直接確認）で得た。

**未取得（この確認では対象外）**: 詳細設計§9が例示するmethod・完全URL・フォーム項目・Referer/Origin/
Cookie・redirect列・確認段階といった通信レベルの詳細は、この確認では採取していない（アプリの通常操作と
外部ブラウザでの結果確認だけで行ったため）。これらはStage 15 HARで別途確認済みであり、今回の確認は
「実装がその二段階通信を実際に使い、実サイトで成立させた」というアウトカムの確定を目的とした。
通信レベルの詳細記録が別途必要になった場合は、明示承認付きの診断（`liveReservationHideDiagnostic`。
handoff.md「非表示診断」参照）を追加で行う。

- **結論**: アプリからの予約取消→自動非表示は実サイトで成立する。§9の受入条件は満たされた。
- 詳細設計冒頭の状態表記、および本節見出しをこの確認結果に更新した。

### 4. 新着資料からキーワード一致で自動予約する（2026-08-03段階1〜13完了）

- 所有者との要件検討を完了し、正本を`docs/spec.md`§3.11へ追記した
- 完全自動、メンバー並び順による動的割当、アプリ独自上限なし、新着更新への連動、
  2か月の制御記録、直近1回の表示履歴、ホームダイアログと端末通知を採用する
- 設計レビュー往復は`docs/design/new-arrival-auto-reservation-review.md`の
  「所有者裁定と設計者の最終結論」で結了した。以後は同節の裁定を優先し、追加レビューを
  実装着手条件にしない
- 予約取消は予約一覧UIから実行可能であり、誤検出時の手動取消手段は整った
- 技術設計は`docs/design/new-arrival-auto-reservation.md`に確定した。Room v8、ルール照合、
  制御・直近履歴、送信時受取館記録、分離利用状況取得、`ReservationSubmissionResolver`抽出まで
  実装済みである

## 検証済み事項（2026-08-04）

### 複数メンバー分をカートに入れた状態での一括予約（確認済み）

本Aをアカウントa、本Bをアカウントbに割り当ててカートに入れ、一度の確定操作で両方を予約できることを
**所有者が実機で確認済み**である（時期は2026-07-26より後、正確な日付・ビルド元SHAは未記録）。

- 実装は`ReservationCartRepositoryImpl.execute`が対象を`memberId`でグループ化し(`processMember`)、
  メンバーごとに分離セッションでログインして`ReservationSubmissionResolver`経由で順に処理する構成を
  維持している（2026-08-04時点のコードで確認済み）。認証失敗や中断は当該メンバーの残件だけを止め、
  他メンバーは続行する。
- 過去に未検証としていた理由（検証用アカウントの一方が予約上限に達しており、失敗時に上限起因か
  アプリの不具合かを切り分けられない）は解消済み。予約上限到達時のサイト応答は実測済みで
  （site-research.md §6.4）、`FailureReason.RESERVATION_LIMIT_EXCEEDED`に落ちることも確認済みである。

## 新着キーワード自動予約（2026-08-03段階1〜13完了）

機能要件の正本は`docs/spec.md`§3.11、技術設計の正本は
`docs/design/new-arrival-auto-reservation.md`である。旧ロードマップの未決事項は所有者との対話で解決済み。

### 確定した処理概要

1. マスタースイッチON時、新着資料の取得成功を契機にルールを照合する
2. 有効ルールの候補があれば各メンバーの予約・貸出状況を直前取得し、家族内の予約中・貸出中・
   読書記録・過去処理済みを除外する
3. ルール順、出版年月順、固定タイブレークで候補を並べる
4. 資料ごとに設定画面のメンバー順で予約し、明示的な上限超過またはPOST前失敗だけ次順位へ回す
5. 既存の予約ゲートウェイによる一回限りのPOSTと予約一覧照合を使い、POST後不明は再送しない
6. 制御記録は最初の候補日から2か月、表示履歴は報告対象のある直近1回だけ保持する
7. 端末通知は個人情報を伏せて「確保済み／見送り／エラー」の件数を示し、ホームの一回限りの
   ダイアログと最新履歴で個別結果を示す

### 実装時に維持する境界

- 自動予約専用ボタンは作らない。日次同期、画面表示時の自動更新、既存の「更新」のあとに共通処理を置く
- 12時間の鮮度抑止は画面表示時の自動更新だけに適用する。日次同期と明示更新は常に取得し、
  取得成功後だけ予約判定する
- 無効ルールは予約しないが照合し、他の理由がなければOFFによる見送りをホームで知らせる
- アプリ独自の冊数上限やルールごとのメンバー固定は実装しない
- 取消操作は自動予約結果から直接行わず、実装済みの予約一覧UIへ集約する
- 端末通知が無効でも自動予約を止めない
- 自動テスト・CIから実サイトへの予約POSTを絶対に送らない
- 利用状況は通常同期要求列の`usrrsv`までの厳密な接頭辞を分離セッションで使い、
  予約セッションには利用状況画面を挟まない
- 手動予約・即時予約・自動予約・予約取消は候補単位の共通書込ゲートと書込世代で調停する
- `PREPARED`残存時は`preparedMemberId`や後続メンバーの有無にかかわらず資料全体を自動再送しない
- 読書記録に同一`tilcod`がある資料は除外する。画面経路では読書記録が最終同期時点で古い可能性を
  既知リスクとして扱う

### 次に行うこと

技術設計§12の段階1〜5は実装済みで、ローカル`testDebugUnitTest`471件が成功した。
実v7相当DBを`MIGRATION_7_8`付きの実`AppDatabase` v8として再オープンし、Roomのスキーマ検証と
既存データ読戻しも確認済みである。

`ReservationSubmissionResolver`は独立差分として抽出済みで、通信順、POST回数、再認証、
結果分類、送信館記録を維持している。コミット`37c267f`のCI APKでカート予約と即時予約の双方が
実サイトで成立したことを、所有者が2026-07-29に確認した。段階6の実機回帰関門は通過済みである。

段階7として、手動カート予約・即時予約・予約取消へ共通書込ゲートを接続した。
共通`ReservationOperationGate`による直列化、POST直前の書込世代加算、
再認証セッションへの境界引継ぎを単体テスト477件で確認した。

段階8として`AutomaticReservationCoordinator`を実装した。ルール照合、既読・利用状況・制御記録による
除外、メンバー優先順のフォールバック、`PREPARED`境界、書込世代に応じた利用状況の再取得、
成功した最新利用状況のRoom反映、直近履歴の保存を単体テストで確認した。
新規予約セッションは、呼出側への所有権移譲前にキャンセルまたは想定外例外が起きた場合も必ず閉じる。
全単体テスト504件が成功し、独立したSolレビューで重大・中指摘がないことを確認した。

段階9として`NewArrivalUpdateCoordinator`を実装し、画面の自動・手動更新と日次Workerを接続した。
更新全体の重複実行は待機させず`AlreadyRunning`とし、画面自動更新だけ12時間の鮮度抑止を適用する。
新着取得・Room全置換に成功した場合だけ自動予約を呼び、`PREPARED`到達後はWorkerの短時間再試行を
行わない。全単体テスト510件と独立したSolレビューで、段階9に重大・中・低指摘がないことを確認した。

段階10として、専用`auto_reservation`チャネルから個人情報を含まない集計通知を出し、
通知タップで`MainActivity`の`onCreate`／`onNewIntent`からHOMEへ一度だけ遷移する経路を実装した。
通知不可・例外は予約結果や機能ON/OFFへ影響させない。全単体テスト519件が成功し、独立した
Solレビューでは機能コードの指摘なし。低重大度の試験不足として、ActivityからComposeまでの
一貫したHOME遷移試験と、既存の返却期限・受取可能Android通知生成の直接回帰試験が残る。

段階11として、設定画面のマスタースイッチ・前提条件検証・ルールのタグ編集・個別ON/OFF・
長押しドラッグ並べ替え・削除確認を実装した。新着画面は取得中と自動予約中を分け、マスターON時の
常設説明を表示する。ホームは最終試行概要を常設し、未確認結果をHOME表示時だけ1回ダイアログ化し、
全画面の最新履歴と予約一覧への導線を持つ。履歴JSONの破損・未知結果は成功扱いにせず安全な文言へ倒す。

レビューで発見した履歴Aの確認が新しい履歴Bへ誤適用される競合は、`runId`を条件にしたRoomのCAS更新と
表示対象`runId`だけを閉じるController境界で解消した。ルール初期読込中の操作が既存ルールを空で
上書きし得る競合も、初回読込完了待機と同一Mutexで解消した。独立したSol最終レビューでは
重大・中程度の指摘なし。全単体テスト542件と`assembleDebug`が成功した。

段階12の実機回帰を一部実施した。CI APKで、ルール適合時の1冊予約、予約枠を
埋める件数が一致した場合の予約、予約上限到達時の見送り件数と「全員の予約枠が不足していたため
見送りました」の表示、**マスタースイッチOFF時に予約されないこと**、ホームの予約一覧、
再起動後の設定・ルール・履歴保持を所有者が2026-08-01に確認した。通知タップからホームと
結果ダイアログが開くこと、第一優先メンバーが上限時に第二優先メンバーへ予約されることも
2026-08-02に確認した。診断エクスポートの同日区間は29件すべてGETで予約POSTは0件だったが、
収録実行が1回で設定状態の印がないため、この区間がマスターOFFによるものかは通信レベルで
個別に立証したものではない。実機上のHilt Singleton同一性は段階13の待機表示試験まで未検証である。

**訂正（2026-08-04）**: 上記の実機確認は**マスタースイッチOFF**についてのみ行われた。
**個別ルールOFF（マスターON・特定ルールだけ無効）時に予約されないことは実機で未検証のまま**である。
コードレベルでは`AutomaticReservationCoordinatorTest`に無効ルールだけの候補が`RULE_DISABLED`として
記録され予約POSTへ進まないユニットテストがあるが、実サイトでの確認は別途必要である。

同じ実機確認で、全画面の最新履歴を最下部までスクロールしても「予約一覧を見る」を確認できない
視認性問題が判明した。導線をタイトル直下の固定領域へ移し、履歴本文だけをスクロール可能に修正した。
上部ボタンと予約一覧への遷移は実機確認済み。本文がスクロールを要する件数での固定挙動は未検証のまま
試験をスキップする。結果ダイアログ側の導線、ナビゲーション、確認済み処理の`runId`契約は変更していない。

段階13として、`ReservationOperationGate`へ自動予約・手動予約・手動取消の操作種別だけを公開する
観測`StateFlow`を追加した。手動カート予約・即時予約・予約取消が自動予約の保持中に実際に待機する間だけ、
各画面へ「自動予約処理の完了待ち…」を表示する。手動操作同士の待機では従来の処理中表示を維持する。
状態更新は短時間の同期ロックで保護し、取得前・取得直後・処理中のキャンセルと例外でも待機・保持状態を
除去して本体Mutexを解放する。観測状態は永続化せず、資料ID・利用者・認証情報を含めない。
対象テストと全単体テスト550件が成功し、独立したSolレビューで重大・中・低いずれも指摘なし。
ローカルAPKビルドは行わず、push後のGitHub CI成果物を実機検証に使用する。

段階13のCI APKについて、所有者が2026-08-03に実機確認を完了した。自動予約中に手動予約・取消を
開始した場合の待機表示と、ゲート解放後に手動操作が続行されることを確認済みである。

段階14として、共通の書誌詳細画面のタイトル直下に「公式サイトで見る」リンクと
「予約順番待ち：N人」を追加した。リンク先は固定HTTPS originの公開書誌詳細URLへ`tilcod`を
クエリパラメータとして安全に組み立て、端末の既定ブラウザで開く。待ち人数は既存の書誌詳細
「予約数」を使い、取得成功時だけ表示して0人も明示する。追加通信・永続化・予約/取消処理の変更はない。
対象テストと全単体テスト554件は成功し、独立したSolレビューでは重大・中程度の指摘なし。自動UI試験は
未整備のままだが、リンク表示・クリックと待ち人数表示はCI APKでの実機確認を所有者が完了した
（2026-08-04）。ブラウザ不在端末での挙動は引き続き未検証。

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
  **【この判定は誤りであり、12回目のライブ実測により改訂された。後述「取消後の一覧仕様の確定と
  判定の改訂」を参照すること。】** 実サイトは取消成立後も対象行を「取消」状態で一覧に残すため、
  「`tilcod`行の消失」を成功条件にすると取消に成功しても永久に成功と判定しない。また取消済み行は
  サマリの予約中件数に数えられないため、完全性ガードも常に不成立になる。
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

#### 10回目アプリライブ取消診断とaction前提の破綻（2026-07-28）

所有者が指定した`tilcod=1000002034513`で実行した（実行時HEAD=`8d2c6a4`）。

- **確認済み**: 複合ガードは初めて全て通過した（`signatureMatched=true`、`targetStillPresent=true`、
  `matched=true`）。第2段階へ進む条件そのものは満たしていた。
- **確認済み**: 停止点は`ReservationCancelConfirmationFormParser`であり、
  `prevRequestFormのactionがありません`で`ParseException`となった。取消POSTは1段階目の1回だけ、
  結果は`IndeterminateAfterPost`、取消後一覧に対象`tilcod`は残存し、取消は不成立である。
- **確認済み（設計上の瑕疵）**: `8d2c6a4`の同パーサは「`prevRequestForm`にaction属性がある」という
  **未実測の前提**の上に書かれていた。実サイトを観測せずに書いたパーサが、実サイトに存在しない要素を
  必須にして自ら停止した。監査レビューが指摘した「見えていない通信を推測で補う」構図のパーサ版の再発である。
  副作用の面ではフェイルクローズであり、余分なPOSTは発生していない。

#### 11回目アプリライブ取消診断と送信先の確定（2026-07-28）

第2段階の送信可否・判定・送信内容を一切変更せず、読み取り専用の匿名診断
`cancel-reservation-prevform`だけを追加して採取した。取消POSTは1段階目の1回のみ、対象は残存。

- **確認済み（実測）**: `forms=1 attrs=method,name action=(empty) method=post target=(empty)
  enctype=(empty) id=(empty) controls=213`。実サイトの`prevRequestForm`の属性は`name`と`method=post`の
  2つだけであり、**action属性は取り損ねではなく本当に存在しない**。
- **確認済み（実測）**: 送信処理は`document.prevRequestForm.submit();`である。actionを設定せずそのまま
  submitしている。採取済みfixture`reservation_cancel_confirmation_live_fragment.js`がforループで
  終わっていた、その続きに当たる。
- **確認済み（実測）**: 1段階目POSTの応答は`status=200 redirect=-`であり、リダイレクトしない。
- **演繹**: action省略時の送信先はHTML標準では現在のドキュメントURLである。リダイレクトが無いので、
  それは1段階目に実際に送ったURL、すなわち
  `WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1`（**クエリ付き**）になる。
- **矛盾と扱い**: `docs/site-research.md`の従前の記録は第2段階を「クエリ無し」としており、これと
  矛盾する。DevToolsのNetwork Name列がパスしか表示しないための読み違いであった可能性が高いが、
  **これは推定であって確定ではない**。実装は今回の直接実測（action不在・`submit()`・リダイレクト無し）を
  根拠とする。8回目にクエリ無しへ2回POSTして取消が成立しなかった事実とも整合する。
- **診断の設計上の教訓**: 当初の診断は識別子`prevRequestForm`を含む文だけを拾う実装だった。レビューで
  「別名束縛（`var f = document.prevRequestForm; f.submit();`）や`document.forms["..."]`経由では
  送信処理を取りこぼす」と指摘され、署名候補の**直後に続く文**を識別子非依存で読む`tail=`へ拡張した。
  結果として`stmts=`側で`document.prevRequestForm.submit();`を捉えられたが、識別子依存の採取だけに
  賭けていたら空振りだった可能性がある。

#### action省略を正常系として扱う改修（2026-07-28、実サイト取消成立は未検証）

- action属性が空でも例外にせず、`ReservationCancelConfirmationAction`（`Explicit(value)` /
  `SameAsCurrentDocument`）で型として区別する。空文字列をそのままURL解決へ渡す曖昧な扱いはしない。
- `SameAsCurrentDocument`の送信先は1段階目に実際に送ったURL（`stage1Page.url`）を引き回して使う。
  クエリ付きURLをハードコードしない。`Explicit`は従来どおり同一origin・同一path・クエリ無しへ限定し、
  `SameAsCurrentDocument`も同一origin・同一pathを必ず検証する（クエリの有無だけ制約しない）。
- 第2段階の本文は不変（`prevRequestForm`のDOM順controls＋末尾に`OK_CODES_NAME`の実field名で
  `OPACUSR001`）。1段階目送信内容との多重集合一致検査も維持する。実サイトのcontrols=213に対する
  この一致検査は、**本改修で初めて実際に評価される**。
- 診断は`noteDiagnostic(stage) { ... }`のラムダ版で遅延評価し、診断無効時は計算自体を行わない。
- **残存リスク（未対応）**: `stage1Page.url`は実際に送ったリクエストURLであり、応答の最終URLではない。
  現在はリダイレクトしないことを実測済みのため一致するが、将来サイトがリダイレクトを返すようになると
  「現在のドキュメントURL」と食い違う。同一origin・同一pathの検証は残るため送信先が外部へ逸れることは
  ないが、リダイレクト検出を安全弁に加える余地がある。
- **未検証**: アプリ実装による実サイトでの取消成立は依然として未検証である。次のライブ診断は
  **第2段階POSTが実際に送信され、予約が取り消され得る**初めての実行になる。所有者承認の対象1件で行い、
  実行前後のHEAD SHA、対象`tilcod`、POST段階数、取消後一覧の完全性と対象行の有無を記録すること。

#### 12回目アプリライブ取消診断で実サイト取消が成立（2026-07-28）

所有者承認のうえ`tilcod=1000002034513`で実行した（HEAD=`8d2c6a4`＋未コミットの改修）。
**アプリ実装による実サイトでの予約取消が初めて成立した。**

- **確認済み（成立の根拠）**: 取消POSTは1段階目・2段階目の計2回。応答の`site-messages`は8件のリストを
  返し、**7番目だけが画面固有で差し替わる**。1段階目は「予約の取消を行います。よろしいですか？」、
  2段階目は「**予約の取消が完了しました。**」であり、他7件は両段階で同一の共通定数だった。
  共通定数リストの同じ位置が入れ替わる構造であるため、「できません」で誤検出した過去の一般語判定とは
  性質が異なり、サーバが取消完了を返したと判断できる。
- **確認済み**: 2段階目の宛先は`WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1`（**クエリ付き**）
  であり、11回目の実測から演繹したとおりだった。本文は`prevRequestForm`のDOM順213 controls＋末尾`okCodes`。
  実サイトのcontrols=213に対する多重集合一致検査もこのとき初めて通過した。
- **確認済み（当時の判定）**: それにも関わらずアプリの判定は`IndeterminateAfterPost`だった。
  `取消後一覧の完全性を確認できない (summary=19, parsed=20)`、`targetPresent=true`。
  取消前は件数一致（不一致の診断が出ていない）だったため、取消後にサマリだけが19へ減り、
  一覧は対象行を含む20行のままだった。

#### 取消後の一覧仕様の確定と判定の改訂（2026-07-28）

所有者から仕様が提供され、書き込み副作用ゼロの一覧観測診断（`liveReservationListInspect`）で実測して
確定させた。実測内訳は`summaryReservationCount=19 parsedRowCount=20`、
`予約中15行 + 提供可能3行 + 移送中1行 + 取消1行 = 20行`である。

| 予約状態 | 取消ボタン | 非表示ボタン | `cancelCode` | サマリ計上 |
|---|---|---|---|---|
| 予約中 | `yoykCancel` | — | あり | ○ |
| 提供可能 | — | — | なし | ○ |
| 移送中 | — | — | なし | ○ |
| 取消 | — | `yoykHihyoji` | なし | **×** |

- **確認済み**: 実サイトは**取消しても対象行を一覧から消さない**。予約状態列が「取消」になり、
  取消ボタン(`yoykCancel`)が消えて「非表示」ボタン(`yoykHihyoji`)が置かれる。「非表示」を押すと
  初めて一覧から消える。この仕様はこれまでどのドキュメントにも記録されていなかった。
- **確認済み**: サマリの予約中件数から除外されるのは**取消済み行だけ**である（19 = 20 − 1）。
  提供可能・移送中は数えられている。**移送中を除外してはならない。**
- **改訂した判定**: `resolveCancelByListDiff`は、取消後の対象`tilcod`行が
  一覧に無ければ`CancelledAndHidden`、`state=CANCELLED`**かつ**非表示ボタンありなら`Cancelled`、
  片方だけの一致・他状態での残存・`tilcod`重複はすべて`IndeterminateAfterPost`とする。
  状態文字列とボタンの両方を要求するのは、「状態文字列だけに依存しない」既存方針と
  「確証がなければ成否不明へ倒す」監査方針の両立である。
- **改訂した完全性ガード**: `summaryReservationCount == 取消済みでない行数`。この修正は取消だけの話では
  なく、取消済み行が一覧に1行でもあると**直接予約の成立照合も常に不完全**と判定されていた。
- `ReservationState`に`CANCELLED`・`IN_TRANSIT`を追加した。Roomは名前文字列で保存する
  （`LocalDateConverters.reservationStateToString`）ため**マイグレーション不要**、DBバージョンは7のまま。
- `ReservationCancelAttempt`/`ReservationCancelOutcome`を`Cancelled`（取消済み・一覧に残存）と
  `CancelledAndHidden`（一覧に無い）に分けた。所有者の意向により、将来「非表示」操作をアプリへ
  組み込む余地を残すためである。`CancelledAndHidden`は「一覧に無い」という観測事実だけを意味し、
  既に非表示化されたのかサイトが即時に消したのかは区別しない。Repositoryは両方とも成功として扱い、
  ローカルDBから即時削除する。
- **非表示ボタンのコード値(`yoykHihyoji('...')`の引数)は現行実装では保持していない。**
  2026-08-03の自動非表示設計では、取消直後の同一処理内だけで使う`ReservationListRow`の通信内部値として
  抽出し、`Reservation` Entityへは持たせない方針に改めた。Roomマイグレーションは不要である
  （`docs/design/reservation-cancel-auto-hide.md`）。
- ライブ取消診断(`LiveReservationCancelDiagnostic`)の成否判定も`report.attempt`ベースへ改めた。
  従来の`assertFalse(stillPresentAfter)`は「取消成功なら対象行が消える」という誤った前提であり、
  12回目が失敗扱い(exit code 1)になった一因である。観測値は`targetRowPresentAfter`へ改名し、
  `cancel-after`ステージへ`state`と非表示ボタンの有無も出す。
- **検証済み（13回目、後述）**: 改訂後の判定が実サイトで`Cancelled`を返すことを確認した。
  「非表示」ボタンの送信実装、および予約取消UIは未着手である。

#### 同一tilcod重複という設計欠陥の発覚と対応（2026-07-28）

13回目の対象を読み取り専用で観測したところ、**同一`tilcod`の行が2つ**あった
（`予約中`(cancelCodeあり) 1行 ＋ `取消`1行）。取消済み行を非表示にせず同じ書誌を予約し直すと
この状態になる。**「取り消してから同じ本を予約し直す」は普通の操作であり実運用で必ず起きる。**

- **確認済み（欠陥）**: 監査レビューの指摘に沿って成功判定を`cancelCode`から`tilcod`へ切り替えたが、
  「取消後も行が残る」仕様を知らずに設計したため重複を想定できていなかった。この状態では
  `取消対象の資料コードを一意に特定できません`で**送信前に停止**し、取消できない。
  `inspectCancelConfirmationStage`（2段階目送信可否の複合ガード）も同じ判定を持っており、
  そこだけ直し忘れると1段階目までは進んでも2段階目が常にスキップされる。
- **対応済み**: サイト側に行の同一性を追える安定IDが無いため、次善策として二重条件で照合する。
  - 送信前: `tilcod`の一意性を**取消可能な行（`cancelCode`非空）の中で**判定する。取消済み行は候補外。
    取消可能な行が同一`tilcod`に2つある本当に曖昧なケースは従来どおり送信前に停止する。
    あわせて対象`tilcod`の`state=CANCELLED`行数を**基準値**として記録する。
  - 送信後: 取消済み行数が**基準値+1**になり、**かつ**取消可能な行が0になったときだけ`Cancelled`。
    対象`tilcod`の行が1つも無ければ`CancelledAndHidden`。それ以外はすべて`IndeterminateAfterPost`。
    「増分」だけでなく「取消可能な行の消滅」も同時に要求するのは、確証がなければ成否不明へ倒すため。

#### 13回目アプリライブ取消診断（2026-07-28、改訂後の判定を実サイトで検証）

所有者指定の`tilcod=1000002035525`（取消済み行が併存する実運用に近い条件）で実行した。
HEAD=`7f8f31c`＋重複対応の未コミット変更。**成功した。**

```
cancel-target: tilcod=1000002035525 matches=2 cancellable=1 cancelled=1
cancel-reservation-stage: targetStillPresent=true signatureMatched=true matched=true
cancel-reservation-prevform: forms=1 attrs=method,name action=(empty) method=post controls=222
cancel-post: attempt=Cancelled      取消POSTは1段階目・2段階目の計2回、完全性ガードの警告なし
```

取消後の読み取り専用観測:

```
list-summary: summaryReservationCount=19  parsedRowCount=21
highlight[0]: tilcod=1000002035525 stateText=取消 state=CANCELLED cancelCodePresent=false buttons=[yoykHihyoji]
highlight[1]: tilcod=1000002035525 stateText=取消 state=CANCELLED cancelCodePresent=false buttons=[yoykHihyoji]
```

- **確認済み**: 取消前「予約中1行＋取消済み1行」→ 取消後「取消済み2行」。基準値1→2の増分、
  取消可能な行の消滅、完全性ガード（19 = 21行 − 取消2行）がすべて設計どおりに成立した。
- **確認済み（診断のバグ）**: `cancel-after`が`targetPresent=false`と記録したが、これは誤りである。
  `LiveReservationCancelDiagnostic`が対象行の検索に`singleOrNull`を使っており、**該当が2件以上あると
  nullを返す**ためである。重複対応を入れた際に診断側だけ取り残されていた。成否判定は
  `resolveCancelByListDiff`が別経路で正しく行うため実害は無かったが、診断ログが事実と食い違うため修正した。
- **接続済み**: 既存3経路の予約取消UIを変更せず、`yoykHihyoji`の二段階送信を取消成立後の内部処理へ接続した。
  実サイト一連確認は2026-08-04に完了した(本書「予約取消に続けて『非表示』まで自動で処理する」節参照)。

#### 予約取消UIの導線方針（2026-07-28、所有者決定。当時未設計・未実装、現在は3経路実装済み）

バックエンドが実サイトで検証済みになったため、UIへ進む。所有者が決めた導線は3経路で、
いずれも予約中一覧を起点とする。詳細は`docs/ui-design.md`§「方針: 予約取消の導線」に記録した。

1. 予約中一覧の各行の「取消」ボタンから1件取消
2. 予約中一覧の各行のチェックボックス＋最上部の「一斉取消」ボタンでまとめて取消
3. 予約中一覧の行タップで開く書誌詳細の取消ボタンから1件取消

経路3には2つの前提がある。**書誌詳細を開いた経路によって出し分ける**（予約中一覧から開いたときだけ
取消ボタンを置く）ことと、**書誌詳細を全画面ではなくオーバーレイのポップアップで重ねる**ことである。
いずれも既存実装に前例があり、書誌詳細は既に共通オーバーレイとして実装され、**予約カートから
開いたときだけ`Dialog`で重ねる分岐が`ui/app/`に存在する**（`current == Destination.RESERVATION_CART`）。
予約中一覧にも同じ方式を適用できる見込みである。

- **確認済み（ドキュメントの更新漏れ）**: `docs/spec.md`§5と本書の禁止事項は2026-07-26の書き込み制限
  撤回時に改定されたが、`docs/ui-design.md`だけ「予約取消のUIは置かない」が2箇所残っていた。
  UI実装前に改定した。
- **未決（設計時に決めること）**: 明示操作と最終確認の形（一斉取消は対象件数と対象が分かる確認が要る）、
  結果表示（**成否不明を成功と誤読させない**こと。一斉取消は件数ごとの内訳が要る）、
  サイト側では取消後も行が残るがアプリのローカルDBからは消える差の見せ方、
  同一`tilcod`が重複したときのUI上の区別。

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
# Stage 15 HAR二段階非表示診断

HAR実測により非表示は二段階POSTと確定した。実装は専用確認パーサーでprevRequestFormの多重集合と固定確認署名を検証してから第2段階を送る。確認scriptは単一inline script・字句安全・到達性付き二重loopに限定済みで、到達性修正後の対象テスト114件は失敗・エラー・スキップ0。Sol最終レビューは重大・中・低の新規指摘なし。

# Stage 16 予約取消後の自動非表示接続

既存3経路の予約取消共通フローへ、Stage 15で確定した二段階非表示を接続した。取消成立後の完全一覧で
今回増えた`hideCode`を集合差分から一意に固定できた場合だけ、同じ排他要求列内で二段階POSTと
送信後の完全一覧照合を行う。未知構文・複数候補・一覧不完全は安全側で停止し、各POSTは自動再送しない。

非表示未完了・成否不明でも取消成功は維持してローカル予約行を削除し、UIでは取消件数とは別に
一覧整理の警告件数を表示する。状態不安定時は同一メンバーの残件を中止し、他メンバーは続行する。
Room、DB version、同期、自動予約、再起動後の再開処理は変更していない。

ローカルでは`testDebugUnitTest` 584件（失敗・エラー・スキップ0）と`assembleDebug`が成功した。
Terra（high）実装後、Sol（low）レビューの中指摘1件を修正して再レビューし、最終結果は
重大・中・低の指摘なし。実サイトでのアプリ取消から非表示までの一連確認は2026-08-04に完了した。

## 一覧画面の行レイアウト改修（2026-08-05〜06、確定・実装済み）

**正本は`docs/ui-design.md`の3つの節**（「方針: 一覧画面の行レイアウト統一」および
「同 追い込み(第2次)」「同 追い込み(第3次)」）。本節は経緯と、他機能へ影響する事項だけを記録する。

対象は**予約中一覧・貸出中一覧・読書記録**の3画面。所有者がCIのAPKで実機確認しながら3回に分けて
指示し、いずれも確定した。**通信・書き込み・成否判定のロジックには影響していない。**

### 確定した内容（要点のみ。詳細はui-design.md）

- 個別の行にメンバー名を出さない（カラードットのみ。名前は各画面上部の絞り込み行にある）
- ドットは**書誌名と同じ`Row`**に置き`CenterVertically`で揃える。3画面共通
- 書誌名より下の行は`MemberDotIndent`（ドット径＋間隔＝17.dp）で書誌名と揃える
- 予約中一覧: 書誌名を太字にしない／館名の接頭辞「受取館」を出さない／取置期限は予約順位と同じ行／
  チェックボックスは1行目へ移し内容を左詰め
- 貸出中一覧: 返却期限を書誌名の下・館名のすぐ右へ。文言は「返却期限 8/19 まで」。
  色は太字＋`cautionInk`。延長ボタンは館名の行の右端

### 他機能へ影響する事項（ここだけは本書に残す）

1. **受取館が未定の予約行に、アプリの送信記録から館名を補完するようになった。**
   `reservation_pickup_submissions`（`ReservationCartRepositoryImpl`が書込み）を
   `StatusRepository.pickupSubmissions()`で観測し、`ReservationsContentBuilder`で解決する。
   - **`Reservation.pickupLibrary`（サイトが返した値）は書き換えていない。** アプリの記録と混ぜると
     「サイトが何と言っているか」が失われ、将来サイト側で受取館が確定したときに差分を検出できなくなる。
     **この制約を壊さないこと。**
   - `origin = UNVERIFIED_SUBMISSION`（POST後不明）の記録は「○○館（未確認）」と区別する。
     その予約行がアプリの送信で作られた保証が無いためである。**区別を外さないこと。**
   - 館コード→館名の対応は`Library.ALL_LIBRARIES`（12件）が唯一の正。**別表を作らないこと。**
2. **貸出中一覧の返却期限から、延滞・返却期限間近（3日以内）の色による区別が無くなった。**
   延滞行は行の背景色（`alertBg`）と枠線で引き続き区別できる。`LoanRow.dueSoon`は削除済み。
   区別を戻す場合は延滞時だけ`alert`にすればよい。
3. **`ReservationRow`/`LoanRow`/`ReadingRow`の`memberName`は削除済み。**
   他画面（`BookDetailView`・`HomeScreen`・`SearchScreen`・`BookshelfScreen`）の`memberName`は
   別モデルであり残っている。
4. **予約中一覧のチェックボックスは、行タップ（書誌詳細への遷移）の`clickable`とは別の`Row`にある。**
   2026-07-28の所有者決定「チェックボックスのタップ領域と行タップ領域を明確に分ける」を、
   レイアウト変更後も構造で担保している。**同じ`clickable`の内側へ入れないこと。**

### 検証状況

- `testDebugUnitTest` 670件成功（失敗・エラー・スキップ0）、`assembleDebug`成功。
- **第1次・第2次・第3次とも所有者が実機（CIのAPK）で表示を確認済み。** これをもって確定とした。
- レイアウトは単体テストで検証できないため、**表示に関する回帰はテストで守られていない**。
  一覧画面に手を入れるときは実機確認を伴わせること。

## 通知（2026-08-06、端末での受信・権限要求フローとも実機確認済み）

### 現在の状態

**通知の実装と配線は完全に機能する（確認済み）。** 所有者の端末で、返却期限リマインダーと
予約受取可能の両方を受信できた。**テスト用の導線ではなく、本番経路の「予約受取可能」通知も
実際に届いている。** 経路は次のとおりで、全段が動作することが確定した。

`SyncWorker`(24時間周期) → `StatusRepositoryImpl.sync` → `NotificationService.notifyAfterSuccessfulSync`
→ `NotificationPlanner` → `AndroidNotificationSink` → `NotificationManager.notify()`

### 通知タップの導線（2026-08-07実装。通知履歴からのタップは実機確認済み）

**不具合**: 返却期限リマインダーと予約受取可能の通知は、タップしても何も起こらず消えもしなかった。
`postReturnReminder`・`postPickupReady`に`setContentIntent`が無く、`setAutoCancel(true)`は
タップ動作が定義されて初めて効くためである。`postAutoReservation`だけが`PendingIntent`を
持っていた（段階10で実装、実機確認済み）。

**対応**: 「アプリを開くだけ」の共通`PendingIntent`（`ui/OpenAppNotificationNavigation.kt`）を新設し、
2つの通知へ接続した。消去は既存の`setAutoCancel(true)`に委ねる。

- **actionを設定しないこと。** 自動予約の`ACTION_OPEN_HOME`を流用すると`HomeNavigationCommandStore`が
  HOMEへ強制遷移させ、「アプリを開く」という要件を超える。前回開いていた画面のまま復帰させる。
  **この不変条件は`AndroidAutoReservationNotificationTest`の`assertEquals(null, savedIntent.action)`で
  固定してある**（誰かが誤って自動予約用の`PendingIntent`を流用する回帰を直接検知する）
- requestCodeは通知ID（1001／1002）を流用する。自動予約は1003。Androidは
  requestCodeと`Intent.filterEquals`（flagsは比較対象外）でPendingIntentの同一性を決めるため、
  **同じ値を使い回すと通知同士が上書きし合う**
- `postAutoReservation`は変更していない
- 検証: `testDebugUnitTest`・`assembleDebug`成功。`setContentIntent`を外すと該当テストだけが
  赤くなることを劣化注入で確認済み。独立レビューでも重大・中の指摘なし
- **確認済み（2026-08-07、所有者の実機）**: 端末の**通知履歴**を遡ってタップすると
  アプリが開くことを確認した。`PendingIntent`がシステムに登録され、タップで`MainActivity`が
  起動する経路そのものは実機で成立している
- **未確認**: 通知シェードに出ている**本通知**でのタップ、およびタップ後に通知が消えること
  （`setAutoCancel`）。通知履歴のエントリは元の通知が既に消えた後の記録であるため、
  自動消去の確認には使えない。次に通知が発生した時点で所有者が確認する

### 権限要求フロー（2026-08-06実装・実機確認済み）

当初、`POST_NOTIFICATIONS`のランタイム権限を要求するコードがアプリのどこにも存在せず
（`AndroidManifest.xml`の宣言のみ）、`targetSdk = 35`のためAndroid 13以降では通知が届かない
状態だった。当時受信できていたのは所有者が端末の設定アプリから手動でオンにしたためである。
設計漏れであり、`spec.md`にも権限要求の記述は無かった。

正本は`docs/design/notification-permission.md`。所有者裁定により、**設定画面での提示**と
**通知トグルをオンにした瞬間の要求**を併用する。文脈のない初回起動時の要求は行わない
（Androidは一度拒否されると以後ダイアログを出せないため、貴重な1回を消費しない）。

- 権限が無いときだけ、設定の「通知」セクション先頭に警告カードと操作ボタンを出す。
  通知設定の既定値は両方オンでトグル操作が発生しないため、この経路が主となる
- 初回拒否と永久拒否は**区別しない**。拒否されたら設定アプリへの導線を出す一本道にした
  （`shouldShowRequestPermissionRationale`はActivityへのキャストが必要で、区別しても導線は同じ）
- **`ON_RESUME`で権限状態を再評価する。** 設定アプリで許可して戻ったときに警告が消えないと
  機能として用をなさない。**ここが最も壊れやすく、単体テストで守れない**
- 判定は`AndroidNotificationSink.canPost()`の**アプリ単位2条件**（ランタイム権限＋
  `areNotificationsEnabled()`）と揃えてある。初期状態・`ON_RESUME`再評価・権限要求の
  コールバックの**3経路すべてが同じ判定を通る**こと。**この対称性を壊さないこと**
  （独立レビュー指摘。権限だけを見ると、権限は許可のままアプリ通知をオフにされた端末で
  警告を出さないまま通知が届かなくなる。要求コールバックも同じ判定を通さないと、
  権限は取れたが通知オフのケースで警告が消える）

`SettingsScreen`の引数は増やしておらず、`SettingsScreenController`・`SettingsUiState`・
DIモジュール・`LibraryApp.kt`は無変更である。

### 未解決（低）: チャンネル単位の無効化は検出しない

`canPost()`は3条件目として対象チャンネルの`importance != IMPORTANCE_NONE`を見るが、
権限フロー側は**アプリ単位の2条件しか見ていない**。「返却期限リマインダー」「予約受取可能」の
チャンネルを個別にオフにされた場合、警告を出さないまま**そのチャンネルの通知だけが届かない**。

対応するなら、どのチャンネルを見るか（片方でも無効なら警告か、両方無効のときだけか）と
文言を決める必要がある。実害が出た時点で検討する。

### 単体テストで守られている範囲（重要）

`NotificationPermissionMessages`（純Kotlin。4状態→文言・表示可否）**だけ**である。
`ON_RESUME`再評価・権限要求・設定アプリ遷移・トグル配線は**テストで守られていない**。
ここに手を入れるときは必ず実機確認を伴わせること。

## リリースビルド（2026-08-07、上書きインストール確認済み）

正本は`docs/design/release-build.md`。CIの成果物は`app-release.apk`（artifact名`app-release-apk`）に
なった。所有者がコミット`7dde301`のAPKで既存インストールへの上書きを確認済みである。

### 維持すべき事項

1. **署名は`debug.keystore`を流用している。絶対に鍵を変えないこと。**
   `buildTypes.release`に`signingConfigs.getByName("debug")`を割り当てている。鍵を変えると
   既存インストールを上書き更新できなくなり、**アンインストールが必要になってメンバー登録・
   カード番号・パスワード・予約の送信記録がすべて消える**（家族全員の端末で再設定が必要）。
   変更する場合は必ず所有者へデータ消失を確認すること。
   APKの署名がdebug鍵と一致することは実測で確認済み
   （SHA-256 `9fec46ca…d043fa00b` / `CN=Android Debug`）。
2. **R8/minifyは意図的に無効（`isMinifyEnabled = false`を明示）。**
   有効にするとJsoup（リフレクション）・Room・Hilt・kotlinx.serializationでProGuardルールが
   必要になり、不備があると**releaseビルドだけが実行時に壊れる**。単体テストはdebugで走るため
   **検出できない**。非公開アプリでサイズ削減の必要も薄い。
3. **`versionCode`はgitのコミット数から自動付与する。手で書かないこと。**
   CIの`actions/checkout`には`fetch-depth: 0`が必要である。shallow cloneのままだと
   `rev-list --count HEAD`が1を返し、versionCodeが常に1になって更新できなくなる。
4. **`versionName`はリリースごとに手で上げる**（2026-08-07所有者決定）。2026-08-06のリリース版を
   `1.0`とし、機能追加・バグ修正を1回行うごとに`1.1`→`1.2`…と増やす。
   `build.gradle.kts`の該当行にも規約をコメントで残してある。
5. 単体テストは`testDebugUnitTest`のままでよい。`BuildConfig.DEBUG`に依存する箇所が
   アプリ内に無く、releaseで挙動が変わる分岐が存在しないためである。

### 未対応

- **アプリアイコンが未設定**（`res`に`mipmap`が無く`android:icon`の指定も無い）。デフォルトの
  Androidアイコンのままである。所有者判断により今回は見送った。
- `ui/di/DebugUiModule.kt`というファイル名は実態（全画面のControllerを提供する本番モジュール）と
  合っていない。リネームは見送った。
- 死にコードだった`ui/debug/DebugScreenController.kt`・`DebugScreenFormatter.kt`とそのテストは
  除去済み（テスト683件→672件）。

### 経緯（今回行ったこと）

通知のトリガーが同期成功時しかなく受動的なため、設定画面へ手動発火用のテストボタンを
一時的に設置して受信を確認し、確認後に撤去した（設置`7874661`、撤去は本コミット）。
撤去は新規1ファイルの削除と`SettingsScreen.kt`の2行削除で完結し、他ファイルへの影響は無い。
一時機能であったため設計書`docs/design/notification-test-button.md`も併せて削除した。

### 通知が出る条件（実装から確認済み）

- 同期が**成功**した会員に対してのみ。同期が走らなければ通知は出ない（自動同期は24時間周期）
- 返却期限リマインダー: 期限が`returnReminderDaysBefore`日後以内（既定1＝前日。**期限超過も含む**）
- 予約受取可能: `state == READY`かつ`firstReadyNotifiedAt == null`の予約のみ
- 通知の投稿に失敗した場合、受取可能通知は`markReadyNotified`を呼ばない
  （[NotificationService.kt](../app/src/main/java/com/fallgist/nishinomiyalibrary/data/sync/NotificationService.kt)）。
  **権限を後から許可すれば、それまでに受取可能になった予約は次の同期で通知される。**
  この取りこぼし防止を壊さないこと

## マイ本棚の編集（2026-08-09、段階3実装完了・段階4未着手）

**次に実装セッションが段階4へ着手する機能。** 技術設計の正本は`docs/design/bookshelf-editing.md`である。
機能要件の正本`docs/spec.md`§3.13、サイト通信の正本`docs/site-research.md`§13と合わせて先に読むこと。

### 状態

- **確認済み**: サイト側の全6操作の送信先・段階数・確認文言・送信項目をブラウザで実測した
  （2026-08-08）。専用のテスト棚を作り、その中だけで作成・追加・更新・資料削除・本棚削除を実行し、
  採取後にテスト棚を削除して既存5本棚の状態へ戻したことを確認済みである
- **確認済み**: 確認コード（`okCodes`）は、作成`OPACSDI017`・更新`OPACSDI011`・
  資料削除`OPACSDI033`・本棚削除`OPACSDI010`。削除2操作は2026-08-09に専用テスト棚へ
  1段階目だけを送る安全診断で、応答HTMLの`okArray`代入から採取した。診断には2段階目を実装せず、
  採取後にテスト資料とテスト棚を削除して既存5本棚へ戻ったことを確認済み
- **確定済み**: 技術設計。正常系・失敗系・境界条件・永続化・排他・セキュリティ・回帰条件、
  6段階の実装分割、初回Terra作業票へ含めるチェックリストを`docs/design/bookshelf-editing.md`に記録した
- **完了（段階1）**: `BookshelfContent`、読取り専用`BookshelfRepository`、空棚を保持するRoom Flow結合、
  `replaceShelfSnapshot`、既存サマリの`shelfCount`更新、本棚画面の空棚列表示。対象テストと
  `testDebugUnitTest`は成功し、5.6-Sol（low）の独立レビューで重大・中程度の指摘なし
- **完了（段階2）**: 6操作のGateway、送信・確認フォームパーサ、Exactly-once、操作前後の全棚比較、
  MockWebServer/合成fixtureテスト。全単体テスト成功、最終Solレビューで重大・中程度の指摘なし
- **完了（段階3）**: 6種の公開mutation、`BookshelfStateGate`、Repositoryの認証・結果変換、成功時の
  Room即時反映、通常同期との直列化。同期→編集・編集→同期の競合統合テストを含む全単体テスト成功、
  修正後Solレビューで重大・中程度の指摘なし
- **未着手**: 段階4の本棚画面編集UI以降
- **未実施**: 実サイトへの書込み通信。段階2ではMockWebServer以外へPOSTしていない

### 設計で必ず織り込むこと

1. **サイトの編集画面は本棚単位の一括フォームで、資料ごとの部分送信ができない。**
   1件のメモを変えるだけでも、本棚内の全資料の`bookcmnt`/`eachcmnt`/`sortno`/`eachsortno`を
   **DOM順のまま**送る。貸出延長の`prevRequestForm`と同じ性質であり、**行の順序を保つこと**が
   正しさの前提になる
2. **資料を識別するフィールドが行ごとに無い。** 対象はDOM順（配列の位置）で決まると見られる。
   この推定は書き込みを伴う検証で確定させること。位置で対象を決める以上、**送信前に取得した一覧と
   送信内容がずれないよう、取得から送信までを1つの排他区間に収める**必要がある
3. **`prevRequestForm`の存在を二段階の判定根拠にしてはならない。** 資料追加は一段階だが、
   重複エラーのalertを出す応答にも`prevRequestForm`がある。段階数は`createConfirmDialog`の
   有無で判断する
4. 資料追加だけが一段階、他は二段階である。**操作ごとに段階数が違う**ことを型で表すか、
   段階数を実行時に判定するかを設計で決める
5. 成否判定は本棚の内容の再取得と照合で行う。成功alertの文言に依存しない
6. 二重登録はサイトが拒否する（「このリストには、既に同一の書誌が登録されています。」）。
   これは失敗ではなく「既に登録済み」として扱う。予約の`AlreadyReserved`と同じ位置づけ

### 調査を続けるときの注意（本調査で実際に踏んだもの）

- **`WOpacMnuTopInitAction.do?WebLinkFlag=1&moveToGamenId=...`はセッションをリセットする。**
  ログイン済みでもログイン画面が返る。本調査ではこれで3回セッションを失った。画面遷移は
  サイト内のリンク・ボタンから行うこと
- **確認ダイアログはネイティブ`window.confirm`であり、自動化ブラウザでは自動的にキャンセルされる。**
  二段階の操作をAIだけで完了させることはできず、人がOKを押す必要がある
- 認証情報の入力はAIが行えないため、ログインは所有者が行う
- キャンセルすると2段階目URLへ`cancelCodes`付きで再POSTされるらしく、1段階目の確認スクリプトが
  失われる。**「キャンセルして応答を読む」方法では確認コードを採れない操作がある**

### 設計での裁定（2026-08-09）

- 本棚削除は本棚名と消える資料件数を本文・確定ボタンに表示する破壊確認とする。名称再入力は求めない
- 成功後の再取得で得た本棚全体をRoomへ原子的に即時反映する。空棚を表示できる本棚基点の読取りAPIを設ける
- 通常同期と本棚編集は新設の`BookshelfStateGate`で直列化し、古い同期結果による成功直後の巻戻りを防ぐ
- 予約データを変更しないため`ReservationOperationGate`には参加しない
- Gatewayは操作直前のサイトDOM順を正本に全行を送り、成功alertではなく操作前後の全本棚比較で成否を決める
- 現時点で所有者判断を要する未決事項はない。未検証事項は設計書§12のとおりテスト条件として扱う
