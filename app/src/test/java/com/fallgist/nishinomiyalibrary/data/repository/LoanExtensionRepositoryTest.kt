package com.fallgist.nishinomiyalibrary.data.repository

import android.content.Context
import androidx.room.Room
import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LoanExtensionGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LoanExtensionSession
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionOutcome
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionTarget
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
        LoanExtensionRepositoryImpl(database.memberDao(), credentials, gateway)

    private fun fakeGateway(extend: suspend (tilcod: String) -> LoanExtensionOutcome): LoanExtensionGateway =
        object : LoanExtensionGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): LoanExtensionSession =
                object : LoanExtensionSession {
                    override suspend fun extendLoan(tilcod: String): LoanExtensionOutcome = extend(tilcod)
                    override fun close() = Unit
                }
        }
}
