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

**予約上限に達したときのサイト応答も未検証である。** 上限の値そのものは下記のとおり分かっているが、
超過して確定POSTを送ったときにサイトが何を返すか（alert文言が出るか、黙って別画面へ差し戻すか）は
確認していない。

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
- 新たに許可する書き込みは、ユーザーの明示操作・最終確認に基づく予約確定だけである。
  自動予約、延長、予約取消、登録変更、公式サイトカート操作は引き続き禁止する
