# 通知テストボタン（設定画面・**一時的な検証用。確認後に撤去する**）

**状態**: 設計確定・実装未着手（2026-08-06）
**位置づけ**: 恒久機能ではない。通知が端末へ届くことを手動で確認するための一時的な導線であり、
確認が取れ次第**撤去する**。撤去手順は本書末尾に記す。

## 背景（確認済み）

通知の実装と配線は存在する。`SyncWorker`(24時間周期) → `StatusRepositoryImpl.sync` →
`NotificationService.notifyAfterSuccessfulSync` → `AndroidNotificationSink` →
`NotificationManager.notify()` まで通っている。設定の既定値も両通知ともオンである。

しかし**`POST_NOTIFICATIONS`のランタイム権限を要求するコードがアプリのどこにも存在しない**
（`AndroidManifest.xml`の宣言のみ。`requestPermissions`/`ActivityResultContracts`は全ソースで0件）。
`targetSdk = 35`のためAndroid 13以降ではユーザーの許可が必須であり、許可が無いと
`AndroidNotificationSink.canPost()`が`false`を返して投稿せずに終わる。

所有者の実機で通知がオフになっており、**権限ダイアログは一度も表示されていない**ことを確認済み。

通知のトリガーが同期成功時しかなく受動的なため、手動で発火できる導線を作って検証する。

## 設計方針

**撤去コストの最小化を最優先**とし、テストボタン一式を新規1ファイルに閉じ込める。

- `SettingsScreenController`・`SettingsUiState`・DIモジュール・`LibraryApp.kt`は**変更しない**。
  `SettingsScreen`の引数も増やさない（呼び出し側の変更が波及しないため）。
- 差し込みは`SettingsScreen.kt`の「通知」`SectionCard`末尾に2行（`DividerLine()`と
  `NotificationTestSection()`）だけ。

**トレードオフ（明示）**: 本プロジェクトの流儀はController を純Kotlinに保ち単体テスト可能にすることだが、
本機能は`@Composable`内で完結させる。権限要求というAndroid依存が本質であり、Controllerへ通すと
State・DI・テストまで波及して撤去コストが跳ね上がるためである。**この機能は単体テストで守らない。**
一時的な検証用であり、動作確認は実機で行うことが目的そのものである。

## 実装

### 新規ファイル: `app/src/main/java/com/fallgist/nishinomiyalibrary/ui/settings/NotificationTestSection.kt`

`@Composable fun NotificationTestSection()` 一つで自己完結させる。引数なし。

**依存の取得**: `LocalContext.current.applicationContext`から`AndroidNotificationSink(context)`を
**直接構築**する。DIモジュールを変更しないためである。`AndroidNotificationSink`は
`@Singleton @Inject constructor(@ApplicationContext Context)`だが、Hiltのアノテーションは
直接構築を妨げない。同クラスは状態を持たない（`NotificationManager`をその都度取得する）ため、
インスタンスが2つになっても無害である。

**押下時のフロー**:

1. `Build.VERSION.SDK_INT < TIRAMISU` → そのまま送信へ
2. `checkSelfPermission(POST_NOTIFICATIONS) == PERMISSION_GRANTED` → そのまま送信へ
3. それ以外 → `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`で
   権限を要求する。`granted == true`なら送信へ、`false`なら結果表示を「未許可」にする

**注意（重要）**: 所有者の端末は設定アプリで通知がオフになっていた。この状態では
`launch()`してもシステムのダイアログが表示されず即座に拒否が返る。そのため
**「アプリの通知設定を開く」ボタンを必ず併設する**（`Settings.ACTION_APP_NOTIFICATION_SETTINGS`に
`EXTRA_APP_PACKAGE`を付けたIntent。`FLAG_ACTIVITY_NEW_TASK`を付けること）。
これが無いとテストが行き止まりになる。

**送信内容**: 実データではなく**ダミー固定**とする。実データでは該当0件で何も出ず、
テストにならないためである。`rememberCoroutineScope()`で以下を順に呼ぶ。

- `postReturnReminder(ReturnReminderPlan(itemsByMember = <2メンバー・計3冊のダミー>, hasOverdue = false))`
- `postPickupReady(PickupReadyPlan(items = <1件のダミー。holdExpiryDateあり>))`

メンバー名・書名はダミーと分かる文字列にする（例「テスト太郎」「通知テスト用の本」）。
実在の書誌名・メンバー名は使わない。

**結果表示**: 押しても何も起きないと切り分けができないため、結果を必ず画面に出す。
両者の戻り値`Boolean`で分岐する。

| 状態 | 表示 |
|---|---|
| 2件とも成功 | 「通知を2件送信しました。通知領域を確認してください。」 |
| 権限が未許可 | 「通知の権限が許可されていません。」＋設定を開くボタン |
| 権限はあるが投稿失敗 | 「送信できませんでした（通知がオフ、またはチャンネルが無効です）。」＋設定を開くボタン |

### 変更ファイル: `app/src/main/java/com/fallgist/nishinomiyalibrary/ui/settings/SettingsScreen.kt`

「通知」`SectionCard`の末尾（`予約受取可能`のスイッチ行の直後）に`DividerLine()`と
`NotificationTestSection()`を追加する。**それ以外は変更しない。**

## スコープ外（触らないこと）

- `NotificationService`・`NotificationPlanner`・`AndroidNotificationSink`本体のロジック
- 通知の判定条件、設定項目、`SettingsStore`
- 同期・WorkManager・Room・予約/貸出まわり
- **恒久的な権限要求フローの実装**（下記「撤去時の課題」参照）

## 受入条件

1. `./gradlew :app:testDebugUnitTest`と`:app:assembleDebug`が通ること（件数を報告に含める）。
   既存テストが赤くならないこと。
2. 実機（CIのAPK）で、設定画面の「通知」セクションにテストボタンが表示されること。
3. 実機で押下し、**返却期限リマインダーと予約受取可能の2件が通知領域に表示される**こと。
   権限が未許可の状態から始める場合は、権限ダイアログまたは設定画面への導線が機能すること。
4. 結果表示が状態に応じて出ること（成功／未許可／投稿失敗）。

## 撤去手順（確認が取れたら実施する）

1. `NotificationTestSection.kt`を削除する。
2. `SettingsScreen.kt`の「通知」`SectionCard`末尾から`DividerLine()`と
   `NotificationTestSection()`の2行を削除する。
3. 本設計書を削除するか、末尾に撤去済みと追記する。

他ファイルへの変更が無いため、撤去はこの2箇所で完結する。

## 撤去時の課題（**別途の判断が必要。撤去とセットで検討すること**）

**恒久的な権限要求フローは未実装のままである。** テストボタンで所有者の端末の権限が許可されても、
それは端末1台の状態が変わっただけであり、**アプリを入れ直した場合や別の端末では同じく通知が届かない**。

テストボタンを撤去する前に、権限要求をどこへ置くか（初回起動時／設定画面の通知セクション／
通知をオンにしたとき）を決めて実装する必要がある。**テストボタンをそのまま恒久フローに
流用しないこと** — 本書のとおり単体テストで守らない前提で作るためである。
