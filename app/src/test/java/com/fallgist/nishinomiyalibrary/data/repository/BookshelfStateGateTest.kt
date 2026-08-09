package com.fallgist.nishinomiyalibrary.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookshelfStateGateTest {
    @Test
    fun `同期の古いスナップショットは待機した編集より先に完了する`() = runBlocking {
        val gate = BookshelfStateGate()
        val syncEntered = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()

        val sync = async {
            gate.withLock {
                syncEntered.complete(Unit)
                releaseSync.await()
                writes += "old-sync-snapshot"
            }
        }
        syncEntered.await()
        val edit = async { gate.withLock { writes += "edit-snapshot" } }
        releaseSync.complete(Unit)

        sync.await()
        edit.await()

        assertEquals(listOf("old-sync-snapshot", "edit-snapshot"), writes)
    }

    @Test
    fun `キャンセルされた待機者はゲートを保持せず次の操作を通す`() = runBlocking {
        val gate = BookshelfStateGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async { gate.withLock { entered.complete(Unit); release.await() } }
        entered.await()
        val cancelledWaiter = async { gate.withLock { error("キャンセル後に実行されてはいけません") } }
        cancelledWaiter.cancelAndJoin()
        release.complete(Unit)
        owner.await()

        var passed = false
        gate.withLock { passed = true }
        assertTrue(passed)
    }
}
