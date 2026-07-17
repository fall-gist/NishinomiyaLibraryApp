# 実装引き継ぎ書(バックエンド)

このドキュメントは、バックエンド実装を担当するAIエージェント/開発者への
引き継ぎ資料です。**会話の文脈なしで読めるように書かれています。**

## まず読むもの(この順で)

1. [docs/spec.md](spec.md) — 確定済みのアプリ仕様。**ここに書かれた決定は変更しない**
2. [docs/backend-design.md](backend-design.md) — 本実装の設計書。モジュール構成・API・データモデルはこれに従う
3. [docs/site-research.md](site-research.md) — 図書館サイトの実地調査結果(エンドポイント・パラメータ・HTML構造の一次情報)

## スコープ

**今回実装するのはデータ層のみ**(UI画面はスコープ外。後日別途設計する)。
ただし動作確認用の最小限のデバッグ画面(§マイルストーンM5)は作る。

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

## フィクスチャ(テスト用HTML)

- 取得スクリプト: [scripts/fetch-fixtures.sh](../scripts/fetch-fixtures.sh)
  (認証情報は環境変数 `LIB_CARD` / `LIB_PASS` で渡す。**スクリプトや
  リポジトリに直書きしない**)
- 保存先: `app/src/test/resources/fixtures/`
- 実行環境からサイトに接続できない場合は、リポジトリ所有者に実行を依頼すること

## 禁止事項(厳守)

1. **書き込み操作の実装・送信禁止**: 予約・延長・登録変更・カート追加などサイトの
   状態を変えるPOSTは実装しない(調査済み参照系のみ)。将来拡張の口
   (LibraryGatewayへのメソッド追加)を塞がなければそれで十分
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
