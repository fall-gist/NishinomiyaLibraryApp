# リリースビルドへの移行

**状態**: 実装済み・**実機確認済み（2026-08-07）**。受入条件1〜5をすべて満たした。
所有者がコミット`7dde301`のリリース版APKで、**既存インストールへの上書きインストールを確認**した。
**所有者裁定（2026-08-06）**: 署名は**現行のdebug鍵をそのまま使う**。CIをreleaseビルドへ変更し、
死にコードを除去し、versionの運用を決める。**アプリアイコンの設定は今回は行わない**（見送り）。

## 背景（確認済み）

- `app/build.gradle.kts`に**`buildTypes`ブロックが存在しない**。`release`はAGP既定のままで
  **署名設定が割り当たっていない**ため、`assembleRelease`すると`app-release-unsigned.apk`が
  出力され**インストールできない**。
- `signingConfigs`には`debug`のみ定義されており、`debug.keystore`をリポジトリに固定している。
  「CI実行ごとに鍵が変わらず、アンインストールせずに上書き更新できる」ためである（既存コメント）。
- CI（`.github/workflows/build.yml`）は`assembleDebug`のみを実行し、`app-debug.apk`を保存している。
- **`BuildConfig.DEBUG`に依存する箇所はアプリ内に存在しない**（grep確認済み）。
  したがってreleaseビルドで挙動が変わる分岐はない。
- `ui/debug/DebugScreenController.kt`・`DebugScreenFormatter.kt`はDIで提供されEntryPointにも
  露出しているが、**UIからは一切使われていない**（`LibraryApp.kt`の`Destination`に無い）。

## 署名（最重要）

**`release`に`signingConfigs.getByName("debug")`を割り当てる。新しい鍵は作らない。**

署名鍵を変えると既存インストールを上書き更新できなくなり、アンインストールが必要になる。
その場合**メンバー登録・カード番号・パスワード・予約の送信記録がすべて消え、家族全員の端末で
再設定が必要になる**。非公開アプリでdebug鍵を固定してきた経緯と整合させ、同じ鍵を使う。

**この判断を将来変更する場合は、データ消失を伴うことを必ず所有者へ確認すること。**

## 実装

### 1. `app/build.gradle.kts`に`buildTypes`を追加

```
buildTypes {
    release {
        signingConfig = signingConfigs.getByName("debug")
        isMinifyEnabled = false
        isShrinkResources = false
    }
}
```

**`isMinifyEnabled = false`を明示すること。** 既定値と同じだが、意図を残して将来の誤変更を防ぐ。
R8を有効にするとJsoup（リフレクション）・Room・Hilt・kotlinx.serializationでProGuardルールが
必要になり、不備があると**releaseビルドだけが実行時に壊れる**。単体テストはdebugで走るため
検出できない。非公開アプリでサイズ削減の必要も薄く、リスクに見合わない。

### 2. versionCodeの自動付与とversionNameの運用

**versionNameの運用（2026-08-07所有者決定）**: 2026-08-06のリリース版を`1.0`とし、
**機能追加・バグ修正を1回行うごとに`1.1`→`1.2`…と手で上げる**。`build.gradle.kts`の
該当行にもこの規約をコメントで残してある。`versionCode`は下記のとおり自動付与のため触らない。


`versionCode`を**gitのコミット数**（`git rev-list --count HEAD`）から与える。既存の
`runGitCommand`を再利用し、取得失敗・空の場合は`1`へフォールバックする（`buildGitSha`と同じ流儀）。
`versionName`は`"1.0"`のまま据え置く（ビルドの識別は既存の`BuildConfig.GIT_SHA`が担っており、
設定画面にも表示されている）。

**落とし穴（必ず対応すること）**: `actions/checkout@v4`は既定でshallow clone（`fetch-depth: 1`）
であり、そのままでは`rev-list --count HEAD`が**1**を返してCIのversionCodeが常に1になる。
**CIのcheckoutに`fetch-depth: 0`を指定すること。**

`versionCode`は単調増加でなければ更新インストールできない。単一ブランチ運用である限り
コミット数は増加するが、**ブランチを切り替えると減り得る**点は認識しておくこと。

### 3. CIをreleaseビルドへ変更

`.github/workflows/build.yml`:

- checkoutに`fetch-depth: 0`を追加（上記のとおり必須）
- ビルドコマンドを`:app:testDebugUnitTest :app:assembleRelease`へ変更
  （**単体テストは`testDebugUnitTest`のまま**でよい。releaseで挙動が変わる分岐が無いため）
- artifactのパスを`app/build/outputs/apk/release/app-release.apk`へ
- artifact名を`app-release-apk`へ
- ステップ名の「デバッグ APK」も実態に合わせて直す

### 4. 死にコードの除去

- `app/src/main/java/com/fallgist/nishinomiyalibrary/ui/debug/DebugScreenController.kt`
- `app/src/main/java/com/fallgist/nishinomiyalibrary/ui/debug/DebugScreenFormatter.kt`
  （**`DebugScreenController`以外から参照されていないことを確認してから**削除すること。
  参照が残っていれば削除しない）
- `ui/di/DebugUiModule.kt`の`provideDebugScreenController`とEntryPointの`debugScreenController()`
- `app/src/test/java/com/fallgist/nishinomiyalibrary/ui/debug/DebugScreenControllerTest.kt`

**テスト件数が減る。** 減少後の件数と、削除したテストの件数を報告に明記すること。

`DebugUiModule.kt`というファイル名は実態（全画面のControllerを提供する本番モジュール）と
合っていないが、**リネームは今回のスコープ外**とする。

## スコープ外（触らないこと）

- アプリアイコン（所有者判断により今回は見送り）
- `applicationId`・`namespace`・`minSdk`・`targetSdk`・`compileSdk`
- `AndroidManifest.xml`
- アプリのロジック全般（`BuildConfig.DEBUG`依存が無いため、release化で触る必要はない）
- `DebugUiModule.kt`のリネーム

## 受入条件

1. `./gradlew :app:assembleRelease`が成功し、**`app/build/outputs/apk/release/app-release.apk`が
   生成される**こと（`-unsigned`が付かないこと）。ファイル名を報告に含めること。
2. 生成されたAPKが**署名されている**ことを確認すること。
   `apksigner verify --print-certs`（Android SDKの`build-tools`にある）等で確認し、
   **証明書のフィンガープリントがdebug.keystoreのものと一致する**ことを報告に含めること。
   これが一致しないと既存インストールを上書き更新できない。確認できない場合は
   「未確認」と明記すること。
3. `./gradlew :app:testDebugUnitTest`が通ること。死にコード除去による減少後の件数を報告すること。
4. CIがグリーンになり、artifactとして`app-release.apk`が保存されること（プッシュ後に確認）。
5. **実機（CIのAPK）で、既存インストールへ上書き更新できること**（所有者が実施）。
   アンインストールを求められないこと、更新後もメンバー登録とデータが残っていること。

## 注意

受入条件5が最も重要である。**上書き更新できなければ、署名の目的が達成できていない。**
所有者が実機で確認するまで完了扱いにしないこと。

## versionNameの履歴（2026-08-12追記）

`versionName`は手で上げる運用である（本書「維持すべき事項」4）。実際の値と内容の対応を残す。

| versionName | 上げたcommit | 含まれるもの |
|---|---|---|
| 1.0 | `01dca96`以降のリリース移行時 | 2026-08-06時点のリリース版（参照系・予約・予約取消・貸出延長・通知） |
| 1.1 | `57cc0a1`（2026-08-09） | 通知タップでアプリを開く導線、**マイ本棚の編集機能**（本棚の作成・削除、資料の追加・削除、本棚名と資料メモの編集）とその関連UI整備 |
| 1.2 | `2922d3f`（2026-08-17） | 予約の連絡方法を「連絡不要」へ（メール未登録アカウントで予約が成立しない不具合の修正）、本棚変更操作のカレント同期（**対象と異なる本棚が削除される不具合**の修正）、本棚0件アカウントの同期落ちの修正、設定のインポート・エクスポート機能。設計は`docs/design/account-and-bookshelf-fixes.md`と`docs/design/settings-export-import.md` |
| 1.3 | `dcbaef4`（2026-08-18） | **設定のインポート・エクスポート機能の完了**。パスワードの同梱（AES-256-GCM）と、初期画面（メンバー未登録）からの復元導線。設計は`docs/design/settings-export-import.md` |
| 1.4 | `51a0406`（2026-08-20） | **開館カレンダーへの返却期限表示**。各マス下端にメンバー色ドット（最大3個＋「＋」）、タップで貸出中画面へ遷移し対象日をスクロール・枠強調（遷移時にメンバー絞り込みを解除）。あわせて`CalendarScreenController`のlost update修正（`_state`3箇所を`update {}`へ）。設計は`docs/design/calendar-due-dates.md` |

**1.1は本棚編集機能の実装途中（`57cc0a1`）で上げられており、「リリースごとに上げる」という規約の
運用としては前倒しだった。** 所有者判断により、1.1を「本棚編集機能を含む版」として確定させる
（2026-08-12）。次に機能追加・バグ修正を行うときは1.2へ上げる。

### 今後の予定（2026-08-16 所有者決定）※1.2・1.3とも実施済み。実績は上の履歴表と各実機確認記録を参照

| versionName | 上げる時点 | 含まれるもの |
|---|---|---|
| 1.2 | 新規アカウント対応と本棚操作の対象ズレ修正が完了した時点 | 予約の連絡方法を「連絡不要」へ、本棚変更操作のカレント同期（対象と異なる本棚が削除される不具合の修正）、本棚0件への対応。設計は`docs/design/account-and-bookshelf-fixes.md` |
| 1.3 | 設定のインポート・エクスポート機能が完了した時点 | 端末間移行のファイル書き出し・読み込み。設計は`docs/design/settings-export-import.md` |

**1.2のAPKにはバックアップ機能（インポート・エクスポート）が含まれる。** 単一ブランチ運用であり、
当該機能のコードは1.2の時点で既にブランチへ入っているためである。設定画面の導線も表示される。
1.3は「機能が完了した（実機での往復確認を経た）」という到達点の記録であって、
そこで初めてAPKへ入るという意味ではない。**1.1のときと同じ齟齬を残さないため明記する。**

### 1.2の実機確認記録（2026-08-17、所有者がCIのAPKで実施）

進行指示6に従い、検証したビルドと実測値を残す。

| 確認したSHA | 対象 | 期待値 | 実測 |
|---|---|---|---|
| `4990e32` | メール未登録アカウントでの予約 | 成立する | **成功** |
| `4990e32` | 複数本棚のうち末尾以外を削除 | 指定した本棚が消える | **問題なし** |
| `4990e32` | 本棚0件アカウントの同期 | 通る | **失敗**（`ShelfParser`が捕捉範囲の外。`2922d3f`で修正） |
| `2922d3f` | 本棚0件アカウントの同期 | 通る | **成功** |
| `2922d3f` | 同アカウントの予約削除 | 成立する | **成功** |
| `2922d3f` | バックアップ: 既存メンバー登録ありの端末へ読み込み | 上書きされる | **成功** |
| `2922d3f` | バックアップ: 新規インストール直後へ読み込み | 移行される | **成功** |

`4990e32`での本棚0件の失敗は、実物のHTMLを取得せず分岐条件を推測したことが原因である
（`docs/design/account-and-bookshelf-fixes.md` §2.1に実測表と教訓を記録）。

### 1.3の実機確認記録（2026-08-18、所有者がCIのAPKで実施）

| 確認したSHA | 対象 | 期待値 | 実測 |
|---|---|---|---|
| `83f7292` | パスワード付きの往復（既存メンバーあり／クリーンインストール直後） | 再入力なしで同期・予約できる | **成功** |
| `dcbaef4` | クリーンインストール直後の初期画面から復元 | 復元され通常画面へ切り替わる | **成功** |
| `dcbaef4` | 復元後の同期・予約 | パスワード再入力なしで可能 | **成功** |
| `dcbaef4` | ファイル選択のキャンセル | ボタンが再度押せる | **成功** |
| `dcbaef4` | 復元ボタンの連打 | 多重に選択画面が開かない | **押せない状態になっており不可** |
| `dcbaef4` | 壊れたファイルの読み込み | エラー表示され、登録フォームは残る | **成功** |

壊れたファイルの確認は、**移行に失敗した利用者がアプリを一切使えなくなる**という最悪の結果を
潰すためのものである。テストではController層まで固定しているが、実機のSAF経由は経路が異なる。

### 1.4の実機確認記録（2026-08-21、所有者がCIのAPKで実施）

| 対象 | 期待値 | 実測 |
|---|---|---|
| 返却期限表示の全体動作 | カレンダーに期限のしるしが出て、タップで貸出中へ移動する | **問題なく動作**（所有者報告） |
| メンバー絞り込み中の遷移（設計§5.3-8） | 遷移先で絞り込みが「ぜんいん」へ戻り、対象行が表示される | **成功**（絞り込み後にカレンダーから飛んで全員表示になることを確認） |

**この2件以外について所有者から個別の言及は無い。** 設計§5.3の1（ドットの視認性）・2（ライト／ダーク）・
6（「＋」の縦位置）・7（凡例の折り返し・320dp幅）は、**不具合の報告が無かった**というだけであり、
一つずつ確かめた記録ではない。見た目の細部が気になった場合は、これらを個別に確認してから調整すること。

絞り込みの確認を独立の項目として立てたのは、**絞り込みが元から「ぜんいん」だと差が出ない**ためである
（独立レビューの指摘で判明した設計の見落とし。詳細は`docs/design/calendar-due-dates.md` §4.6の訂正メモ）。
