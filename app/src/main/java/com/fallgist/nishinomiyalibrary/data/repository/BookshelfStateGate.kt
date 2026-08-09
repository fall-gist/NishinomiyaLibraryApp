package com.fallgist.nishinomiyalibrary.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同一メンバーの同期スナップショットと本棚編集を直列化する。
 * 予約操作とは独立しており、ReservationOperationGate には参加しない。
 */
@Singleton
class BookshelfStateGate @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
