package com.fallgist.nishinomiyalibrary.data.repository

import android.content.Context
import androidx.room.Room
import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.entity.LoanEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LoanExtensionGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LoanExtensionSession
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionOutcome
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionTarget
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionItemResult
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [LoanExtensionRepositoryImpl]のテスト。`docs/design/loan-extension.md` §9.1の層分離要件
 * (Gateway/Repositoryが画面種別に依存しない、UI層がrenewalCodeを保持しない)もここで固定する。
 */
@RunWith(RobolectricTestRunner::class)
class LoanExtensionRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var credentials: CredentialStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        credentials = CredentialStore(context)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `延長成功はExtendedをそのまま返す`() = runBlocking {
        val member = addMember("延長成功", "1")
        val newDueDate = LocalDate.of(2026, 8, 19)
        val repository = repository(fakeGateway { LoanExtensionOutcome.Extended(newDueDate) })

        val outcome = repository.extendLoan(LoanExtensionTarget(member, "tilcod-1"))

        assertEquals(LoanExtensionOutcome.Extended(newDueDate), outcome)
    }

    // ------------------------------------------------------------------
    // 一斉延長(`docs/design/bulk-selection.md` §5.1・§8.1)
    // ------------------------------------------------------------------

    @Test
    fun `extendLoansは空リストなら通信せず空の結果を返す`() = runBlocking {
        val repository = repository(
            object : LoanExtensionGateway {
                override suspend fun openAuthenticatedSession(cardNumber: String, password: String): LoanExtensionSession =
                    error("呼ばれてはならない")
            },
        )

        val result = repository.extendLoans(emptyList())

        assertEquals(LoanExtensionBatchResult(emptyList()), result)
    }

    @Test
    fun `extendLoansは1件がFailureでも後続が実行され件ごとの結果が返る`() = runBlocking {
        val ok = addMember("一斉延長成功", "bulk-ok")
        val missing = 999_999L
        val ok2 = addMember("一斉延長成功2", "bulk-ok-2")
        val newDueDate = LocalDate.of(2026, 8, 19)
        val repository = repository(fakeGateway { LoanExtensionOutcome.Extended(newDueDate) })

        val targets = listOf(
            LoanExtensionTarget(ok, "tilcod-bulk-1"),
            // 会員が存在しないためFailure(AUTH)になるが、後続の処理は続行される。
            LoanExtensionTarget(missing, "tilcod-bulk-2"),
            LoanExtensionTarget(ok2, "tilcod-bulk-3"),
        )

        val result = repository.extendLoans(targets)

        assertEquals(
            listOf(
                LoanExtensionItemResult(targets[0], LoanExtensionOutcome.Extended(newDueDate)),
                LoanExtensionItemResult(targets[1], LoanExtensionOutcome.Failure(FailureReason.AUTH)),
                LoanExtensionItemResult(targets[2], LoanExtensionOutcome.Extended(newDueDate)),
            ),
            result.items,
        )
    }

    @Test
    fun `extendLoansは対象を渡した順にGatewayへ送る`() = runBlocking {
        val member = addMember("順序確認", "bulk-order")
        val received = mutableListOf<String>()
        val repository = repository(
            fakeGateway { tilcod ->
                received += tilcod
                LoanExtensionOutcome.Unknown
            },
        )

        repository.extendLoans(
            listOf(
                LoanExtensionTarget(member, "order-1"),
                LoanExtensionTarget(member, "order-2"),
                LoanExtensionTarget(member, "order-3"),
            ),
        )

        assertEquals(listOf("order-1", "order-2", "order-3"), received)
    }

    @Test
    fun `Unknownはそのまま返す`() = runBlocking {
        val member = addMember("成否不明", "2")
        val repository = repository(fakeGateway { LoanExtensionOutcome.Unknown })

        val outcome = repository.extendLoan(LoanExtensionTarget(member, "tilcod-2"))

        assertEquals(LoanExtensionOutcome.Unknown, outcome)
    }

    @Test
    fun `会員が存在しなければAUTHになりGatewayを呼ばない`() = runBlocking {
        var opened = false
        val repository = repository(
            object : LoanExtensionGateway {
                override suspend fun openAuthenticatedSession(cardNumber: String, password: String): LoanExtensionSession {
                    opened = true
                    error("呼ばれてはならない")
                }
            },
        )

        val outcome = repository.extendLoan(LoanExtensionTarget(999_999, "tilcod-missing"))

        assertEquals(LoanExtensionOutcome.Failure(FailureReason.AUTH), outcome)
        assertTrue(!opened)
    }

    @Test
    fun `パスワード未保存はAUTHになりGatewayを呼ばない`() = runBlocking {
        val id = database.memberDao().insert(MemberEntity(name = "パスワード無し", colorHex = "#000000", cardNumber = "3", sortOrder = 0))
        var opened = false
        val repository = repository(
            object : LoanExtensionGateway {
                override suspend fun openAuthenticatedSession(cardNumber: String, password: String): LoanExtensionSession {
                    opened = true
                    error("呼ばれてはならない")
                }
            },
        )

        val outcome = repository.extendLoan(LoanExtensionTarget(id, "tilcod-nopass"))

        assertEquals(LoanExtensionOutcome.Failure(FailureReason.AUTH), outcome)
        assertTrue(!opened)
    }

    @Test
    fun `openAuthenticatedSessionの例外をFailureへ変換する`() = runBlocking {
        val cases = mapOf(
            LibraryError.Auth(null) to FailureReason.AUTH,
            LibraryError.Parse("screen", "reason") to FailureReason.SITE_RESPONSE_CHANGED,
            LibraryError.Maintenance() to FailureReason.SITE_MAINTENANCE,
        )
        for ((exception, reason) in cases) {
            val member = addMember("open失敗-$reason", "open-$reason")
            val repository = repository(
                object : LoanExtensionGateway {
                    override suspend fun openAuthenticatedSession(cardNumber: String, password: String): LoanExtensionSession =
                        throw exception
                },
            )
            val outcome = repository.extendLoan(LoanExtensionTarget(member, "tilcod-open"))
            assertEquals(LoanExtensionOutcome.Failure(reason), outcome)
        }
    }

    @Test
    fun `extendLoanの例外をFailureへ変換しセッションを必ず閉じる`() = runBlocking {
        val member = addMember("extend失敗", "extend-fail")
        var closed = false
        val repository = repository(
            object : LoanExtensionGateway {
                override suspend fun openAuthenticatedSession(cardNumber: String, password: String): LoanExtensionSession =
                    object : LoanExtensionSession {
                        override suspend fun extendLoan(tilcod: String): LoanExtensionOutcome =
                            throw LibraryError.Network(java.io.IOException("切断"))
                        override fun close() {
                            closed = true
                        }
                    }
            },
        )

        val outcome = repository.extendLoan(LoanExtensionTarget(member, "tilcod-network"))

        assertEquals(LoanExtensionOutcome.Failure(FailureReason.NETWORK), outcome)
        assertTrue(closed)
    }

    @Test
    fun `対象のtilcodをセッションへそのまま渡す`() = runBlocking {
        val member = addMember("対象伝達", "target-relay")
        val received = mutableListOf<String>()
        val repository = repository(
            fakeGateway { tilcod ->
                received += tilcod
                LoanExtensionOutcome.Unknown
            },
        )

        repository.extendLoan(LoanExtensionTarget(member, "tilcod-relay"))

        assertEquals(listOf("tilcod-relay"), received)
    }

    @Test
    fun `二重タップ相当の同時呼び出しはセッションを直列に開く`() = runBlocking {
        val member = addMember("直列化", "serial")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var openCount = 0
        val order = mutableListOf<String>()
        val repository = repository(
            object : LoanExtensionGateway {
                override suspend fun openAuthenticatedSession(cardNumber: String, password: String): LoanExtensionSession {
                    val call = openCount++
                    order += "open-$call"
                    if (call == 0) {
                        started.complete(Unit)
                        release.await()
                    }
                    return object : LoanExtensionSession {
                        override suspend fun extendLoan(tilcod: String): LoanExtensionOutcome = LoanExtensionOutcome.Unknown
                        override fun close() = Unit
                    }
                }
            },
        )

        val first = async { repository.extendLoan(LoanExtensionTarget(member, "tilcod-a")) }
        started.await()
        val second = async { repository.extendLoan(LoanExtensionTarget(member, "tilcod-b")) }
        // 2件目が(ロック待ちではなく)実際に走り始める機会を与えたうえで検査する。
        yield()
        yield()
        // 2件目は1件目のGateway呼び出しが終わるまで開始されない(専用Mutexによる直列化)。
        assertEquals(listOf("open-0"), order)
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("open-0", "open-1"), order)
    }

    // ------------------------------------------------------------------
    // §6.1: 延長成功後のローカル反映
    // ------------------------------------------------------------------

    @Test
    fun `Extendedのとき対象行のdueDateとextendableを更新する`() = runBlocking {
        val member = addMember("ローカル反映", "local-1")
        val oldDueDate = LocalDate.of(2026, 8, 5)
        val newDueDate = LocalDate.of(2026, 8, 19)
        database.loanDao().insert(loanEntity(member, "tilcod-local", oldDueDate, extendable = true))
        val repository = repository(fakeGateway { LoanExtensionOutcome.Extended(newDueDate) })

        repository.extendLoan(LoanExtensionTarget(member, "tilcod-local"))

        val updated = database.loanDao().getAll().single { it.tilcod == "tilcod-local" }
        assertEquals(newDueDate, updated.dueDate)
        assertEquals(false, updated.extendable)
    }

    @Test
    fun `対象行が1件でなければExtendedでも更新しない`() = runBlocking {
        val member = addMember("重複行", "local-2")
        val oldDueDate = LocalDate.of(2026, 8, 5)
        val newDueDate = LocalDate.of(2026, 8, 19)
        database.loanDao().insert(loanEntity(member, "tilcod-dup", oldDueDate, extendable = true))
        database.loanDao().insert(loanEntity(member, "tilcod-dup", oldDueDate, extendable = true))
        val repository = repository(fakeGateway { LoanExtensionOutcome.Extended(newDueDate) })

        repository.extendLoan(LoanExtensionTarget(member, "tilcod-dup"))

        val rows = database.loanDao().getAll().filter { it.tilcod == "tilcod-dup" }
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.dueDate == oldDueDate })
        assertTrue(rows.all { it.extendable })
    }

    @Test
    fun `Unknownでは対象行を一切更新しない`() = runBlocking {
        val member = addMember("Unknown反映無し", "local-3")
        val oldDueDate = LocalDate.of(2026, 8, 5)
        database.loanDao().insert(loanEntity(member, "tilcod-unknown", oldDueDate, extendable = true))
        val repository = repository(fakeGateway { LoanExtensionOutcome.Unknown })

        repository.extendLoan(LoanExtensionTarget(member, "tilcod-unknown"))

        val row = database.loanDao().getAll().single { it.tilcod == "tilcod-unknown" }
        assertEquals(oldDueDate, row.dueDate)
        assertTrue(row.extendable)
    }

    @Test
    fun `Failureでは対象行を一切更新しない`() = runBlocking {
        val member = addMember("Failure反映無し", "local-4")
        val oldDueDate = LocalDate.of(2026, 8, 5)
        database.loanDao().insert(loanEntity(member, "tilcod-failure", oldDueDate, extendable = true))
        val repository = repository(
            object : LoanExtensionGateway {
                override suspend fun openAuthenticatedSession(cardNumber: String, password: String): LoanExtensionSession =
                    throw LibraryError.Maintenance()
            },
        )

        repository.extendLoan(LoanExtensionTarget(member, "tilcod-failure"))

        val row = database.loanDao().getAll().single { it.tilcod == "tilcod-failure" }
        assertEquals(oldDueDate, row.dueDate)
        assertTrue(row.extendable)
    }

    private fun loanEntity(memberId: Long, tilcod: String, dueDate: LocalDate, extendable: Boolean) = LoanEntity(
        memberId = memberId,
        title = "資料-$tilcod",
        materialType = "本",
        lendingLibrary = "本館",
        loanDate = dueDate.minusDays(14),
        dueDate = dueDate,
        status = "貸出中",
        tilcod = tilcod,
        extendable = extendable,
    )

    // ------------------------------------------------------------------
    // 層分離(設計 §9.1)の構造テスト
    // ------------------------------------------------------------------

    @Test
    fun `LoanExtensionTargetはmemberIdとtilcodだけを持ちrenewalCodeを保持しない`() {
        val fields = LoanExtensionTarget::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name == "\$stable" }
            .map { it.name }
        assertEquals(setOf("memberId", "tilcod"), fields.toSet())
    }

    @Test
    fun `LoanExtensionSession_extendLoanはtilcodだけを受け取り画面種別の引数を持たない`() {
        // suspend funはバイトコード上、末尾にContinuationが追加される。それ以外の実引数がString1個だけであることを確認する。
        val method = LoanExtensionSession::class.java.methods.single { it.name == "extendLoan" }
        assertEquals(listOf(String::class.java), method.parameterTypes.dropLast(1))
    }

    @Test
    fun `LoanExtensionOutcomeは画面依存の情報を持たない`() {
        // Extendedは新しい返却期日だけ、Failureは既存のFailureReasonだけを持つ。HTML本文や資料名は持たない。
        val extendedFields = LoanExtensionOutcome.Extended::class.java.declaredFields.filterNot { it.isSynthetic || it.name == "\$stable" }.map { it.name }
        assertEquals(setOf("newDueDate"), extendedFields.toSet())
        val failureFields = LoanExtensionOutcome.Failure::class.java.declaredFields.filterNot { it.isSynthetic || it.name == "\$stable" }.map { it.name }
        assertEquals(setOf("reason"), failureFields.toSet())
    }

    private suspend fun addMember(name: String, cardNumber: String): Long {
        val id = database.memberDao().insert(MemberEntity(name = name, colorHex = "#000000", cardNumber = cardNumber, sortOrder = 0))
        credentials.savePassword(id, "password")
        return id
    }

    private fun repository(gateway: LoanExtensionGateway) =
        LoanExtensionRepositoryImpl(database.memberDao(), credentials, gateway, database.loanDao())

    private fun fakeGateway(extend: suspend (tilcod: String) -> LoanExtensionOutcome): LoanExtensionGateway =
        object : LoanExtensionGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): LoanExtensionSession =
                object : LoanExtensionSession {
                    override suspend fun extendLoan(tilcod: String): LoanExtensionOutcome = extend(tilcod)
                    override fun close() = Unit
                }
        }
}
