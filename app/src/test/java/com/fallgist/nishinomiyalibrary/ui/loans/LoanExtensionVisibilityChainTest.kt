package com.fallgist.nishinomiyalibrary.ui.loans

import com.fallgist.nishinomiyalibrary.data.remote.licsxp.parser.LoanListParser
import com.fallgist.nishinomiyalibrary.data.repository.toDomain
import com.fallgist.nishinomiyalibrary.data.repository.toEntity
import com.fallgist.nishinomiyalibrary.domain.model.Member
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 実サイト由来フィクスチャ(`usrlend.html`)から延長ボタンの表示までを**通しで**固定する。
 *
 * 実機で「実サイトでは延長できるのにアプリに延長ボタンが出ない」事象が報告されたため追加した
 * (2026-08-05)。パーサ単体・UI組み立て単体はそれぞれ既にテストされていたが、
 * **同期→Room往復→表示行の組み立て までの経路全体を通したテストが無く、
 * どこか1箇所で`extendable`や`tilcod`が落ちても検出できない状態だった**
 * (`docs/handoff.md`進行指示15と同型の「経路をまたぐと見えなくなる欠陥」)。
 */
class LoanExtensionVisibilityChainTest {
    private val memberId = 7L
    private val member = Member(id = memberId, name = "テスト", colorHex = "#112233", cardNumber = "x", sortOrder = 0)

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader).getResource("fixtures/$name")!!.readText()

    /** パース → Roomエンティティ往復 → 表示行、の全経路で延長ボタンの表示条件が保たれること。 */
    @Test
    fun `実サイトフィクスチャからRoom往復を経ても延長ボタンの表示条件が保たれる`() {
        val parsed = LoanListParser.parse(fixture("usrlend.html"), memberId)
        // 前提: フィクスチャには延長ボタンあり7件・なし5件が含まれる。
        assertEquals(7, parsed.count { it.extendable })

        // 同期はloansをdeleteForMember+insertAllで入れ替える。その往復でフラグが落ちないこと。
        val roundTripped = parsed.map { it.toEntity(memberId).toDomain() }
        assertEquals(7, roundTripped.count { it.extendable })
        assertTrue(roundTripped.all { it.tilcod.isNotBlank() })

        val rows = LoansContentBuilder.build(
            members = listOf(member),
            loans = roundTripped,
            selectedMemberId = null,
            today = LocalDate.of(2026, 7, 1),
        )
        assertEquals(12, rows.size)
        // 延長ボタンの表示条件はcanExtend(extendable && tilcodが空でない)に集約されている。
        assertEquals(7, rows.count { it.canExtend })
    }

    /**
     * `tilcod`が取れない行では延長ボタンを出さない(§9.1: 対象を特定できないまま送信させない)。
     * この分岐は「実機でボタンが出ない」原因の候補でもあるため、明示的に固定する。
     */
    @Test
    fun `tilcodが空の行はextendableでも延長ボタンを出さない`() {
        val parsed = LoanListParser.parse(fixture("usrlend.html"), memberId)
        val extendableLoan = parsed.first { it.extendable }
        val rows = LoansContentBuilder.build(
            members = listOf(member),
            loans = listOf(extendableLoan.copy(tilcod = "")),
            selectedMemberId = null,
            today = LocalDate.of(2026, 7, 1),
        )
        assertEquals(1, rows.size)
        assertTrue(rows.single().extendable)
        assertEquals(false, rows.single().canExtend)
    }
}
