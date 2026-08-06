# 通知権限（POST_NOTIFICATIONS）の要求フロー

**状態**: 設計確定・実装未着手（2026-08-06）
**背景**: `docs/handoff.md`「通知（2026-08-06、端末での受信を確認済み・権限要求フローが未実装）」
**所有者裁定（2026-08-06）**: 設定画面での提示と、通知トグルをオンにした瞬間の要求を併用する。

## 解決する問題（確認済み）

通知の実装と配線は完全に機能し、端末で受信も確認済みである（本番経路の受取可能通知を含む）。
しかし**`POST_NOTIFICATIONS`のランタイム権限を要求するコードがアプリのどこにも存在しない**。
`targetSdk = 35`のためAndroid 13以降ではユーザーの許可が必須で、許可が無いと
`AndroidNotificationSink.canPost()`が`false`を返して投稿せずに終わる。

現在受信できているのは所有者が端末の設定アプリから手動でオンにしたためであり、
**アプリを入れ直した場合や別の端末では通知が届かない。**

## 方針

**要求はユーザーが通知を欲していると分かる文脈でのみ行う。** Androidは一度拒否されると
以後ダイアログを出せなくなるため、文脈のない初回起動時の要求で拒否を消費しない。

1. **設定画面の「通知」セクション**に、権限が無いときだけ警告行と要求ボタンを出す。
   通知設定の既定値は両方オンであり、**トグル操作が一度も発生しないまま気づかない**ケースを
   ここで拾う。
2. **通知トグルをオフ→オンにした瞬間**に、権限が無ければ要求する。設定値の保存は権限とは独立に
   行う（権限が取れなくても設定は保存する）。

## 設計

### 権限状態

```
NotificationPermissionUiState:
  NotRequired  — Android 12以下。権限不要（このセクションは表示しない）
  Granted      — 許可済み（表示しない）
  Missing      — 未許可。要求できる可能性がある
  Denied       — 要求したが拒否された。設定アプリへ誘導する
```

**初回拒否と永久拒否は区別しない。** `shouldShowRequestPermissionRationale`はActivityを要求し、
Compose側でのキャストが必要になるうえ、区別しても導線は「設定アプリを開く」で同じである。
`launch()`の結果が`false`なら`Denied`へ遷移し、以後は設定アプリへの導線を出す。これで
「設定アプリで明示的にオフにされていてダイアログが出ない」ケースも同じ導線で救える。

### 状態の再評価（重要）

ユーザーが設定アプリで許可して戻ってきたとき、警告が残り続けてはならない。
**画面の`ON_RESUME`で権限状態を再評価する**（`LocalLifecycleOwner`＋`LifecycleEventObserver`）。
再評価で`Granted`になったら`Denied`も解除する。

### 配置と型

- 新規 `app/src/main/java/com/fallgist/nishinomiyalibrary/ui/settings/NotificationPermission.kt`
  - `NotificationPermissionUiState`（enum）
  - `NotificationPermissionMessages`（**純Kotlin**。状態→警告文言・ボタン表示の判定。テスト対象）
  - `@Composable fun rememberNotificationPermissionController(): NotificationPermissionController`
    - `state: NotificationPermissionUiState`
    - `fun requestOrOpenSettings()` — `Missing`なら権限要求、`Denied`なら設定アプリを開く
  - `@Composable fun NotificationPermissionNotice(controller)` — 警告行と操作ボタン。
    `NotRequired`/`Granted`のときは**何も描画しない**
- 変更 `SettingsScreen.kt`
  - 冒頭で`rememberNotificationPermissionController()`を呼ぶ
  - 「通知」`SectionCard`の**先頭**に`NotificationPermissionNotice(controller)`を置く
    （警告は設定項目より上にあるべき）
  - 2つの通知トグルの`onCheckedChange`をラップし、`enabled == true`のときに
    `controller.requestOrOpenSettings()`を呼ぶ。**設定値の保存は従来どおり無条件に行う**

**`SettingsScreen`の引数は増やさない。** 権限はAndroid依存でありController／Stateへ通す必要がなく、
`LibraryApp.kt`・`SettingsScreenController`・`SettingsUiState`・DIモジュールを変更しないためである。

### 設定アプリを開くIntent

`Settings.ACTION_APP_NOTIFICATION_SETTINGS`に`Settings.EXTRA_APP_PACKAGE`を付ける。
`applicationContext`から起動するため`FLAG_ACTIVITY_NEW_TASK`が必須。

## 文言（案。実装時に整えてよい）

- `Missing`: 「通知が許可されていません。返却期限や受取可能のお知らせが届きません。」
  ボタン「通知を許可する」
- `Denied`: 「通知が許可されていません。端末の設定から通知をオンにしてください。」
  ボタン「アプリの通知設定を開く」

## スコープ外（触らないこと）

- `NotificationService`・`NotificationPlanner`・`AndroidNotificationSink`のロジック
- 通知の判定条件・設定項目・`SettingsStore`
- 同期・WorkManager・Room・予約/貸出まわり
- `SettingsScreenController`・`SettingsUiState`・DIモジュール・`LibraryApp.kt`

## 受入条件

1. `NotificationPermissionMessages`（純Kotlin部分）の単体テストを追加する。
   4状態それぞれで、警告を出すか・どのボタンを出すかを固定すること。
   **`NotRequired`と`Granted`で何も表示しないこと**を明示的に固定する。
2. `./gradlew :app:testDebugUnitTest`と`:app:assembleDebug`が通ること（件数を報告に含める）。
   既存テストが赤くならないこと。
3. 実機（CIのAPK）での確認（所有者が実施）:
   - 権限を許可した状態では、通知セクションに警告が**出ない**こと
   - 端末設定で通知をオフにすると警告が出ること
   - 「アプリの通知設定を開く」から設定アプリへ遷移し、**オンにして戻ると警告が消える**こと
     （`ON_RESUME`再評価の確認。ここが最も壊れやすい）
   - 通知トグルをオフ→オンにしたときの挙動

## 注意

**所有者の端末は既に権限が許可されている。** そのままでは`Granted`の経路しか確認できない。
実機確認の際は、端末の設定アプリで一度通知をオフにしてから各経路を確認すること。

Composeの表示そのものは単体テストで守れない。実機確認を伴わせること
（一覧画面の行レイアウト改修と同じ制約）。
