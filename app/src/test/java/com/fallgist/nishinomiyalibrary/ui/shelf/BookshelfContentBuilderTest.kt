package com.fallgist.nishinomiyalibrary.ui.shelf

import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfContent
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class BookshelfContentBuilderTest {
    private val papa = Member(id = 1, name = "パパ", colorHex = "#3D6DB5", cardNumber = "", sortOrder = 0)
    private val hana = Member(id = 2, name = "はな", colorHex = "#5FA05A", cardNumber = "", sortOrder = 1)
    private val members = listOf(papa, hana)

    private fun item(memberId: Long, title: String, shelfNo: Int, shelfName: String, date: LocalDate) = ShelfItem(
        memberId = memberId,
        tilcod = "t-$title",
        title = title,
        memo = "",
        registeredDate = date,
        shelfNo = shelfNo,
        shelfName = shelfName,
    )

    private fun shelf(memberId: Long, shelfNo: Int, name: String, vararg items: ShelfItem) = BookshelfContent(
        memberId = memberId,
        shelfNo = shelfNo,
        name = name,
        items = items.toList(),
    )

    @Test
    fun columns_orderedByMemberThenShelfNoWithColorHead() {
        val shelves = mapOf(
            hana.id to listOf(
                shelf(hana.id, 1, "よみたい", item(hana.id, "はなの本", 1, "よみたい", LocalDate.of(2026, 6, 1))),
            ),
            papa.id to listOf(
                shelf(papa.id, 2, "小説", item(papa.id, "パパ本B", 2, "小説", LocalDate.of(2026, 5, 1))),
                shelf(
                    papa.id,
                    1,
                    "技術書",
                    item(papa.id, "パパ本A", 1, "技術書", LocalDate.of(2026, 4, 1)),
                    item(papa.id, "パパ本C", 1, "技術書", LocalDate.of(2026, 7, 1)),
                ),
            ),
        )

        val columns = BookshelfContentBuilder.build(members, shelves, selectedMemberId = null)

        // メンバー順(パパ→はな)→本棚番号順
        assertEquals(listOf("技術書", "小説", "よみたい"), columns.map { it.shelfName })
        assertEquals(listOf("パパ", "パパ", "はな"), columns.map { it.memberName })
        assertEquals("#3D6DB5", columns[0].memberColorHex)
        assertEquals(papa.id, columns[0].memberId)
        assertEquals(1, columns[0].shelfNo)
        // 本棚内は登録日の新しい順
        assertEquals(listOf("パパ本C", "パパ本A"), columns[0].books.map { it.title })
        assertEquals("2026/7/1", columns[0].books[0].registeredDateLabel)
        // 書誌詳細遷移用にタイトルコードを保持する
        assertEquals("t-パパ本C", columns[0].books[0].tilcod)
    }

    @Test
    fun memberFilter_showsOnlySelectedMembersShelves() {
        val shelves = mapOf(
            papa.id to listOf(shelf(papa.id, 1, "技術書", item(papa.id, "パパ本", 1, "技術書", LocalDate.of(2026, 4, 1)))),
            hana.id to listOf(shelf(hana.id, 1, "よみたい", item(hana.id, "はな本", 1, "よみたい", LocalDate.of(2026, 6, 1)))),
        )

        val columns = BookshelfContentBuilder.build(members, shelves, selectedMemberId = hana.id)

        assertEquals(1, columns.size)
        assertEquals("はな", columns.single().memberName)
    }

    @Test
    fun emptyShelf_remainsAsAnEmptyColumn() {
        val shelves = mapOf(papa.id to listOf(shelf(papa.id, 3, "空の本棚")))

        val columns = BookshelfContentBuilder.build(members, shelves, selectedMemberId = papa.id)

        assertEquals(1, columns.size)
        assertEquals(3, columns.single().shelfNo)
        assertEquals("空の本棚", columns.single().shelfName)
        assertEquals(emptyList<ShelfBook>(), columns.single().books)
    }
}
