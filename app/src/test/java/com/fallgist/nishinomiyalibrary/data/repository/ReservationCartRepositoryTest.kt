package com.fallgist.nishinomiyalibrary.data.repository

import android.content.Context
import androidx.room.Room
import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationCartItemEntity
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.DirectReservationAttempt
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationSession
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationConfirmation
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.ReservationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReservationCartRepositoryTest {
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
    fun `バッチはメンバー毎に一回認証照合し成功だけカートから削除する`() = runBlocking {
        val first = database.memberDao().insert(MemberEntity(name = "一人目", colorHex = "#000000", cardNumber = "1", sortOrder = 0))
        val second = database.memberDao().insert(MemberEntity(name = "二人目", colorHex = "#111111", cardNumber = "2", sortOrder = 1))
        credentials.savePassword(first, "pw1")
        credentials.savePassword(second, "pw2")
        val gateway = FakeGateway(mapOf("ok" to DirectReservationAttempt.Submitted, "bad" to DirectReservationAttempt.RejectedBeforeSubmit))
        val repository = ReservationCartRepositoryImpl(
            database, database.reservationCartDao(), database.memberDao(), credentials, gateway,
            Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC),
        )
        repository.addToCart(ReservationTarget(null, first, "ok", "成功"))
        repository.addToCart(ReservationTarget(null, second, "bad", "失敗"))

        val result = repository.confirmCart(ReservationConfirmation("106", 1000))

        assertEquals(2, gateway.openCount)
        assertEquals(1, gateway.fetchCount)
        assertEquals(ReservationOutcome.Success, result.members[0].itemResults.single().outcome)
        assertTrue(result.members[1].itemResults.single().outcome is ReservationOutcome.Failure)
        assertEquals(listOf("bad"), repository.cartItems().first().map { it.tilcod })
    }

    @Test
    fun `認証失敗は当該メンバーだけを失敗にし他メンバーを続行する`() = runBlocking {
        val good = addMember("良", "1")
        val bad = addMember("悪", "2")
        val gateway = object : ReservationGateway {
            var opens = 0
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession {
                opens++
                if (cardNumber == "2") throw LibraryError.Auth(null)
                return successfulSession("good")
            }
        }
        val repository = repository(gateway)
        repository.addToCart(ReservationTarget(null, good, "good", "成功"))
        repository.addToCart(ReservationTarget(null, bad, "bad", "認証失敗"))
        val result = repository.confirmCart(ReservationConfirmation("106", 1000))
        assertEquals(2, gateway.opens)
        assertEquals(ReservationOutcome.Success, result.members[0].itemResults.single().outcome)
        assertTrue(result.members[1].itemResults.single().outcome is ReservationOutcome.Failure)
        assertEquals(listOf("bad"), repository.cartItems().first().map { it.tilcod })
    }

    @Test
    fun `二度目のセッション切れは一回だけ再認証して残件を停止する`() = runBlocking {
        val member = addMember("切れ", "3")
        val gateway = object : ReservationGateway {
            var opens = 0
            var directs = 0
            var fetches = 0
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession {
                opens++
                return object : ReservationSession {
                    override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt {
                        directs++
                        return DirectReservationAttempt.SessionExpiredBeforeSubmit
                    }
                    override suspend fun fetchReservations(): List<Reservation> { fetches++; return emptyList() }
                    override fun close() = Unit
                }
            }
        }
        val repository = repository(gateway)
        repository.addToCart(ReservationTarget(null, member, "first", "一冊目"))
        repository.addToCart(ReservationTarget(null, member, "second", "二冊目"))
        val result = repository.confirmCart(ReservationConfirmation("106", 1000))
        assertEquals(2, gateway.opens)
        assertEquals(2, gateway.directs)
        assertEquals(0, gateway.fetches)
        assertTrue(result.members.single().itemResults.all { it.outcome is ReservationOutcome.Failure })
        assertEquals(listOf("first", "second"), repository.cartItems().first().map { it.tilcod })
    }

    @Test
    fun `即時予約は既存カートを変更しない`() = runBlocking {
        val member = addMember("即時", "4")
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession = successfulSession("now")
        })
        repository.addToCart(ReservationTarget(null, member, "cart", "カート"))
        repository.reserveNow(ReservationTarget(null, member, "now", "即時"), ReservationConfirmation("106", 1000))
        assertEquals(listOf("cart"), repository.cartItems().first().map { it.tilcod })
    }

    @Test
    fun `重複は照合で達成済みとなり不明は照合不能ならカートに残る`() = runBlocking {
        val member = addMember("照合", "5")
        val gateway = object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession = object : ReservationSession {
                override suspend fun directReserve(tilcod: String, pickupLibraryCode: String) = when (tilcod) {
                    "dup" -> DirectReservationAttempt.DuplicateDetected
                    else -> DirectReservationAttempt.IndeterminateAfterPost
                }
                override suspend fun fetchReservations(): List<Reservation> = listOf(
                    Reservation(0, "dup", "", "", LocalDate.of(2030, 1, 1), null, ReservationState.WAITING, null, "dup"),
                )
                override fun close() = Unit
            }
        }
        val repository = repository(gateway)
        repository.addToCart(ReservationTarget(null, member, "dup", "重複"))
        repository.addToCart(ReservationTarget(null, member, "unknown", "不明"))
        val result = repository.confirmCart(ReservationConfirmation("106", 1000))
        assertEquals(ReservationOutcome.AlreadyReserved, result.members.single().itemResults[0].outcome)
        assertTrue(result.members.single().itemResults[1].outcome is ReservationOutcome.Unknown)
        assertEquals(listOf("unknown"), repository.cartItems().first().map { it.tilcod })
    }

    @Test
    fun `POST後不明は再送せず後続を止め一覧を一回だけ取得する`() = runBlocking {
        val member = addMember("不明", "6")
        var directs = 0; var fetches = 0
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = object : ReservationSession {
                override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt { directs++; return DirectReservationAttempt.IndeterminateAfterPost }
                override suspend fun fetchReservations(): List<Reservation> { fetches++; return emptyList() }
                override fun close() = Unit
            }
        })
        repository.addToCart(ReservationTarget(null, member, "one", "一")); repository.addToCart(ReservationTarget(null, member, "two", "二"))
        val result = repository.confirmCart(ReservationConfirmation("106", 1000))
        assertEquals(1, directs); assertEquals(1, fetches)
        assertTrue(result.members.single().itemResults[0].outcome is ReservationOutcome.Unknown)
        assertEquals(listOf("one", "two"), repository.cartItems().first().map { it.tilcod })
    }

    @Test
    fun `POST後不明でも逐次照合で確認できれば同じsessionで次資料へ進む`() = runBlocking {
        val member = addMember("逐次", "10")
        val sent = mutableListOf<String>(); var fetches = 0
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = object : ReservationSession {
                override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt { sent += tilcod; return DirectReservationAttempt.IndeterminateAfterPost }
                override suspend fun fetchReservations(): List<Reservation> {
                    fetches++
                    return sent.map { tilcod -> Reservation(0, tilcod, "", "", LocalDate.of(2030, 1, 1), null, ReservationState.WAITING, null, tilcod) }
                }
                override fun close() = Unit
            }
        })
        repository.addToCart(ReservationTarget(null, member, "first", "一")); repository.addToCart(ReservationTarget(null, member, "second", "二"))
        val result = repository.confirmCart(ReservationConfirmation("106", 1000))
        assertEquals(listOf("first", "second"), sent); assertEquals(2, fetches)
        assertTrue(result.members.single().itemResults.all { it.outcome == ReservationOutcome.Success })
        assertTrue(repository.cartItems().first().isEmpty())
    }

    @Test
    fun `逐次照合の取得失敗はUnknownとして残件を止める`() = runBlocking {
        val member = addMember("逐次不可", "11")
        var directs = 0; var fetches = 0
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = object : ReservationSession {
                override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt { directs++; return DirectReservationAttempt.IndeterminateAfterPost }
                override suspend fun fetchReservations(): List<Reservation> { fetches++; throw IllegalStateException("offline") }
                override fun close() = Unit
            }
        })
        repository.addToCart(ReservationTarget(null, member, "first", "一")); repository.addToCart(ReservationTarget(null, member, "second", "二"))
        val result = repository.confirmCart(ReservationConfirmation("106", 1000))
        assertEquals(1, directs); assertEquals(1, fetches)
        assertEquals(ReservationOutcome.Unknown(com.fallgist.nishinomiyalibrary.domain.model.UnknownReason.VERIFICATION_UNAVAILABLE), result.members.single().itemResults[0].outcome)
        assertTrue(result.members.single().itemResults[1].outcome is ReservationOutcome.Failure)
    }

    @Test
    fun `再認証後のPOST後不明も即時照合して同一sessionで次資料へ進む`() = runBlocking {
        val member = addMember("再認証照合", "12")
        var opens = 0; var firstSessionCalls = 0; var retryPostCalls = 0; var fetches = 0
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession {
                opens++
                if (opens == 1) return object : ReservationSession {
                    override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt { firstSessionCalls++; return DirectReservationAttempt.SessionExpiredBeforeSubmit }
                    override suspend fun fetchReservations(): List<Reservation> = emptyList()
                    override fun close() = Unit
                }
                val posted = mutableListOf<String>()
                return object : ReservationSession {
                    override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt { retryPostCalls++; posted += tilcod; return DirectReservationAttempt.IndeterminateAfterPost }
                    override suspend fun fetchReservations(): List<Reservation> { fetches++; return posted.map { tilcod -> Reservation(0, tilcod, "", "", LocalDate.of(2030, 1, 1), null, ReservationState.WAITING, null, tilcod) } }
                    override fun close() = Unit
                }
            }
        })
        repository.addToCart(ReservationTarget(null, member, "first", "一")); repository.addToCart(ReservationTarget(null, member, "second", "二"))
        val result = repository.confirmCart(ReservationConfirmation("106", 1000))
        // firstSessionCalls は確認GET段階の切れを模した一回、retryPostCalls は確定POST相当の二回。
        assertEquals(2, opens); assertEquals(1, firstSessionCalls); assertEquals(2, retryPostCalls); assertEquals(2, fetches)
        assertTrue(result.members.single().itemResults.all { it.outcome == ReservationOutcome.Success })
        assertTrue(repository.cartItems().first().isEmpty())
    }

    @Test
    fun `照合不能なら送信済みをUnknownとして残す`() = runBlocking {
        val member = addMember("照合不可", "7")
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = object : ReservationSession {
                override suspend fun directReserve(tilcod: String, pickupLibraryCode: String) = DirectReservationAttempt.Submitted
                override suspend fun fetchReservations(): List<Reservation> = throw IllegalStateException("offline")
                override fun close() = Unit
            }
        })
        repository.addToCart(ReservationTarget(null, member, "sent", "送信"))
        val result = repository.confirmCart(ReservationConfirmation("106", 1000))
        assertEquals(ReservationOutcome.Unknown(com.fallgist.nishinomiyalibrary.domain.model.UnknownReason.VERIFICATION_UNAVAILABLE), result.members.single().itemResults.single().outcome)
        assertEquals(listOf("sent"), repository.cartItems().first().map { it.tilcod })
    }

    @Test
    fun `CancellationExceptionは握り潰さない`() = runBlocking {
        val member = addMember("取消", "8")
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession = throw CancellationException("cancel")
        })
        repository.addToCart(ReservationTarget(null, member, "cancel", "取消"))
        var cancelled = false
        try { repository.confirmCart(ReservationConfirmation("106", 1000)) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test
    fun `資格情報未設定メンバーはGatewayを開かずAUTHで残し正常メンバーを継続する`() = runBlocking {
        val missing = database.memberDao().insert(MemberEntity(name = "未設定", colorHex = "#000000", cardNumber = "missing", sortOrder = 0))
        val good = addMember("正常", "good")
        var openCount = 0
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession {
                openCount++
                return successfulSession("good-book")
            }
        })
        repository.addToCart(ReservationTarget(null, missing, "missing-book", "未設定"))
        repository.addToCart(ReservationTarget(null, good, "good-book", "正常"))

        val result = repository.confirmCart(ReservationConfirmation("106", 1000))

        assertEquals(1, openCount)
        assertEquals(ReservationOutcome.Failure(com.fallgist.nishinomiyalibrary.domain.model.FailureReason.AUTH), result.members[0].itemResults.single().outcome)
        assertEquals(ReservationOutcome.Success, result.members[1].itemResults.single().outcome)
        assertEquals(listOf("missing-book"), repository.cartItems().first().map { it.tilcod })
    }

    @Test
    fun `確定開始後に追加されたカート項目は開始時スナップショットに含めない`() = runBlocking {
        val member = addMember("追加中", "9")
        val sent = mutableListOf<String>()
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession = object : ReservationSession {
                override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt {
                    sent += tilcod
                    database.reservationCartDao().insertIgnoreDuplicate(
                        ReservationCartItemEntity(memberId = member, tilcod = "added-during-confirm", title = "後から追加", writerLine = null, addedAtEpochMillis = 2000),
                    )
                    return DirectReservationAttempt.Submitted
                }
                override suspend fun fetchReservations(): List<Reservation> = listOf(
                    Reservation(0, "initial", "", "", LocalDate.of(2030, 1, 1), null, ReservationState.WAITING, null, "initial"),
                )
                override fun close() = Unit
            }
        })
        repository.addToCart(ReservationTarget(null, member, "initial", "開始時"))

        repository.confirmCart(ReservationConfirmation("106", 1000))

        assertEquals(listOf("initial"), sent)
        assertEquals(listOf("added-during-confirm"), repository.cartItems().first().map { it.tilcod })
    }

    @Test
    fun `予約画面解析失敗は当該資料に原因を示し後続を中止する`() = runBlocking {
        val member = addMember("解析失敗", "parse")
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = object : ReservationSession {
                override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt =
                    throw LibraryError.Parse("reservation-confirm", "fixture")
                override suspend fun fetchReservations(): List<Reservation> = emptyList()
                override fun close() = Unit
            }
        })
        repository.addToCart(ReservationTarget(null, member, "first", "一冊目"))
        repository.addToCart(ReservationTarget(null, member, "second", "二冊目"))

        val result = repository.confirmCart(ReservationConfirmation("106", 1000))

        assertEquals(
            ReservationOutcome.Failure(FailureReason.SITE_RESPONSE_CHANGED),
            result.members.single().itemResults[0].outcome,
        )
        assertEquals(
            ReservationOutcome.Failure(FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE),
            result.members.single().itemResults[1].outcome,
        )
    }

    @Test
    fun `予約処理の保守中と通信失敗を区別する`() = runBlocking {
        val cases = listOf(
            LibraryError.Maintenance() to FailureReason.SITE_MAINTENANCE,
            LibraryError.Network(IllegalStateException("offline")) to FailureReason.NETWORK,
        )
        cases.forEachIndexed { index, (error, expected) ->
            val member = addMember("失敗$index", "error-$index")
            val repository = repository(object : ReservationGateway {
                override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = object : ReservationSession {
                    override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt = throw error
                    override suspend fun fetchReservations(): List<Reservation> = emptyList()
                    override fun close() = Unit
                }
            })

            val result = repository.reserveNow(
                ReservationTarget(null, member, "book-$index", "資料"),
                ReservationConfirmation("106", 1000),
            )

            assertEquals(ReservationOutcome.Failure(expected), result.members.single().itemResults.single().outcome)
        }
    }

    private suspend fun addMember(name: String, card: String): Long {
        val id = database.memberDao().insert(MemberEntity(name = name, colorHex = "#000000", cardNumber = card, sortOrder = 0))
        credentials.savePassword(id, "pw")
        return id
    }

    private fun repository(gateway: ReservationGateway) = ReservationCartRepositoryImpl(
        database, database.reservationCartDao(), database.memberDao(), credentials, gateway,
        Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC),
    )

    private fun successfulSession(vararg submittedTilcods: String): ReservationSession = object : ReservationSession {
        override suspend fun directReserve(tilcod: String, pickupLibraryCode: String) = DirectReservationAttempt.Submitted
        override suspend fun fetchReservations(): List<Reservation> = submittedTilcods.map { tilcod ->
            Reservation(0, tilcod, "", "", LocalDate.of(2030, 1, 1), null, ReservationState.WAITING, null, tilcod)
        }
        override fun close() = Unit
    }

    private class FakeGateway(private val attempts: Map<String, DirectReservationAttempt>) : ReservationGateway {
        var openCount = 0
        var fetchCount = 0
        override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession {
            openCount++
            return object : ReservationSession {
                private val submitted = mutableSetOf<String>()
                override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt =
                    attempts.getValue(tilcod).also { if (it == DirectReservationAttempt.Submitted) submitted += tilcod }
                override suspend fun fetchReservations(): List<Reservation> {
                    fetchCount++
                    return submitted.map { tilcod ->
                        Reservation(0, tilcod, "", "", LocalDate.of(2030, 1, 1), null, ReservationState.WAITING, null, tilcod)
                    }
                }
                override fun close() = Unit
            }
        }
    }
}
