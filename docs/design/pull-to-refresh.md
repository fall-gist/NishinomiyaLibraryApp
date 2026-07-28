# 各画面のプルリフレッシュ（スワイプダウン更新）設計書

作成日: 2026-07-28
状態: 所有者承認済み・実装未着手
対応する構想: `docs/handoff.md` §7「各画面でスワイプダウンによる手動更新」
対応する仕様: `docs/spec.md` §3.7「手動同期: プルリフレッシュ」（実装が仕様に追いついていなかった項目）

## 1. 目的と成果物

**目的**: 仕様に記載がありながら未実装だったプルリフレッシュを、同期対象の各画面で使えるようにする。

**成果物**: ホーム・貸出中・予約中・本棚・読書記録の5画面で、リスト領域を下方向へ引いて離すと
`StatusRepository.syncAll(SyncTrigger.MANUAL)` が走り、結果がSnackbarで通知される状態。

**スコープ外**（依頼されていないので手を出さない）:
- 蔵書検索・新着資料・カレンダー・設定・予約カートへのプルリフレッシュ追加
  （新着資料は`syncAll`とは別系統の`onRefresh`を既に持つ。今回は接続しない）
- ホームAppBarの「いますぐ同期」ボタンの撤去（残す）
- 同期処理そのもの（`StatusRepositoryImpl.syncAll`）の変更

## 2. 所有者による確定事項（2026-07-28）

1. 対象画面は同期系5画面のみ（ホーム・貸出中・予約中・本棚・読書記録）
2. 同期状態は新設の共有Controllerに集約する
3. 結果表示はSnackbarに統一する
4. 書誌詳細オーバーレイ表示中の特別扱いは不要
5. 本棚（LazyRow構造）も一旦対象に含める

## 3. 前提として確認済みの事実

- `PullToRefreshBox` は material3 1.3.0（Compose BOM 2024.09.00）に**実在する**。
  パッケージ `androidx.compose.material3.pulltorefresh`、`@ExperimentalMaterial3Api`。
  ソースjar（`material3-android-1.3.0-sources.jar`）で実シグネチャを確認済み:

  ```kotlin
  @Composable
  @ExperimentalMaterial3Api
  fun PullToRefreshBox(
      isRefreshing: Boolean,
      onRefresh: () -> Unit,
      modifier: Modifier = Modifier,
      state: PullToRefreshState = rememberPullToRefreshState(),
      contentAlignment: Alignment = Alignment.TopStart,
      indicator: @Composable BoxScope.() -> Unit = { ... },
      content: @Composable BoxScope.() -> Unit
  )
  ```

  内部実装は `Modifier.pullToRefresh(state, isRefreshing, onRefresh)` によるネストスクロール連携である。
  **子孫にスクロール可能なコンポーネントが無いとプルジェスチャを受け取れない**点が、後述の空状態対応の根拠。

- `syncAll` は `StatusRepositoryImpl` 内の `syncMutex` で直列化済み。複数画面から同時に呼ばれても二重実行しない。
- 手動同期の5分クールダウンは2026-07-28に撤廃済み。リクエスト間500ms制限（`LicsXpSession.MINIMUM_REQUEST_INTERVAL_MILLIS`）は残存。プルを連打してもサイトへの負荷は制御されている。

## 4. 設計

### 4.1 新設: `SyncUiController`

新規ファイル: `app/src/main/java/com/fallgist/nishinomiyalibrary/ui/sync/SyncUiController.kt`

```kotlin
package com.fallgist.nishinomiyalibrary.ui.sync

/** 手動同期の共有状態。全画面のプルリフレッシュとホームの「いますぐ同期」が同じ状態を見る。 */
data class SyncUiState(
    val isSyncing: Boolean = false,
    val message: SyncMessage? = null,
)

/**
 * Snackbarへ一度だけ渡すメッセージ。
 * 単なるStringをStateFlowへ置くと画面回転や再コンポーズのたびに再表示されるため、
 * idで「表示済み」を識別してconsumeMessageで消す。
 */
data class SyncMessage(val id: Long, val text: String)

class SyncUiController(
    private val statusRepository: StatusRepository,
) {
    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state
    private val syncMutex = Mutex()
    private val messageSequence = AtomicLong(0L)

    /** 同期中の再入は黙って無視する（tryLockで判定）。 */
    suspend fun requestManualSync() { ... }

    /** Snackbar表示が完了したら呼ぶ。表示中に新しいメッセージが来ていた場合は消さない。 */
    fun consumeMessage(id: Long) { ... }
}
```

**ディスパッチャは受け取らない**（2026-07-28のレビュー指摘を反映して修正）。初版の設計では
旧`HomeScreenController`に倣って`dispatcher`引数を書いていたが、旧Controllerでこれを使っていたのは
`scope`と`register`の`withContext`であって`requestManualSync`ではなく、新Controllerには
どちらも無いため使い道がない。`requestManualSync`は呼び出し元のコンテキストで動くが、
通信は`LicsXpSession.executeOnce`が`withContext(Dispatchers.IO)`で逃がしているため問題ない。

実装の中身は現行 `HomeScreenController.requestManualSync`（`HomeScreenController.kt:130-144`）と
`manualSyncMessage`（同151-157）をそのまま移設する。文言も変えない:

- 開始時: `isSyncing = true`、メッセージ「同期中です」
- 成功: 「同期が完了しました」／一部失敗時「同期が完了しました(一部失敗: N人)」
- 例外: 「同期に失敗しました。通信状況を確認してください」
- `finally` で `isSyncing = false`、`syncMutex.unlock()`
- `CancellationException` は再スローする（現行どおり）

`consumeMessage(id)` は `_state.update { if (it.message?.id == id) it.copy(message = null) else it }` とする。
表示中に次のメッセージが到着した場合に、新しいほうを消してしまわないための条件付きクリアである。

**注意**: 「同期中です」もメッセージとして流すと、開始と完了で2回Snackbarが出る。
Snackbarは同時に1つしか出せず、新しいものが来ると前のものは差し替わるため実害は小さいが、
インジケータが回っている最中に「同期中です」と出すのは冗長である。
**開始時のメッセージは流さず（`message`は据え置き）、完了・失敗時のみ流すこと。**
プル中であることは `PullToRefreshBox` のインジケータが示す。

### 4.2 DI配線

`ui/di/DebugUiModule.kt`:

```kotlin
@Provides
@Singleton
fun provideSyncUiController(
    statusRepository: StatusRepository,
): SyncUiController = SyncUiController(statusRepository = statusRepository)
```

`MainActivityEntryPoint` に `fun syncUiController(): SyncUiController` を追加。
`MainActivity` で解決して `LibraryApp` へ渡す。既存Controller群と同じ書き方に揃えること。

### 4.3 `HomeScreenController` からの移設

削除するもの:
- `HomeUiState.isSyncing`、`HomeUiState.syncMessage`（`ui/home/HomeState.kt:24-25`）
- `HomeScreenController.requestManualSync`、`syncMutex`、`manualSyncMessage`
- 不要になったimport（`SyncResult`、`SyncTrigger`、`Mutex` など。`launchMutex`は`onScreenLaunched`で使い続けるので`Mutex`は残る）

残すもの:
- `lastSyncText` / `lastSyncFailed` の観測（AppBarの最終同期表示に使う）
- `onScreenLaunched`、`selectMember`、`register`

### 4.4 `LibraryApp` へのSnackbar追加

`ui/app/LibraryApp.kt`:

```kotlin
@Composable
fun LibraryApp(
    state: HomeUiState,
    syncState: SyncUiState,              // 追加
    onManualSync: () -> Unit,            // 既存。SyncUiControllerへ向け直す
    onConsumeSyncMessage: (Long) -> Unit, // 追加
    ...
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(syncState.message) {
        syncState.message?.let { message ->
            snackbarHostState.showSnackbar(message.text)
            onConsumeSyncMessage(message.id)
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = colors.paper,
        bottomBar = { ... },
    ) { ... }
}
```

対象5画面の呼び出しには `isRefreshing = syncState.isSyncing` と `onRefresh = onManualSync` を渡す。

`MainActivity` 側:
```kotlin
onManualSync = { uiScope.launch { syncUiController.requestManualSync() } },
onConsumeSyncMessage = syncUiController::consumeMessage,
```

### 4.5 各画面への `PullToRefreshBox` 適用

**包む範囲はリスト領域のみ**。`ScreenTopBar`（ホームは`AppBar`）と `MemberFilterRow` は
プル対象の外に置き、固定する。理由: 絞り込み行まで一緒に動くと落ち着かない見え方になる。

各画面Composableに引数を2つ追加する:

```kotlin
isRefreshing: Boolean,
onRefresh: () -> Unit,
```

適用形（貸出中の例）:

```kotlin
Column(modifier = modifier.fillMaxSize().background(colors.paper)) {
    ScreenTopBar(title = "貸出中", onOpenMenu = onOpenMenu)
    MemberFilterRow(...)
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.rows.isEmpty()) {
            // 空状態でもプルできるようスクロール可能にする（§4.6）
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                EmptyNote("貸出中の本はありません")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)) { ... }
        }
    }
}
```

画面ごとの注意点:

| 画面 | ファイル | 注意 |
|---|---|---|
| ホーム | `ui/home/HomeScreen.kt` | 全体が `Column + verticalScroll`。`AppBar`と`MemberFilter`を外に出し、残りを包む形へ組み替える。`syncMessage`のテキスト行（`HomeScreen.kt:91-98`）を削除。AppBarの`isSyncing`引数は`syncState.isSyncing`を渡す |
| 貸出中 | `ui/loans/LoansScreen.kt` | 上記の素直な形 |
| 予約中 | `ui/reservations/ReservationsScreen.kt` | `BulkCancelBar`と取消エラー表示（`:83-93`）は**プル領域の外**（`MemberFilterRow`の直下）に置く。ただし現状これらは`rows.isEmpty()`のelse節の内側にある。空のときに出さない条件は維持したまま、プル領域の外へ移すこと |
| 読書記録 | `ui/reading/ReadingRecordsScreen.kt` | 検索`OutlinedTextField`と`MemberFilterRow`はプル領域の外。空状態が3種（`showActivationHint` / 検索ヒット0 / 記録なし）あり、いずれもスクロール化が必要 |
| 本棚 | `ui/shelf/BookshelfScreen.kt` | §4.7の懸念あり |

### 4.6 空状態のスクロール化（必須）

貸出中・予約中・本棚・読書記録は、行が0件のとき `EmptyNote` だけを置いており、
スクロール可能なコンポーネントが存在しない。`PullToRefreshBox` はネストスクロール経由で
ジェスチャを受け取るため、**このままでは空状態でプルできない**。

「1冊も無いから同期して確かめたい」場面こそプルリフレッシュが要る場面なので、
空状態を `Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()))` で包む。

この対応は4画面すべてに要る（ホームは元から`verticalScroll`を持つので不要）。

### 4.7 本棚の未検証事項

本棚は `LazyRow`（横スクロール）の中に、列ごとの `LazyColumn`（縦スクロール）が並ぶ構造である
（`BookshelfScreen.kt:57-63` と `:104-111`）。

**懸念**: 縦方向のプルジェスチャが列内 `LazyColumn` に消費され、親の `PullToRefreshBox` へ
届かない可能性がある。Composeのネストスクロール仕様では、子が先頭にあってそれ以上スクロール
できないとき親へ伝播するため**動作するはずだが、実機で確認するまで断定しない**。

実装後、本棚画面で以下を確認すること:
- 列内リストが先頭にある状態で下へ引くと、インジケータが出て同期が走るか
- 列の途中までスクロールした状態では引けないこと（これは正常な挙動）
- 横スクロールが阻害されていないこと

動かなかった場合は勝手に大改造せず、**事実を報告して判断を仰ぐ**こと。
選択肢としては、インジケータ位置の調整、`LazyRow`より上の階層で受ける形への変更、
本棚のみ対象外にする、などがある。

## 5. テスト

### 変更するテスト
- `app/src/test/java/com/fallgist/nishinomiyalibrary/ui/home/HomeScreenControllerTest.kt:90`
  `requestManualSync_reportsCompletionMessage` を削除（新Controllerのテストへ移設）

### 新規テスト
`app/src/test/java/com/fallgist/nishinomiyalibrary/ui/sync/SyncUiControllerTest.kt`

- 同期完了でメッセージ「同期が完了しました」が入り、`isSyncing`がfalseへ戻る
- 一部失敗時に人数入りの文言になる
- `syncAll`が例外を投げたとき失敗メッセージになり、`isSyncing`がfalseへ戻る
- `consumeMessage(id)` で当該メッセージが消える
- `consumeMessage(古いid)` では新しいメッセージを消さない
- 同期中の再入（`requestManualSync`の二重呼び出し）で`syncAll`が2回呼ばれない

既存の `HomeScreenControllerTest` の他のテストは通ったままであること。

## 6. 影響範囲

新規2ファイル:
- `ui/sync/SyncUiController.kt`
- `test/.../ui/sync/SyncUiControllerTest.kt`

変更9ファイル:
- `ui/di/DebugUiModule.kt`（Provides + EntryPoint）
- `ui/MainActivity.kt`
- `ui/app/LibraryApp.kt`
- `ui/home/HomeScreen.kt`、`ui/home/HomeState.kt`、`ui/home/HomeScreenController.kt`
- `ui/loans/LoansScreen.kt`、`ui/reservations/ReservationsScreen.kt`
- `ui/reading/ReadingRecordsScreen.kt`、`ui/shelf/BookshelfScreen.kt`
- `test/.../ui/home/HomeScreenControllerTest.kt`

破壊的変更なし。Roomのマイグレーションなし。サイトへの新たな書き込みなし
（既存の`syncAll`=読み取り専用の同期を呼ぶだけ）。

## 7. 検討した代替案

- **画面ごとに個別に`syncAll`を呼び、状態も個別保持**: 却下。他画面で同期中でもインジケータが
  出ず、状態が画面間で食い違う。`syncMutex`があるので二重実行はしないが、UIの一貫性が失われる
- **プル対象にトップバー・絞り込み行も含める**: 一般的なアプリの見え方には近いが、この画面構成では
  絞り込みチップが一緒に動いて落ち着かない。リスト領域のみを採る
- **Snackbarでなく各画面に常設メッセージ行**: 却下。成功メッセージが残り続けるのは邪魔。
  ただし**失敗が数秒で流れてしまう弱点は残る**。ホームはAppBarの`lastSyncFailed`で後追いできるが、
  他4画面にはその表示が無い。必要になったら「失敗時のみ再試行ボタン付きSnackbar」を後から足せる

## 8. 実装後に残る既知の弱点

- 同期失敗が、ホーム以外の画面ではSnackbarが消えると追えなくなる（上記のとおり意図した割り切り）
- ~~本棚のプルリフレッシュ動作は未検証（§4.7）~~ → §9で実機確認済み

## 9. 実機検証の結果と追加修正（2026-07-28）

所有者がCI（GitHub Actions）の`app-debug-apk`を実機へ入れて確認した。

### 確認できたこと

- **本棚のプルは動く**（§4.7の懸念は解消）。`LazyRow`内の`LazyColumn`構造でも縦プルが
  親の`PullToRefreshBox`へ届く。下方向プルは`onPostScroll`＝子が消費しなかった残りで
  拾う実装（material3 1.3.0 `PullToRefresh.kt:308-323`）であるため、という読みどおりだった
- ホーム・貸出中・予約中・読書記録でプルが効き、Snackbarで結果が出る
- ホームの「いますぐ同期」で同期中に他画面へ移ると、そちらでもインジケータが回っている
  （`SyncUiController`への状態集約が意図どおり働いている）
- 画面回転でSnackbarが二重表示にはならない
- 空状態は未確認。予約が0件になる状況が作れないため。ただし他画面と同じ形であり実害は無い見込み

### 判明した不具合: 画面回転で同期がキャンセルされる

**現象**: インジケータ表示中に画面を回転させると更新が中断される。ホームの更新ボタンも待機表示に戻る。

**原因**: `MainActivity`が同期を`uiScope`で起動しているが（`MainActivity.kt:79`）、この
`uiScope`は`onDestroy`で`cancel()`される（`MainActivity.kt:101`）。画面回転はActivityの
破棄・再生成なので、同期コルーチンごとキャンセルされる。

`requestManualSync`の`finally`は実行されるため`isSyncing=false`に戻り`Mutex`も解放される。
デッドロックはしないが、**同期は途中で止まる**。メンバーが複数いる場合、2人目の途中で回転すると
1人目だけ更新された状態で終わり、しかも`CancellationException`は再スローされるため
Snackbarでの通知も出ない。利用者からは「更新したのに古いまま」に見える。

**これは本機能で入れた退行ではない。** 旧`HomeScreenController.requestManualSync`も同じ
`uiScope`から呼ばれており、同じ問題を抱えていた。ただしプルリフレッシュで手動同期の使用頻度が
上がるぶん、遭遇しやすくなる。

### 修正: SyncUiController自身のスコープで走らせる

`SyncUiController`は`@Singleton`でActivityより長生きするため、自前のスコープを持たせる。

```kotlin
class SyncUiController(
    private val statusRepository: StatusRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    // Activity再生成で同期が中断されないよう、Controller自身のscopeで走らせる。
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** suspendではない。呼ぶとscope上で同期が始まり、Activityが壊れても走り続ける。 */
    fun requestManualSync() {
        scope.launch { runManualSync() }
    }

    private suspend fun runManualSync() { /* 従来requestManualSyncにあった中身をそのまま */ }
}
```

- `MainActivity`側は`onManualSync = syncUiController::requestManualSync`となり、
  `uiScope.launch`で包む必要が無くなる
- **`dispatcher`引数が復活する。** §4.1で「使い道がない」として一度削除したが、今度は
  scopeの生成という正当な用途がある。他のController（`HomeScreenController`等）も
  同じ形であり、プロジェクトの既存パターンと揃う。`private val`にはせず、scope生成にだけ使う
- 副次的効果として、回転を挟んでもSnackbarの結果表示が失われなくなる

**テストへの影響**: `requestManualSync`が非suspendになるため、`SyncUiControllerTest`は
待ち方を書き換える必要がある。`dispatcher`に`UnconfinedTestDispatcher(testScheduler)`を
渡せば今度は実際に効くので、`runCurrent()`/`advanceUntilIdle()`で制御できる。
検証する6つの性質（§5）は変えない。

**スコープのライフサイクル**: `close()`は設けない。`@Singleton`でありプロセスと寿命を共にする。
これは同期を最後まで走らせたいという本修正の目的そのものである。
