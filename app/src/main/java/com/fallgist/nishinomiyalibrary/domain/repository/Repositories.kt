package com.fallgist.nishinomiyalibrary.domain.repository

import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.domain.model.ClosedDay
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReadingInfo
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.model.UserSummary
import com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartItem
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelTarget
import com.fallgist.nishinomiyalibrary.domain.model.ReservationConfirmation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationControl
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationLatestRun
import com.fallgist.nishinomiyalibrary.domain.model.AutoReservationRule
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

/** 読書履歴をローカル永続層から提供する公開API。 */
interface ReadingRecordRepository {
    fun records(memberId: Long? = null): Flow<List<ReadingRecord>>

    fun search(query: String, memberId: Long? = null): Flow<List<ReadingRecord>>

    fun hasRead(tilcod: String): Flow<List<ReadingInfo>>
}

/** 全ジャンル統合の新着資料をローカルにキャッシュしつつ提供する公開API。 */
interface NewArrivalRepository {
    fun newArrivals(): Flow<List<NewArrival>>

    /** 公式サイトから取得し直してローカルを全置換する。失敗時は例外を投げる。 */
    suspend fun refresh()

    /** 直近の全置換取得時刻(epoch millis)。未取得ならnull。 */
    suspend fun lastFetchedAtEpochMillis(): Long?

    /** ローカルに新着資料が1件以上保持されているか。 */
    suspend fun hasCachedItems(): Boolean
}

/** 明示的な最終確認を境界とする、ローカル予約カートの公開API。 */
interface ReservationCartRepository {
    fun cartItems(): Flow<List<ReservationCartItem>>

    suspend fun addToCart(target: ReservationTarget)

    suspend fun removeFromCart(cartItemId: Long)

    suspend fun confirmCart(confirmation: ReservationConfirmation): ReservationBatchResult

    suspend fun reserveNow(
        target: ReservationTarget,
        confirmation: ReservationConfirmation,
    ): ReservationBatchResult
}

/**
 * 予約取消の公開API。複数件をまとめて依頼できる（UIの一括取消向け）。
 * 取消は利用者の明示操作からのみ呼ぶこと。自動処理・同期からは絶対に呼ばないこと
 * （サイトに副作用を及ぼす操作であり、誤って自動実行すると取り返しがつかないため）。
 */
interface ReservationCancelRepository {
    suspend fun cancelReservations(targets: List<ReservationCancelTarget>): ReservationCancelBatchResult
}

/** 自動予約の設定・制御記録・直近表示履歴を扱う内部ユースケース用の永続境界。 */
interface AutoReservationRepository {
    suspend fun rules(): List<AutoReservationRule>
    suspend fun replaceRules(rules: List<AutoReservationRule>)
    suspend fun removeExpiredControls(today: LocalDate): Int
    /** 前回のPOST境界で停止した資料を、preparedMemberIdに依存せず安全側の終端へ倒す。 */
    suspend fun markPreparedControlsUnknown(): Int
    suspend fun control(tilcod: String): AutoReservationControl?
    suspend fun saveControl(control: AutoReservationControl)
    fun latestRun(): Flow<AutoReservationLatestRun?>
    suspend fun replaceLatestRun(run: AutoReservationLatestRun)
    /** 指定した実行だけを確認済みにする。別実行へ更新済みの場合は false を返す。 */
    suspend fun markLatestRunAcknowledged(runId: Long): Boolean
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
}
