package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.dao.ShelfDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ShelfItemDao
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfContent
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.repository.BookshelfRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** Room上の棚を正本に、本棚番号順で空棚も含めて公開する。 */
@Singleton
class BookshelfRepositoryImpl @Inject constructor(
    private val shelfDao: ShelfDao,
    private val shelfItemDao: ShelfItemDao,
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
                // DAOの登録日降順を保ったまま、親棚の現行名称を付与する。
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
}
