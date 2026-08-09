package com.fallgist.nishinomiyalibrary.ui.shelf

import com.fallgist.nishinomiyalibrary.domain.model.BookshelfContent
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutation
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.FailureReason
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.repository.BookshelfRepository
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val shelfTarget = BookshelfShelfTarget(1, 3, "父", "技術書", 2)
    private val itemTarget = BookshelfItemTarget(1, 3, "技術書", "t-1", "資料A", "既存メモ")

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
        controller.requestInputConfirmation()
        val confirmation = controller.state.value.pendingConfirmation as BookshelfEditingConfirmation.CreateShelf
        assertEquals("父", confirmation.memberName)
        assertEquals("新しい本棚", confirmation.shelfName)
        assertEquals(0, repo.calls.size)

        controller.confirmPending()
        advanceUntilIdle()
        assertEquals(listOf(BookshelfMutation.CreateShelf(father.id, "新しい本棚")), repo.calls)
        controller.close()
    }

    @Test
    fun `5操作は確認確定時だけ対応するmutationを一度ずつ送る`() = runTest {
        val repo = FakeBookshelfRepository()
        val controller = controller(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestRenameShelf(shelfTarget)
        controller.updateInput("名前変更")
        controller.requestInputConfirmation()
        assertEquals(0, repo.calls.size)
        controller.confirmPending()
        advanceUntilIdle()

        controller.requestEditItemMemo(itemTarget)
        controller.updateInput("変更メモ")
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
        controller.updateInput("子の棚")
        controller.requestInputConfirmation()
        controller.confirmPending()
        advanceUntilIdle()

        assertEquals(
            listOf(
                BookshelfMutation.RenameShelf(1, 3, "名前変更"),
                BookshelfMutation.UpdateItemMemo(1, 3, "t-1", "変更メモ"),
                BookshelfMutation.DeleteItem(1, 3, "t-1"),
                BookshelfMutation.DeleteShelf(1, 3),
                BookshelfMutation.CreateShelf(2, "子の棚"),
            ),
            repo.calls,
        )
        controller.close()
    }

    @Test
    fun `本棚名とメモの境界値を検証し送信文字列をtrimしない`() = runTest {
        val controller = controller(FakeBookshelfRepository(), StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.requestRenameShelf(shelfTarget)
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
        assertEquals(fifty, (controller.state.value.pendingConfirmation as BookshelfEditingConfirmation.RenameShelf).newName)
        controller.dismissConfirmation()

        controller.requestRenameShelf(shelfTarget)
        controller.updateInput(" 前後空白を残す ")
        controller.requestInputConfirmation()
        assertEquals(" 前後空白を残す ", (controller.state.value.pendingConfirmation as BookshelfEditingConfirmation.RenameShelf).newName)
        controller.dismissConfirmation()

        controller.requestEditItemMemo(itemTarget)
        controller.updateInput("m".repeat(1000))
        controller.requestInputConfirmation()
        assertEquals("m".repeat(1000), (controller.state.value.pendingConfirmation as BookshelfEditingConfirmation.EditItemMemo).newMemo)
        controller.dismissConfirmation()
        controller.requestEditItemMemo(itemTarget)
        controller.updateInput("m".repeat(1001))
        controller.requestInputConfirmation()
        assertEquals("資料メモは1000文字以内で入力してください", controller.state.value.inputError)
        controller.close()
    }

    @Test
    fun `削除確認は正確な本棚名と件数および資料名と本棚名を表示する`() {
        val deleteShelf = BookshelfEditingConfirmation.DeleteShelf(
            shelfTarget,
            BookshelfMutation.DeleteShelf(1, 3),
        )
        val deleteItem = BookshelfEditingConfirmation.DeleteItem(
            itemTarget,
            BookshelfMutation.DeleteItem(1, 3, "t-1"),
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
        controller.requestCreateShelf()
        controller.requestRenameShelf(shelfTarget)
        controller.requestEditItemMemo(itemTarget)
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

    private fun controller(
        repo: FakeBookshelfRepository,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): BookshelfEditingUiController =
        BookshelfEditingUiController(
            bookshelfRepository = repo,
            familyRepository = FakeFamilyRepository(),
            dispatcher = dispatcher,
        )

    private inner class FakeFamilyRepository : FamilyRepository {
        override fun members(): Flow<List<Member>> = flowOf(listOf(father, child))
        override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) = Unit
        override suspend fun updateMember(member: Member, newPassword: String?) = Unit
        override suspend fun removeMember(memberId: Long) = Unit
    }

    private class FakeBookshelfRepository(
        private val outcome: BookshelfMutationOutcome = BookshelfMutationOutcome.Applied(),
        private val onMutate: (suspend () -> Unit)? = null,
    ) : BookshelfRepository {
        val calls = mutableListOf<BookshelfMutation>()
        override fun observeShelves(memberId: Long): Flow<List<BookshelfContent>> = MutableStateFlow(emptyList())
        override suspend fun mutate(mutation: BookshelfMutation): BookshelfMutationOutcome {
            calls += mutation
            onMutate?.invoke()
            return outcome
        }
    }
}
