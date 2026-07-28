package com.fallgist.nishinomiyalibrary.ui.sync

import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SyncResult
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/** 手動同期の共有状態。全画面のプルリフレッシュとホームの「いますぐ同期」が同じ状態を見る。 */
data class SyncUiState(
    val isSyncing: Boolean = false,
    val message: SyncMessage? = null,
)

/**
 * Snackbarへ一度だけ渡すメッセージ。
 * 単なるStringをStateFlowへ置くと画面回転や再コンポーズのたびに再表示されるため、
 * idで「表示済み」を識別してconsumeMessageで消す。
 */
data class SyncMessage(val id: Long, val text: String)

/**
 * 手動同期の共有Controller。ホームの「いますぐ同期」と同期系5画面のプルリフレッシュが
 * この1つの状態を見ることで、どの画面から起動してもインジケータと結果表示が一致する。
 * 中身は元HomeScreenController.requestManualSyncからの移設で、文言も変えていない。
 */
class SyncUiController(
    private val statusRepository: StatusRepository,
) {
    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state
    private val syncMutex = Mutex()
    private val messageSequence = AtomicLong(0L)

    /**
     * 同期中の再入は黙って無視する（tryLockで判定）。
     * 呼び出し元のコンテキストで動くが、通信は LicsXpSession が Dispatchers.IO へ逃がしており
     * (executeOnce の withContext)、ここでディスパッチャを切り替える必要はない。
     */
    suspend fun requestManualSync() {
        if (!syncMutex.tryLock()) return
        // 「同期中です」はメッセージとして流さない。プル中であることは
        // PullToRefreshBoxのインジケータ・ホームの「同期中…」表示が示すため、
        // Snackbarで二重に知らせると冗長になる。
        _state.update { it.copy(isSyncing = true) }
        try {
            val result = statusRepository.syncAll(SyncTrigger.MANUAL)
            _state.update { it.copy(message = nextMessage(manualSyncMessage(result))) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            _state.update {
                it.copy(message = nextMessage("同期に失敗しました。通信状況を確認してください"))
            }
        } finally {
            _state.update { it.copy(isSyncing = false) }
            syncMutex.unlock()
        }
    }

    /** Snackbar表示が完了したら呼ぶ。表示中に新しいメッセージが来ていた場合は消さない。 */
    fun consumeMessage(id: Long) {
        _state.update { if (it.message?.id == id) it.copy(message = null) else it }
    }

    private fun nextMessage(text: String) = SyncMessage(id = messageSequence.incrementAndGet(), text = text)

    private fun manualSyncMessage(result: SyncResult): String = when (result) {
        is SyncResult.Completed -> if (result.failedMemberCount == 0) {
            "同期が完了しました"
        } else {
            "同期が完了しました(一部失敗: ${result.failedMemberCount}人)"
        }
    }
}
