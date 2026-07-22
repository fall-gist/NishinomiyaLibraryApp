# 西宮市立図書館サイト調査結果

調査日: 2026-07-17 / 予約導線ライブ検証追記: 2026-07-22
対象: https://tosho.nishi.or.jp/ (蔵書検索システム: LICS-XP `/licsxp-opac/`)

アプリのスクレイピング設計の基礎資料。参照系に加え、2026-07-22に、ユーザーの
最終確認に基づく**直接予約確定だけ**をライブ検証した。延長・取消・登録変更・公式カート
操作は調査・実装対象外である。

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
**注意: サマリの「マイ本棚N件」は登録書籍数ではなく本棚(リスト)の数**(2026-07-18のライブ検証で確認)。

### 4.1 マイ本棚は複数存在する(重要・2026-07-18追記)

マイ本棚は利用者が任意の名前で複数作成でき、`gamen=mybooklist` で開くページは
**「現在選択中の本棚」1つ分だけ**を表示する。実サイトでの検証結果:

- **本棚名**: `table[summary=本棚属性]` 内の `em.huge` のテキスト
- **本棚メモ**: 同テーブルの `td.memo`(空の場合はskipラベルのみ)
- **現在の本棚番号**: LBForm hidden `otherbook` の値
- **本棚一覧(番号+名前)**: ページ内の `<select name="otherbook" id="otherbook">` の
  `<option value='N'>名前</option>`。**ただしこのselectはHTMLコメント内にある**ため、
  Jsoupセレクタでは取得できず、生HTMLへの正規表現で抽出する必要がある
- **本棚の切り替え**: `POST WOpacSdiBookListToOtherBookDispAction.do?flg=1`
  - フォーム: `hash`(現在ページの値) / `gamenid=tiles.WSdiBookList` /
    `otherbook={切替先番号}` / `tilcod=` / `btnflg=`
  - 応答は切替先本棚の同型ページ(以降のhashは応答から更新して使う)
  - 表示状態の変更のみでデータ書き込みではない(読み取り専用ポリシーの範囲内)
- 各書籍行の表紙画像URL `book/cover?isbn=...` からISBNが取れる(`isbn=null` の資料もある)

データはすべて`<table>`ベースで構造は素直。返却期限・予約順位など必要な情報はすべてHTMLに直接出力される(JS後埋めではない)。

### 4.2 読書履歴(2026-07-19追記・ライブ検証済み)

サイトには**読書履歴機能**があり、「今までに借りた資料」が蓄積されている
(検証アカウントでは約600件)。

- **初回表示**: `POST WOpacMnuTopToPwdLibraryAction.do?gamen=usrread&initFlag=0`
  (通常の利用者ページ遷移と同じく `hash`/`gamenid` を同送)
- **一覧構造**: `table[summary=読書履歴一覧表]`。行は `<tr class="ItemNo ...">` で、
  **`</tr>` 閉じタグやtbodyが不揃いのため注意**(Jsoupは寛容にパースするが、
  セルは「次の `<th|td>` 開始まで」を1セルとして扱う想定でテストすること)。
  列: No / 書誌情報(タイトル+著者+出版社、`tilcod` 付き詳細リンクあり) /
  貸出日(`yyyy/MM/dd`) / 貸出館 / 貸出区分 / (削除ボタン列)
- **ページ送り**: `GET WOpacUsrReadListAction.do?sortKey=KASYMD&isAsc=false&startIndex={N}&hash={現在のhash}`
  (貸出日降順)。1ページの件数は画面の表示件数設定に従う(10/20/50/100)
- **表示件数の変更(2026-07-19実証)**: クエリの `pagingMax` は**無視される**。
  `POST WOpacUsrReadListAction.do` にフォームで `hash`/`gamenid=tiles.WUsrReadList`/
  `rowsPerPage=100` を送ると100件/ページになり、**以降のページ送りGETでも
  セッションに記憶されて100件が維持される**(実測: 100件×6ページで全量取得可)
- **注意**:
  - 行内に**削除ボタン**があるが、削除系アクションには**絶対に触れない**(読み取り専用)
  - 読書履歴はLICS-XPの仕様上、**利用者ごとに記録有効化が必要な場合がある**。
    履歴0件のメンバーは未有効の可能性

## 5. 開館カレンダー

- `GET /licsxp-opac/WOpacMnuTopInitAction.do?WebLinkFlag=1&moveToGamenId=msgcld&loccod={館コード}`
- 休館日はページ内JSに `holiday="YYYY-MM-DD ..."` の形で埋め込まれており、正規表現で抽出可能(当月から約3ヶ月分)
- 館コード: `001`中央 / `002`北口 / `003`鳴尾 / `004`北部 / `101`越木岩 / `102`若竹分室 / `103`段上分室 / `104`上ケ原分室 / `105`甲東園分室 / `106`高須分室 / `107`山口分室 / `109`義務教育校(計12施設)

## 5b. 新着資料(認証不要)

- ジャンル一覧: `GET /licsxp-opac/WOpacMsgNewMenuDispAction.do?moveToGamenId=msgnewmenu`
  - `<a href="...newMenuCode=NN">` が全28ジャンル(`01`総記〜`28`コミック)。h1=「新着資料ジャンル一覧」
- 各ジャンルの一覧: `GET /licsxp-opac/WOpacMsgNewMenuToMsgNewListAction.do?newMenuCode=NN`
  - `table.list` に **No./書誌種別/書名/巻次/著者/出版者/出版年月/分類/貸出** の列。1ページに全件(ページングなし)
  - 書名セルの `<a href="...tilcod=...">` からtilcod取得。貸出列は○/×
  - `newMenuCode` を空にするとエラー画面(全ジャンル一括取得は不可)
- GETのみで到達でき、hash/gamenidは不要。**全ジャンルを巡回してtilcodで名寄せ**して統合リストにする
  (アプリはジャンルを保持しない)。巡回はレート制御(500ms間隔)に従う

## 6. 直接予約(2026-07-22ライブ検証済み)

> 2026-07-22追記: 確認画面の`LBForm`は`action`を持たず、ボタンのJavaScriptが
> `WOpacEsTifDirectYoyExecAction.do`を設定する場合がある。確認フォームは固定`action`ではなく、
> `gamenid=tiles.WEsYoyConfirm`・`tilcod`・`contactweb=4`のhidden値と`receivename`のselectで
> 一意に特定する。実機での再予約成立は未検証。

### 6.1 公式カートと直接予約は別導線

- 公開検索結果には、別々の「カート」と「予約 / いますぐ予約」アクションが存在する
- 公式カートを経由せず、タイトルコード(`tilcod`)から直接予約できる
- アプリは公式カートを使用しない。候補の追加・削除はアプリ独自のRoomローカルカートで行い、
  この段階ではサイト通信をしない

### 6.2 ライブ確認したURLとフォーム

| 状態/操作 | 経路・項目 |
|---|---|
| ログアウト状態の直接予約導線 | `OpacInitLoginAction.do?...yoycartflg=WYoyConfirm&tilcod=...` |
| ログイン済みの直接予約確認 | `GET WOpacEsTifDirectYoyDispAction.do?tilcod=...` |
| 予約確定 | `POST WOpacEsTifDirectYoyExecAction.do?tilcod=...` |
| 確認フォームhidden | `gamenid=tiles.WEsYoyConfirm`、`tilcod` |
| 受取館 | select名=`receivename` |
| 連絡方法 | select名=`contact`。Email値=`4`、連絡不要値=`9`。hidden `contactweb=4` |

- アプリでは連絡方法を**Email固定**とし、連絡方法を選ばせるUIは置かない。確定POSTでは
  `contact=4`と`contactweb=4`を送る
- `gamenid`、`tilcod`、確認画面に存在するその他のhidden値は確認画面から抽出して同送する。
  検証していないhidden値を実装側で推測・生成してはならない
- 受取館コードは次のとおり。予約確認画面の`receivename`選択肢に選択コードがない場合は、
  別館に置換せずエラーにする

| コード | 受取館 |
|---|---|
| `001` | 中央 |
| `002` | 北口 |
| `003` | 鳴尾 |
| `004` | 北部 |
| `101` | 越木岩 |
| `102` | 若竹分室 |
| `103` | 段上分室 |
| `104` | 上ケ原分室 |
| `105` | 甲東園分室 |
| `106` | 高須分室 |
| `107` | 山口分室 |
| `109` | 西宮浜義務教育学校 |

### 6.3 成否・セッションに関する確認結果

- 実予約成功を確認済み: 『頭のいい子を育てるおはなし366』を高須分室・Emailで予約し、
  予約数が17→18となり、結果画面で予約済みであることを確認した
- 成功alertの正確な文言は取得していない。成功判定をalert文字列に依存させず、予約一覧・
  予約数・`tilcod`で照合する
- 同じ資料への重複確定POSTでは、確認画面に留まり、予約数は18のままで、alertは
  **「予約済の書誌があります。予約できません。」**だった。アプリではこれは
  `AlreadyReserved`(目的状態達成済み)として扱う
- 確認画面を開いてから確定まで時間を空けてセッションが切れると、POSTは
  `OpacLoginAction.do`のログインフォームを返し、予約は成立しなかった。そのため、
  実装は`login → confirm GET → fields抽出 → exec POST`をUI待機なしで同一セッション内に
  連続実行する
- POST後の通信断など成否不明時は、POSTを再送してはならない。メンバーごとに予約一覧を
  1回取得して`tilcod`を照合し、`Success` / `AlreadyReserved` / `Unknown`を確定する
- 明確に確定POST前のセッション切れだけは、新しいセッションで1回だけ再試行できる

### 6.4 未検証事項

予約上限、利用制限、予約不可資料、無効受取館コード時のサイト応答、メンテナンス中の
予約画面・確定POSTの挙動は未検証である。これらを既知のalert文言や成功条件として
実装に埋め込まない。

## 7. 設計への示唆

1. **パースは現実的**: 主要データはclass付きdivか素直なtableで、Jsoupで安定してパースできる
2. **フロー再現が必要**: 「フォームGET→hash取得→POST」の2段階を踏むHTTPクライアント設計にする(ブラウザ偽装UA必須)
3. **JSON APIの活用**: 貸出可否とオートコンプリートはJSONで取れるのでHTMLパース不要
4. **書誌詳細はURL直行可**: `tilcod` さえ分かればGET一発。マイ本棚のタイトルコードから書誌詳細への連携も可能
5. **予約の境界分離**: 公式カートは使わず、直接予約だけを`ReservationGateway`へ隔離する。
   ユーザーの最終確認後に限り、メンバー別の分離セッションで実行する
6. **リスク**: `hash` 検証・画面ID(`gamenid`)の整合性チェックがあるため、フローを飛ばすとエラー画面になる可能性。パーサは差し替え可能なモジュールに分離すること

## 8. 注意事項

- 認証情報(カード番号・パスワード)はこのリポジトリに**絶対に含めない**。アプリでは端末内の暗号化ストレージ(Android Keystore + EncryptedSharedPreferences相当)に保存する
- アクセス頻度は低く保つ(定期取得は1日数回まで)。サイトに負荷をかけない
- サイト改修でパーサが壊れる前提で、HTML変化の検知(パース失敗時の通知)を組み込む
- 新たに許可する書き込みは、ユーザーの明示操作・最終確認に基づく予約確定だけである。
  自動予約、延長、予約取消、登録変更、公式サイトカート操作は引き続き禁止する
