# 設定のインポート・エクスポート（端末間移行）

状態: 設計確定（2026-08-15作成・同日承認）。§4は案A（アプリ内固定鍵による暗号化）で確定。
対象: 端末を買い替えたとき、移行元で1ファイルを書き出し、移行先で読み込めば
アプリの利用を継続できるようにする。

## 1. 目的とスコープ

現状、アプリで登録した利用者データ（メンバー、パスワード、各種設定、自動予約ルール、
読書記録）はすべて端末内に閉じており、移行手段が存在しない。`android:allowBackup="false"`
のため、Android標準の自動バックアップも効かない（`AndroidManifest.xml`）。

本機能は**利用者が明示的に操作するファイル入出力**を提供する。クラウド同期・自動バックアップ・
複数端末の並行利用は対象外である。

## 2. 移行対象の分類

DBを丸ごと写すのではなく、**サイトから再取得できないものだけ**を移す。同期キャッシュを含めると、
ファイルが肥大化するうえ、移行先で古い貸出・予約が一時的に表示される不整合を招く。

### 含める

| 保存先 | 対象 | 理由 |
|---|---|---|
| Room `members` | メンバー全件（id・名前・色・カード番号・並び順） | 利用者が入力したもの。id はパスワードと制御テーブルの参照キーなので**そのまま保持する** |
| `CredentialStore` | メンバーごとのパスワード | §4参照 |
| DataStore `AppSettings` | 同期時刻・通知2種・既定カレンダー館・リマインダ日数・診断ログ・自動予約スイッチ | 利用者設定そのもの |
| Room `auto_reservation_rules` / `auto_reservation_terms` | 自動予約のキーワードルール全件 | 利用者が組み立てた資産。再入力の手間が最も大きい |
| Room `auto_reservation_controls` | 自動予約の重複防止状態 | 移行しないと、移行先で**同じ資料を再度予約しに行く**恐れがある。`expiresOn` で自然消滅するため保持期間は限定的 |
| Room `reading_records` / `reading_history_checkpoints` | 読書記録と差分同期チェックポイント | 同期で削除されない累積データ。サイト側の履歴が消えると復元不能 |
| Room `reservation_cart_items` | アプリ内予約カート | アプリ内にしか存在しない未確定の予約候補 |

### 含めない

- 同期で再構築されるキャッシュ: `loans` / `reservations` / `shelves` / `shelf_items` /
  `user_summaries` / `closed_days` / `new_arrivals`
- 端末固有の記録: `sync_logs`、診断ログ、`lastNewArrivalFetchedAt`（内部状態であり利用者設定ではない）
- 自動予約の実行結果表示用: `auto_reservation_latest_run` / `auto_reservation_latest_items`
  （直近1回の実行報告であり、移行先で再現する意味がない）

## 3. ファイル形式

単一のJSONファイル。**Roomエンティティを直接シリアライズせず、専用のDTOへ写す。**
DBスキーマの変更（現在 v9）が、そのままエクスポート形式の破壊にならないよう結合を切るためである。

```json
{
  "formatVersion": 1,
  "exportedAt": "2026-08-15T10:30:00+09:00",
  "appVersion": "1.2.3",
  "sourceDbVersion": 9,
  "members": [
    { "id": 1, "name": "…", "colorHex": "#3D6DB5", "cardNumber": "…", "sortOrder": 0,
      "password": "…" }
  ],
  "settings": { "syncHour": 18, "syncMinute": 0, "…": "…" },
  "autoReservation": { "rules": [ { "id": 1, "enabled": true, "sortOrder": 0,
                                    "terms": [ { "kind": "INCLUDE", "sortOrder": 0,
                                                 "original": "…", "normalized": "…" } ] } ],
                       "controls": [ … ] },
  "readingRecords": [ … ], "readingHistoryCheckpoints": [ … ],
  "reservationCartItems": [ … ]
}
```

規則:

- `LocalDate` は ISO-8601 文字列（`2026-08-15`）。エンティティのTypeConverterには依存しない
- enum（`AutoReservationTermKind`、`AutoReservationControlStatus`、
  `ReservationPickupSubmissionOrigin` 等）は名前文字列。未知の名前は読み込み時に拒否する
- `formatVersion` が読み込み側の対応上限を超えるファイルは**読み込まず、明示的に拒否**する
  （新しい版のアプリで書いたファイルを古い版で壊さないため）
- `sourceDbVersion` と `appVersion` は診断用の情報であり、読み込みの可否判定には使わない
- 既定ファイル名: `nishinomiya-library-backup-YYYYMMDD-HHmm.json`、MIME `application/json`

## 4. パスワードの扱い（案Aで確定）

**所有者の指示**: パスフレーズは求めず、エクスポートファイルのみで移行できること。
したがって、パスワードはファイルに含める。**復号に必要な情報はファイルとアプリ内に閉じる。**

これは、**ファイルを入手した第三者がパスワードを取得できる**ことを意味する。
所有者の判断として受け入れたうえで、以下の案Aを採る。

**案A: アプリ内固定鍵によるAES-GCM暗号化。** JSONの `password` 欄だけを暗号化してBase64で
格納する。テキストエディタやクラウドのプレビューで**偶然に目に入ることを防ぐ**のが目的である。

**鍵はAPK内にあり、解析すれば復号できる。これは秘匿の保証ではない。**
実際の流出経路として現実的なのは「クラウドやDownloadフォルダに残ったファイルを別のアプリや
プレビューが読む」であって、APKを解析する攻撃者ではない、という判断に基づく。

「暗号化されているから安全」という誤解を生まないよう、UI・本文書ともに上記を明記する。

### 実装仕様

- アルゴリズム: AES-256-GCM（`javax.crypto`）。認証タグ128bit
- 鍵: アプリ内の32バイト定数。**この機能専用の鍵を新規に用意する**（他用途の鍵を流用しない）
- IV: 暗号化のたびに `SecureRandom` で12バイト生成し、**暗号文の先頭に連結**してからBase64化する。
  IVを固定値にしないこと（GCMでIVを再利用すると鍵が破れる）
- JSON上の表現: `"password": "<Base64(IV || ciphertext || tag)>"`。平文との判別のため、
  暗号化済みであることを payload 側のフラグ（`"passwordEncrypted": true`）で明示する
- 復号に失敗したパスワードは、そのメンバーのパスワードのみ未設定として扱い、
  **インポート全体は失敗させない**（他の移行データを道連れにしない）。画面には
  「N人分のパスワードを復元できませんでした。再入力してください」と表示する
- 鍵定数は R8 の対象だが、難読化は解析の手間を上げるだけで秘匿にはならない。上記の前提を変えない

エクスポート画面には「このファイルにはログインパスワードが含まれます。取り扱いに注意してください」
という趣旨の警告を出す。

## 5. 入出力経路

Storage Access Framework を使う。追加パーミッションが不要で、保存先（端末内・Google Drive等）を
利用者が選べる。

- エクスポート: `ActivityResultContracts.CreateDocument("application/json")`
- インポート: `ActivityResultContracts.OpenDocument(arrayOf("application/json"))`
  - 一部のファイル提供元は MIME を `application/octet-stream` 等で返すため、
    **拡張子やMIMEで弾かず、内容のパース結果で判定する**

診断ログの共有（`FileProvider` + `ACTION_SEND`）とは別導線とする。診断ログは「送りつける」用途、
本機能は「利用者が保存先を選んで置く／選んで読む」用途で、必要な操作が異なるため。

## 6. インポートの手順

方式は**全置換のみ**。移行という用途では新規端末が前提であり、挙動が予測可能であることを優先する。
マージは、メンバー同定キー（`cardNumber` に一意制約がない）と `auto_reservation_rules.sortOrder`
の unique 制約の再採番が絡み、複雑さの割に用途が薄い。

手順:

1. ファイルを読み、**全体をパースして検証してから**書き込みを始める。途中まで書いて失敗する状態を作らない
2. 既存データがある場合は確認ダイアログを出す（「現在のメンバーN人と設定は破棄されます」）
3. 単一の Room トランザクション内で、対象テーブルを削除してから投入する
4. パスワードは `CredentialStore` へ書き込む。**Roomトランザクションの外**であり原子性が異なるため、
   Room投入の成功後に書く（先にパスワードを書くと、DB投入失敗時に孤児が残る）
5. DataStore の設定を書き込む
6. **同期スケジュールを再構成する**（`SyncScheduleStarter.scheduleFromSettings()`）。
   同期時刻の設定を移行しても、WorkManagerへの登録は端末ごとに別であるため、これを忘れると
   自動同期が動かない
7. 通知権限は端末の権限であり移行できない。インポート完了後、通知設定がオンなら
   既存の `NotificationPermission` の導線で許可を求める

技術的な注意:

- `members.id` は元の値のまま挿入する。`AppDatabase.insertMemberAtEnd` は `id=0` と
  `sortOrder` 再採番を行うため**使わない**。`MemberDao.insert` を直接使う
- Roomの `autoGenerate = true` は SQLite の `AUTOINCREMENT` を生成する。明示idでの挿入後、
  次の自動採番が既存idと衝突しないこと（`sqlite_sequence` が追随すること）は**推定であり、
  テストで固定する**
- `auto_reservation_rules.id` も `auto_reservation_terms.ruleId` の参照先なので保持する
- 外部キー（`reservation_cart_items.memberId` 等）を満たすため、**members を最初に投入する**

## 7. エラー処理と境界条件

読み込みを拒否し、既存データを一切変更しないケース:

- JSONとしてパースできない／必須項目が欠けている
- `formatVersion` が未対応（上位）
- enum名が未知
- 参照整合性違反（`terms.ruleId` に対応する rule がない、`cartItems.memberId` に対応する
  member がない）
- 値域違反（`syncHour` が 0..23 の外、`returnReminderDaysBefore` が 1..7 の外）。
  `SettingsStore.update` は `require` で例外を投げるため、**投入前に自前で検証して
  利用者向けメッセージにする**

その他:

- 空ファイル・巨大ファイル: 読み込みサイズに上限を設ける（読書記録が主な嵩の源。
  上限値は実データの規模を見て決める）
- エクスポート時にメンバーが0人でも、設定だけのファイルとして書き出せる
- 書き込み先URIへの権限が失効している場合（クラウド提供元の都合）はエラー表示にとどめる

## 8. モジュール構成

```
data/backup/
  BackupPayload.kt        … @Serializable なDTO群。formatVersion定数もここ
  BackupPayloadCodec.kt   … JSON⇔DTO。パース検証と拒否理由の判定（純Kotlin、テスト容易）
  BackupExporter.kt       … 各Store/DAOから読み出してDTOを組む
  BackupImporter.kt       … DTOを検証し、トランザクションで投入する
  BackupSecret.kt         … パスワード欄の暗号化・復号（案A採用時のみ）
ui/settings/
  BackupSection.kt        … 設定画面のセクション（エクスポート／インポートのボタンと確認・結果表示）
```

`BackupExporter` / `BackupImporter` は `SettingsScreenController` から呼ぶ。既存の
Controllerは純Kotlinで組まれているため、SAFのURI取得（Android依存）は Composable 側で行い、
Controller へは `InputStream` / `OutputStream` またはバイト列を渡す境界にする。

## 9. テスト方針

- `BackupPayloadCodec` のラウンドトリップ（DTO→JSON→DTO で等価）
- **ゴールデンJSON**を固定する。形式が意図せず変わったらテストが落ちる状態を作る
- 拒否系: 未対応 `formatVersion`、未知enum名、参照整合性違反、値域違反のそれぞれで
  **既存データが変更されていないこと**を確認する（拒否の確認だけでは不十分）
- インポート後の `members.id` 保持と、その後の新規メンバー追加でid衝突が起きないこと
- インポート後に `SyncScheduleStarter.scheduleFromSettings()` が呼ばれること
- 案A採用時: 暗号化した値が元に戻ること、他端末（別インスタンス）で復号できること

## 10. 段階分け

**第1段**: パスワードを除く全項目のエクスポートとインポート（全置換）。SAF導線、設定画面UI、
上記テスト。これで「パスワードだけ再入力すれば移行できる」状態になる。

**第2段**: パスワードの同梱（§4の案A: アプリ内固定鍵によるAES-GCM暗号化）。

各段でテストを通してからコミットする。第1段の完了時点で実機（CIのAPK）による
往復確認を行う。

## 11. 未解決・保留

- 読み込みサイズの上限値（実データの規模を測ってから決める）
- マージ方式のインポートは実装しない。将来必要になった場合、メンバー同定キーの設計から
  やり直しになる
