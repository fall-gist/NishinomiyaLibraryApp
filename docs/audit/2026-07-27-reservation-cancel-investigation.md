# 予約取消の実装・検証の生ログ（2026-07-27）

監査用の記録である。要約ではなく、**実際に行った順序・投げたコマンド・返ってきた出力・そのときの判断**を、無駄だった手順も含めてそのまま残す。

前提として、この直前まで「予約確定が成立しない不具合」を長時間追っており、その反省として所有者から進行指示を受けていた（`docs/handoff.md`「主担当AIへの進行指示」）。

- 送ったつもりではなく、実際に送られた通信を最初に確認する
- ブラウザとアプリを同じ粒度で突き合わせる
- 直前の成功要求と失敗要求を先に比較する
- 仮説は予測・反証条件・差分を一つずつ定めて検証する。効果が無ければrevertして基準へ戻す
- 実機検証のビルド元を毎回固定・記録する
- 成功実績と必要性を区別する

以下、その指示を守れた箇所と守れなかった箇所の両方が含まれる。

---

## 1. 着手（サイト構造の読み取り。通信なし）

所有者「予約削除に取りかかろう。これを作っておくと、自動予約機構にも使えそうな気がする」

### 1-1. fixtureから取消ボタンの構造を確認

    $ grep -o "yoykCancel([^)]*)\|yoycod[^,;\"']\{0,30\}" app/src/test/resources/fixtures/usrrsv.html | sort -u | head -15
    yoycod
    yoycod).value = 
    yoycod1013074729
    yoycod1013074730

    $ grep -o "<input[^>]*yoyk[^>]*>" app/src/test/resources/fixtures/usrrsv.html | head -5
    <input type="hidden" name="yoykcode" id="yoycod1013074729" value="">
    <input tabindex="26" type="button" class="button remove" onclick="javascript:yoykCancel('1013074729')" value="取消">

判断: 予約コードは取消ボタンの `onclick` から取れる。行ごとに `name="yoykcode"` の空hiddenが並ぶことも同時に確認した（後でこれが効いてくる）。

### 1-2. 取消可能な行の範囲

    $ python …（usrrsv.html の各行で yoykCancel の有無と状態を集計）
      状態=予約中   取消=有  × 16
      状態=提供可能 取消=無  × 2
    該当行: 18

判断: 「提供可能」には取消ボタンが無い。**状態で判断せず、取消ボタンの有無で判断する**設計にした。この判断は最後まで妥当だった。

### 1-3. 既に判明していたJS（前回セッションの診断ログより）

    function yoykCancel(val) {
      document.LBForm.yoycod.value = val;
      document.LBForm.action = "WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1";
      document.LBForm.submit();
    }

判断: クライアント側の確認ダイアログは無い、と読んだ。**この読みが誤りだった。** JS関数の中に無かっただけで、サーバ応答ページ側に確認ダイアログがあった。関数本体だけを見て「確認は無い」と結論したのは、観測範囲を超えた断定である。

---

## 2. バックエンド実装（commit 3b77933）

設計を提示し、所有者から次の指示を受けた。

- 取消の入口は3つ（行ごとのボタン／チェックボックスによる一括／書誌詳細）
- 書誌詳細は既存の共通画面を文脈で出し分ける
- 予約中から開く詳細はダイアログ表示
- 併せて、貸出中・予約中から開いた詳細では予約追加ボタンを出さない
- 貸出延長は構想として記録のみ（commit 2fba18d）

実装（サブエージェントへ委譲、UIは対象外）:

- `Reservation.cancelCode` 追加、Room v6→v7 マイグレーション
- `ReservationListParser` が `yoykCancel\('(\d+)'\)` から抽出
- `ReservationCancelFormParser` 新設。**同名重複（`yoykcode` が行数分並ぶ）を保持**する必要があり、既存の確認フォーム用パーサ（一意性を要求）は流用できなかった
- `cancelReservation`：排他区間、exactly-once、自動リトライ無効、成否は一覧照合

この時点でのサイト応答の想定は「1回POSTすれば取り消される」。**未検証の想定だった。**

---

## 3. ライブ診断の追加（commit 1e06b6a）

書き込みを伴うため、既存の予約診断と同じ流儀で専用タスクと専用同意フラグを用意した。

- `LICSXP_LIVE_CANCEL_CONFIRM=CANCEL_ON_PRODUCTION`
- 対象は資料コードで指定し、一覧に無い／複数一致／取消コード空なら停止

環境変数なしでの起動が `doFirst` で停止することを確認した。

---

## 4. 1回目のライブ検証（失敗）

### 4-1. 検証用に1件予約

    $ gradlew :app:liveReservationDiagnostic  （tilcod=1000002034513, 受取館=106）
    [live-reservation] reservation-before: targetPresent=false
    [live-reservation] site-messages: /licsxp-opac/WOpacTifDirectYoyExecAction.do
      […, 予約登録しました。確認したい場合は予約状況一覧で確認して下さい。, …]
    [live-reservation] reservation-post: attempt=Registered
    [live-reservation] reservation-after: targetPresent=true

予約成立。

### 4-2. 取消を実行 → 失敗

    $ gradlew :app:liveReservationCancelDiagnostic  （tilcod=1000002034513）
    [live-reservation] cancel-target: tilcod=1000002034513 matches=1
    [live-reservation] site-messages: /licsxp-opac/WOpacUsrRsvCancelAction.do
      [電子図書館システムの書誌詳細へ展開出来ませんでした。<br>{0},
       利用カード番号下４桁「{0}」でログインしています。ログアウトしますか？,
       仮パスワードでは利用できません。パスワード変更を行なってください。,
       予約パスワードでは利用できません。図書館の窓口でパスワードを申請してください。,
       en, 郵送貸出の郵送先住所が指定されていません。,
       予約の取消を行います。よろしいですか？,
       パスワードが{0}以上変更されていません。…]
    [live-reservation] cancel-post: attempt=Rejected(message=仮パスワードでは利用できません。パスワード変更を行なってください。)
    [live-reservation] cancel-after: targetPresent=true

ここで2つ判明した。

1. **取消は2段階だった。** 1回目のPOSTは確認画面を返すだけで、取り消されていない（`targetPresent=true`）。
2. **拒否判定に欠陥があった。** 私がサブエージェントへの指示で「『できません』『越えています』など明確な拒否語が含まれる場合だけ Rejected」と書いたのが誤り。抽出される文言には**全ページ共通のJS定数**が含まれており、「仮パスワードでは利用できません」に反応して誤検出した。一般語での判定は成立しない。

---

## 5. 修正（commit 10513b1）

- 一般語による拒否判定を削除し、実測文言「取消を行います」で `ConfirmationRequired` を返す
- 診断のスクリプト抽出を拡張（`confirm(`/`lbConfirm(` を含む関数の本体、トップレベルの `confirm` 呼び出しを出力）

目的は「OKを押した後に何が送信されるか」を特定すること。

---

## 6. OK後の送信先を探す（迂遠だった区間）

### 6-1. 読み取り専用のdry-runで探索 → 空振り

    $ gradlew :app:liveReservationInspect
    （抽出結果から confirm を含むものを抜くと）
    FUNC logout: if (confirm("ログアウトしますか？"
    TOP: if (lbConfirm(message)) { document.LBForm.action="WOpacUsrChPassDispAction.do";

パスワード変更の確認しか出ない。**取消の確認は取消応答ページにしか無い**ため、このページ群を見ても出ないのは当然だった。無駄足である。

### 6-2. 取消応答ページを再取得して抽出（1段階目のみ。非破壊）

    $ gradlew :app:liveReservationCancelDiagnostic
    [seg30] TOP: … if (0 != 1) { if (0 == 1) { rest = confirm("予約の取消を行います。よろしいですか？"
    [seg30] TOP: // 業務用 } else { rest = lbConfirm("予約の取消を行います。よろしいですか？"

**抽出が300文字で切れており、`if (rest) {…}` の中身が見えない。** この時点で抽出窓を広げれば良かったが、そうせずに次へ進んだ。判断ミス。

### 6-3. action一覧から送信先を推測

    （取消応答ページの screen-script actions から抜粋）
    (top-level):/licsxp-opac/WOpacUsrRsvCancelAction.do     ← 絶対パス・クエリ無し
    (top-level):WOpacUsrRsvListDeteleAction.do?mngFlg2_handan=1&kbnchgflag=1
    (top-level):WOpacUsrRsvListCsvAction.do
    yoykCancel:WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1
    yoykSyusei:WOpacUsrRsvSyuseiAction.do?mngFlg2_handan=1&kbnchgflag=1

判断: 「絶対パス・クエリ無しの同一アクション」が2段階目だろうと**推測**した。結果的にURLは当たっていたが、**本文の作り方を外した**。

### 6-4. 2段階目を実装 → 解析で失敗

    [live-reservation] note: cancel-reservation: 2段階目: reservation-cancel: 予約状況一覧フォームを一意に特定できません

原因: 1段階目用パーサが `gamenid` の値も条件にしていたため、確認応答ページで特定できず。

### 6-5. 確認応答ページのフォーム構成を実測

    classification=authenticated-menu
      FORM: yoycodなし  | 項目数 5   hash,loginFlg,loginOutFlg,message,sound
      FORM: ★yoycodあり | 項目数 26  baserenrakukata,…,yoycod,yoykcode,…
      FORM: yoycodなし  | 項目数 8   baserenrakukata,…,gamenid

2段階目用に条件を緩和（`yoycod` を1つ持つフォーム、`gamenid` 値は不問）。

### 6-6. 再実行 → 依然として未完了

    [live-reservation] note: cancel-reservation: 2段階目確認応答ページのgamenid=tiles.WUsrRsvList
    [live-reservation] note: cancel-reservation: 2段階目もダイアログ文言が返り取消は未完了
    [live-reservation] cancel-post: attempt=ConfirmationRequired(message=予約の取消を行います。よろしいですか？)
    [live-reservation] cancel-after: targetPresent=true

### 6-7. さらに推測を重ねる（悪手）

予約確定時のブラウザHARに `jp.co.necsoft.licsxp.base.util.validation.MessageUtil.CONFIRM_DIALOG_SEND_REDIRECT=true` というパラメータがあったことを思い出し、これを2段階目に付けて再実行。

    [live-reservation] cancel-post: attempt=ConfirmationRequired(…)   ← 変化なし
    [live-reservation] cancel-after: targetPresent=true

**変化なし。ここで推測の積み上げを止め、6-4以降の変更を全てrevertして基準（10513b1）へ戻した。** 進行指示の「効果が無ければrevertして基準へ戻す」には従えた。

---

## 7. ブラウザ操作による実測（所有者の指示）

所有者「computer useであなた自身がchromeを操作して実施されたい」

### 7-1. 1回目 → セッション切れで失敗

- `WOpacMnuTopInitAction.do?WebLinkFlag=1` へ移動 → ログイン画面。**認証情報の入力は私が行わない方針**のため、所有者にログインボタンの押下を依頼した
- 予約状況一覧が表示。対象行の取消ボタンを `find` で特定してクリック
- **ネイティブの `confirm()` がページをブロックし、スクリーンショットもJS実行も不能になった**（`Script injection timed out after 5000ms`）
- 拡張機能側からEnterを送ったが**ダイアログには届かない**
- 所有者がOKを押す前にセッションが切れた

### 7-2. 2回目 → 成功（ただし部分的）

対策として、クリック前にページ側で `window.confirm = () => true` に差し替えた。

    window.confirm = () => true; window.alert = () => undefined;
    const hit = [...document.querySelectorAll('input[onclick*="yoykCancel"]')]
      .find(b => (b.closest('tr')?.innerText||'').includes('スマホAIさん超入門'));
    hit.click();

結果: **1段階目は差し替えが効いたが、送信後に読み込まれた新しいページが自前で確認ダイアログを出すため、そこでまたブロックされた。** 差し替えは document 単位でしか効かない。所有者がOKを押した。

    $ read_network_requests（urlPattern=UsrRsv）
    1. POST WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1   200
    2. POST WOpacUsrRsvCancelAction.do                                  200
    3. POST WOpacUsrRsvListAction.do                                    200

    ({件数: "19", 対象消滅: false, 次の検証用code: "1013074729"})

取消は成功（20件→19件）。**URLの列は取れたが、本文は取れなかった**（このツールはボディを返さない）。

### 7-3. fetchで確認画面のHTMLを読もうとして失敗

    const r = await fetch('WOpacUsrRsvCancelAction.do?mngFlg2_handan=1&kbnchgflag=1', {method:'POST', body});
    ({status: 403, script: "[BLOCKED: Cookie/query string data]"})

拡張機能側でブロックされた。ブラウザ経由での本文取得を断念。

---

## 8. 診断の抽出窓を広げて核心に到達（commit 75493ed）

6-2で先送りにした修正をここで行った。トップレベルの `confirm` 抽出を、最初のセミコロンで切るのをやめ、呼び出しの後ろ1200文字まで含めるようにした（上限300→1500文字）。

    $ gradlew :app:liveReservationCancelDiagnostic （1段階目のみ。非破壊）

    … rest = lbConfirm("予約の取消を行います。よろしいですか？", "", "#F1F1FF");
    } } } else { rest = window.confirm("予約の取消を行います。よろしいですか？"); }
    if (rest) { okArray[okArray.length] = "OPACUSR001"; submitFlg = false; }
    else { return cancelDialog(); }
    // OKボタンを押下されたメッセージコードを hidden に格納する
    for (var i = 0; i < okArray.length; i++) {
      var newHidden = document.createElement("input");
      newHidden.type = "hidden";
      newHidden.name = OK_CODES_NAME;
      newHidden.value = okArray[i];
      document.prevRequestForm.appendChild(newHidden);
    }

**仕組みが判明した。** OKを押すと、メッセージコード `OPACUSR001` を `OK_CODES_NAME` という名前のhiddenへ入れ、`prevRequestForm`（前回要求のフォーム）へ追加して送り直す。

残る不明点は `OK_CODES_NAME` の実体だけとなった。

### 8-1. `OK_CODES_NAME` を探して空振り（3手）

1. 予約状況一覧のページでJS評価 → `(未定義)`（確認応答ページにしか無い）
2. 公開JSファイルを取得して検索 → `lbwebdialog.js` は `confirm()` を包むだけ、`htmlMessage2dialog.js` にも無し
3. 診断に汎用の定数抽出（`NAME = "リテラル"`）を追加 → **1件も取れず**。revert

ここで深追いを止め、所有者へDevToolsでのペイロード確認を依頼した。

---

## 9. 所有者から2段階目の実ペイロードを受領（決着）

    mngFlg2_handan=1&kbnchgflag=1&hash=…&returnid=https%3A%2F%2Ftosho.nishi.or.jp%2F%3Fv%3DPC
    &mngFlg2=1&gamenid=tiles.WUsrRsvList&yoycod=1013363220&hTilcod=&scrollToTilcod=null&pageID=
    &popupBMtime=&grpcod=&gamenFlag=&returnLoccod=&changeYoycod=&rsvSortKey=&rsvSortDefKey=0
    &yoykcode=&…（21回）…&watloccod=&…（21回）…&watspt=&…（21回）…
    &renrakukata=&…（21回）…&dropChangeFlg=&…（21回）…&postSeq=&…（21回）…
    &basewatspt=106&…（21回）…&baserenrakukata=4&…（21回）…&dropwatspt=106&…（16回）…
    &droprenraku=4&…（21回）…&okCodes=OPACUSR001

ここから確定した3点:

1. `OK_CODES_NAME` の実体は **`okCodes`**、値は **`OPACUSR001`**
2. **1段階目のクエリ（`mngFlg2_handan=1`、`kbnchgflag=1`）が2段階目では本文の先頭に入る**
3. 続く内容は1段階目の本文と同じ（同名重複もそのまま）

**アプリが失敗し続けていた理由は3つあった。** `okCodes` を送っていない／1段階目のクエリを本文に含めていない／確認画面を再解析して送っていた（正しくは1段階目に送った内容の再送）。

---

## 10. 実装（commit 0dbb0a0）

- 1段階目で実際に送ったフォーム内容を保持し、2段階目でそれを再利用
- 2段階目 = `mngFlg2_handan=1`,`kbnchgflag=1` を先頭 → 1段階目の本文（順序・重複そのまま）→ `okCodes=OPACUSR001`
- クエリ無しの `WOpacUsrRsvCancelAction.do` へ exactly-once・自動リトライ無効で1回だけ
- 成否は取消後の一覧に対象が残っているかで判定

`testDebugUnitTest` / `assembleDebug` は成功。**実サイトでの成功は未検証のまま中断した。**

---

## 11. この過程の自己評価（監査の観点）

### 守れた点

- 書き込みを伴う操作は全て所有者の承認を経た。診断は専用同意フラグと安全弁（対象が一覧に無ければ何もしない）を備えた
- 効果が無かった変更（6-4〜6-7、8-1の定数抽出）を全てrevertし、基準へ戻した
- 実測と推測を分けて記録し、未検証事項をドキュメントに残した

### 迂遠だった点

1. **1-3で、JS関数の本体だけを見て「クライアント側の確認ダイアログは無い」と結論した。** 関数の外（ページのトップレベル）を見ていなかった。設計の前提を1つ外した。
2. **6-2で抽出が300文字で切れているのを見ながら、窓を広げずに次へ進んだ。** ここで広げていれば、6-3〜6-7の推測（4回のライブ実行）は全て不要だった。最短経路は「見えていないものがあると分かった時点で、見えるようにする」だった。
3. **サブエージェントへの指示で「一般的な拒否語で判定」と書いた。** 全ページに共通JS定数が埋め込まれていることは前回セッションで既に分かっていたのに、指示に反映しなかった。
4. **6-7で、別機能（予約確定）のHARで見たパラメータを、根拠なく取消に流用した。**
5. **7-1で、ネイティブダイアログがページをブロックすることを想定せずにクリックした。** 結果としてセッション切れで1往復を失った。

### 教訓

- 「見えていない箇所がある」と気づいた時点で、推測に進まず観測手段を直す。今回はそれを2回先送りし、そのたびにライブ実行を余分に消費した
- 抽出・観測のツールは、対象が変わるたびに**範囲が足りているかを確認する**。前回セッションの真因（Cookie欠落）も観測範囲の不足が原因であり、同じ構図を繰り返した
- サブエージェントへの指示に、既知の落とし穴（共通JS定数など）を明示的に含める

### 数値

- ライブ実行の回数: 予約2回、取消系7回（成功1、失敗6）
- revertした変更: 2件（2段階目の初期実装一式、汎用定数抽出）
- 最も時間を消費した区間: 6章（推測の積み上げ）
