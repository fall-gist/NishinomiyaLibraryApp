package com.fallgist.nishinomiyalibrary.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 共通書込ゲートを利用する操作の種別。観測状態に利用者・資料の情報は含めない。 */
enum class ReservationOperationType {
    AUTOMATIC_RESERVATION,
    MANUAL_RESERVATION,
    MANUAL_CANCELLATION,
}

/** 待機中の操作と、その時点で待機している保持操作の種別。 */
data class ReservationOperationWait(
    val operation: ReservationOperationType,
    val waitingFor: ReservationOperationType?,
)

/**
 * 予約書込ゲートの公開観測状態。
 *
 * 資料ID、書名、認証情報などの個人・操作対象情報を含めず、排他状態だけを表す。
 */
data class ReservationOperationGateState(
    val activeOperation: ReservationOperationType? = null,
    val waitingOperations: List<ReservationOperationWait> = emptyList(),
) {
    fun isWaitingFor(
        operation: ReservationOperationType,
        activeOperation: ReservationOperationType,
    ): Boolean = waitingOperations.any { wait ->
        wait.operation == operation && wait.waitingFor == activeOperation
    }
}

/**
 * 予約・取消の状態変更を一つに直列化する内部ゲート。
 *
 * このゲートを取得してから各セッションのリクエスト制御へ入るため、書込み経路のロック順は
 * 常に Gate → RequestRateLimiter となる。世代は、状態変更 POST を開始し得る直前にだけ
 * 増やす。POST 開始後に停止しても世代だけが余分に進む安全側の設計である。
 */
@Singleton
class ReservationOperationGate @Inject constructor() {
    private val mutex = Mutex()
    private val stateLock = Any()
    private var writeGeneration = 0L
    private var activeOperation: ReservationOperationType? = null
    private val waitingOperations = mutableListOf<WaitingOperation>()
    private var nextWaitingId = 0L
    private val _state = MutableStateFlow(ReservationOperationGateState())

    /** 操作種別だけを公開する読み取り専用の観測契約。 */
    val state: StateFlow<ReservationOperationGateState> = _state

    internal suspend fun <T> withOperation(
        operation: ReservationOperationType,
        block: suspend ReservationOperationScope.() -> T,
    ): T {
        val waiting = synchronized(stateLock) {
            WaitingOperation(++nextWaitingId, operation).also {
                waitingOperations += it
                publishState()
            }
        }
        var acquired = false
        try {
            mutex.lock()
            acquired = true
            synchronized(stateLock) {
                check(activeOperation == null) { "書込ゲートの保持状態が不正です" }
                waitingOperations.remove(waiting)
                activeOperation = operation
                publishState()
            }
            return ReservationOperationScope(this).block()
        } finally {
            if (acquired) {
                try {
                    synchronized(stateLock) {
                        // 取得直後に例外・キャンセルしても、登録済み待機行を残さない。
                        waitingOperations.remove(waiting)
                        activeOperation = null
                        publishState()
                    }
                } finally {
                    // 観測状態の更新が想定外に失敗しても、後続書込みを止めない。
                    mutex.unlock()
                }
            } else {
                synchronized(stateLock) {
                    waitingOperations.remove(waiting)
                    publishState()
                }
            }
        }
    }

    internal suspend fun currentWriteGeneration(): Long {
        mutex.lock()
        return try {
            writeGeneration
        } finally {
            mutex.unlock()
        }
    }

    private fun markWriteStarted(): Long {
        check(writeGeneration != Long.MAX_VALUE) { "書込み世代が上限に達しました" }
        writeGeneration += 1
        return writeGeneration
    }

    internal class ReservationOperationScope internal constructor(
        private val gate: ReservationOperationGate,
    ) {
        val currentWriteGeneration: Long
            get() = gate.writeGeneration

        /** 状態変更 POST を送信し得る直前に一度だけ呼ぶ。 */
        fun markWriteStarted(): Long = gate.markWriteStarted()
    }

    private fun publishState() {
        _state.value = ReservationOperationGateState(
            activeOperation = activeOperation,
            waitingOperations = waitingOperations.map { waiting ->
                ReservationOperationWait(waiting.operation, activeOperation)
            },
        )
    }

    private data class WaitingOperation(
        val id: Long,
        val operation: ReservationOperationType,
    )
}
