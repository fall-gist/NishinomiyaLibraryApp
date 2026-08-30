package com.fallgist.nishinomiyalibrary.data.repository

import android.content.Context
import androidx.room.Room
import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationCartItemEntity
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.DirectReservationAttempt
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationListSnapshot
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationSession
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationSnapshotSource
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.ReservationWriteBoundaryAware
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationConfirmation
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.ReservationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.ReservationPickupSubmissionOrigin
import com.fallgist.nishinomiyalibrary.domain.model.ReservationState
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartAddSummary
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ------------------------------------------------------------------
    // 一斉カート追加(`docs/design/bulk-selection.md` §7.1・§8.1・§10-2)
    // ------------------------------------------------------------------

    @Test
    fun `一斉カート追加は空リストなら通信も追加もせず0件0件を返す`() = runBlocking {
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = error("呼ばれてはならない")
        })

        val summary = repository.addToCart(emptyList())

        assertEquals(ReservationCartAddSummary(added = 0, skipped = 0), summary)
    }

    @Test
    fun `一斉カート追加は重複を含む入力でaddedとskippedが正しく数えられる`() = runBlocking {
        val member = addMember("一斉追加", "bulk-add")
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = error("呼ばれてはならない")
        })
        repository.addToCart(ReservationTarget(null, member, "dup", "既存"))

        val summary = repository.addToCart(
            listOf(
                ReservationTarget(null, member, "dup", "既存(重複)"),
                ReservationTarget(null, member, "new-1", "新規1"),
                ReservationTarget(null, member, "new-2", "新規2"),
            ),
        )

        assertEquals(ReservationCartAddSummary(added = 2, skipped = 1), summary)
        val cartTilcods = database.reservationCartDao().getAll().map { it.tilcod }.toSet()
        assertEquals(setOf("dup", "new-1", "new-2"), cartTilcods)
    }

    @Test
    fun `一斉カート追加は存在しないメンバーをskippedとして扱い残りは追加を続行する(design §10-2)`() = runBlocking {
        val goodMember = addMember("在籍", "bulk-good")
        val missingMemberId = 999_999L
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = error("呼ばれてはならない")
        })

        val summary = repository.addToCart(
            listOf(
                ReservationTarget(null, missingMemberId, "missing-book", "存在しないメンバー"),
                ReservationTarget(null, goodMember, "good-book", "正常"),
            ),
        )

        assertEquals(ReservationCartAddSummary(added = 1, skipped = 1), summary)
        val cartTilcods = database.reservationCartDao().getAll().map { it.tilcod }
        assertEquals(listOf("good-book"), cartTilcods)
    }

    // ------------------------------------------------------------------
    // 一斉直接予約(`docs/design/bulk-selection-followup.md` §5・§7.1・§9-2)
    // ------------------------------------------------------------------

    @Test
    fun `一斉直接予約は複数件を渡すとexecuteへ同じ並びで渡り件ごとの結果が返る`() = runBlocking {
        val first = addMember("一人目", "bulk-reserve-1")
        val second = addMember("二人目", "bulk-reserve-2")
        val sent = mutableListOf<String>()
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = object : ReservationSession {
                override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt {
                    sent += tilcod
                    return DirectReservationAttempt.Submitted
                }
                override suspend fun fetchReservations(): List<Reservation> = sent.map { tilcod ->
                    Reservation(0, tilcod, "", "", LocalDate.of(2030, 1, 1), null, ReservationState.WAITING, null, tilcod)
                }
                override fun close() = Unit
            }
        })

        val result = repository.reserveNow(
            listOf(
                ReservationTarget(null, first, "book-a", "資料A"),
                ReservationTarget(null, first, "book-b", "資料B"),
                ReservationTarget(null, second, "book-c", "資料C"),
            ),
            ReservationConfirmation("106", 1000),
        )

        assertEquals(listOf("book-a", "book-b", "book-c"), sent)
        assertEquals(2, result.members.size)
        assertEquals(listOf("book-a", "book-b"), result.members[0].itemResults.map { it.target.tilcod })
        assertEquals(listOf("book-c"), result.members[1].itemResults.map { it.target.tilcod })
        assertTrue(result.members.flatMap { it.itemResults }.all { it.outcome == ReservationOutcome.Success })
        // カートを経由しない(設計追補§5.2)。カートには何も残らない・追加されない。
        assertTrue(repository.cartItems().first().isEmpty())
    }

    @Test
    fun `一斉直接予約は空リストなら通信せず空の結果を返す(design追補§9-2で決定)`() = runBlocking {
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = error("呼ばれてはならない")
        })

        val result = repository.reserveNow(emptyList(), ReservationConfirmation("106", 1000))

        assertTrue(result.members.isEmpty())
    }

    @Test
    fun `一斉直接予約はcartItemIdを持つ対象を含むと例外にする(既存validateTargetの契約)`() = runBlocking {
        val member = addMember("不正対象", "bulk-reserve-invalid")
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = error("呼ばれてはならない")
        })

        var threw = false
        try {
            repository.reserveNow(
                listOf(ReservationTarget(cartItemId = 1L, memberId = member, tilcod = "x", title = "資料")),
                ReservationConfirmation("106", 1000),
            )
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    // ------------------------------------------------------------------
    // カートの一括削除・「カートを空にする」(`docs/design/bulk-selection.md` §6.1・§8.1)
    // ------------------------------------------------------------------

    @Test
    fun `removeFromCartのList版は指定したidだけを削除する`() = runBlocking {
        val member = addMember("一括削除", "bulk-remove")
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = error("呼ばれてはならない")
        })
        repository.addToCart(ReservationTarget(null, member, "keep", "残す"))
        repository.addToCart(ReservationTarget(null, member, "drop-1", "消す1"))
        repository.addToCart(ReservationTarget(null, member, "drop-2", "消す2"))
        val idsToDelete = database.reservationCartDao().getAll()
            .filter { it.tilcod != "keep" }
            .map { it.id }

        repository.removeFromCart(idsToDelete)

        val remaining = database.reservationCartDao().getAll().map { it.tilcod }
        assertEquals(listOf("keep"), remaining)
    }

    @Test
    fun `removeFromCartのList版は空リストならDAOへ触れず何も削除しない`() = runBlocking {
        val member = addMember("空リスト削除", "bulk-remove-empty")
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = error("呼ばれてはならない")
        })
        repository.addToCart(ReservationTarget(null, member, "keep", "残す"))

        repository.removeFromCart(emptyList())

        assertEquals(listOf("keep"), database.reservationCartDao().getAll().map { it.tilcod })
    }

    @Test
    fun `clearCartは全件削除する`() = runBlocking {
        val member = addMember("空にする", "clear-cart")
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = error("呼ばれてはならない")
        })
        repository.addToCart(ReservationTarget(null, member, "1", "一"))
        repository.addToCart(ReservationTarget(null, member, "2", "二"))
        repository.addToCart(ReservationTarget(null, member, "3", "三"))

        repository.clearCart()

        assertTrue(database.reservationCartDao().getAll().isEmpty())
    }

    @Test
    fun successfulReservationStoresConfirmedPickupSubmission() = runBlocking {
        val memberId = addMember("送信館記録", "confirmed")
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) =
                successfulSession("confirmed-tilcod")
        })

        val result = repository.reserveNow(
            ReservationTarget(null, memberId, "confirmed-tilcod", "確認済み資料"),
            ReservationConfirmation("106", 1000),
        )

        assertEquals(ReservationOutcome.Success, result.members.single().itemResults.single().outcome)
        val submission = database.reservationPickupSubmissionDao().get(memberId, "confirmed-tilcod")
        assertEquals("106", submission?.pickupLibraryCode)
        assertEquals(ReservationPickupSubmissionOrigin.CONFIRMED_SUBMISSION, submission?.origin)
    }

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
    fun `確認画面のまま返り完全な一覧に無ければ拒否とし同一メンバーの次の項目へ進む`() = runBlocking {
        val member = addMember("拒否", "13")
        val sent = mutableListOf<String>()
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) =
                object : ReservationSession, ReservationSnapshotSource {
                    override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt {
                        sent += tilcod
                        return DirectReservationAttempt.StayedOnConfirmation
                    }
                    override suspend fun fetchReservations(): List<Reservation> = emptyList()
                    // サマリ件数と解析行数が一致し、一覧が完全だと確認できた状態を模す。
                    override suspend fun fetchReservationSnapshot() = ReservationListSnapshot(emptyList(), complete = true)
                    override fun close() = Unit
                }
        })
        repository.addToCart(ReservationTarget(null, member, "first", "一")); repository.addToCart(ReservationTarget(null, member, "second", "二"))
        val result = repository.confirmCart(ReservationConfirmation("106", 1000))
        assertEquals(listOf("first", "second"), sent)
        assertEquals(ReservationOutcome.Failure(FailureReason.REJECTED_BY_SITE), result.members.single().itemResults[0].outcome)
        assertEquals(ReservationOutcome.Failure(FailureReason.REJECTED_BY_SITE), result.members.single().itemResults[1].outcome)
    }

    @Test
    fun `確認画面のまま返り一覧の完全性を確認できなければ拒否と断定せず残件を中止する`() = runBlocking {
        val member = addMember("照合不能拒否", "17")
        val sent = mutableListOf<String>()
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) =
                object : ReservationSession, ReservationSnapshotSource {
                    override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt {
                        sent += tilcod
                        return DirectReservationAttempt.StayedOnConfirmation
                    }
                    override suspend fun fetchReservations(): List<Reservation> = emptyList()
                    // サマリ件数と解析行数の不一致・サマリ解析失敗のいずれも complete=false で表現される。
                    override suspend fun fetchReservationSnapshot() = ReservationListSnapshot(emptyList(), complete = false)
                    override fun close() = Unit
                }
        })
        repository.addToCart(ReservationTarget(null, member, "first", "一")); repository.addToCart(ReservationTarget(null, member, "second", "二"))
        val result = repository.confirmCart(ReservationConfirmation("106", 1000))
        assertEquals(listOf("first"), sent)
        assertEquals(
            ReservationOutcome.Unknown(com.fallgist.nishinomiyalibrary.domain.model.UnknownReason.VERIFICATION_UNAVAILABLE),
            result.members.single().itemResults[0].outcome,
        )
        assertEquals(ReservationOutcome.Failure(FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE), result.members.single().itemResults[1].outcome)
    }

    @Test
    fun `上限超過は一覧を取得せず拒否とし同一メンバーの次の項目へ進む`() = runBlocking {
        val member = addMember("上限超過", "18")
        var fetchCount = 0
        val sent = mutableListOf<String>()
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = object : ReservationSession {
                override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt {
                    sent += tilcod
                    return DirectReservationAttempt.LimitExceeded("図書・雑誌は予約制限を1冊越えています。")
                }
                override suspend fun fetchReservations(): List<Reservation> { fetchCount++; return emptyList() }
                override fun close() = Unit
            }
        })
        repository.addToCart(ReservationTarget(null, member, "first", "一")); repository.addToCart(ReservationTarget(null, member, "second", "二"))
        val result = repository.confirmCart(ReservationConfirmation("106", 1000))
        assertEquals(listOf("first", "second"), sent)
        assertEquals(0, fetchCount)
        assertEquals(
            ReservationOutcome.Failure(FailureReason.RESERVATION_LIMIT_EXCEEDED, "図書・雑誌は予約制限を1冊越えています。"),
            result.members.single().itemResults[0].outcome,
        )
        assertEquals(
            ReservationOutcome.Failure(FailureReason.RESERVATION_LIMIT_EXCEEDED, "図書・雑誌は予約制限を1冊越えています。"),
            result.members.single().itemResults[1].outcome,
        )
    }

    @Test
    fun `成功文言でも一覧照合を最終根拠とし対象があれば成功にする`() = runBlocking {
        val member = addMember("成功文言", "19")
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) =
                object : ReservationSession, ReservationSnapshotSource {
                    override suspend fun directReserve(tilcod: String, pickupLibraryCode: String) = DirectReservationAttempt.Registered
                    override suspend fun fetchReservations(): List<Reservation> = listOf(
                        Reservation(0, "found", "", "", LocalDate.of(2030, 1, 1), null, ReservationState.WAITING, null, "found"),
                    )
                    override suspend fun fetchReservationSnapshot() = ReservationListSnapshot(fetchReservations(), complete = true)
                    override fun close() = Unit
                }
        })
        repository.addToCart(ReservationTarget(null, member, "found", "見つかる"))
        val result = repository.confirmCart(ReservationConfirmation("106", 1000))
        assertEquals(ReservationOutcome.Success, result.members.single().itemResults.single().outcome)
    }

    @Test
    fun `確認画面のまま返っても一覧にあれば成功として扱う`() = runBlocking {
        val member = addMember("防御的成功", "14")
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = object : ReservationSession {
                override suspend fun directReserve(tilcod: String, pickupLibraryCode: String) = DirectReservationAttempt.StayedOnConfirmation
                override suspend fun fetchReservations(): List<Reservation> = listOf(
                    Reservation(0, "found", "", "", LocalDate.of(2030, 1, 1), null, ReservationState.WAITING, null, "found"),
                )
                override fun close() = Unit
            }
        })
        repository.addToCart(ReservationTarget(null, member, "found", "見つかる"))
        val result = repository.confirmCart(ReservationConfirmation("106", 1000))
        assertEquals(ReservationOutcome.Success, result.members.single().itemResults.single().outcome)
    }

    @Test
    fun `確認画面のまま返り一覧取得に失敗すれば照合不能として残件を中止する`() = runBlocking {
        val member = addMember("確認画面照合不可", "15")
        var directs = 0
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = object : ReservationSession {
                override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt { directs++; return DirectReservationAttempt.StayedOnConfirmation }
                override suspend fun fetchReservations(): List<Reservation> = throw IllegalStateException("offline")
                override fun close() = Unit
            }
        })
        repository.addToCart(ReservationTarget(null, member, "first", "一")); repository.addToCart(ReservationTarget(null, member, "second", "二"))
        val result = repository.confirmCart(ReservationConfirmation("106", 1000))
        assertEquals(1, directs)
        assertEquals(
            ReservationOutcome.Unknown(com.fallgist.nishinomiyalibrary.domain.model.UnknownReason.VERIFICATION_UNAVAILABLE),
            result.members.single().itemResults[0].outcome,
        )
        assertTrue(result.members.single().itemResults[1].outcome is ReservationOutcome.Failure)
    }

    @Test
    fun `再認証後に確認画面のまま返り完全な一覧に無ければ拒否とし同一メンバーの次の項目へ進む`() = runBlocking {
        val member = addMember("再認証拒否", "16")
        var opens = 0
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession {
                opens++
                if (opens == 1) return object : ReservationSession {
                    override suspend fun directReserve(tilcod: String, pickupLibraryCode: String) = DirectReservationAttempt.SessionExpiredBeforeSubmit
                    override suspend fun fetchReservations(): List<Reservation> = emptyList()
                    override fun close() = Unit
                }
                return object : ReservationSession, ReservationSnapshotSource {
                    override suspend fun directReserve(tilcod: String, pickupLibraryCode: String) = DirectReservationAttempt.StayedOnConfirmation
                    override suspend fun fetchReservations(): List<Reservation> = emptyList()
                    override suspend fun fetchReservationSnapshot() = ReservationListSnapshot(emptyList(), complete = true)
                    override fun close() = Unit
                }
            }
        })
        repository.addToCart(ReservationTarget(null, member, "first", "一")); repository.addToCart(ReservationTarget(null, member, "second", "二"))
        val result = repository.confirmCart(ReservationConfirmation("106", 1000))
        assertEquals(2, opens)
        assertEquals(ReservationOutcome.Failure(FailureReason.REJECTED_BY_SITE), result.members.single().itemResults[0].outcome)
        assertEquals(ReservationOutcome.Failure(FailureReason.REJECTED_BY_SITE), result.members.single().itemResults[1].outcome)
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
        assertEquals(
            com.fallgist.nishinomiyalibrary.domain.model.ReservationPickupSubmissionOrigin.UNVERIFIED_SUBMISSION,
            database.reservationPickupSubmissionDao().get(member, "sent")?.origin,
        )
    }

    @Test
    fun `POST前の重複検出が照合不能でUnknownでも送信館記録は作らない`() = runBlocking {
        val member = addMember("重複", "duplicate")
        val repository = repository(object : ReservationGateway {
            override suspend fun openAuthenticatedSession(cardNumber: String, password: String) = object : ReservationSession {
                override suspend fun directReserve(tilcod: String, pickupLibraryCode: String) =
                    DirectReservationAttempt.DuplicateDetected
                override suspend fun fetchReservations(): List<Reservation> =
                    throw IllegalStateException("offline")
                override fun close() = Unit
            }
        })

        val result = repository.reserveNow(
            ReservationTarget(null, member, "duplicate", "重複資料"),
            ReservationConfirmation("106", 1000),
        )

        assertTrue(result.members.single().itemResults.single().outcome is ReservationOutcome.Unknown)
        assertNull(database.reservationPickupSubmissionDao().get(member, "duplicate"))
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

    @Test
    fun `再認証後の予約POSTは書込み世代を一度だけ増加させる`() = runBlocking {
        val member = addMember("再認証POST", "retry-write")
        val gate = ReservationOperationGate()
        val first = writeAwareSession { DirectReservationAttempt.SessionExpiredBeforeSubmit }
        val second = writeAwareSession { beforeWrite ->
            beforeWrite()
            DirectReservationAttempt.Submitted
        }
        var opens = 0
        val repository = ReservationCartRepositoryImpl(
            database, database.reservationCartDao(), database.memberDao(), credentials,
            object : ReservationGateway {
                override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession =
                    if (opens++ == 0) first else second
            },
            Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC),
            operationGate = gate,
        )

        repository.reserveNow(ReservationTarget(null, member, "retry-write", "書名"), ReservationConfirmation("106", 1000))

        assertEquals(1L, gate.currentWriteGeneration())
        assertEquals(listOf(true, false), first.boundaryAssignments)
        assertEquals(listOf(true, false), second.boundaryAssignments)
        assertTrue(first.closed)
    }

    @Test
    fun `再認証後も期限切れなら書込み世代を増加させない`() = runBlocking {
        val member = addMember("再認証失敗", "retry-no-write")
        val gate = ReservationOperationGate()
        var opens = 0
        val repository = ReservationCartRepositoryImpl(
            database, database.reservationCartDao(), database.memberDao(), credentials,
            object : ReservationGateway {
                override suspend fun openAuthenticatedSession(cardNumber: String, password: String): ReservationSession =
                    writeAwareSession { DirectReservationAttempt.SessionExpiredBeforeSubmit }.also { opens++ }
            },
            Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC),
            operationGate = gate,
        )

        repository.reserveNow(ReservationTarget(null, member, "retry-no-write", "書名"), ReservationConfirmation("106", 1000))

        assertEquals(2, opens)
        assertEquals(0L, gate.currentWriteGeneration())
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

    private class WriteAwareSession(
        private val attempt: suspend ((() -> Unit)) -> DirectReservationAttempt,
    ) : ReservationSession, ReservationWriteBoundaryAware {
        private var beforeWrite: (() -> Unit)? = null
        val boundaryAssignments = mutableListOf<Boolean>()
        var closed = false

        override fun setBeforeWriteBoundary(callback: (() -> Unit)?) {
            boundaryAssignments += callback != null
            beforeWrite = callback
        }

        override suspend fun directReserve(tilcod: String, pickupLibraryCode: String): DirectReservationAttempt =
            attempt(requireNotNull(beforeWrite))

        override suspend fun fetchReservations(): List<Reservation> = listOf(
            Reservation(0, "", "", "", LocalDate.of(2030, 1, 1), null, ReservationState.WAITING, null, "retry-write"),
        )

        override fun close() {
            closed = true
        }
    }

    private fun writeAwareSession(
        attempt: suspend ((() -> Unit)) -> DirectReservationAttempt,
    ) = WriteAwareSession(attempt)

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
