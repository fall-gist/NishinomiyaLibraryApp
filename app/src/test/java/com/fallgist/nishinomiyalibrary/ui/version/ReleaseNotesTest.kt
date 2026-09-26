package com.fallgist.nishinomiyalibrary.ui.version

import com.fallgist.nishinomiyalibrary.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * バージョン情報ページの更新履歴データの整合性テスト。
 * docs/design/version-info-page.md §4 の要求(先頭の版番号とversionNameの一致)を単体テストで固定する。
 */
class ReleaseNotesTest {

    @Test
    fun `先頭の版番号はBuildConfig VERSION_NAMEと一致する`() {
        // 版を上げたのに更新履歴を書き忘れると、ここで落ちる。
        assertEquals(BuildConfig.VERSION_NAME, ReleaseNotes.all.first().version)
    }

    @Test
    fun `一覧は版番号の降順(新しい順)に並んでいる`() {
        val versions = ReleaseNotes.all.map { it.version }
        val sortedDescending = versions.sortedWith(compareByDescending { parseVersion(it) })
        assertEquals(sortedDescending, versions)
    }

    @Test
    fun `版番号に重複が無い`() {
        val versions = ReleaseNotes.all.map { it.version }
        assertEquals(versions.size, versions.distinct().size)
    }

    @Test
    fun `本文は空でない`() {
        assertTrue(ReleaseNotes.all.all { it.body.isNotBlank() })
    }

    /**
     * "1.10" > "1.9" のような比較を文字列比較ではなく数値として行うための変換。
     * メジャー・マイナーの2要素構成(例: "1.10")を前提とする。
     */
    private fun parseVersion(version: String): List<Int> =
        version.split(".").map { it.toInt() }
}
