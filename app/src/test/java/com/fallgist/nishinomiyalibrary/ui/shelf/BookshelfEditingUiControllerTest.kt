package com.fallgist.nishinomiyalibrary.ui.shelf

import com.fallgist.nishinomiyalibrary.domain.model.BookshelfContent
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfEditItem
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutation
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.repository.BookshelfRepository
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BookshelfEditingUiControllerTest {
    private val father = Member(1, "父", "#111111", "card", 0)
    private val child = Member(2, "子", "#222222", "card", 1)

    private val shelfTarget = BookshelfShelfTarget(1, 3, "父", "技術書", 2, shelfCount = 1, items = listOf(BookshelfEditItem("t-1", "資料A", "既存メモ", "既存メモ"), BookshelfEditItem("t-2", "資料B", "", "")))
    private val itemTarget = BookshelfItemTarget(1, 3, "技術書", "t-1", "資料A", "既存メモ", itemCount = 2, shelfCount = 1)
    private val testExpectation = com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutationExpectation("父", 1)

    @Test
    fun `作成はメンバー選択と入力確認を経るまでmutateしない`() = runTest {
        val repo = FakeBookshelfRepository()
        val controller = controller(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestCreateShelf()
        controller.updateInput("新しい本棚")
        controller.requestInputConfirmation()
        assertEquals("対象メンバーを選択してください", controller.state.value.inputError)
        assertEquals(0, repo.calls.size)

        controller.selectCreateMember(father.id)
        advanceUntilIdle()
        controller.requestInputConfirmation()
        val confirmation = controller.state.value.pendingConfirmation as BookshelfEditingConfirmation.CreateShelf
        assertEquals("父", confirmation.memberName)
        assertEquals("新しい本棚", confirmation.shelfName)
        assertEquals(0, repo.calls.size)

        controller.confirmPending()
        advanceUntilIdle()
        assertEquals(listOf(confirmation.mutation), repo.calls)
        controller.close()
    }

    @Test
    fun `5操作は確認確定時だけ対応するmutationを一度ずつ送る`() = runTest {
        val repo = FakeBookshelfRepository()
        val controller = controller(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestEditShelf(shelfTarget)
        controller.updateInput("名前変更")
        controller.requestInputConfirmation()
        assertEquals(0, repo.calls.size)
        controller.confirmPending()
        advanceUntilIdle()

        controller.requestEditShelf(shelfTarget)
        controller.updateEditShelfMemo("t-1", "変更メモ")
        controller.requestInputConfirmation()
        assertEquals(1, repo.calls.size)
        controller.confirmPending()
        advanceUntilIdle()

        controller.requestDeleteItem(itemTarget)
        assertEquals(2, repo.calls.size)
        controller.confirmPending()
        advanceUntilIdle()

        controller.requestDeleteShelf(shelfTarget)
        assertEquals(3, repo.calls.size)
        controller.confirmPending()
        advanceUntilIdle()

        controller.requestCreateShelf()
        controller.selectCreateMember(child.id)
        advanceUntilIdle()
        controller.updateInput("子の棚")
        controller.requestInputConfirmation()
        controller.confirmPending()
        advanceUntilIdle()

        assertEquals(
            listOf(
                BookshelfMutation.EditShelf::class,
                BookshelfMutation.EditShelf::class,
                BookshelfMutation.DeleteItem::class,
                BookshelfMutation.DeleteShelf::class,
                BookshelfMutation.CreateShelf::class,
            ),
            repo.calls.map { it::class },
        )
        assertTrue(repo.calls.all { it.expected != null })
        controller.close()
    }

    @Test
    fun `本棚名とメモの境界値を検証し送信文字列をtrimしない`() = runTest {
        val controller = controller(FakeBookshelfRepository(), StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestEditShelf(shelfTarget)
        controller.updateInput("   ")
        controller.requestInputConfirmation()
        assertEquals("本棚名を入力してください", controller.state.value.inputError)

        val fifty = "a".repeat(50)
        controller.updateInput(fifty + "a")
        controller.requestInputConfirmation()
        // 51文字は拒否する。
        assertEquals("本棚名は50文字以内で入力してください", controller.state.value.inputError)

        controller.updateInput(fifty)
        controller.requestInputConfirmation()
        assertEquals(fifty, (controller.state.value.pendingConfirmation as BookshelfEditingConfirmation.EditShelf).newName)
        controller.dismissConfirmation()

        controller.requestEditShelf(shelfTarget)
        controller.updateInput(" 前後空白を残す ")
        controller.requestInputConfirmation()
        assertEquals(" 前後空白を残す ", (controller.state.value.pendingConfirmation as BookshelfEditingConfirmation.EditShelf).newName)
        controller.dismissConfirmation()

        controller.requestEditShelf(shelfTarget)
        controller.updateEditShelfMemo("t-1", "m".repeat(1000))
        controller.requestInputConfirmation()
        assertEquals("m".repeat(1000), (controller.state.value.pendingConfirmation as BookshelfEditingConfirmation.EditShelf).items.first().newMemo)
        controller.dismissConfirmation()
        controller.requestEditShelf(shelfTarget)
        controller.updateEditShelfMemo("t-1", "m".repeat(1001))
        controller.requestInputConfirmation()
        assertEquals("資料メモは1000文字以内で入力してください", controller.state.value.inputError)
        controller.close()
    }

    @Test
    fun `削除確認は正確な本棚名と件数および資料名と本棚名を表示する`() {
        val deleteShelf = BookshelfEditingConfirmation.DeleteShelf(
            shelfTarget,
            BookshelfMutation.DeleteShelf(1, 3, testExpectation),
        )
        val deleteItem = BookshelfEditingConfirmation.DeleteItem(
            itemTarget,
            BookshelfMutation.DeleteItem(1, 3, "t-1", testExpectation),
        )

        assertEquals("本棚と2件を削除", BookshelfEditingContentBuilder.confirmLabel(deleteShelf))
        assertTrue(BookshelfEditingContentBuilder.confirmationMessage(deleteShelf).contains("本棚「技術書」と登録資料2件"))
        assertTrue(BookshelfEditingContentBuilder.confirmationMessage(deleteItem).contains("資料名：資料A\n本棚：技術書"))
        assertTrue(BookshelfEditingContentBuilder.isDestructive(deleteShelf))
        assertTrue(BookshelfEditingContentBuilder.isDestructive(deleteItem))
    }

    @Test
    fun `Outcomeの意味を崩さず更新警告とサイト変更の注意を表示する`() {
        assertEquals("本棚へ反映しました", BookshelfEditingContentBuilder.resultMessage(BookshelfMutationOutcome.Applied()).message)
        assertTrue(
            BookshelfEditingContentBuilder.resultMessage(BookshelfMutationOutcome.Applied(localRefreshRequired = true)).message
                .contains("表示更新に失敗しました。画面を更新してください"),
        )
        assertEquals(
            "この資料はすでに選択した本棚に登録されています",
            BookshelfEditingContentBuilder.resultMessage(BookshelfMutationOutcome.AlreadyRegistered()).message,
        )
        assertEquals(
            "処理結果を確認できません。自動では再送しません。本棚を更新して確認してください",
            BookshelfEditingContentBuilder.resultMessage(BookshelfMutationOutcome.Unknown).message,
        )
        assertTrue(
            BookshelfEditingContentBuilder.resultMessage(
                BookshelfMutationOutcome.Failure(FailureReason.SITE_RESPONSE_CHANGED),
            ).message.contains("変更された可能性"),
        )
    }

    @Test
    fun `処理中は全ての編集入口と二重確定を無視する`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repo = FakeBookshelfRepository(onMutate = {
            started.complete(Unit)
            release.await()
        })
        val controller = controller(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestDeleteItem(itemTarget)
        controller.confirmPending()
        runCurrent()
        started.await()
        assertTrue(controller.state.value.processing)

        controller.confirmPending()
        controller.requestAddItem("t-2", "資料B")
        controller.requestCreateShelf()
        controller.requestEditShelf(shelfTarget)
        controller.requestEditShelf(shelfTarget)
        controller.requestDeleteShelf(shelfTarget)
        assertNull(controller.state.value.dialog)
        assertNull(controller.state.value.pendingConfirmation)
        assertEquals(1, repo.calls.size)

        release.complete(Unit)
        advanceUntilIdle()
        assertFalse(controller.state.value.processing)
        assertEquals(1, repo.calls.size)
        controller.close()
    }

    @Test
    fun `資料追加は毎回未選択から開始し確認後にだけ正確なmutationを送る`() = runTest {
        val fatherShelves = MutableStateFlow(listOf(BookshelfContent(father.id, 3, "父の棚", emptyList())))
        val repo = FakeBookshelfRepository(shelves = mapOf(father.id to fatherShelves))
        val controller = controller(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestAddItem("t-2", "資料B")
        assertEquals(BookshelfEditingDialog.AddItem(tilcod = "t-2", title = "資料B"), controller.state.value.dialog)
        controller.selectAddItemMember(father.id)
        advanceUntilIdle()
        controller.selectAddItemShelf(3)
        controller.updateInput("m".repeat(1000))
        controller.requestInputConfirmation()

        assertEquals(0, repo.calls.size)
        val confirmation = controller.state.value.pendingConfirmation as BookshelfEditingConfirmation.AddItem
        assertEquals("父", confirmation.memberName)
        assertEquals("父の棚", confirmation.shelfName)
        assertEquals("資料B", confirmation.title)
        assertEquals("m".repeat(1000), confirmation.memo)
        assertEquals("m".repeat(1000), confirmation.mutation.memo)
        assertEquals(1, requireNotNull(confirmation.mutation.expected).shelfCount)

        controller.confirmPending()
        advanceUntilIdle()
        assertEquals(listOf(confirmation.mutation), repo.calls)

        controller.requestAddItem("t-3", "資料C")
        assertEquals(BookshelfEditingDialog.AddItem("t-3", "資料C"), controller.state.value.dialog)
        controller.close()
    }

    @Test
    fun `資料追加はメンバー変更時に棚選択を解除し空棚と1001文字を拒否する`() = runTest {
        val fatherShelves = MutableStateFlow(listOf(BookshelfContent(father.id, 3, "父の棚", emptyList())))
        val childShelves = MutableStateFlow(emptyList<BookshelfContent>())
        val repo = FakeBookshelfRepository(shelves = mapOf(father.id to fatherShelves, child.id to childShelves))
        val controller = controller(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestAddItem("t-2", "資料B")
        controller.selectAddItemMember(father.id)
        advanceUntilIdle()
        controller.selectAddItemShelf(3)
        controller.selectAddItemMember(child.id)
        assertEquals(null, controller.state.value.addItemShelvesLoadedForMemberId)
        advanceUntilIdle()
        assertEquals(child.id, controller.state.value.addItemShelvesLoadedForMemberId)
        assertEquals(null, (controller.state.value.dialog as BookshelfEditingDialog.AddItem).shelfNo)
        controller.requestInputConfirmation()
        assertEquals("先に本棚を作成してください", controller.state.value.inputError)
        assertEquals(0, repo.calls.size)

        controller.dismissDialog()
        controller.requestAddItem("t-2", "資料B")
        controller.selectAddItemMember(father.id)
        advanceUntilIdle()
        controller.selectAddItemShelf(3)
        controller.updateInput("m".repeat(1001))
        controller.requestInputConfirmation()
        assertEquals("資料メモは1000文字以内で入力してください", controller.state.value.inputError)
        assertEquals(0, repo.calls.size)
        controller.close()
    }

    @Test
    fun `資料追加の確認前に追加先が消えた場合は送信しない`() = runTest {
        val shelves = MutableStateFlow(listOf(BookshelfContent(father.id, 3, "父の棚", emptyList())))
        val repo = FakeBookshelfRepository(shelves = mapOf(father.id to shelves))
        val controller = controller(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestAddItem("t-2", "資料B")
        controller.selectAddItemMember(father.id)
        advanceUntilIdle()
        controller.selectAddItemShelf(3)
        controller.requestInputConfirmation()
        shelves.value = emptyList()
        advanceUntilIdle()
        controller.confirmPending()

        assertEquals(0, repo.calls.size)
        assertTrue(controller.state.value.errorMessage != null)
        controller.close()
    }

    @Test
    fun `資料追加は棚Flowの初回値を待ち空棚と区別して確認を拒否する`() = runTest {
        val shelves = MutableSharedFlow<List<BookshelfContent>>()
        val repo = FakeBookshelfRepository(shelves = mapOf(father.id to shelves))
        val controller = controller(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestAddItem("t-2", "資料B")
        controller.selectAddItemMember(father.id)
        runCurrent()
        assertEquals(null, controller.state.value.addItemShelvesLoadedForMemberId)
        controller.requestInputConfirmation()
        assertEquals(null, controller.state.value.pendingConfirmation)
        assertTrue(controller.state.value.inputError != null)

        shelves.emit(emptyList())
        runCurrent()
        assertEquals(father.id, controller.state.value.addItemShelvesLoadedForMemberId)
        controller.requestInputConfirmation()
        assertEquals(null, controller.state.value.pendingConfirmation)
        assertTrue(controller.state.value.inputError != null)
        controller.close()
    }

    @Test
    fun `確認中のmember削除と棚emitが競合してもmemberを復元せず送信しない`() = runTest {
        val members = MutableStateFlow(listOf(father, child))
        val shelves = MutableStateFlow(listOf(BookshelfContent(father.id, 3, "父の棚", emptyList())))
        val repo = FakeBookshelfRepository(shelves = mapOf(father.id to shelves))
        val controller = controller(repo, StandardTestDispatcher(testScheduler), members)
        advanceUntilIdle()

        controller.requestAddItem("t-2", "資料B")
        controller.selectAddItemMember(father.id)
        advanceUntilIdle()
        controller.selectAddItemShelf(3)
        controller.requestInputConfirmation()

        members.value = listOf(child)
        shelves.value = listOf(BookshelfContent(father.id, 3, "更新後の棚", emptyList()))
        runCurrent()
        assertTrue(controller.state.value.members.none { it.id == father.id })

        controller.confirmPending()
        advanceUntilIdle()
        assertEquals(0, repo.calls.size)
        assertTrue(controller.state.value.members.none { it.id == father.id })
        controller.close()
    }

    @Test
    fun `確認中に棚名が変わった場合は表示対象との不一致を検出して送信しない`() = runTest {
        val shelves = MutableStateFlow(listOf(BookshelfContent(father.id, 3, "父の棚", emptyList())))
        val repo = FakeBookshelfRepository(shelves = mapOf(father.id to shelves))
        val controller = controller(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestAddItem("t-2", "資料B")
        controller.selectAddItemMember(father.id)
        advanceUntilIdle()
        controller.selectAddItemShelf(3)
        controller.requestInputConfirmation()
        shelves.value = listOf(BookshelfContent(father.id, 3, "変更後の棚", emptyList()))
        runCurrent()

        controller.confirmPending()
        advanceUntilIdle()
        assertEquals(0, repo.calls.size)
        assertTrue(controller.state.value.errorMessage != null)
        controller.close()
    }

    @Test
    fun `確認中にmember名が変わった場合は表示対象との不一致を検出して送信しない`() = runTest {
        val members = MutableStateFlow(listOf(father, child))
        val shelves = MutableStateFlow(listOf(BookshelfContent(father.id, 3, "父の棚", emptyList())))
        val repo = FakeBookshelfRepository(shelves = mapOf(father.id to shelves))
        val controller = controller(repo, StandardTestDispatcher(testScheduler), members)
        advanceUntilIdle()

        controller.requestAddItem("t-2", "資料B")
        controller.selectAddItemMember(father.id)
        advanceUntilIdle()
        controller.selectAddItemShelf(3)
        controller.requestInputConfirmation()
        members.value = listOf(father.copy(name = "変更後"), child)
        runCurrent()

        controller.confirmPending()
        advanceUntilIdle()
        assertEquals(0, repo.calls.size)
        assertTrue(controller.state.value.errorMessage != null)
        controller.close()
    }

    private fun controller(
        repo: FakeBookshelfRepository,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        membersFlow: Flow<List<Member>> = flowOf(listOf(father, child)),
    ): BookshelfEditingUiController =
        BookshelfEditingUiController(
            bookshelfRepository = repo,
            familyRepository = FakeFamilyRepository(membersFlow),
            dispatcher = dispatcher,
        )

    private inner class FakeFamilyRepository(private val membersFlow: Flow<List<Member>>) : FamilyRepository {
        override fun members(): Flow<List<Member>> = membersFlow
        override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) = Unit
        override suspend fun updateMember(member: Member, newPassword: String?) = Unit
        override suspend fun removeMember(memberId: Long) = Unit
    }

    private class FakeBookshelfRepository(
        private val outcome: BookshelfMutationOutcome = BookshelfMutationOutcome.Applied(),
        private val onMutate: (suspend () -> Unit)? = null,
        private val shelves: Map<Long, Flow<List<BookshelfContent>>> = emptyMap(),
    ) : BookshelfRepository {
        val calls = mutableListOf<BookshelfMutation>()
        override fun observeShelves(memberId: Long): Flow<List<BookshelfContent>> = shelves[memberId] ?: MutableStateFlow(emptyList())
        override suspend fun mutate(mutation: BookshelfMutation): BookshelfMutationOutcome {
            calls += mutation
            onMutate?.invoke()
            return outcome
        }
    }
}
