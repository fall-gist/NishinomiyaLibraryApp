# リリースビルドへの移行

**状態**: 設計確定・実装未着手（2026-08-06）
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

### 2. versionCodeの自動付与

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
