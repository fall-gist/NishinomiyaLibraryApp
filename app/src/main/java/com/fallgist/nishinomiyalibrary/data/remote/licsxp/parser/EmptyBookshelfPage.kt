package com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser

/**
 * 「マイ本棚」の応答が本棚0件の画面(実物: fixtures/mybooklist_empty.html)かどうかを判定する共通の
 * 純関数(docs/design/bookshelf-create-from-empty.md §2.1・§6.3)。
 *
 * 次の**すべて**を満たすときだけtrueを返す。
 * - `ShelfListParser` の結果がプレースホルダ(番号0)だけである
 * - `ShelfParser` が失敗する
 * - `BookshelfCreateFormParser` が成功する(`listname`・`commnt` を持つ作成フォームがある)
 *
 * いずれかを欠けばfalseを返す。呼び出し側はfalseのとき0件と断定せず、従来どおりの規則
 * (スキップ・停止・Unknown等)に従うこと。この関数自体は状態を変更せず、判定結果を返すだけである。
 *
 * 呼び出し元(2026-09-26時点):
 * 1. 本棚作成の送信前(`BookshelfGateway.zeroShelfSnapshot`)
 * 2. 本棚1件からの削除の送信後(`BookshelfGateway.afterPost` の `zeroShelfFallback`)
 * 3. 同期(`LicsXpClient.fetchUserData`)
 */
internal object EmptyBookshelfPage {
    fun matches(html: String): Boolean {
        val listed = try {
            ShelfListParser.parse(html)
        } catch (_: ParseException) {
            return false
        }
        if (listed.singleOrNull()?.no != 0) return false
        val shelfParses = try {
            ShelfParser.parse(html)
            true
        } catch (_: ParseException) {
            false
        }
        if (shelfParses) return false
        return try {
            BookshelfCreateFormParser.parse(html)
            true
        } catch (_: ParseException) {
            false
        }
    }
}
