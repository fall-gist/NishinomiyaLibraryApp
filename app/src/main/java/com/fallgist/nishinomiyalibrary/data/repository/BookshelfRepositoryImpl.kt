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
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfContent
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutation
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

    override suspend fun mutate(mutation: BookshelfMutation): BookshelfMutationOutcome =
        requireNotNull(bookshelfStateGate) { "本棚編集用の依存関係が設定されていません" }.withLock {
            mutateLocked(mutation)
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
            is RemoteBookshelfOutcome.Failure -> BookshelfMutationOutcome.Failure(remoteOutcome.reason)
        }
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
    is BookshelfMutation.UpdateItemMemo -> RemoteBookshelfMutation.UpdateItemMemo(shelfNo, tilcod, memo, expected)
    is BookshelfMutation.CreateShelf -> RemoteBookshelfMutation.CreateShelf(name, expected)
    is BookshelfMutation.RenameShelf -> RemoteBookshelfMutation.RenameShelf(shelfNo, name, expected)
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
