# 西宮市立図書館サイト調査結果

調査日: 2026-07-17 / 予約導線ライブ検証追記: 2026-07-22
対象: https://tosho.nishi.or.jp/ (蔵書検索システム: LICS-XP `/licsxp-opac/`)

アプリのスクレイピング設計の基礎資料。参照系に加え、2026-07-22に、ユーザーの
最終確認に基づく**直接予約確定だけ**をライブ検証した。延長・登録変更・公式カート操作は
調査・実装対象外である。予約取消は当初対象外だったが、2026-07-27以降に追加調査・バックエンド実装を
行っている（本番での取消成立は未検証。§9参照）。

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

### 表紙画像(2026-07-26追記・実測)

- openBD(`https://api.openbd.jp/v1/get?isbn=`)は蔵書によって `summary.cover` を**空文字**で返し、
  この場合アプリでは表紙が表示できない
- 一方、図書館サイト自身が `GET /licsxp-opac/book/cover?isbn={ISBNの数字のみ}` で表紙画像を配信しており、
  実測で3件とも画像(jpeg/png、50〜90KB)が返ることを確認した
- **既知の制約**: このエンドポイントは、該当する表紙が無いISBNでも200で「画像なし」の
  プレースホルダ画像を返す(実測: 存在しないISBN `9999999999999` でも200・69043バイトのPNGが返る)。
  したがってアプリ側のロジックで「表紙なし」を判別することはできない
- 対応: openBDが表紙を返さないときだけ、この図書館サイトのURLへフォールバックする
  (`SearchRepositoryImpl.coverUrl`)

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
> `WOpacTifDirectYoyExecAction.do`を設定する。確認フォームは固定`action`ではなく、
> `gamenid=tiles.WYoyConfirm`・`tilcod`のhidden値と`receivename`のselectで
> 一意に特定する。実機での再予約成立は未検証。
>
> 2026-07-25追記: フォーム特定条件は、`contactdirectweb`(hidden、値は問わない)・
> `receivename`・`contact`(select)の一意存在も加えた。旧記述の`contactweb=4`は
> 誤りだった(詳細は6.6)。
>
> 2026-07-22の追加ライブ検証では、同じ確認フォームでも時間を空けて確定するとログイン画面へ戻り
> 予約は成立しなかった。一方、再ログイン直後に確認から確定まで連続して送信した場合は予約件数が
> 17件から18件になった。アプリは確認GET・フォーム解析・確定POSTを、全セッション共通の排他区間で
> 連続実行し、この間に同期等の別要求を割り込ませない必要がある。

### 6.1 公式カートと直接予約は別導線

- 公開検索結果には、別々の「カート」と「予約 / いますぐ予約」アクションが存在する
- 公式カートを経由せず、タイトルコード(`tilcod`)から直接予約できる
- アプリは公式カートを使用しない。候補の追加・削除はアプリ独自のRoomローカルカートで行い、
  この段階ではサイト通信をしない

### 6.2 ライブ確認したURLとフォーム

| 状態/操作 | 経路・項目 |
|---|---|
| ログアウト状態の直接予約導線 | `OpacInitLoginAction.do?...yoycartflg=WYoyConfirm&tilcod=...` |
| 通常書誌詳細 | `GET WOpacTifTilListToTifTilDetailAction.do?urlNotFlag=1&tilcod=...` |
| ログイン済みの予約確認表示 | 詳細LBFormを`POST WOpacTifDirectYoyDispAction.do?tilcod=...`へ送信 |
| 予約確定 | `POST WOpacTifDirectYoyExecAction.do?tilcod=...` |
| 確認フォームhidden | `gamenid=tiles.WYoyConfirm`、`tilcod` |
| 受取館 | select名=`receivename` |
| 連絡方法 | select名=`contact`。Email値=`4`、連絡不要値=`9`。hidden `contactdirectweb`(値は未取得) |

- アプリでは連絡方法を**Email固定**とし、連絡方法を選ばせるUIは置かない。確定POSTでは
  `contact=4`に上書きする。`contactdirectweb`はサイト発行値をそのまま送る(6.6参照)
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
- ライブ診断では、確認GETの直後の確定POSTが200であっても、本文が詳細検索フォーム
  (`gamenid=tiles.WEsSchCmpd`、`condition1Text`)となり、予約後一覧で対象`tilcod`が確認できない
  事象を確認した。確認フォームの項目名・値はブラウザ実測と一致していた。確認GETの完全URLを
  同一originの`Referer`、サイトoriginを`Origin`として確定POSTへ付与して再検証したが、単独では
  解消しなかった。このヘッダはブラウザ等価性のため維持する
- 次の有力仮説はログインPOSTのsuccessful controls不足である。ブラウザのログインフォームには
  `hash`、`gamenid=tiles.WMnuTop`、`username`、`j_username`、`h_username`、`j_password`があり、
  これまでは後者2項目だけを送っていた。ログインフォームからhidden項目をDOM順で抽出し、
  `username`・`j_username`・`j_password`を制御上書きした全項目を送る
- 予約確定ボタンの`onclick=exec(tilcod)`は、送信抑止フラグをfalseにして`LBForm.action`を設定し
  `submit()`するだけで、hiddenやselectの値を変更しないことを確認した。確定POSTは確認フォームの
  successful controlsをDOM順で送る必要がある。受取館・連絡方法はフォーム中間位置にあるため、
  末尾追加ではなく元位置で上書きする(6.6参照)
- 実サイト確認で、検索結果用の`EsTif`直接予約導線は検索セッション状態を前提とし、アプリ独自
  カートの通常書誌コンテキストからは予約確認へ遷移できないことが分かった。通常書誌詳細を開き、
  `imasuguyoyk`と同様に詳細LBFormのsuccessful controlsをDOM順で
  `WOpacTifDirectYoyDispAction.do`へPOSTしてから、`tiles.WYoyConfirm`の確認フォームを確定する
- 診断画面分類は、ページ内の共通JavaScript文字列に依存しない。予約確認は同一`form`に
  `gamenid=tiles.WYoyConfirm`、`tilcod`、`select[name=receivename]`が揃う場合だけと判定する。
  詳細検索フォームは`tiles.WEsSchCmpd`または`condition1Text`で`search-form`と判定する
- POST後の通信断など成否不明時は、POSTを再送してはならない。メンバーごとに予約一覧を
  1回取得して`tilcod`を照合し、`Success` / `AlreadyReserved` / `Unknown`を確定する
- 明確に確定POST前のセッション切れだけは、新しいセッションで1回だけ再試行できる

### 6.4 未検証事項

利用制限、予約不可資料、無効受取館コード時のサイト応答、メンテナンス中の
予約画面・確定POSTの挙動は未検証である。これらを既知のalert文言や成功条件として
実装に埋め込まない。

#### 確定POST応答の実測: 成功・上限超過とも同じ確認画面、違いはダイアログ文言だけ（2026-07-27、確認済み）

**実施したのはライブ診断（本番サイトへの実POST）である。** 対象アカウントで確定POSTを複数回実行し、
成功時・拒否時それぞれの応答本文を実測した。結論は次のとおり:

- 確定POST(`WOpacTifDirectYoyExecAction.do`)の応答本文は、**成功時も失敗時も同じ「予約確認」画面**
  （`gamenid=tiles.WYoyConfirm` を含む、診断の画面分類も `reservation-confirmation`）である
- 両者の**唯一の違いは、ページ内スクリプトの `alert(...)` 等のダイアログ文言**である
- 成功時の文言: `予約登録しました。確認したい場合は予約状況一覧で確認して下さい。`
  （このとき予約一覧への反映も確認済み）
- 予約上限超過時の文言: `図書・雑誌は予約制限を1冊越えています。`
  （このとき予約は作られない。末尾に改行文字が付く場合がある）

以前この節に記していた「確定POST後の応答が確認画面である＝受け付けられなかった」という含意は
**誤りだった。** 成功時にも全く同じ画面構造が返るため、確認画面の構造一致だけでは成否を判断できない。

上限超過の文言は可変である。**「1冊」の数値は超過数によって変わり、「図書・雑誌」の部分は資料区分
（コミック・大型絵本・課題図書など）によって別の語になる可能性が高い。** したがって数値や区分名で
完全一致させてはならず、`予約制限を` と `越えています` の両方を含むかどうかで判定する
(`DirectReservationResponseParser.Result.LimitExceeded`)。同様に成功文言も
`予約登録しました` の包含で判定する(`Result.Registered`)。

ダイアログ文言の抽出には、既存の `LicsXpSession.extractSiteMessages`（`alert`/`lbAlert`/
`lbConfirm`/`lbWarning`/`confirm` の第1引数リテラルおよび `div#messages` のテキストを対象とする）
をそのまま再利用している。これは通常経路の診断ログ収集と同じ抽出規則であり、今回の実測でも
問題なく両文言を抜き出せている。

**アプリの解決方針**: `Result.LimitExceeded` を受けた場合は、サイトが理由を明示しているため
**予約一覧との照合を行わずに** `FailureReason.RESERVATION_LIMIT_EXCEEDED` として拒否確定する
（同一メンバーの次の項目は、資料区分ごとに上限が異なるため中止せず続行する）。
一方 `Result.Registered`（成功文言）を受けた場合でも、**成否判定の最終根拠は従来どおり予約一覧
照合のままにする**（サイトの文言だけを信頼する設計へは変えない。理由は下記参照）。
文言がどちらでもない確認画面の再表示（`Result.StayedOnConfirmation`）は、従来どおり予約一覧照合で
解決する。

**成功文言でも一覧照合を最終根拠に残す理由**: 今回実測できたのは検証した2つの文言だけであり、
サイトが将来他の文言（別の拒否理由など）を返す可能性を排除できない。「成功文言が来たら常に成功と
みなす」という実装は、未知の文言に対して安全側に倒れない。一覧照合という既存の確実な確認手段が
利用できる以上、それを外す理由が無い。

予約状況一覧が20件でもページングされないことを実測済み（§9末尾参照）であるため、この照合に
偽陰性は起きない。

**自動予約の設計への影響**: 所有者からの情報（ブラウザでの観察）によれば、上限超過時は空きがあっても
1冊も予約されない（部分的な受け付けは無い）。アプリの直接予約は1冊ずつ確定POSTを送るため各冊が
独立して成否を持つが、この違いは把握しておくこと。

#### 予約上限（2026-07-26。参考情報）

リポジトリ所有者が図書館サイトの記載から書き写したもので、**アプリからの実測ではない**。
サイト改定で変わり得る。

- 全体で **20件**（所蔵していない資料のリクエストを含む）
- うち **コミック（マンガ）は4件まで**
- **大型絵本は1件**
- **課題図書は夏季のみ2件まで**

**アプリの方針（所有者の判断、2026-07-26）**: 予約数やカテゴリを事前に数えて送信可否を判断することは
**しない**。予約を試みて失敗したら予約しない、それだけで足りる。したがってこの上限値も、資料の
カテゴリ判定も、実装に埋め込まない。上限値をここに残すのは、失敗の原因を人が理解するための
参考情報としてである。

### 6.5 確認画面の実HTML未取得と対応(2026-07-25追記、事実)

- 予約確認画面(`tiles.WYoyConfirm`)の実HTMLは一度も取得できていない。
  `app/src/test/resources/fixtures/reservation_confirm.html`は合成fixtureであり、
  実サイトのDOM構造を反映したものではない。確認画面にhidden以外のコントロール
  (text/radio/checkbox/textarea/他のselect)が存在するかどうかは未確定である。
- ブラウザは`document.LBForm.submit()`でDOM順のsuccessful controlsを全て送るため、
  確定POSTの実装も同じ規則(hidden限定のホワイトリストではなく、確認フォームの
  successful controlsを全てDOM順で送る)に修正した。
- 書誌詳細画面のフォーム構成・ログインフォームの項目は2026-07-25に実サイトの生HTML
  (未ログイン状態)で照合済みで、現行実装と一致することを確認した(確認済み)。
  予約ボタンは`type=button`であり`submit()`では送信されないため、「送信にボタン要素が
  無いことが原因」という仮説は**書誌詳細画面については反証済み**である。ただし確認画面の
  ボタン構成は未検証。
- ライブ診断(`LiveReservationDiagnostic`)は、予約前の重複チェックを使い捨ての別セッションで
  行うよう変更した。予約実行本体は、ログイン直後に確定POSTまで連続実行する新しいセッションで
  行う。6.3で実測済みなのは「ログイン→確認→確定を同一セッションで連続実行する必要がある」
  ことまでであり、「予約前一覧の取得が確定を妨げる」ことは**未検証の推定**である。診断が
  ブラウザで成功した列と同じ列になるようにするための設計判断として分離した。

### 6.6 確認フォームの実コントロール判明と`contactweb`誤りの訂正(2026-07-25ライブdry-run診断、確認済み)

- 2026-07-25のライブdry-run診断で、予約確認画面(`tiles.WYoyConfirm`)フォームの実コントロールを
  fingerprint取得により確認した。全コントロールは次のとおり(名前とDOM順は実測、**各値は未取得**):
  - hidden 12個とselect 2個の計14個。確定POSTで送るDOM順は
    `gamenFlag` → `hash` → `returnid` → `gamenid` → `tilcod` → `loginshuflag` →
    `contactdirectweb` → `receivenameFocus` → `watsptcodFocus` → `contactFocus` →
    `returnValue` → `bmtime_hide` → select `receivename` → select `contact`
  - text/textarea/radio/checkbox/buttonは存在しない
  - `receivename`の選択肢は12館すべてが揃っており、指定館(`106`)が候補に含まれることを確認した
  - 上記DOM順は2026-07-25の再診断で確定し、fixture `reservation_confirm.html` /
    `reservation_confirm_js_action.html` の並び順も実測に一致させた(値のみ合成)
- **`contactweb`というフィールドは実在しない。正しくは`contactdirectweb`である。** 6.2表の
  「hidden `contactweb=4`」という記述は誤りだった。この誤った前提により、旧実装は確認フォームを
  一意に特定できず`ParseException`で停止し、確定POSTは一度も送信されていなかった。
- `contactdirectweb`の値は未取得である。ブラウザは利用者が「予約確認メールを送信する/しない」
  ボタンを押さない限りこの値を変更しないため、確定POSTでは`contactdirectweb`をサイト発行値の
  まま送り、値の推測・上書きは行わない。確定POSTで上書きするのは`receivename`と`contact`の
  2項目のみとした。
- 「hidden以外のコントロールを送っていないことが原因」という仮説は、実フォームにhidden以外の
  入力欄が存在しなかったため**反証された**。ただし、6.5で導入したDOM順successful controls全送信
  の実装自体はブラウザ等価性として維持する。
- 未検証事項: `receivenameFocus` / `watsptcodFocus` / `contactFocus` / `bmtime_hide` /
  `returnValue` / `gamenFlag` / `loginshuflag`の各値が何を意味するか、また受取館selectを
  変更した際にブラウザがこれらの値を書き換えるかどうかは不明である。`contactdirectweb`修正後も
  確定POSTが失敗する場合、次の調査対象はこれらの値の挙動である。

### 6.7 ブラウザでの確定POST実測と`contactdirectweb`再表示POST仮説の反証(2026-07-26ブラウザ実測、確認済み)

- 2026-07-26、ブラウザで実際に確定POSTを送って本文を実測した。DOM順の項目は次のとおり
  (`hash`の実値は秘匿ポリシーにより記載しない):
  `gamenFlag`(空) → `hash`(**非空**) → `returnid`(`tiles.WTifTilDetail2`) → `gamenid`
  (`tiles.WYoyConfirm`) → `tilcod` → `loginshuflag`(空) → `contactdirectweb`(**空**) →
  `receivenameFocus`(`0`) → `watsptcodFocus`(`0`) → `contactFocus`(`0`) → `returnValue`(空) →
  `bmtime_hide`(空) → `receivename` → `contact`。
- **`contactdirectweb`はブラウザでも空のまま確定している。** 6.6での「予約確認メール選択の
  再表示POST(`WOpacTifDirectYoyDispAction.do?webrak=1`)が確定前に必要」という仮説は
  **反証された**。実装(コミット`0ce645a`)にあったこの再表示POST分岐は誤りであり撤去した。
- **アプリはURL直接GETで書誌詳細を開くため`hash`が空になる**ことをdry-run診断で実測済み
  (書誌詳細LBForm・確認画面フォームの両方)。一方ブラウザは非空の`hash`を送っている。
  ログイン直後メニュー画面(`WOpacMnuTopInitAction.do`)には有効な`hash`があり、アプリはこれを
  `LicsXpSession.updateTokens(menu)`で保持している。利用者ページのPOST(`fetchReservations`)は
  実際にこの値で成功しているため、書誌詳細・確認画面の両POSTでも、ページ自身の`hash`が空の
  ときだけこのセッショントークンの`hash`を元DOM位置へ補うよう実装した。ページが非空の`hash`を
  発行している場合は上書きしない。
- `returnid`の値の差(ブラウザ`tiles.WTifTilDetail2` / アプリ従来`tiles.WTifTilDetail`)は
  各画面の到達経路の違いによるものであり、実装側で値を作ってはならない。**今回は変更しておらず、
  未検証事項として残す。**
- ブラウザが送るリクエストヘッダのうちアプリが送っていないもの: `Accept`, `Accept-Language`,
  `Accept-Encoding`, `Cache-Control`, `Pragma`, `Upgrade-Insecure-Requests`,
  `Sec-Fetch-Dest`/`Mode`/`Site`/`User`, `sec-ch-ua*`, `DNT`。Referer と Origin はアプリも
  ブラウザと一致した値を送っている。これらのヘッダ差の予約成否への影響は**未検証事項**である。

### 6.8 予約導線の書誌詳細入口アクションを実測で特定(2026-07-26匿名アクセス・ブラウザDevTools実測、確認済み)

- 6.7で「未検証事項」として残した`returnid`差(ブラウザ`tiles.WTifTilDetail2` / アプリ従来
  `tiles.WTifTilDetail`)の原因を特定した。**書誌詳細への入口アクションの違い**である。
  同一`tilcod`に対して匿名アクセスで実測した結果:

  | 入口アクション | 描画される`gamenid` | LBFormの`hash` |
  |---|---|---|
  | `WOpacTifTilListToTifTilDetailAction.do?urlNotFlag=1&tilcod=…`(アプリが従来使用) | `tiles.WTifTilDetail` | **空** |
  | `WOpacMsgNewListToTifTilDetailAction.do?urlNotFlag=1&tilcod=…` | **`tiles.WTifTilDetail2`** | **非空** |
  | `WOpacEsTilListToTifTilDetailAction.do?urlNotFlag=1&tilcod=…` | `tiles.WTifSchCmpd`(詳細ではない) | — |

- `WOpacMsgNewListToTifTilDetailAction.do`は複数の`tilcod`(1000000961766 / 1000002035136 /
  1000001898886 / 1000000060578)で`tiles.WTifTilDetail2`と非空`hash`を返すことを確認した。
  事前に一覧画面を開いていなくても200で詳細が返る。この画面の`imasuguyoyk`(即予約リンク)は
  従来どおり`WOpacTifDirectYoyDispAction.do?tilcod=`へ送信し、`kensakuFlg`/`kensaku`は空。
  LBFormの項目構成は従来の詳細画面と同一(29項目、`id=LBForm`)。
- **結論**: 予約導線の書誌詳細取得は`WOpacTifTilListToTifTilDetailAction.do`ではなく
  `WOpacMsgNewListToTifTilDetailAction.do`を使う。`WOpacTifTilListToTifTilDetailAction.do`は
  `hash`が空で描画され、確定POSTが詳細検索画面へ差し戻される（ブラウザの確定POST本文との
  差異の実体はこれだった）。読み取り専用の書誌詳細取得(`LicsXpClient`)は対象外で、
  予約導線(`ReservationGateway`)だけを切り替えた。
- **hash補完の撤去**: 6.7で導入した「ページの`hash`が空ならログイン後メニューの
  セッショントークンの`hash`で補う」処理は、入口アクションを直せばページ自身が非空の
  `hash`を発行するため不要になった。ブラウザとの完全一致を優先し、`hash`はサイト発行値の
  素通しへ戻した(`BookDetailReservationForm.buildForm()` / `DirectReservationConfirmationPage.buildForm()`
  から`hashOverride`引数と`ReservationGateway.requireSessionHash()`を削除)。
- `BookDetailReservationFormParser`が要求する`gamenid`も`tiles.WTifTilDetail2`へ変更した
  (両方を許すのではなく、ブラウザで予約成立が確認されている画面だけを受け入れるfail-closed)。

### 6.9 予約導線のログイン手順を実測で特定(2026-07-26ブラウザDevTools実測、予約成立時の遷移列)

- ブラウザで予約が成立した際の`.do`リクエスト列を実測した:

  ```
  WOpacInitLoginActiontemp.do
  OpacLoginAction.do
  WPwdLoginCheckAction.do
  WOpacMnuTopInitAction.do?WebLinkFlag=1&moveToGamenId=msgnewmenu
  WOpacMsgNewMenuToMsgNewListAction.do
  WOpacMsgNewListToTifTilDetailAction.do?urlNotFlag=1
  WOpacTifDirectYoyDispAction.do?tilcod=...
  ```

  (`j_security_check`は拡張子が`.do`でないため上記一覧には現れないが、ログインフォームの
  送信先として存在する。)
- アプリ(`LicsXpReservationGateway.openAuthenticatedSession`)の従来の経路は次のとおりで、
  `WPwdLoginCheckAction.do`に到達していなかった:

  ```
  WOpacEsSchCmpdDispAction.do
  OpacInitLoginAction.do?subSystemFlag=0
  j_security_check?subSystemFlag=0   → 302 → OpacLoginAction.do → 302 → /（ポータルのトップ）
  WOpacMnuTopInitAction.do?WebLinkFlag=1
  ```

  認証後の戻り先はログインフォームをどの画面から出したかで決まるため、入口の違い
  (`OpacInitLoginAction.do` vs `WOpacInitLoginActiontemp.do`)がそのまま戻り先の差として現れる。
- 匿名アクセスで確認済み: `WOpacInitLoginActiontemp.do`はログインフォームを返し、送信先は
  `j_security_check?subSystemFlag=0`で既存と同一。`WPwdLoginCheckAction.do`は未認証だと
  200・空ボディを返す。
- **対応**: 予約導線(`ReservationGateway`)だけ、ログインフォーム取得を
  `OpacInitLoginAction.do?subSystemFlag=0`から`WOpacInitLoginActiontemp.do`へ変更し、
  ログインPOST直後に`WPwdLoginCheckAction.do`を1回GETするようにした(内容は解析せず、
  メンテナンス判定のみ)。読み取り用(`LicsXpClient.fetchUserData`)の`OpacInitLoginAction.do`は
  意図的に変更していない。
- **未検証事項**:
  - この修正で予約が成立するかは未検証である。
  - ブラウザは書誌詳細の前に新着ジャンル一覧(`WOpacMsgNewMenuToMsgNewListAction.do`)を
    経ているが、アプリは書誌詳細へ直接入っている。この差の予約成否への影響は未検証である。

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
- 書き込み操作の全面禁止は2026-07-26に撤回された。手動予約・予約取消は実装済みであり、
  新着キーワード自動予約は2026-07-29に`docs/spec.md`§3.11として要件承認済みである。
  いずれも専用ゲートウェイ、一回限りの副作用送信、POST後不明の再送禁止を守る。
  延長、登録変更、公式サイトカート操作は個別の設計・承認なしに実装しない

## 9. 予約状況一覧の画面内アクション（2026-07-27。スクリプト観測のみ、未実行）

診断ログの画面スクリプト抽出で、予約状況一覧（`gamen=usrrsv`）のページに次のアクションが
定義されていることが分かった。**アプリからはいずれも呼んでいない。ページ内のJavaScriptを
読み取っただけであり、実際の要求・応答・必要パラメータは未検証である。**

| 用途 | アクション | JSが設定するフィールド |
|---|---|---|
| 予約取消 | `WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1` | `yoycod` |
| 順番解除 | `WOpacUsrRsvJunbanKaijoAction.do?mngFlg2_handan=1&kbnchgflag=1` | `grpcod` |
| 予約の非表示 | `WOpacUsrRsvHiddenAction.do?mngFlg2_handan=1` | `yoycod` |
| 予約期限の延長 | `WOpacUsrRsvExtendAction.do?mngFlg2_handan=1` | `yoycod` |
| 順番待ちへ | `WOpacUsrToJunbanAction.do` | `mngFlg2` |
| 書誌詳細へ | `WOpacUsrRsvListToTifTilDetailAction.do` | `hTilcod` |
| CSV出力 | `WOpacUsrRsvListCsvAction.do` | （未確認） |
| 確認済みにする | `WOpacUsrRsvkakuninsumiAction.do` | （未確認） |
| 受取場所の変更 | `WOpacUsrRsvPostAddrChangeAction.do` | （未確認） |

構想2番の「予約の削除機能」を実装する際の出発点になる。予約取消は `yoycod`（予約コード）を
指定する形であり、**アプリは現在この値を保持していない**（`Reservation` は `tilcod` は持つが
`yoycod` は持たない）。実装時は予約一覧のパーサで `yoycod` を取り出す必要がある。

`WOpacUsrRsvListCsvAction.do` によるCSV出力は、HTML解析より安定した予約一覧の取得手段に
なり得る。予約成立の照合にも使える可能性があるが、内容・形式・必要パラメータはいずれも未確認。

### 予約状況一覧のページング（実測済み、2026-07-27）

同ページのフォームには `pageID`、`postSeq`、`rsvSortKey`、`scrollToTilcod` といったhiddenがあり、
ページ送りの仕組みがある可能性を示唆していたが、**予約上限の20件（サイト側の上限）まで登録した
状態で実機確認した結果、20件でもページングされず1ページに全件表示されることを確認した**。
アプリの「予約中」画面の件数表示と、サイト上の実際の予約件数が一致することでも確認済み。

予約上限が20件である以上、予約一覧は常に1ページで完結する。したがって**`ReservationListParser`
による1ページ分の読み取りだけで、予約成立の照合に偽陰性は起きない**。この実測により、
確定POST後に予約一覧へ対象`tilcod`が無ければ「サイトに受け付けられなかった」と断定してよい
根拠が得られた（§6.4参照）。

### 予約取消フォームの実HTML構造（fixture `usrrsv.html` で確認済み。実サイトへの通信は未実施）

上記の「スクリプト観測のみ」段階から進み、実HTML fixture (`usrrsv.html`) を精査してフォーム構造を
確定した。**ただし取消の確定POST自体は一度も実サイトへ送っていない。成功・失敗時にサイトが
返す文言は未実測である。**

- 予約状況一覧の各行には、行ごとに **`name="yoykcode"` という同名のhidden**が並ぶ
  (`id="yoycod<コード>"`、値は常に空文字列)。fixture上は19行あれば19個の`yoykcode`が存在する。
  これは制御用の値ではなく、実際に送信で使われる制御フィールドは別に1個だけ存在する
  **`name="yoycod"`**(hidden、こちらは複数存在しない)である。
- 取消ボタン(`<input type="button" class="button remove" onclick="javascript:yoykCancel('コード')">`)
  は「予約中」の行にだけ存在し、「提供可能」（取置済み）の行には存在しない。ボタン押下時のJS
  (`yoykCancel`)は、この`yoycod`へ対象コードを代入してから同じ`LBForm`をそのまま送信するだけで、
  **クライアント側の確認ダイアログは無い**。
- したがって、`LBForm`を一意に特定する条件は「hidden `gamenid`が`tiles.WUsrRsvList`であること」
  かつ「hidden `yoycod`がちょうど1個だけ存在すること」で足りる(行ごとに複数存在する`yoykcode`とは
  区別できる)。この条件はfixture `usrrsv.html`で実際に成立することを確認済み。
- 取消の確定POST先は前掲のとおり`WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1`であり、
  `yoycod`以外のフィールドはブラウザのsuccessful controlsをDOM順・同名重複込みでそのまま送る
  (`ReservationCancelFormParser`参照)。
- **実装仕様（2026-07-27時点。12回目のライブ実測(2026-07-28)により判定は§12回目のとおり改訂済み）**:
  取消依頼は`memberId`・`tilcod`・`cancelCode`で対象を固定する。送信直前に取得した一覧で`cancelCode`
  一致行を一意に特定し、非空かつ依頼時の`tilcod`と一致するときだけ1段階目POSTを送る。
  この時点では「取消後に固定した`tilcod`行が一覧から消えた場合だけ`Cancelled`とする」判定だったが、
  これは誤りだった。実サイトは取消後も対象行を一覧から消さず、「取消」状態のまま残すことが
  12回目のライブ取消＋一覧観測(2026-07-28、§12回目参照)で判明したため、状態文字列
  （`state=CANCELLED`）と非表示ボタン(`yoykHihyoji`)の両方一致で`Cancelled`、一覧から消えていれば
  `CancelledAndHidden`と判定するよう改めた。状態変更POSTはクライアントが自動再試行しない1回限りの
  試行であり、通信断時にネットワーク上のexactly-onceを保証するものではない（この方針は不変）。
  詳細は`docs/backend-design.md`の予約取消の節を参照。

### 予約取消は2段階である（実測、2026-07-27。2段階目の実装は同日中に完了）

実サイトへ実際に`WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1`をPOSTして次が判明した。
**この記述より前の実装(既知拒否語による判定)は誤りであり、修正済み。**

- **1段階目の応答は予約状況一覧の画面であり、ダイアログ文言 `予約の取消を行います。よろしいですか？`
  （`lbConfirm`呼び出し）を含む。この時点では取り消されていない**ことを、取消後の一覧に
  対象がそのまま残っていることで実測確認した。すなわちサイトの取消操作は
  「確認ダイアログ表示→OK→実際の取消送信」の2段階であり、1回目のPOSTは1段階目（確認表示）
  にしか到達していない。
- **履歴（旧実装、廃止済み）— 拒否判定の欠陥**: 旧実装は「できません」「越えています」という一般語で拒否を判定して
  いたが、これは**全ページ共通で埋め込まれているJS定数**
  （例: `仮パスワードでは利用できません。パスワード変更を行なってください。`）にも一致してしまい、
  取消と無関係の応答でも誤ってRejected扱いになる欠陥があった。この判定は削除した。さらに、
  `取消を行います`という文言だけで`ConfirmationRequired`を返す判定も旧仕様であり、現行実装では
  `ConfirmationRequired`は互換型として残すだけで生成しない。1段階目から2段階目へ進むかどうかは、
  対象行・取消フォーム・採取済みの確認プロトコル署名を合わせた複合ガードで判定する。

#### 2段階目の仕組みと実装（実測、2026-07-27）

ユーザーがブラウザのDevToolsで、確認ダイアログのOK後に実際に送信された通信（POST先のURL・本文、
および画面スクリプトのソース）を直接採取し、次のとおり判明した。
（なお、確認ダイアログ自体を検出するための診断ログの強化（本体抽出条件へ`confirm(`/`lbConfirm(`を
追加、トップレベルの`confirm`呼び出し文の抽出を追加）は、これより前に別目的で行った作業であり、
今回のOK後の挙動の解明はこの診断ログ強化によるものではない。）

- 確認ダイアログ（`lbConfirm(...)`）のOKコールバックは、`document.prevRequestForm`
  （直前に実際へ送ったリクエストのフォーム、すなわち1段階目に送ったのと同じ`LBForm`）へ
  `okCodes`という名前のhiddenを追加したうえで、そのフォームをそのまま再送信するだけである。
  別アクションへの新規POSTではなく、**1段階目と同一のフォーム内容の再送＋1フィールド追加**という
  単純な仕組みだった。
- 2段階目のPOST先は実測のとおり`WOpacUsrRsvCancelAction.do`（**クエリ無し**。1段階目は
  `?mngFlg2_handan=1&kbnchgflag=1`というクエリ付きだった）。なぜクエリが無くなるのか
  （`document.prevRequestForm`のaction属性の実値など、submit処理の詳細）は未確認であり、
  ここでは実測できた結果（クエリ無しでPOSTされたという事実）のみを記載する。
  > **注記（2026-07-28、11回目診断後に追記）**: この「クエリ無し」という記録は、後日
  > 11回目のアプリ側ライブ診断（§11回目参照）による直接実測と矛盾する。11回目では
  > `prevRequestForm`にaction属性自体が存在せず、ページのJSは`document.prevRequestForm.submit()`を
  > そのまま呼ぶだけであり、かつ1段階目応答はリダイレクトしていない
  > （`status=200 redirect=-`）ことを確認した。HTML標準どおりなら送信先は現在のドキュメントURL、
  > すなわち1段階目に実際に送ったクエリ付きURLになるはずであり、上記の「クエリ無し」という
  > ブラウザDevTools採取の記録とは食い違う。DevToolsのNetworkパネルのName列がパスしか
  > 表示しないための読み違いだった可能性が高いが、**これは推定であって確定ではない**。
  > 実装は11回目の直接実測（action属性が無い・`submit()`でそのまま送信・リダイレクト無し）を
  > 根拠として、2段階目を1段階目と同じクエリ付きURLへ送るよう変更した。
- 2段階目の本文は次の順で構成する: 1段階目のクエリパラメータ（`mngFlg2_handan=1`、
  `kbnchgflag=1`）を先頭に置き、続けて1段階目に実際に送った本文と同じ内容を同じ順序・
  同名重複のまま並べ、最後に`okCodes=OPACUSR001`を付ける。
- `okCodes`の値`OPACUSR001`、および`mngFlg2_handan`/`kbnchgflag`を本文へ含めることは
  **予約取消の確認ダイアログに固有の実測値**であり、他の操作（メール選択の再表示など）では
  異なるメッセージコードになる可能性が高い。他の確認ダイアログへ流用してはならない。
- 2段階目のReferer/Originは1段階目の応答ページ（`WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1`）
  由来とする。状態変更POSTは各段階とも自動リトライしない。**現行実装**は、1段階目の応答を再解析して
  同じ`cancelCode`・`tilcod`の対象行があり、採取済みのインラインlegacy script構造
  （外側の`if (document.all || IS_EXPLORER_11 || isEdge)`→`lbConfirm`/`lbConfirm1`/`window.confirm`→
  `OPACUSR001`→`for`ループ内の`newHidden.type/name/value`→
  `document.prevRequestForm.appendChild(newHidden)`）にも一致するときだけ
  2段階目を送る。実サイトではこの構造が巨大な同一`script`タグ内にあり、構造の前後には無関係な
  function・class・arrow関数・正規表現が存在する。そこでscript全体を許容・解釈するのではなく、
  コメント・文字列等を飛ばす字句走査で、丸括弧・角括弧の外かつ同一blockの文頭にある外側ifを深度を問わず探し、連続する3文
  （外側if/else、if(rest)/else、for(okArray)）だけを括弧対応で切り出してから固定署名と照合する。2段階目の本文は
  送信前に一意に解析済みの`cancelForm`を再利用するため、stage1応答のformは再解析しない。
  実測に不要なtemplate literal（backtick）が同一script内にあれば候補全体を拒否する。`/`は字句トークン
  文脈で正規表現開始を判定し、曖昧な場合は正規表現として扱って偽陽性より偽陰性を選ぶ。
  文言だけでは判定せず、2段階目送信後に**新しく取得した完全な予約一覧**で、
  送信前に固定した`tilcod`行が消えたことを確認する二段階検証により
  `Cancelled`/`IndeterminateAfterPost`を判定する。ユニットテスト（`ReservationGatewayTest`、
  MockWebServerのみ）で本文の順序・Referer/Origin・POST回数（取消POSTは1段階目・2段階目の合計2回まで）を
  検証済み。
- **未確認事項**: 2段階目のPOSTは実装したが、**実サイト上でこのPOSTにより実際に予約が
  取り消されることは、本実装時点(2026-07-27)ではまだ確認できていない**。ここまでの判明事項は
  ブラウザ実測とMockWebServerによるユニットテストに基づくものであり、実サイトへの2段階目POST
  自体の実行結果はまだ確認していない。

#### 初回アプリライブ取消診断（2026-07-27）

- **確認済み**: 専用CLI診断の初回実行では1段階目POSTだけがHTTP 200で完了し、
  `ReservationCancelAttempt.IndeterminateAfterPost`となった。取消後一覧で対象`tilcod`は残存しており、
  2段階目POSTは送信していない。従って、この試行では実サイトの予約は取り消されていない。
- **確認済み**: 停止理由は、当時のプロトコル署名が内側の`if (0 != 1)`から始まる形だけを許可していたためである。
  実サイトの確認scriptはその外側に`if (document.all || IS_EXPLORER_11 || isEdge)`を持ち、
  `lbConfirm`だけでなく`lbConfirm1`分岐も含む。この不一致はフェイルクローズとして働き、意図しない
  2段階目POSTを防いだ。
- **対応済み（要再ライブ確認）**: 署名を上記の外側構造、`OPACUSR001`の設定、`for`ループ内の
  `newHidden.type/name/value`、`document.prevRequestForm.appendChild(newHidden)`まで含む実測形に厳密化した。
  初回ライブ診断の`screen-script`ログから採取したtop-level confirm抽出断片（実サイトHTML全文ではない）を
  認証情報・個人情報を含まないfixtureとして主正例にし、内側断片だけおよびtailが`if (false)`内にある場合は
  2段階目へ進まない回帰試験を追加した。修正後もアプリからの取消成立は未検証であり、所有者承認の対象1件で
  再実行して初めて確認する。

#### 2回目アプリライブ取消診断（2026-07-28）

- **確認済み**: 初回対応後の再実行も、1段階目POSTだけがHTTP 200で完了し、
  `IndeterminateAfterPost`、取消後一覧の対象`tilcod`残存、2段階目POST未送信となった。今回も実サイトの
  予約は取り消されていない。
- **確認済み**: 原因は、1段階目応答の確認構造が巨大な同一`script`タグ内にあり、従来の
  `sanitizeLegacyCancelScript`が候補外のfunction/class/arrow関数/正規表現まで拒否してscript全体を
  不適格にしていたためである。この停止もフェイルクローズであり、意図しない2段階目POSTは発生しなかった。
- **対応済み（要再ライブ確認）**: script全体の検証を緩和せず、トップレベルの実測済み外側ifから連続する
  3文だけを括弧対応で切り出し、その候補内部だけに従来のサニタイズと固定署名を適用するよう変更した。
  無関係なfunction・正規表現・arrow関数を前後に同居させた正例と、候補内部の禁止構文・非トップレベル・
  `if (false)`内tail・template literal、`if`/`else`/arrow直後の正規表現内署名を拒否する負例を追加した。
  アプリによる取消成立は引き続き未検証である。

#### 3回目アプリライブ取消診断（2026-07-28）

- **確認済み**: 3回目も1段階目POSTだけがHTTP 200で完了し、`IndeterminateAfterPost`、取消後一覧の
  対象`tilcod`残存、2段階目POST未送信となった。推測による署名緩和や本番での再送は行わず、予約は不成立のまま
  安全停止している。
- **対応済み（診断追加、再ライブ未実施）**: 判定結果を変えず、1段階目応答ごとに
  `cancel-reservation-signature`診断へ`matched`、inline script数、完全一致確認文言数、外側if数、
  字句走査の完了/拒否理由別件数、candidate数、sanitize成功数、固定署名一致数だけを集計記録する。
  HTML・script本文・資料コード・取消コード・hash・認証情報は出力しない。次の所有者承認済みライブ診断では、
  まずこの集計を採取して停止原因を確定する。アプリによる取消成立は未検証である。

#### 4回目アプリライブ取消診断（2026-07-28）

- **確認済み**: 4回目も1段階目POSTだけがHTTP 200で完了し、`IndeterminateAfterPost`、取消後一覧の
  対象`tilcod`残存、2段階目POST未送信となった。新しい集計は`matched=false`、inline script 11件、
  完全一致確認文言1件、外側if 1件、字句走査completed 11件、candidate 0件を示した。従って停止点は
  文字列/template/sanitize/固定署名ではなく、構造抽出層まで絞り込まれた。
- **対応済み（診断追加、再ライブ未実施）**: 判定・送信可否を変えず、外側ifごとにトップレベルかネストか、
  statement-start条件、top-level `return`遮断、後続3文の切出し失敗段階
  （外側if構造、rest-if開始/rest-if構造、for開始/for構造）を固定enum件数で集計する。次の所有者承認済み
  ライブ診断では、この構造集計を採取して候補0の理由を確定する。アプリによる取消成立は未検証である。

#### 5回目アプリライブ取消診断と候補境界の変更（2026-07-28）

- **確認済み**: 第5回の構造集計は`outerIfDepth=top:0,nested:1`、他の抽出段階は0件だった。候補0の原因は
  実測済み外側ifが外側block内にあり、従前のトップレベル制約が除外していたことと確定した。
- **対応済み（再ライブ未実施）**: 外側block深度と一般到達可能性を第2段階送信の安全条件から外した。字句走査は
  コメント・通常文字列・template literal・正規表現を除外し、丸括弧・角括弧の外で同一blockの文頭
  （先頭または`{`/`;`/`}`後）にある候補を深度を問わず抽出する。候補内部の外側if→`if(rest)`→OK hiddenの
  `for`ループ固定署名と、対象の一意な`cancelCode`・`tilcod`の複合ガードは維持する。
  `if (false)`や未呼出し関数等の到達可能性は静的に証明しない。コメント・文字列・template・正規表現内の
  偽署名、候補内部の禁止構文、文言改変、対象/form不一致は引き続き送信前に拒否する。アプリによる取消成立は未検証である。

#### 6回目アプリライブ取消診断と複合ガードの切分け（2026-07-28）

- **確認済み**: 第6回も1段階目POSTだけが完了し、対象`tilcod`は残存、2段階目POSTは未送信だった。
  `cancel-reservation-signature`は`matched=true`、candidate 1件、sanitize成功1件、固定署名一致1件であり、
  停止点は署名判定以外の複合ガードにあると切り分けた。

#### 7回目アプリライブ取消診断とstage1 form再解析の除去（2026-07-28）

- **確認済み**: 第7回の`cancel-reservation-stage`は`targetStillPresent=true`、`signatureMatched=true`だった。
  停止原因はstage1応答に元一覧formと`prevRequestForm`が併存し、冗長な取消form再解析が一意性判定で失敗したことだった。
- **対応済み（再ライブ未実施）**: 2段階目本文には送信前に一意に解析済みの`cancelForm`を再利用しており、
  stage1のform解析結果は使用していない。このためstage1の再解析を送信条件から完全に除去し、
  `targetStillPresent && signatureMatched`だけを判定する。診断はこの2値と最終`matched`のみを記録する。
  次の所有者承認済みライブ診断では第2段階POSTと取消後照合を確認する。アプリによる取消成立は未検証である。

#### 8回目アプリライブ取消診断とブラウザ再送フォーム化（2026-07-28）

- **確認済み**: 第8回の固定値再構成（hardcode）版は、第1・第2段階の計2回のPOSTがいずれもHTTP 200で完了したが、
  取消後一覧に対象`tilcod`が残り、取消は不成立だった。取消後のサマリは19件、一覧パーサの行数は20件であり、
  この照合は完全一覧とも確認できなかった。既存実装はaction・`okCodes`・本文順を固定値で再構成しており、
  ブラウザが実際に再送する`prevRequestForm`の詳細は未確認だった。
- **対応済み（本番第2段階成功は未検証）**: stage1 HTMLから一意な`form[name=prevRequestForm]`を解析し、actionと
  DOM順・重複を保ったsuccessful controlsを使用する。`OK_CODES_NAME`はコメント・文字列等を候補にしない字句走査で
  単純文字列代入だけを一意に取得し、実際のfield名へ`OPACUSR001`を末尾追加する。既存controlsはstage1送信内容との
  multiset一致、actionは同一origin・取消action pathで検証し、失敗時は第2段階を送らない。正規表現開始文脈も
  署名字句走査と同等に扱い、偽代入と既存control名の衝突を拒否する。アプリによる取消成立は未検証である。

#### 9回目アプリライブ取消診断と現行版の安全停止（2026-07-28）

- **確認済み**: `prevRequestForm`を実DOMから再送する現行版の次回ライブでは、開始時点で対象`tilcod`は予約一覧に
  1件あったが、行の`cancelCode`は空だった。診断の送信前安全弁が停止したため取消POSTは0回であり、
  本番の第2段階POSTは送信していない。
- **未確認**: `cancelCode`が空になった原因は未確認である。予約状態の自然変化、前回POSTの影響などを含め、
  いずれの原因もこの結果だけから断定してはならない。
- **結論**: 現行の`prevRequestForm`版が実サイトで第2段階POSTを成功させ、取消を成立させることは未検証である。
  次のライブ検証には、現在取消可能で非空の`cancelCode`を持つ別の`tilcod`が必要である。

#### 10回目アプリライブ取消診断とprevRequestForm構造診断の追加（2026-07-28）

- **確認済み**: 第10回では`cancel-reservation-signature`/`cancel-reservation-stage`とも`matched=true`まで
  到達したが、`ReservationCancelConfirmationFormParser.parse`が「prevRequestFormのactionがありません」で
  `ParseException`となり、2段階目POSTは送信されなかった（取消POST 1回のみ、安全側で停止）。実サイトの
  `form[name=prevRequestForm]`にはaction属性が無いことがここで初めて判明した。
- **対応済み（診断追加、判定・送信内容は無変更）**: 判定ロジックを変えず、`cancel-reservation-prevform`
  診断を追加した。1段階目応答の`prevRequestForm`の属性一覧（値ではなく属性名）、`action`/`method`/`target`/
  `enctype`/`id`の値（6桁以上の数字・8文字以上の数字混じり英数字トークンはマスク、`hash`等を含む文は
  文全体を伏せる）、`controls`件数、識別子`prevRequestForm`を含むscript文（`stmts=`）、既存の取消確認
  プロトコル署名の候補が切り出せたときその直後に続く文（`tail=`、識別子非依存）、`OK_CODES_NAME`の
  実際のfield名を1行のサマリとして記録するだけで、2段階目の送信可否・送信内容には一切関与しない。
  アプリによる取消成立は引き続き未検証である。

#### 11回目アプリライブ取消診断とprevRequestForm action省略の実装反映（2026-07-28）

- **確認済み**: 10回目で追加した`cancel-reservation-prevform`診断が実測データを返した。
  ```
  cancel-reservation-prevform: forms=1 attrs=method,name action=(empty) method=post
    target=(empty) enctype=(empty) id=(empty) controls=213
    stmts=document.prevRequestForm.appendChild(newHidden);|// フォームをサブミット …|document.prevRequestForm.submit();
  ```
  実サイトの`prevRequestForm`は`name`/`method="post"`の2属性だけを持ち、**action属性は存在しない**。
  ページのJSはactionを設定せず`document.prevRequestForm.submit()`でそのまま送信する。加えて、
  1段階目POSTの応答は`status=200 redirect=-`（リダイレクト無し）である。
- **確認済み**: HTML標準ではform actionの省略時、送信先は現在のドキュメントURLである。1段階目は
  リダイレクトしていないため、stage1のドキュメントURLは実際に1段階目を送ったURL、すなわち
  `WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1`（**クエリ付き**）になる。これは
  上述（§2段階目の仕組みと実装）の「クエリ無し」という所有者ブラウザ採取記録と矛盾する。
  DevTools Network パネルのName列がパスしか表示しないための読み違いだった可能性が高いが、
  **これは推定であって確定ではない**。
- **対応済み（本番第2段階成功は依然未検証）**: `ReservationCancelConfirmationFormParser.parse`は、
  action属性が空でも`ParseException`にせず、`ReservationCancelConfirmationAction`
  （`Explicit(value)` / `SameAsCurrentDocument`）という型で「明示action」と「現在のドキュメントURL」を
  区別するよう変更した。`LicsXpSession.resolveReservationCancelAction`は`SameAsCurrentDocument`の場合、
  1段階目に実際に送ったURL（`LicsXpReservationCancelStagePage.url`、クエリ付き）をハードコードせずに
  そのまま2段階目の送信先として使う。安全条件（同一origin・パスが`WOpacUsrRsvCancelAction.do`）は
  action明示時と同様に必ず検証する。2段階目の本文（DOM順のsuccessful controls＋`OK_CODES_NAME`の
  実field名で`OPACUSR001`）と、1段階目送信内容との多重集合一致検査は無変更である。実サイトでは
  `controls=213`であり、この一致検査は今回の改修で初めて実際に評価されることになる。
  アプリによる実サイトでの取消成立は依然として未検証である。
- **未確認・今回は対応せず**: `stmts=`のサンプル出力に`// フォームをサブミット …`という、コメント文が
  そのまま混入して見える箇所がある。これは字句走査が「文」の範囲をコメントを除去せず生テキストの
  スライスとして切り出しているためで、直前に長いコメントがあると160文字の切り詰めで実コードが
  隠れることがある既知の制約である。今回のタスクの依頼範囲外のため未対応。

#### 12回目のライブ取消＋一覧観測と成否判定の改修（2026-07-28）

- **確認済み・実サイトの取消が成立した**: 11回目の実装（action省略時は1段階目と同じクエリ付きURLへ
  2段階目を送る）を反映した12回目のライブ取消診断で、2段階目応答の画面固有メッセージが
  「予約の取消が完了しました。」だった。診断が拾った8件のメッセージのうち、7番目だけが1段階目の
  確認文言「予約の取消を行います。よろしいですか？」から差し替わっており、他7件は全ページ共通の
  JS定数（会員種別文言等）で1段階目と同一だった。**これにより、これまで「未検証」としてきた
  「アプリによる実サイトでの取消成立」は確認済みになった。**
- **確認済み・取消後も一覧に対象が残る（所有者提供の仕様、実測で裏付け）**: 取消しても対象書誌は
  予約状況一覧から消えない。予約状態列が赤字で「取消」と表示され、取消ボタン(`yoykCancel`)の位置に
  「非表示」ボタン(`yoykHihyoji`)が置かれる。「非表示」ボタンを押すと初めて一覧から消える。
  読み取り専用の一覧観測タスク`liveReservationListInspect`（書き込み副作用ゼロ、取消・非表示・予約の
  POSTを一切送らない）で、この状態が実サイトに出ているうちに構造を直接確認した:
  ```
  list-summary: summaryReservationCount=19  parsedRowCount=20
  list-row[19]: tilcod=...  stateText=取消    state=UNKNOWN(旧)  cancelCodePresent=false  buttons=[yoykHihyoji]
  list-row[12]: tilcod=...  stateText=提供可能  state=READY        cancelCodePresent=false  buttons=[]
  list-row[18]: tilcod=...  stateText=移送中   state=UNKNOWN(旧)  cancelCodePresent=false  buttons=[]
  他17行:       stateText=予約中  state=WAITING      cancelCodePresent=true   buttons=[yoykCancel]
  ```
  （`state=UNKNOWN(旧)`は観測時点＝本改修前の解釈。本改修でCANCELLED/IN_TRANSITへ変更した。）
- **確認済み・状態とボタンの実測一覧**:

  | 予約状態(stateText) | ReservationState | 取消ボタン(yoykCancel) | 非表示ボタン(yoykHihyoji) | cancelCode |
  |---|---|---|---|---|
  | 予約中 | WAITING | あり | なし | 非空 |
  | 提供可能 | READY | なし | なし | 空 |
  | 移送中 | IN_TRANSIT | なし | なし | 空 |
  | 取消 | CANCELLED | なし | あり | 空 |

  実測内訳（全20行）: 予約中15行 + 提供可能3行 + 移送中1行 + 取消1行 = 20行。
  サマリの予約中件数(19)から除外されるのは**取消済み行だけ**であり、提供可能・移送中はサマリに
  数えられている（19 = 20 − 1(取消)）。移送中を完全性判定から除外してはならない。
- **対応済み（本改修）**: `ReservationState`に`CANCELLED`・`IN_TRANSIT`を追加し、
  `ReservationListParser`で「取消」→CANCELLED、「移送中」→IN_TRANSITへマップするようにした。
  `LocalDateConverters.reservationStateToString`が`name`文字列で保存する既存実装のため、
  Roomマイグレーションは不要（DBバージョンは7のまま）。
  `ReservationListParser`に判定専用の内部関数`parseRows`を追加し、`Reservation`に加えて非表示ボタン
  (`yoykHihyoji`)の有無（コード値は保持しない）を返す。公開`parse()`の戻り値・挙動は
  `parseRows`の結果を`map`しているだけで変えていない。
  `ReservationCancelAttempt.Cancelled`を`Cancelled`（取消済みで一覧に残っている）と
  `CancelledAndHidden`（一覧に無い。既に非表示化されたのか、サイトが別の理由で即時に消したのかは
  区別しない）に分けた。所有者の方針（非表示操作を将来アプリへ組み込むかもしれない）による。
  取消後一覧照合(`resolveCancelByListDiff`)の判定表:

  | 取消後の対象tilcod行 | 判定 |
  |---|---|
  | 一覧に無い | `CancelledAndHidden` |
  | `state=CANCELLED` **かつ** `yoykHihyoji`ボタンあり | `Cancelled` |
  | 状態のみ一致／ボタンのみ一致 | `IndeterminateAfterPost` |
  | 予約中・提供可能・移送中等で残存 | `IndeterminateAfterPost` |
  | 一覧の再取得・解析に失敗 | `IndeterminateAfterPost`（従来どおり） |

  状態文字列とボタンの両方を要求するのは、既存の「状態文字列だけに依存しない」方針と「確証がなければ
  成否不明へ倒す」方針の両立である。
  完全性ガード（`fetchReservationSnapshot`の`complete`、および`resolveCancelByListDiff`内の同種の
  判定）は、`summaryReservationCount == reservations.size`から
  `summaryReservationCount == (取消状態でない行数)`へ改めた。取消済み行が1行でもあると常に不完全
  になっていた既存の欠陥（直接予約側の成立照合にも影響していた）もこの修正で解消される。
  `ReservationCancelOutcome`にも`Cancelled`/`CancelledAndHidden`を追加し、
  `ReservationCancelRepositoryImpl`は両方とも成功として扱い、どちらもローカルDBから即時削除する。
- **対応済み（12回目直後に追補）**: 前段落で「未対応」としていた`liveReservationCancelDiagnostic`タスクの
  `LiveReservationCancelDiagnostic.kt`の矛盾を修正した。取消後照合の成否判定は
  `assertFalse(report.stillPresentAfter)`（対象tilcodが一覧に一切無いことを期待する誤った判定）
  から、`report.attempt`が`Cancelled`または`CancelledAndHidden`であることの判定へ改めた。
  一覧からの消失を表す観測値は`targetRowPresentAfter`へ改名し、フィールドとしては残しつつ、
  成否の断定には使わないことを明記した。あわせて`cancel-after`診断ステージで、対象tilcod行が
  一覧にあるかに加え、ある場合は`state`（CANCELLED/WAITING/READY/IN_TRANSIT/UNKNOWN）と
  非表示ボタン(`yoykHihyoji`)の有無を記録するようにした（資料名・cancelCode値は出さない）。
  これにより次回のライブでは「取消成立→取消状態で残存」を直接確認できる。

#### 13回目のライブ観測と同一tilcod重複への対応（2026-07-28）

- **確認済み・実運用で同一tilcodの行が重複する**: 13回目の対象として指定された書誌を読み取り専用で
  観測したところ、同じ`tilcod`の行が2つあった。
  ```
  list-summary: summaryReservationCount=20  parsedRowCount=21
  highlight[0]: tilcod=1000002035525  stateText=予約中  state=WAITING    cancelCodePresent=true   buttons=[yoykCancel]
  highlight[1]: tilcod=1000002035525  stateText=取消    state=CANCELLED  cancelCodePresent=false  buttons=[yoykHihyoji]
  ```
  取消済み行を非表示にせず同じ書誌を予約し直すと、この状態になる。「取り消してから同じ本を予約し直す」
  は普通の運用操作であるため、実運用で必ず起きる。`tilcod`を安定IDとして採用したのは監査レビューの
  指摘に沿った判断だったが、「取消後も行が残る」仕様（§12回目参照）を知らずに設計したため、この重複を
  想定できていなかった。
- **確認済み・重複があると取消が一切できなかった**: 改修前は、`cancelReservation`の送信前対象特定
  （`reservations.count { it.tilcod == target.tilcod } != 1`）と、`resolveCancelByListDiff`の
  `matches.size > 1 -> STILL_PRESENT_OTHERWISE`が、いずれも同一tilcodの行が2つある時点で送信前・
  送信後を問わず常に不一致・成否不明にしていた。行の同一性を追える安定IDがサイト側に無いため、
  「取消可能な行の一意性」と「取消済み行の増分」で照合する方式へ改めた。
- **対応済み（本改修）**: 送信前の対象特定を「`tilcod`が一致し、かつ`cancelCode`が非空（取消可能）な
  行」の中での一意性に変更した（`ReservationGateway.kt`の`cancelReservation`）。取消済み行1つ＋
  予約中行1つは一意特定でき、取消可能な行が2つある本当に曖昧なケースだけ従来どおり送信前に停止する。
  同じ理由で、stage1応答から2段階目送信可否を判定する`inspectCancelConfirmationStage`の
  `targetStillPresent`判定（複合ガードの一部）も同様に「取消可能な行の一意性」へ改めた
  （改めないと、重複がある状態で1段階目までは進んでも2段階目が常にスキップされ、今回の改修の目的を
  果たせなかったため）。
  送信前一覧における「対象tilcod・state=CANCELLEDの行数」を基準値として記録し、`resolveCancelByListDiff`
  へ渡す。判定表:

  | 送信後の対象tilcod行 | 判定 |
  |---|---|
  | 対象tilcodの行が1つも無い | `CancelledAndHidden` |
  | `state=CANCELLED`かつ非表示ボタンありの行数 == 基準値+1、かつ`cancelCode`非空の行が0 | `Cancelled` |
  | 上記以外（増分が0や2以上、取消可能な行が残っている、状態とボタンの片方だけ一致 等） | `IndeterminateAfterPost` |

  「取消済み行が1つ増えた」だけでなく「取消可能な行が無くなった」も同時に要求するのは、確証がなければ
  成否不明へ倒す既存方針のためであり、緩めていない。`CancelledAndHidden`の判定は基準値で場合分けしない
  （対象tilcodの行が全て消えている状態は、並行操作が無い限り「取消＋非表示」以外では起きないため）。
  完全性ガード（`summary == 取消済みでない行数`、§12回目参照）は前置き条件として維持している。
  `LiveReservationCancelDiagnostic.kt`の対象特定も同じ考え方に直し、`cancel-target`ステージのログに
  対象tilcodの行数の内訳（`matches`・`cancellable`・`cancelled`の件数）を出すようにした
  （cancelCodeの値・資料名は出さない）。
  「非表示」ボタン(`yoykHihyoji`)自体の送信（一覧から実際に消す操作）は今回もスコープ外で未着手。

- **確認済み・重複併存下での取消が実サイトで成立した（本改修のライブ検証完了）**: 同一tilcod重複
  対応版を、対象tilcod行が「予約中1・取消済み1」の状態の書誌で実行した。

  ```
  cancel-target: tilcod=1000002035525 matches=2 cancellable=1 cancelled=1
  cancel-reservation-stage: targetStillPresent=true signatureMatched=true matched=true
  cancel-reservation-prevform: forms=1 attrs=method,name action=(empty) method=post controls=222
  cancel-post: attempt=Cancelled     ← 取消POSTは2回、完全性ガードの警告なし
  ```

  取消後の一覧観測（読み取り専用、`liveReservationListInspect`）:
  ```
  list-summary: summaryReservationCount=19  parsedRowCount=21
  highlight[0]: tilcod=1000002035525  stateText=取消  state=CANCELLED  cancelCodePresent=false  buttons=[yoykHihyoji]
  highlight[1]: tilcod=1000002035525  stateText=取消  state=CANCELLED  cancelCodePresent=false  buttons=[yoykHihyoji]
  ```

  取消前は「予約中1行＋取消済み1行」、取消後は「取消済み2行」。基準値1→2の増分、取消可能な行の消滅
  （`cancellable=1`→0）、完全性ガード（19 = 21 − 取消2行）のいずれも設計どおりに動作した。
  `attempt=Cancelled`（`matched`が対象tilcod2行目でも一致）であり、送信前の対象特定（取消可能な行の
  一意性）・stage1複合ガード・送信後判定の3箇所の改修がいずれも実サイトで正しく機能したことを確認した。
  **これにより、アプリ実装による実サイトでの予約取消は、同一tilcod重複がある状態を含めて成立することを
  確認済みとする**（12回目で単純なケース、13回目で重複併存ケースを確認）。これまで本節・
  `docs/backend-design.md`で「未検証」としてきた記述はこの実測をもって更新した。
- **修正済み・診断ログの`singleOrNull`バグ**: 上記のライブ検証自体は成功したが、取消後の
  一覧観測ログに1件バグがあった。`LiveReservationCancelDiagnostic.kt`の`cancel-after`ステージが
  `inspection.rows.singleOrNull { it.tilcod == config.tilcod }`を使っており、`singleOrNull`は該当が
  2件以上のとき`null`を返す。今回まさに取消後は対象tilcodが2行だったため、行が実在するのに
  `cancel-after: targetPresent=false`と誤って記録された。成否判定は`resolveCancelByListDiff`が
  別途正しく行うため実害は無かったが（`attempt=Cancelled`は正しい）、診断ログが事実と食い違って
  いた。あなたが対応した同一tilcod重複が、診断側だけ取り残されていた形である。対象tilcodの行を
  `filter`で全件取得し、行数と各行の`state`・非表示ボタン有無を記録するよう修正した（資料名・
  cancelCode値は引き続き出さない）。`targetRowPresentAfter`も「対象tilcodの行が1行以上あるか」
  （`isNotEmpty()`相当）の意味に改めた。`ReservationListInspector`未対応セッション向けの
  フォールバック経路も件数ベースへ揃えた。
