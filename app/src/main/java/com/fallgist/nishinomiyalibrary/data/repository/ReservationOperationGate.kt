package com.fallgist.nishinomiyalibrary.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

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
    private var writeGeneration = 0L

    internal suspend fun <T> withOperation(block: suspend ReservationOperationScope.() -> T): T = mutex.withLock {
        ReservationOperationScope(this).block()
    }

    internal suspend fun currentWriteGeneration(): Long = mutex.withLock { writeGeneration }

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
}
