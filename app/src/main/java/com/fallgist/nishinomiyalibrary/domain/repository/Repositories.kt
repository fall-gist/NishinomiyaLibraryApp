package com.fallgist.nishinomiyalibrary.domain.repository

import com.fallgist.nishinomiyalibrary.domain.model.BookDetail
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfContent
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutation
import com.fallgist.nishinomiyalibrary.domain.model.BookshelfMutationOutcome
import com.fallgist.nishinomiyalibrary.domain.model.ClosedDay
import com.fallgist.nishinomiyalibrary.domain.model.Library
import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.NewArrival
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationPickupSubmissionRecord
import com.fallgist.nishinomiyalibrary.domain.model.ReadingInfo
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord
import com.fallgist.nishinomiyalibrary.domain.model.SearchPage
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.model.UserSummary
import com.fallgist.nishinomiyalibrary.domain.model.ReservationBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartAddSummary
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCartItem
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelBatchResult
import com.fallgist.nishinomiyalibrary.domain.model.ReservationCancelTarget
import com.fallgist.nishinomiyalibrary.domain.model.ReservationConfirmation
import com.fallgist.nishinomiyalibrary.domain.model.ReservationTarget
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionOutcome
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionTarget
import com.fallgist.nishinomiyalibrary.domain.model.LoanExtensionBatchResult
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

    /**
     * 予約送信時にアプリが記録した受取館(表示専用)。予約中一覧が受取館「未定」の名前解決に使う
     * (`docs/ui-design.md`「方針: 一覧画面の行レイアウト統一」6番)。[reservations]の`pickupLibrary`
     * (サイトの値)とは別入力であり、混ぜて返してはならない。
     */
    fun pickupSubmissions(): Flow<List<ReservationPickupSubmissionRecord>>

    fun shelf(memberId: Long): Flow<List<ShelfItem>>

    fun summaries(): Flow<List<UserSummary>>

    fun lastSync(): Flow<SyncLog?>

    suspend fun syncAll(trigger: SyncTrigger): SyncResult
}

/** 本棚の読み取りと、明示的な利用者操作からのみ呼ぶ編集公開API。 */
interface BookshelfRepository {
    fun observeShelves(memberId: Long): Flow<List<BookshelfContent>>

    suspend fun mutate(mutation: BookshelfMutation): BookshelfMutationOutcome
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

    /**
     * 複数件をまとめてカートへ追加する(`docs/design/bulk-selection.md` §7.1)。
     * 既に同一(memberId, tilcod)がカートにある対象は加算せず`skipped`に数える。
     * 存在しないmemberIdを含む対象も同様に`skipped`として扱い、他の対象の追加は継続する
     * (§10-2で確定した契約。全体を失敗させない)。
     */
    suspend fun addToCart(targets: List<ReservationTarget>): ReservationCartAddSummary

    suspend fun removeFromCart(cartItemId: Long)

    /** 複数件をまとめてカートから削除する(`docs/design/bulk-selection.md` §6.1)。 */
    suspend fun removeFromCart(cartItemIds: List<Long>)

    /** カートを空にする(`docs/design/bulk-selection.md` §6.1)。件数に関わらず全件削除する。 */
    suspend fun clearCart()

    suspend fun confirmCart(confirmation: ReservationConfirmation): ReservationBatchResult

    suspend fun reserveNow(
        target: ReservationTarget,
        confirmation: ReservationConfirmation,
    ): ReservationBatchResult

    /**
     * 複数件をまとめて直接予約する(`docs/design/bulk-selection-followup.md` §5)。カートを経由しない
     * (§5.2。`confirmCart`はカート内の全項目を確定するため、既存のカート内容を巻き込んでしまう)。
     * サイトに実データを作る、取り返しのつかない操作である。UIの明示操作・最終確認の後にだけ呼ぶこと。
     */
    suspend fun reserveNow(
        targets: List<ReservationTarget>,
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

/**
 * 貸出延長の公開API。1件ずつ処理する(`docs/design/loan-extension.md` §9.1)。
 * 呼出し元が貸出中一覧であることを前提にせず、画面種別に依存する引数・分岐を持たない。
 * 延長は利用者の明示操作からのみ呼ぶこと。自動処理・同期からは絶対に呼ばないこと。
 */
interface LoanExtensionRepository {
    suspend fun extendLoan(target: LoanExtensionTarget): LoanExtensionOutcome

    /**
     * 複数件を順に延長する(`docs/design/bulk-selection.md` §5.1)。1件が失敗しても後続を続行し、
     * 件ごとの結果を返す(`ReservationCancelRepository.cancelReservations`と同じ流儀)。
     * 実装は既存[extendLoan]を順に呼ぶループとする。Gateway(1件の二段階POSTと照合)は不変。
     *
     * [onProgress]は1件処理し終えるたびに呼ばれる(`completed`は1始まりの完了件数、`total`は対象件数)。
     * 一斉延長は1件ごとに一覧再取得＋二段階POSTを行うため長時間かかり、UI側の進捗表示
     * (`docs/design/bulk-selection.md` §5.2「N件目/M件」)に必要。既定値は何もしないラムダなので、
     * 進捗を使わない呼び出し元(テスト等)は指定しなくてよい。
     */
    suspend fun extendLoans(
        targets: List<LoanExtensionTarget>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): LoanExtensionBatchResult
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
