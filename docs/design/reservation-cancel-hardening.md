# 予約取消への横展開是正（貸出延長で確立した2方式の適用）

**状態**: 設計確定・実装未着手（2026-08-06）
**背景**: `docs/handoff.md`「予約取消側への横展開が可能になった（未着手）」
**前提**: 貸出延長の実サイト一連確認（2026-08-05成功）により、確認コード抽出方式と
1段階目POST後の結果型統一（`docs/design/loan-extension.md` §5.1・§5.3）が実サイトで動くことは確認済み。
**所有者裁定（2026-08-06）**: 横展開の対象は下記A・Bの2件。Bの適用範囲は**stage1応答とstage2応答の両方**。

## 対象と現状の欠陥

### A. 確認コード`OPACUSR001`のハードコード

`ReservationCancelConfirmationFormParser.kt`の`ReservationCancelConfirmationForm.buildForm()`が
`add(okCodesFieldName, "OPACUSR001")`と無条件に値を埋めている。

サイトが取消時に別の確認（例「この資料には予約が入っています。よろしいですか？」＝別コード）を
出すよう変わった場合、アプリは**表示されていない別の問いに盲目的にOKを返す**。二段階確認という
安全機構の意味が失われる。貸出延長のレビュー指摘1と同型。

### B. メンテナンス検知だけが「送っていない」意味の失敗へ倒れる

`ReservationGateway.kt`の`cancelReservation`内、

- `:653` `requireNotMaintenance(stage1Html)` — 1段階目POST**後**
- `:720` `requireNotMaintenance(stage2Response)` — 2段階目POST**後**

がいずれも`LibraryError.Maintenance`を投げ、`ReservationCancelRepositoryImpl`で
`FailureReason.SITE_MAINTENANCE`（＝送っていない意味の失敗）になり、さらに`abortRemaining`で
**同一会員の残件処理まで中止**する。実際には既にPOSTを送った後である。

同じstage1応答に対する他の検証（署名不一致・確認フォーム解析失敗・action解決失敗）は
すべて`resolveCancelAndHide`＝一覧照合経路へ倒れており、**メンテナンス検知だけが非対称**。
貸出延長のレビュー指摘2と同型（`docs/design/loan-extension.md` §5.3）。

`:720`は2段階目（状態変更POST）を送った後であり、実害はstage1より大きい。

## 確認済みの事実（実装前に判明していること）

- 実測フラグメント`app/src/test/resources/fixtures/reservation_cancel_confirmation_live_fragment.js`の
  23行目に`okArray[okArray.length] = "OPACUSR001";`が実在する。貸出延長と同じ構文である。
- 貸出延長の実サイト由来HTML`usrlend_extend_confirm.html`では`okArray[`への**代入は1件のみ**
  （105行目）。`newHidden.value = okArray[i]`は代入ではなく参照であり、抽出条件（`= "文字列";`）に
  合致しない。同一テンプレート由来である予約取消stage1でも同様と見込める。
- `fetchCompleteReservationListForCancellation`は全体を`catch (_: Exception) { null }`で包むため、
  非表示経路（`:881`/`:886`）の`requireNotMaintenance`は既に`IndeterminateAfterPost`へ安全に倒れる。
  **今回の対象外**。`:919`（`performContactSelectionRetry`）は直接予約の連絡先再表示であり無関係。

## 未検証のリスク（実装者は必ず認識すること）

**予約取消stage1応答の完全なHTMLを保持していない**（実測物はJSフラグメントのみ）。
そのため「stage1 HTML全体で`okArray`への代入が1件だけである」ことは**未検証**である。

Aの修正はfail-close（抽出不能・複数・想定外値ならParseException→2段階目を送らず一覧照合へ）で
あるため、この見込みが外れると**予約取消が成立しなくなる**。`docs/handoff.md`進行指示14が警告する
「テストが通っていても機能が永久に動かない」形で現れる。

したがって**実サイトでの取消成功を再確認するまで、この変更は完了扱いにしない**（受入条件5）。

## 修正方針

### A. 確認コードを都度抽出し、想定値と照合する

`ReservationCancelConfirmationFormParser`に、貸出延長`LoanExtensionConfirmationFormParser`の
`extractConfirmationCode` / `parseArrayElementAssignment` / `skipMatchingBracket` と**同型**の
抽出を追加する。

- `okArray[<添字>] = "VALUE";`の代入値を字句走査で抽出する。添字の中身は検証しない。
- 抽出値が1件でなければ`ParseException(SCREEN, "確認コードを一意に特定できません")`。
- 抽出値が想定値`OPACUSR001`と一致しなければ`ParseException(SCREEN, "確認コードが想定外です")`。
  想定値は**照合にのみ使い、無条件に送る値としては使わない**。
- `ReservationCancelConfirmationForm`は抽出済みの確認コードをコンストラクタで受け取り、
  `buildForm()`はそれを送る。**リテラル`"OPACUSR001"`を`buildForm()`から消すこと。**

既存の`extractOkCodesAssignments`はループ本体に判定をインライン展開しているため、そのままでは
配列要素代入を載せられない。**貸出延長と同じ`scanJavaScriptIdentifiers(source) { token -> ... }`
コールバック形へ整理してから**、`OK_CODES_NAME`抽出と確認コード抽出の2つを載せる。
走査の受理／拒否規則（コメント・文字列・template literal・正規表現の内部を候補にしない、
未終端や括弧不一致で走査打切り）は**現行と同じ挙動を保つこと**。既存の走査系テスト
（正規表現内の偽代入・コメント/文字列/template内の偽代入）が引き続き通ることで担保する。

**共通ユーティリティへの切り出しは行わない。** 実サイト検証済みの貸出延長側に手を入れる回帰リスクに
対し、得られるのは重複解消だけである（2026-08-06裁定）。`LoanExtensionConfirmationFormParser`・
`ReservationHideConfirmationFormParser`は**一切変更しない**。

呼び出し側`ReservationGateway`は変更不要。`ParseException`は既に`resolveCancelAndHide`へ倒れる。

### B. POST後のメンテナンス検知を一覧照合経路へ倒す

`ReservationGateway.kt`にメンテナンス判定の述語（`isMaintenancePage(html)`相当。既存の
`MAINTENANCE_MARKERS`を用いる）を用意し、`requireNotMaintenance`はそれを使う形へ整理する。
`requireNotMaintenance`自体の挙動（例外送出）は変えない — **POST前の呼び出し（`:581`・`:594`ほか）は
現状のままが正しい**。まだ送っていないため`SITE_MAINTENANCE`失敗が適切である。

変更するのは以下2箇所のみ。

- `:653`（stage1応答）: 例外送出をやめ、診断注記のうえ
  `return@withExclusiveRequestSequence resolveCancelAndHide(session, cancelBaseline, ::listForm)`。
  署名不一致・確認フォーム解析失敗・action解決失敗と**同じ経路**になる。
- `:720`（stage2応答）: 例外送出をやめ、診断注記のうえそのまま直後の`resolveCancelAndHide`へ進む。

いずれも`resolveCancelAndHide`内の一覧再取得がメンテナンスに当たれば
`fetchCompleteReservationListForCancellation`が`null`を返し`IndeterminateAfterPost`になる
（＝送った後の成否不明）。意味論として正しい。

この変更後、`cancelReservation`が`LibraryError.Maintenance`を外へ漏らすのは
**stage1 POST前の経路だけ**になる。実装後にこれを`grep`で確認すること。

## 受入条件

1. `ReservationCancelConfirmationFormParserTest`の**正常系を実測由来フィクスチャで固定する**
   （進行指示13）。現行の正常系は自作HTMLのみである。
   `reservation_cancel_confirmation_live_fragment.js`を`<script>`として埋め込んだHTMLで、
   確認コード`OPACUSR001`が抽出され送信bodyの末尾に付くことを固定する。
2. 異常系（自作HTMLでよい）: `okArray`代入が複数／想定外の値（例`OPACUSR999`）／代入が無い／
   コメント・文字列・template literal・正規表現の内部にある偽代入、のいずれも`ParseException`。
3. 既存の走査系テスト（`OK_CODES_NAME`の曖昧・重複・偽代入）が**すべて現状どおり通る**こと。
   コールバック形への整理で挙動が変わっていないことの担保である。
4. `ReservationGatewayTest`に、**対になる2ケースを並べたテスト**を追加する（進行指示15）。
   片方へ寄せる変更で必ずどちらかが赤くなる状態を作ること。
   - stage1応答がメンテナンス画面 / stage1応答が署名不一致 → **同じ経路**へ倒れること。
     いずれも取消POST総数が**1回**（2段階目を送らない）ことを検証する。
   - stage2応答がメンテナンス画面 → `LibraryError.Maintenance`が漏れず一覧照合で判定されること。
     取消POST総数が**2回を超えない**ことを検証する。
5. **実サイトでの取消成功の再確認**（所有者が実機・CIのAPKで実施）。上記「未検証のリスク」のため、
   これが済むまで完了扱いにしない。実施前に`BuildConfig.GIT_SHA`を記録すること（進行指示6）。
6. `./gradlew :app:testDebugUnitTest`と`:app:assembleDebug`が通ること。件数（失敗・エラー・
   スキップ0）を報告に含めること。

## 実装者への注意

- **スコープ厳守**: 上記A・B以外を変更しない。非表示経路（`hideNewlyCancelledReservation`・
  `fetchCompleteReservationListForCancellation`）、`ReservationHideConfirmationFormParser`、
  貸出延長の全ファイル、Room・同期・UIは**対象外**。
- 既存テストで`SITE_MAINTENANCE`を期待しているものがBの変更で赤くなった場合、
  **黙って期待値を書き換えず、どのテストがなぜ変わったかを報告に明記すること**。
- 実サイトへの通信は行わない。テストはMockWebServerのみ。
- 段階ごとにコミットする（A・Bは別コミット）。コミット後は
  `git log origin/<branch>..HEAD --oneline`が**空**になるまでプッシュすること（進行指示16）。
