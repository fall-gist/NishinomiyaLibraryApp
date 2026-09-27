package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.dao.MemberDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ShelfDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ShelfItemDao
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.BookshelfGateway
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.BookshelfSession
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.LibraryError
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.RemoteBookshelfMutation
import com.fallgist.nishinomiyalibrary.data.remote.licsxp.RemoteBookshelfOutcome
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddCreateOutcome
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddItem
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddItemOutcome
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddItemResult
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddRequest
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddResult
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddTarget
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfContent
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfExpectedShelf
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutation
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutationExpectation
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.repository.BookshelfRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** Room上の本棚表示と、サイトに対する明示的な本棚編集を扱う。 */
@Singleton
class BookshelfRepositoryImpl @Inject constructor(
    private val shelfDao: ShelfDao,
    private val shelfItemDao: ShelfItemDao,
    private val database: AppDatabase? = null,
    private val memberDao: MemberDao? = null,
    private val credentialStore: CredentialStore? = null,
    private val gateway: BookshelfGateway? = null,
    private val bookshelfStateGate: BookshelfStateGate? = null,
) : BookshelfRepository {
    override fun observeShelves(memberId: Long): Flow<List<BookshelfContent>> = combine(
        shelfDao.observeForMember(memberId),
        shelfItemDao.observeForMember(memberId),
    ) { shelves, shelfItems ->
        val itemsByShelfNo = shelfItems.groupBy { it.shelfNo }
        shelves.map { shelf ->
            BookshelfContent(
                memberId = shelf.memberId,
                shelfNo = shelf.shelfNo,
                name = shelf.name,
                // DAOのposition→登録日降順を保つ。棚番号順はDAO側の責務とする。
                items = itemsByShelfNo[shelf.shelfNo].orEmpty().map { item ->
                    ShelfItem(
                        memberId = item.memberId,
                        tilcod = item.tilcod,
                        title = item.title,
                        memo = item.memo,
                        registeredDate = item.registeredDate,
                        shelfNo = item.shelfNo,
                        shelfName = shelf.name,
                        position = item.position,
                    )
                },
            )
        }
    }

    override suspend fun mutate(mutation: BookshelfMutation): BookshelfMutationOutcome {
        // memberDao/credentialStore/gateway/database と同様、DI漏れも例外を投げずFailureとして返す。
        // 該当するFailureReasonが無いため、toFailureReasonのelse節と同じMEMBER_ABORTED_AFTER_SITE_CHANGEを用いる。
        val gate = bookshelfStateGate
            ?: return BookshelfMutationOutcome.Failure(FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE)
        return gate.withLock { mutateLocked(mutation) }
    }

    private suspend fun mutateLocked(mutation: BookshelfMutation): BookshelfMutationOutcome {
        val member = try {
            requireNotNull(memberDao).getById(mutation.memberId)
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            return BookshelfMutationOutcome.Failure(FailureReason.AUTH)
        } ?: return BookshelfMutationOutcome.Failure(FailureReason.AUTH)
        if (member.name != mutation.expected.memberName) {
            return BookshelfMutationOutcome.Failure(FailureReason.SITE_RESPONSE_CHANGED)
        }

        val password = try {
            requireNotNull(credentialStore).getPassword(member.id)
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            return BookshelfMutationOutcome.Failure(FailureReason.AUTH)
        }
        if (password.isNullOrBlank()) return BookshelfMutationOutcome.Failure(FailureReason.AUTH)

        var session: BookshelfSession? = null
        val remoteOutcome = try {
            session = requireNotNull(gateway).openAuthenticatedSession(member.cardNumber, password)
            session.mutate(mutation.toRemote())
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            return BookshelfMutationOutcome.Failure(exception.toFailureReason())
        } finally {
            session?.close()
        }

        return when (remoteOutcome) {
            is RemoteBookshelfOutcome.Applied -> persistSnapshot(
                mutation.memberId,
                remoteOutcome.shelves,
                remoteOutcome.items,
            ) { localRefreshRequired -> BookshelfMutationOutcome.Applied(localRefreshRequired) }
            is RemoteBookshelfOutcome.AlreadyRegistered -> persistSnapshot(
                mutation.memberId,
                remoteOutcome.shelves,
                remoteOutcome.items,
            ) { localRefreshRequired -> BookshelfMutationOutcome.AlreadyRegistered(localRefreshRequired) }
            RemoteBookshelfOutcome.Unknown -> BookshelfMutationOutcome.Unknown
            is RemoteBookshelfOutcome.Failure -> BookshelfMutationOutcome.Failure(remoteOutcome.reason, remoteOutcome.diagnosticCode)
        }
    }

    // §9で確定: 開始時のメンバー名不一致(および同種の事前チェック失敗)は、1件も送らず全件Failedとする。
    // 全件NotAttemptedにすると「前の資料で処理を中断した」という文言が実態(前の資料は存在しない)と
    // 食い違うため、理由を持つFailedの方がUIの説明として正確である(`docs/design/bulk-bookshelf-add.md` §9)。
    override suspend fun addItems(
        request: BookshelfBulkAddRequest,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): BookshelfBulkAddResult {
        // 空リストでは通信・ログインを一切行わない(`bulk-bookshelf-add.md` §6.1-11)。
        if (request.items.isEmpty()) return BookshelfBulkAddResult(emptyList(), localRefreshRequired = false)
        val gate = bookshelfStateGate
            ?: return failAll(request.items, FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE, onProgress, request.target)
        return gate.withLock { addItemsLocked(request, onProgress) }
    }

    private suspend fun addItemsLocked(
        request: BookshelfBulkAddRequest,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): BookshelfBulkAddResult {
        val member = try {
            requireNotNull(memberDao).getById(request.memberId)
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            return failAll(request.items, FailureReason.AUTH, onProgress, request.target)
        } ?: return failAll(request.items, FailureReason.AUTH, onProgress, request.target)
        if (member.name != request.confirmed.memberName) {
            return failAll(request.items, FailureReason.SITE_RESPONSE_CHANGED, onProgress, request.target)
        }

        val password = try {
            requireNotNull(credentialStore).getPassword(member.id)
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            return failAll(request.items, FailureReason.AUTH, onProgress, request.target)
        }
        if (password.isNullOrBlank()) return failAll(request.items, FailureReason.AUTH, onProgress, request.target)

        var session: BookshelfSession? = null
        return try {
            session = try {
                requireNotNull(gateway).openAuthenticatedSession(member.cardNumber, password)
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                return failAll(request.items, exception.toFailureReason(), onProgress, request.target)
            }
            processBulkAddItems(request, session, onProgress)
        } finally {
            session?.close()
        }
    }

    /**
     * 追加先の解決結果(`docs/design/add-to-new-shelf.md` §3.2)。[shelfNo]は以降のAddItemの対象、
     * [expected]は1件目のAddItemに使う期待値(既存の本棚ならconfirmedそのもの、新しい本棚なら
     * 作成後の状態から組み立てた期待値)。[stopped]が真なら資料は1件も送らずNotAttemptedにする。
     */
    private data class TargetResolution(
        val shelfNo: Int,
        val expected: BookshelfMutationExpectation,
        val createOutcome: BookshelfBulkAddCreateOutcome,
        val stopped: Boolean,
        val localRefreshRequired: Boolean,
    )

    /**
     * 追加先が新しい本棚なら、資料追加の前にCreateShelfを1回送る(`docs/design/add-to-new-shelf.md` §3.2)。
     * ゲートとログインは呼び出し元(addItemsLocked)で全体1回のまま保たれる。
     */
    private suspend fun resolveBulkAddTarget(
        request: BookshelfBulkAddRequest,
        session: BookshelfSession,
    ): TargetResolution {
        val target = request.target
        if (target is BookshelfBulkAddTarget.ExistingShelf) {
            return TargetResolution(
                shelfNo = target.shelfNo,
                expected = request.confirmed,
                createOutcome = BookshelfBulkAddCreateOutcome.NotApplicable,
                stopped = false,
                localRefreshRequired = false,
            )
        }
        val name = (target as BookshelfBulkAddTarget.NewShelf).name
        val remoteOutcome = try {
            session.mutate(RemoteBookshelfMutation.CreateShelf(name, request.confirmed))
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            return TargetResolution(
                shelfNo = 0,
                expected = request.confirmed,
                createOutcome = BookshelfBulkAddCreateOutcome.Failed(exception.toFailureReason()),
                stopped = true,
                localRefreshRequired = false,
            )
        }
        return when (remoteOutcome) {
            is RemoteBookshelfOutcome.Applied -> {
                val createdShelfNo = remoteOutcome.createdShelfNo
                if (createdShelfNo == null) {
                    // 起きない想定だが、結果不明として扱い止める(§3.2)。
                    TargetResolution(0, request.confirmed, BookshelfBulkAddCreateOutcome.Unknown, stopped = true, localRefreshRequired = false)
                } else {
                    val refreshFailed = persistBulkSnapshot(request.memberId, remoteOutcome.shelves, remoteOutcome.items)
                    val nextExpected = request.confirmed.copy(
                        shelfCount = request.confirmed.shelfCount + 1,
                        shelf = BookshelfExpectedShelf(createdShelfNo, name, itemCount = 0),
                    )
                    TargetResolution(
                        shelfNo = createdShelfNo,
                        expected = nextExpected,
                        createOutcome = BookshelfBulkAddCreateOutcome.Created(createdShelfNo),
                        stopped = false,
                        localRefreshRequired = refreshFailed,
                    )
                }
            }
            // AddItemと異なりCreateShelfはAlreadyRegisteredを返さない(Gateway側で生成されない)が、
            // sealed interfaceを網羅するため、来た場合も安全側のUnknownに倒す。
            is RemoteBookshelfOutcome.AlreadyRegistered ->
                TargetResolution(0, request.confirmed, BookshelfBulkAddCreateOutcome.Unknown, stopped = true, localRefreshRequired = false)
            RemoteBookshelfOutcome.Unknown ->
                TargetResolution(0, request.confirmed, BookshelfBulkAddCreateOutcome.Unknown, stopped = true, localRefreshRequired = false)
            is RemoteBookshelfOutcome.Failure ->
                TargetResolution(0, request.confirmed, BookshelfBulkAddCreateOutcome.Failed(remoteOutcome.reason), stopped = true, localRefreshRequired = false)
        }
    }

    /**
     * ログイン後、必要なら本棚を作成し(§3.2)、資料を1件ずつ追加する。
     * 期待値の資料数だけを直前の結果で更新する(`bulk-bookshelf-add.md` §4.2)。
     */
    private suspend fun processBulkAddItems(
        request: BookshelfBulkAddRequest,
        session: BookshelfSession,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): BookshelfBulkAddResult {
        val total = request.items.size
        val resolution = resolveBulkAddTarget(request, session)
        val results = mutableListOf<BookshelfBulkAddItemResult>()
        var localRefreshRequired = resolution.localRefreshRequired
        // 資料追加の期待値の基準(baseExpected)は、既存の本棚なら確認時の値そのもの、
        // 新しい本棚なら作成後の状態から組み立てた期待値。nextExpectationはこれを基準に
        // 資料数だけを直前の結果で更新する(名前・本棚の総数は基準のまま追随しない)。
        val baseExpected = resolution.expected
        var currentExpected = baseExpected
        var stopped = resolution.stopped
        for ((index, bulkItem) in request.items.withIndex()) {
            if (stopped) {
                results += BookshelfBulkAddItemResult(bulkItem, BookshelfBulkAddItemOutcome.NotAttempted)
                continue
            }
            val remoteOutcome = try {
                session.mutate(RemoteBookshelfMutation.AddItem(resolution.shelfNo, bulkItem.tilcod, request.memo, currentExpected))
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                stopped = true
                results += BookshelfBulkAddItemResult(bulkItem, BookshelfBulkAddItemOutcome.Failed(exception.toFailureReason()))
                onProgress(index + 1, total)
                continue
            }
            when (remoteOutcome) {
                is RemoteBookshelfOutcome.Applied -> {
                    if (persistBulkSnapshot(request.memberId, remoteOutcome.shelves, remoteOutcome.items)) localRefreshRequired = true
                    results += BookshelfBulkAddItemResult(bulkItem, BookshelfBulkAddItemOutcome.Added)
                    currentExpected = nextExpectation(baseExpected, resolution.shelfNo, remoteOutcome.items)
                }
                is RemoteBookshelfOutcome.AlreadyRegistered -> {
                    if (persistBulkSnapshot(request.memberId, remoteOutcome.shelves, remoteOutcome.items)) localRefreshRequired = true
                    results += BookshelfBulkAddItemResult(bulkItem, BookshelfBulkAddItemOutcome.AlreadyRegistered)
                    currentExpected = nextExpectation(baseExpected, resolution.shelfNo, remoteOutcome.items)
                }
                RemoteBookshelfOutcome.Unknown -> {
                    stopped = true
                    results += BookshelfBulkAddItemResult(bulkItem, BookshelfBulkAddItemOutcome.Unknown)
                }
                is RemoteBookshelfOutcome.Failure -> {
                    stopped = true
                    results += BookshelfBulkAddItemResult(bulkItem, BookshelfBulkAddItemOutcome.Failed(remoteOutcome.reason))
                }
            }
            onProgress(index + 1, total)
        }
        return BookshelfBulkAddResult(results, localRefreshRequired, resolution.createOutcome)
    }

    /**
     * 2件目以降の期待値。資料数だけを直前の結果(操作後の全本棚)から取り、それ以外
     * (メンバー名・本棚の総数・対象本棚の番号と名前)は基準(baseExpected)の値のまま照合する(§4.2)。
     * Roomからは読まない。名前や本棚の総数を直前の結果から取ってはならない(改名・番号ずれの
     * 検出が無効になるため)。
     */
    private fun nextExpectation(
        baseExpected: BookshelfMutationExpectation,
        shelfNo: Int,
        latestItems: List<ShelfItem>,
    ): BookshelfMutationExpectation {
        val shelf = baseExpected.shelf ?: return baseExpected
        val actualCount = latestItems.count { it.shelfNo == shelfNo }
        return baseExpected.copy(shelf = shelf.copy(itemCount = actualCount))
    }

    /** persistSnapshotと同じRoom置換規則。戻り値は置換に失敗したか(=localRefreshRequiredを立てるか)。 */
    private suspend fun persistBulkSnapshot(
        memberId: Long,
        shelves: List<com.fallgist.nishinomiyalibrary.domain.model.Shelf>,
        items: List<ShelfItem>,
    ): Boolean = try {
        requireNotNull(database).replaceShelfSnapshot(
            memberId = memberId,
            shelves = shelves.map { it.toEntity(memberId) },
            shelfItems = items.map { it.toEntity(memberId) },
        )
        false
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        true
    }

    /**
     * 1件も送らず全件を同じ理由でFailedにする(§9)。onProgressは各件ごとに呼ぶ(NotAttemptedではないため)。
     * 追加先が新しい本棚のときは、本棚作成も送っていないため結果の[BookshelfBulkAddCreateOutcome]も
     * 同じ理由のFailedにする(`docs/design/add-to-new-shelf.md` §3の事前チェック失敗時の扱い)。
     * 既存の本棚が追加先のときは作成自体が無関係なのでNotApplicableのままにする。
     */
    private fun failAll(
        items: List<BookshelfBulkAddItem>,
        reason: FailureReason,
        onProgress: (completed: Int, total: Int) -> Unit,
        target: BookshelfBulkAddTarget,
    ): BookshelfBulkAddResult {
        val total = items.size
        val results = items.mapIndexed { index, item ->
            onProgress(index + 1, total)
            BookshelfBulkAddItemResult(item, BookshelfBulkAddItemOutcome.Failed(reason))
        }
        val createOutcome = if (target is BookshelfBulkAddTarget.NewShelf) {
            BookshelfBulkAddCreateOutcome.Failed(reason)
        } else {
            BookshelfBulkAddCreateOutcome.NotApplicable
        }
        return BookshelfBulkAddResult(results, localRefreshRequired = false, create = createOutcome)
    }

    /** Gate保持中に、完全スナップショットとサマリの棚数を一つのRoomトランザクションで更新する。 */
    private suspend fun persistSnapshot(
        memberId: Long,
        shelves: List<com.fallgist.nishinomiyalibrary.domain.model.Shelf>,
        items: List<ShelfItem>,
        outcome: (localRefreshRequired: Boolean) -> BookshelfMutationOutcome,
    ): BookshelfMutationOutcome = try {
        // ロック順: BookshelfStateGate -> LicsXpSessionの共有limiter -> Room。
        // この地点ではサイト操作を終え、同じGateを保ったままRoomへ反映する。
        requireNotNull(database).replaceShelfSnapshot(
            memberId = memberId,
            shelves = shelves.map { it.toEntity(memberId) },
            shelfItems = items.map { it.toEntity(memberId) },
        )
        outcome(false)
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        // サイト側の成功を失敗へ偽装せず、次回同期での再取得を要求する。
        outcome(true)
    }
}

private fun BookshelfMutation.toRemote(): RemoteBookshelfMutation = when (this) {
    is BookshelfMutation.AddItem -> RemoteBookshelfMutation.AddItem(shelfNo, tilcod, memo, expected)
    is BookshelfMutation.DeleteItem -> RemoteBookshelfMutation.DeleteItem(shelfNo, tilcod, expected)
    is BookshelfMutation.CreateShelf -> RemoteBookshelfMutation.CreateShelf(name, expected)
    is BookshelfMutation.EditShelf -> RemoteBookshelfMutation.EditShelf(shelfNo, newName, items, baseOrder, expected)
    is BookshelfMutation.DeleteShelf -> RemoteBookshelfMutation.DeleteShelf(shelfNo, expected)
}

private fun Exception.toFailureReason(): FailureReason = when (this) {
    is LibraryError.Auth -> FailureReason.AUTH
    is LibraryError.Parse -> FailureReason.SITE_RESPONSE_CHANGED
    is LibraryError.Maintenance -> FailureReason.SITE_MAINTENANCE
    is LibraryError.Network -> FailureReason.NETWORK
    else -> FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE
}

private fun Exception.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
