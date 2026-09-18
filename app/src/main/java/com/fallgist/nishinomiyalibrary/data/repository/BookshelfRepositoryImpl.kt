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
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddItem
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddItemOutcome
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddItemResult
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddRequest
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfBulkAddResult
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfContent
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
                // DAOの登録日降順を保つ。棚番号順はDAO側の責務とする。
                items = itemsByShelfNo[shelf.shelfNo].orEmpty().map { item ->
                    ShelfItem(
                        memberId = item.memberId,
                        tilcod = item.tilcod,
                        title = item.title,
                        memo = item.memo,
                        registeredDate = item.registeredDate,
                        shelfNo = item.shelfNo,
                        shelfName = shelf.name,
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
            ?: return failAll(request.items, FailureReason.MEMBER_ABORTED_AFTER_SITE_CHANGE, onProgress)
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
            return failAll(request.items, FailureReason.AUTH, onProgress)
        } ?: return failAll(request.items, FailureReason.AUTH, onProgress)
        if (member.name != request.confirmed.memberName) {
            return failAll(request.items, FailureReason.SITE_RESPONSE_CHANGED, onProgress)
        }

        val password = try {
            requireNotNull(credentialStore).getPassword(member.id)
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            return failAll(request.items, FailureReason.AUTH, onProgress)
        }
        if (password.isNullOrBlank()) return failAll(request.items, FailureReason.AUTH, onProgress)

        var session: BookshelfSession? = null
        return try {
            session = try {
                requireNotNull(gateway).openAuthenticatedSession(member.cardNumber, password)
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                return failAll(request.items, exception.toFailureReason(), onProgress)
            }
            processBulkAddItems(request, session, onProgress)
        } finally {
            session?.close()
        }
    }

    /** ログイン後、資料を1件ずつ追加する。期待値の資料数だけを直前の結果で更新する(§4.2)。 */
    private suspend fun processBulkAddItems(
        request: BookshelfBulkAddRequest,
        session: BookshelfSession,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): BookshelfBulkAddResult {
        val total = request.items.size
        val results = mutableListOf<BookshelfBulkAddItemResult>()
        var localRefreshRequired = false
        var currentExpected = request.confirmed
        var stopped = false
        for ((index, bulkItem) in request.items.withIndex()) {
            if (stopped) {
                results += BookshelfBulkAddItemResult(bulkItem, BookshelfBulkAddItemOutcome.NotAttempted)
                continue
            }
            val remoteOutcome = try {
                session.mutate(RemoteBookshelfMutation.AddItem(request.shelfNo, bulkItem.tilcod, "", currentExpected))
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
                    currentExpected = nextExpectation(request.confirmed, request.shelfNo, remoteOutcome.items)
                }
                is RemoteBookshelfOutcome.AlreadyRegistered -> {
                    if (persistBulkSnapshot(request.memberId, remoteOutcome.shelves, remoteOutcome.items)) localRefreshRequired = true
                    results += BookshelfBulkAddItemResult(bulkItem, BookshelfBulkAddItemOutcome.AlreadyRegistered)
                    currentExpected = nextExpectation(request.confirmed, request.shelfNo, remoteOutcome.items)
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
        return BookshelfBulkAddResult(results, localRefreshRequired)
    }

    /**
     * 2件目以降の期待値。資料数だけを直前の結果(操作後の全本棚)から取り、それ以外
     * (メンバー名・本棚の総数・対象本棚の番号と名前)は確認時の値のまま照合する(§4.2)。
     * Roomからは読まない。名前や本棚の総数を直前の結果から取ってはならない(改名・番号ずれの
     * 検出が無効になるため)。
     */
    private fun nextExpectation(
        confirmed: BookshelfMutationExpectation,
        shelfNo: Int,
        latestItems: List<ShelfItem>,
    ): BookshelfMutationExpectation {
        val shelf = confirmed.shelf ?: return confirmed
        val actualCount = latestItems.count { it.shelfNo == shelfNo }
        return confirmed.copy(shelf = shelf.copy(itemCount = actualCount))
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

    /** 1件も送らず全件を同じ理由でFailedにする(§9)。onProgressは各件ごとに呼ぶ(NotAttemptedではないため)。 */
    private fun failAll(
        items: List<BookshelfBulkAddItem>,
        reason: FailureReason,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): BookshelfBulkAddResult {
        val total = items.size
        val results = items.mapIndexed { index, item ->
            onProgress(index + 1, total)
            BookshelfBulkAddItemResult(item, BookshelfBulkAddItemOutcome.Failed(reason))
        }
        return BookshelfBulkAddResult(results, localRefreshRequired = false)
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
    is BookshelfMutation.EditShelf -> RemoteBookshelfMutation.EditShelf(shelfNo, newName, items, expected)
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
