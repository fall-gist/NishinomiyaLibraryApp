# 西宮市立図書館サイト調査結果

調査日: 2026-07-17
対象: https://tosho.nishi.or.jp/ (蔵書検索システム: LICS-XP `/licsxp-opac/`)

アプリのスクレイピング設計の基礎資料。**読み取り操作のみ**を調査対象とした。

## 1. 技術基盤

| 項目 | 内容 |
|---|---|
| システム | LICS-XP(三菱電機系の図書館システム)。Java Servlet(`*.do`)+ Tiles |
| 文字コード | UTF-8 |
| セッション | Cookie `JSESSIONID`(HttpOnly, path=`/licsxp-opac`) |
| CSRF対策 | ログイン後の画面に `hash` hiddenフィールドあり。画面遷移POSTに現在ページの `hash` と `gamenid` を同送する必要がある |
| 画面遷移 | ほぼ全てJSによるフォームPOST(`document.LBForm.action=...; submit()`)。URL直叩きできるGET画面も一部あり |
| User-Agent | ブラウザ系UAなら200。ボット系UA(WebFetchなど)は403で拒否される |

## 2. 蔵書検索(ログイン不要)

### かんたん検索

- 検索フォーム: `GET /licsxp-opac/WOpacEsSchCmpdDispAction.do`
- 検索実行: `POST /licsxp-opac/WOpacEsSchCmpdExecAction.do`
  - パラメータ: `condition1Text`(キーワード), `gamenid=tiles.WEsSchCmpd`, `tifKanrabtn=1`, `loccodschkflg=nocheck`, `returnid=`, `hash=`, `chkflg=`
  - セッションCookieが必要(先に検索フォームをGETしてセッション確立)

### 検索結果一覧のHTML構造

`div.doc` 単位で1件。安定したclass名でパース容易:

```html
<div class="doc">
  <div class="doc-title"><a href="...tilcod=1001000072590">タイトル</a></div>
  <div class="doc-writer">著者／著 出版社 １９６８</div>
  <div class="doc-available">貸出可否: <span id="LendFlg0"></span></div>  <!-- JSで後埋め -->
</div>
```

- 書誌ID: `tilcod`(13桁)
- ページング/ソート: `WOpacWebEsTilSubListAction.do?pagingMax=N / ?sortKey=...`(POST)
- 絞り込み: `WOpacWebEsTilListNarrowSearchAction.do`

### JSON API(発見。アプリに最適)

| API | 形式 | 内容 |
|---|---|---|
| `POST /licsxp-opac/getIsLend.do` (`tilcod=...`) | `{"isLend":"1"}` | 貸出可否(1=可, 0=不可) |
| `GET /licsxp-opac/WOpacEsApiAutoCompleteAction.do?keyword=...` | `["候補1",...]` | 検索キーワードのオートコンプリート |

### 書誌詳細

- `GET /licsxp-opac/WOpacTifTilListToTifTilDetailAction.do?urlNotFlag=1&tilcod={tilcod}`(URL直接アクセス可)
- 取得できる情報: 書名/著者名/出版者/出版年/請求記号/ISBN等の書誌事項、所蔵数/在庫数/予約数/発注数
- 所蔵一覧テーブル(1行=1冊): `No / 館名 / 資料番号 / 資料種別 / 請求記号 / 配架場所 / 帯出区分 / 状態(在庫・貸出中) / 貸出可否(○×)`

## 3. ログイン(利用者認証)

- ログイン画面: `GET /licsxp-opac/OpacInitLoginAction.do?subSystemFlag=0`
- 認証: `POST /licsxp-opac/j_security_check?subSystemFlag=0`(Java EEコンテナ管理認証)
  - `j_username` = `"0000000000000000"` + 利用者カード番号(16個のゼロを前置)
  - `j_password` = パスワード
  - 成功するとトップへリダイレクト、`JSESSIONID` セッションが認証済みになる
- パスワード種別が3種ある(本パスワード=フル機能 / 仮パスワード / 予約パスワード)。利用状況照会には本パスワードが必要

## 4. 利用者ページ(要ログイン)

メニューからの遷移はすべて `POST /licsxp-opac/WOpacMnuTopToPwdLibraryAction.do?gamen={画面名}`(`hash` と `gamenid` を同送):

| gamen | 画面 | 取得できる情報 |
|---|---|---|
| `usrlend` | 貸出状況一覧 | No / 資料名+著者+出版社 / 書誌種別 / 貸出館 / 貸出日 / **返却期限日** / 状態 / 予約数 ほか |
| `usrrsv` | 予約状況一覧 | No / 資料名 / 種別 / 受取館 / 連絡方法 / 予約日→割当日 / 予約期限 / **順位** / 予約状態 / 取置期限 |
| `mybooklist` | マイ本棚 | No / タイトルコード / 資料名+著者+出版社 / **メモ** / 登録日 |

さらに、ログイン後の全ページ共通ヘッダに **利用状況サマリ**(マイ本棚N件 / 貸出中N件 / 予約中N件 / カートN件)が出るため、1リクエストで概況が取れる。

データはすべて`<table>`ベースで構造は素直。返却期限・予約順位など必要な情報はすべてHTMLに直接出力される(JS後埋めではない)。

## 5. 開館カレンダー

- `GET /licsxp-opac/WOpacMnuTopInitAction.do?WebLinkFlag=1&moveToGamenId=msgcld&loccod={館コード}`
- 休館日はページ内JSに `holiday="YYYY-MM-DD ..."` の形で埋め込まれており、正規表現で抽出可能(当月から約3ヶ月分)
- 館コード: `001`中央 / `002`北口 / `003`鳴尾 / `004`北部 / `101`越木岩 / `102`若竹分室 / `103`段上分室 / `104`上ケ原分室 / `105`甲東園分室 / `106`高須分室 / `107`山口分室 / `109`義務教育校(計12施設)

## 6. 設計への示唆

1. **パースは現実的**: 主要データはclass付きdivか素直なtableで、Jsoupで安定してパースできる
2. **フロー再現が必要**: 「フォームGET→hash取得→POST」の2段階を踏むHTTPクライアント設計にする(ブラウザ偽装UA必須)
3. **JSON APIの活用**: 貸出可否とオートコンプリートはJSONで取れるのでHTMLパース不要
4. **書誌詳細はURL直行可**: `tilcod` さえ分かればGET一発。マイ本棚のタイトルコードから書誌詳細への連携も可能
5. **予約系の拡張余地**: 「カート追加→予約確定」のPOSTフローも同じ形式で存在するため、将来 `LibraryClient` に書き込み系メソッドを足す形で拡張できる(今回は実装しない)
6. **リスク**: `hash` 検証・画面ID(`gamenid`)の整合性チェックがあるため、フローを飛ばすとエラー画面になる可能性。パーサは差し替え可能なモジュールに分離すること

## 7. 注意事項

- 認証情報(カード番号・パスワード)はこのリポジトリに**絶対に含めない**。アプリでは端末内の暗号化ストレージ(Android Keystore + EncryptedSharedPreferences相当)に保存する
- アクセス頻度は低く保つ(定期取得は1日数回まで)。サイトに負荷をかけない
- サイト改修でパーサが壊れる前提で、HTML変化の検知(パース失敗時の通知)を組み込む
