package com.fallgist.nishinomiyalibrary.ui.version

/**
 * バージョン情報ページの更新履歴1件分。
 * [version] はversionName（例: "1.10"）、[date] は所有者確定の表記（例: "2026-09-27"）、
 * [body] は文語体・箇条書きにしない短文。
 */
data class ReleaseNote(
    val version: String,
    val date: String,
    val body: String,
)

/**
 * 更新履歴の文面（docs/design/version-info-page.md §2 に所有者が確定したもの。一字一句そのまま）。
 * 通信では取りに行かず、アプリのコードにこの1か所だけで持つ。
 * 版を上げるときは、ここと docs/design/release-build.md の履歴表の両方を更新すること。
 */
object ReleaseNotes {
    /** 新しい版が先頭になるように並べる。 */
    val all: List<ReleaseNote> = listOf(
        ReleaseNote(
            version = "2.1",
            date = "2026-10-05",
            body = "蔵書検索の結果を書誌種別・書名・著者・出版者・出版年月・分類で並べ替え可能とし、詳細検索と条件の追加による絞り込みに対応。",
        ),
        ReleaseNote(
            version = "2.0",
            date = "2026-09-28",
            body = "本棚内の並び順を公式サイトと揃え、ドラッグでの並べ替えに対応。本棚への追加時に、新しい本棚を作成して追加することを可能とした。以上をもって、予定していた機能を一通り完成とする。",
        ),
        ReleaseNote(
            version = "1.10",
            date = "2026-09-27",
            body = "処理中でも書誌詳細・検索・新着資料を待たずに開けるよう改善。",
        ),
        ReleaseNote(
            version = "1.9",
            date = "2026-09-26",
            body = "本棚を1つも持たない場合に、本棚の作成・削除ができない不具合を修正。処理中の帯を緑色に変更。",
        ),
        ReleaseNote(
            version = "1.8",
            date = "2026-09-26",
            body = "一斉操作・同期の進み具合を画面下の帯に表示し、処理中も他の画面へ移動可能とした。",
        ),
        ReleaseNote(
            version = "1.7",
            date = "2026-09-26",
            body = "複数選択を長押しで始める方式に変更。読書記録からの一斉予約・カート追加・本棚追加と、複数語での検索に対応。",
        ),
        ReleaseNote(
            version = "1.6",
            date = "2026-09-20",
            body = "貸出の一斉延長、予約カートの一括削除、蔵書検索・新着資料からの一斉カート追加・予約・本棚追加に対応。",
        ),
        ReleaseNote(
            version = "1.5",
            date = "2026-08-29",
            body = "返却期限の通知で、当日や期限超過の本にも「明日返却」と表示される不具合を修正。",
        ),
        ReleaseNote(
            version = "1.4",
            date = "2026-08-21",
            body = "開館カレンダーに返却期限を表示。日付のタップで、その日が期限の貸出を表示。",
        ),
        ReleaseNote(
            version = "1.3",
            date = "2026-08-18",
            body = "設定の書き出しにパスワードを暗号化して同梱。最初の画面からの復元に対応。",
        ),
        ReleaseNote(
            version = "1.2",
            date = "2026-08-17",
            body = "メールアドレス未登録時に予約できない不具合と、操作と別の本棚が削除される不具合を修正。設定の書き出し・読み込みに対応。",
        ),
        ReleaseNote(
            version = "1.1",
            date = "2026-08-09",
            body = "マイ本棚の編集に対応。",
        ),
        ReleaseNote(
            version = "1.0",
            date = "2026-08-06",
            body = "リリース。",
        ),
    )
}
