# 返却リマインダー通知のタイトル出し分け 設計書

作成日: 2026-08-21
状態: 所有者承認済み（文言）・実装未着手
報告: 所有者が実機で発見（2026-08-21）。「前日に『明日返却の本があります』と通知されるのは正常だが、
返却当日や超過の場合にも同じ文言で表示される」

## 1. 症状と原因

### 1.1 報告された不具合（確認済み）

`AndroidNotificationSink.postReturnReminder` のタイトルが固定文字列である。

```kotlin
// app/src/main/java/.../data/sync/AndroidNotificationSink.kt:33
.setContentTitle("明日返却の本があります")
```

一方 `NotificationPlanner.returnReminder` の抽出条件は `dueDate <= today + daysBefore` であり、
**返却当日・期限超過の貸出も同じ1通にまとめる**（コメントにも「期限超過を含む」と明記）。
抽出は仕様どおりであり、**タイトルだけが実態に追随していない**。

通知日数は設定で1〜7日前を選べる（`SettingsStore.RETURN_REMINDER_DAYS_RANGE`）。
したがって**2日前以上に設定した場合も「明日返却」は誤り**である。根は同じ。

### 1.2 付随して発見した不具合（未報告・確認済み）

```kotlin
// NotificationPlanner.kt:71
hasOverdue = relevant.any { !it.dueDate.isAfter(today) }
```

`!isAfter(today)` は `dueDate <= today` であり、**返却当日の本も「期限超過あり」に数えている**。
当日はまだ超過ではない。この値は通知本文の「・期限超過あり」に直結する。

### 1.3 なぜテストをすり抜けたか

`NotificationPlannerTest` の既存3件は `today.plusDays(1)` / `plusDays(2)` / `plusDays(3)` /
`plusDays(4)` / `plusDays(8)` / `minusDays(3)` しか使っておらず、**返却当日（`today`）のケースを
1件も通していない**。1.1・1.2はいずれも当日境界の扱いの誤りであり、この空白と正確に対応する。

## 2. 所有者による確定事項（2026-08-21）

タイトルは**3区分で出し分ける**。複数の区分が混ざる場合は**最も差し迫ったもの**を採用する。

| 区分 | タイトル |
|---|---|
| 期限超過あり | 返却期限が過ぎた本があります |
| 超過なし・当日あり | 今日が返却期限の本があります |
| 最短が明日 | 明日返却の本があります（現行の正常時の文言をそのまま維持） |
| 最短が明後日以降 | まもなく返却期限の本があります |

## 3. 設計

### 3.1 「明日」の判定は設定日数ではなく実際の最短期限日で行う

当初「設定が1日前なら『明日返却』」とする案もあったが、**採用しない**。
通知日数を3日前に設定していても、その通知に含まれる最短の期限が明日なら「明日返却」が正しい。
設定値ではなく**実際に通知へ含まれる最短の `dueDate`** で判定する。

### 3.2 文言の決定は純粋関数側に置く

このプロジェクトには、表示文言を Android 非依存の純粋関数に置いてユニットテストで固定する前例がある
（`LoansContentBuilder.dueLabel`、`HomeState.dueHeaderLabel`、`NewArrivalsLastFetchedTextBuilder`）。
今回の不具合は**まさに文言がテストの外にあったために起きた**ので、この前例に倣う。

`AndroidNotificationSink` は Android の通知機構だけを担当し、文言の分岐を持たない（現状の役割分担を維持）。

### 3.3 変更内容

`data/sync/NotificationPlanner.kt`

```kotlin
data class ReturnReminderPlan(
    val itemsByMember: List<MemberLoanItems>,
    /** 期限超過(dueDate < today)を含むか。**返却当日は含めない**(2026-08-21修正)。本文の「・期限超過あり」に使う。 */
    val hasOverdue: Boolean,
    /** この通知に含まれる最も早い返却期限日。タイトルの決定に使う。 */
    val earliestDueDate: LocalDate,
    /** 通知タイトル。文言の分岐をテスト可能な位置に置くため、planner側で確定させる(§3.2)。 */
    val title: String,
)

/** 返却リマインダーのタイトルを決める純粋関数。最も差し迫った区分を採る(§2)。 */
object ReturnReminderTextBuilder {
    fun title(today: LocalDate, earliestDueDate: LocalDate): String = when {
        earliestDueDate.isBefore(today) -> "返却期限が過ぎた本があります"
        earliestDueDate == today -> "今日が返却期限の本があります"
        earliestDueDate == today.plusDays(1) -> "明日返却の本があります"
        else -> "まもなく返却期限の本があります"
    }
}
```

`returnReminder()` 内の修正:

- `hasOverdue = relevant.any { it.dueDate.isBefore(today) }`（`!isAfter` → `isBefore`）
- `earliestDueDate = relevant.minOf { it.dueDate }`（`relevant` が空でないことは直前の早期returnで保証済み）
- `title = ReturnReminderTextBuilder.title(today, earliestDueDate)`

`data/sync/AndroidNotificationSink.kt`

- `.setContentTitle("明日返却の本があります")` → `.setContentTitle(plan.title)`
- 本文（`setContentText`）の組み立ては**変更しない**。`hasOverdue` の意味が正されることで、当日の本だけの
  ときに「・期限超過あり」が出なくなる

### 3.4 スコープ外

- 抽出条件（`dueDate <= today + daysBefore`）は**変更しない**。当日・超過をまとめて1通にするのは仕様である
- 通知の本文・InboxStyleの行構成・通知チャンネル・タップ導線は変更しない
- 予約受取可能通知（`postPickupReady`）は変更しない
- 設定画面の「通知日数」の選択肢・文言は変更しない

## 4. 影響範囲

`ReturnReminderPlan` にフィールドが2つ増えるため、**このデータクラスを直接構築している箇所は修正が必要**。
確認済みの構築箇所は次のとおり（いずれもテスト）。

| ファイル | 箇所 |
|---|---|
| `AndroidAutoReservationNotificationTest.kt` | 37行目付近、136行目付近 |

`RepositoryAndSyncTest.kt:455` は `plan.hasOverdue` を参照するだけで構築はしていない。
**確認済み（2026-08-21）**: 同テストの入力は「明日期限」「当日期限」「超過(`today.minusDays(1)`)」
「対象外(`today.plusDays(2)`)」であり、**真の超過が1件含まれる**。したがって `hasOverdue` の意味を
`isBefore(today)` へ正しても `assertTrue(returnPlan.hasOverdue)` は成立し続ける。**このテストは修正不要**。

なお、当日境界を通していないのは `NotificationPlannerTest` であって、`RepositoryAndSyncTest` は
当日の貸出を入力に含んでいる。ただし超過も同時に含むため、1.2の誤りを検出できる形にはなっていなかった。

本番コードで `ReturnReminderPlan` を構築するのは `NotificationPlanner.returnReminder` のみである。

## 5. テスト計画

`NotificationPlannerTest` に以下を追加する。**当日境界を必ず含める**（§1.3の空白がこの不具合の温床だった）。

1. 期限超過を含むとき、タイトルが「返却期限が過ぎた本があります」になる
2. 超過が無く当日の本があるとき、タイトルが「今日が返却期限の本があります」になる
3. 最短が明日のとき、タイトルが「明日返却の本があります」になる
4. **`daysBefore = 3` で最短が明日のとき**も「明日返却の本があります」になる（§3.1の裏付け。
   設定値ではなく実際の期限日で決まることを固定する）
5. 最短が明後日以降のとき、タイトルが「まもなく返却期限の本があります」になる
6. **返却当日の本だけのとき `hasOverdue` が false になる**（§1.2の回帰防止）
7. 期限超過の本があるとき `hasOverdue` が true になる
8. 超過・当日・明日が混在するとき、タイトルは超過のもの（最も差し迫った区分）になる

既存3件は `hasOverdue` の期待値を変えずに通るはずである（当日の貸出を使っていないため）。
**通らなくなった場合は、テストを書き換える前に実装側を疑うこと。**

## 6. 未解決・既知のリスク

1. 実機での確認は、当日・超過の貸出が実際に存在する状態でしか行えない。通知は同期後に発火するため、
   確認には返却期限が当日以前の貸出が必要になる。**実機確認の難度が高い項目である**ことを認識しておく
2. 本設計はタイトルの文言だけを対象とする。「まとめて1通」という通知設計そのものは変更しない
