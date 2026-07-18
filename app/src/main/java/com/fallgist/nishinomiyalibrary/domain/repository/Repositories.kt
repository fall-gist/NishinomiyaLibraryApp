package com.fallgist.nishinomiyalibrary.domain.repository

import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.domain.model.ClosedDay
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.model.UserSummary
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** UIが参照する家族メンバーの公開API。 */
interface FamilyRepository {
    fun members(): Flow<List<Member>>

    suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String)

    suspend fun updateMember(member: Member, newPassword: String?)

    suspend fun removeMember(memberId: Long)
}
/** UIが参照する利用状況と同期の公開API。 */
interface StatusRepository {
    fun loans(): Flow<List<Loan>>

    fun reservations(): Flow<List<Reservation>>

    fun shelf(memberId: Long): Flow<List<ShelfItem>>

    fun summaries(): Flow<List<UserSummary>>

    fun lastSync(): Flow<SyncLog?>

    suspend fun syncAll(trigger: SyncTrigger): SyncResult
}

/** 検索はキャッシュせず、都度公式サイトとopenBDへ委譲する。 */
interface SearchRepository {
    suspend fun search(keyword: String, page: Int): SearchPage

    suspend fun autocomplete(keyword: String): List<String>

    suspend fun isLendable(tilcod: String): Boolean?

    suspend fun bookDetail(tilcod: String): BookDetail

    suspend fun coverUrl(isbn: String): String?
}

interface CalendarRepository {
    fun closedDays(libraryCode: String): Flow<List<ClosedDay>>

    suspend fun refreshClosedDays(libraryCode: String)

    val libraries: List<Library>
}

enum class SyncTrigger { MANUAL, SCHEDULED }

data class SyncLog(
    val id: Long,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long?,
    val trigger: SyncTrigger,
    val succeeded: Boolean?,
    val details: String,
)

/**
 * 同期の結果。partial failure はログ済みで、Workerだけが再試行可否を判断する。
 */
sealed interface SyncResult {
    data class Completed(
        val syncedMemberCount: Int,
        val failedMemberCount: Int,
    ) : SyncResult {
        val isCompleteSuccess: Boolean get() = failedMemberCount == 0
    }

    data class SkippedCooldown(val nextAllowedAtEpochMillis: Long) : SyncResult
}
