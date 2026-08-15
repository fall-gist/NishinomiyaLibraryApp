package com.fallgist.nishinomiyalibrary.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverters
import androidx.room.withTransaction
import com.fallgist.nishinomiyalibrary.data.local.dao.ClosedDayDao
import com.fallgist.nishinomiyalibrary.data.local.dao.LoanDao
import com.fallgist.nishinomiyalibrary.data.local.dao.MemberDao
import com.fallgist.nishinomiyalibrary.data.local.dao.NewArrivalDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReservationDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReservationCartDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReadingRecordDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ShelfItemDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ShelfDao
import com.fallgist.nishinomiyalibrary.data.local.dao.SyncLogDao
import com.fallgist.nishinomiyalibrary.data.local.dao.UserSummaryDao
import com.fallgist.nishinomiyalibrary.data.local.dao.AutoReservationDao
import com.fallgist.nishinomiyalibrary.data.local.dao.ReservationPickupSubmissionDao
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationControlEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationLatestItemEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationLatestRunEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationRuleEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.AutoReservationTermEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ClosedDayEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.LoanEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.NewArrivalEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationCartItemEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingHistoryCheckpointEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReadingRecordEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfItemEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ShelfEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.SyncLogEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.UserSummaryEntity
import com.fallgist.nishinomiyalibrary.data.local.entity.ReservationPickupSubmissionEntity
import java.time.LocalDate

/**
 * `@Database(version = ...)`と同じ値を保つ複製定数。バックアップの`sourceDbVersion`(診断用の
 * 情報であり読み込みの可否判定には使わない)に使う。アノテーション引数への自己参照を避けるため、
 * `@Database`側は引き続きリテラルの9を書き、こちらは手動で同期させる。
 */
const val APP_DATABASE_VERSION = 9

@Database(
    entities = [
        MemberEntity::class,
        LoanEntity::class,
        ReservationEntity::class,
        ShelfItemEntity::class,
        ShelfEntity::class,
        ClosedDayEntity::class,
        SyncLogEntity::class,
        UserSummaryEntity::class,
        ReadingRecordEntity::class,
        ReadingHistoryCheckpointEntity::class,
        NewArrivalEntity::class,
        ReservationCartItemEntity::class,
        AutoReservationRuleEntity::class,
        AutoReservationTermEntity::class,
        AutoReservationControlEntity::class,
        AutoReservationLatestRunEntity::class,
        AutoReservationLatestItemEntity::class,
        ReservationPickupSubmissionEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
@TypeConverters(LocalDateConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memberDao(): MemberDao
    abstract fun loanDao(): LoanDao
    abstract fun reservationDao(): ReservationDao
    abstract fun reservationCartDao(): ReservationCartDao
    abstract fun readingRecordDao(): ReadingRecordDao
    abstract fun shelfItemDao(): ShelfItemDao
    abstract fun shelfDao(): ShelfDao
    abstract fun closedDayDao(): ClosedDayDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun userSummaryDao(): UserSummaryDao
    abstract fun newArrivalDao(): NewArrivalDao
    abstract fun autoReservationDao(): AutoReservationDao
    abstract fun reservationPickupSubmissionDao(): ReservationPickupSubmissionDao

    /**
     * 同期結果をメンバー単位で置き換える。予約の通知済み時刻だけは、
     * 状態が変化しても同じ通知識別項目の行へ引き継ぐ。
     */
    @Transaction
    suspend fun replaceMemberSnapshot(
        memberId: Long,
        loans: List<LoanEntity>,
        reservations: List<ReservationEntity>,
        shelves: List<ShelfEntity>,
        shelfItems: List<ShelfItemEntity>,
        summary: UserSummaryEntity,
        readingRecords: List<ReadingRecordEntity> = emptyList(),
        readingHistoryCheckpoints: List<ReadingHistoryCheckpointEntity> = emptyList(),
    ) {
        require(loans.all { it.memberId == memberId }) { "貸出データのmemberIdが一致しません" }
        require(reservations.all { it.memberId == memberId }) { "予約データのmemberIdが一致しません" }
        require(shelfItems.all { it.memberId == memberId }) { "本棚データのmemberIdが一致しません" }
        require(summary.memberId == memberId) { "サマリのmemberIdが一致しません" }
        require(readingRecords.all { it.memberId == memberId }) { "読書記録のmemberIdが一致しません" }
        require(readingHistoryCheckpoints.all { it.memberId == memberId }) { "読書履歴チェックポイントのmemberIdが一致しません" }

        require(shelves.all { it.memberId == memberId }) { "本棚データのmemberIdが一致しません" }
        require(shelves.map { it.shelfNo }.distinct().size == shelves.size) { "本棚番号が重複しています" }
        require(shelfItems.all { item -> shelves.any { it.shelfNo == item.shelfNo } }) {
            "本棚項目に対応する本棚がありません"
        }

        withTransaction {
            val previousNotificationTimes = reservationDao()
                .getForMember(memberId)
                .groupBy { it.notificationKey() }
                .mapValues { (_, matchingReservations) ->
                    matchingReservations.map { it.firstReadyNotifiedAt }
                }
            val previousNotificationIndexes = mutableMapOf<ReservationNotificationKey, Int>()
            val reservationsToInsert = reservations.map { reservation ->
                val key = reservation.notificationKey()
                val index = previousNotificationIndexes[key] ?: 0
                previousNotificationIndexes[key] = index + 1
                reservation.copy(
                    firstReadyNotifiedAt = previousNotificationTimes[key]
                        ?.getOrNull(index)
                        ?: reservation.firstReadyNotifiedAt,
                )
            }

            loanDao().deleteForMember(memberId)
            reservationDao().deleteForMember(memberId)
            shelfItemDao().deleteForMember(memberId)
            shelfDao().deleteForMember(memberId)
            userSummaryDao().deleteForMember(memberId)

            loanDao().insertAll(loans)
            reservationDao().insertAll(reservationsToInsert)
            val activeTilcods = reservationsToInsert
                .filter { it.state != com.fallgist.nishinomiyalibrary.domain.model.ReservationState.CANCELLED }
                .filter { it.tilcod.isNotBlank() }
                .map { it.tilcod }
                .distinct()
            // サマリ件数と取消以外の解析行数が一致するときだけ、陰性を送信館記録の削除根拠にする。
            if (summary.reservationCount == reservationsToInsert.count { it.state != com.fallgist.nishinomiyalibrary.domain.model.ReservationState.CANCELLED }) {
                if (activeTilcods.isEmpty()) {
                    reservationPickupSubmissionDao().deleteForMember(memberId)
                } else {
                    reservationPickupSubmissionDao().deleteMissingFromCompleteSnapshot(memberId, activeTilcods)
                }
            }
            shelfDao().insertAll(shelves)
            shelfItemDao().insertAll(shelfItems)
            userSummaryDao().insert(summary)
            // 読書記録は同期で削除しない。新規・更新分だけを永続蓄積する。
            readingRecordDao().upsertAll(readingRecords)
            // サイト読書履歴由来のキーだけを差分同期の停止判定に使う。
            readingRecordDao().upsertHistoryCheckpoints(readingHistoryCheckpoints)
        }
    }

    /** 本棚だけを完全スナップショットで置き換え、既存サマリの本棚数だけを同期する。 */
    @Transaction
    suspend fun replaceShelfSnapshot(
        memberId: Long,
        shelves: List<ShelfEntity>,
        shelfItems: List<ShelfItemEntity>,
    ) {
        require(shelves.all { it.memberId == memberId }) { "本棚データのmemberIdが一致しません" }
        require(shelfItems.all { it.memberId == memberId }) { "本棚項目のmemberIdが一致しません" }
        require(shelves.map { it.shelfNo }.distinct().size == shelves.size) { "本棚番号が重複しています" }
        val shelfNos = shelves.mapTo(mutableSetOf()) { it.shelfNo }
        require(shelfItems.all { it.shelfNo in shelfNos }) { "本棚項目に対応する本棚がありません" }

        withTransaction {
            shelfItemDao().deleteForMember(memberId)
            shelfDao().deleteForMember(memberId)
            shelfDao().insertAll(shelves)
            shelfItemDao().insertAll(shelfItems)
            // サマリが未取得のケースで、他件数を推測した行は作らない。
            userSummaryDao().updateShelfCount(memberId, shelves.size)
        }
    }

    /** 新着資料を取得結果で全置換する(ジャンル横断の統合リストを丸ごと入れ替える)。 */
    @Transaction
    suspend fun replaceNewArrivals(items: List<NewArrivalEntity>) {
        withTransaction {
            newArrivalDao().clear()
            newArrivalDao().insertAll(items)
        }
    }

    /** 現在貸出だけを原子的に置換する。自動予約の利用状況取得からも利用する。 */
    @Transaction
    suspend fun replaceCurrentLoans(memberId: Long, loans: List<LoanEntity>) {
        require(loans.all { it.memberId == memberId }) { "貸出データのmemberIdが一致しません" }
        withTransaction {
            loanDao().deleteForMember(memberId)
            loanDao().insertAll(loans)
        }
    }

    /** 完全な予約一覧だけを原子的に置換し、既存の通知済み時刻を引き継ぐ。 */
    @Transaction
    suspend fun replaceCurrentReservations(memberId: Long, reservations: List<ReservationEntity>) {
        require(reservations.all { it.memberId == memberId }) { "予約データのmemberIdが一致しません" }
        withTransaction {
            val previousNotificationTimes = reservationDao()
                .getForMember(memberId)
                .groupBy { it.notificationKey() }
                .mapValues { (_, matchingReservations) -> matchingReservations.map { it.firstReadyNotifiedAt } }
            val indexes = mutableMapOf<ReservationNotificationKey, Int>()
            val toInsert = reservations.map { reservation ->
                val key = reservation.notificationKey()
                val index = indexes[key] ?: 0
                indexes[key] = index + 1
                reservation.copy(firstReadyNotifiedAt = previousNotificationTimes[key]?.getOrNull(index) ?: reservation.firstReadyNotifiedAt)
            }
            reservationDao().deleteForMember(memberId)
            reservationDao().insertAll(toInsert)
        }
    }

    /** 指定館の当日以降の休館日を、取得結果で置き換える。 */
    @Transaction
    suspend fun replaceFutureClosedDays(
        libraryCode: String,
        today: LocalDate,
        days: List<ClosedDayEntity>,
    ) {
        require(days.all { it.libraryCode == libraryCode }) { "休館日のlibraryCodeが一致しません" }

        withTransaction {
            closedDayDao().deleteFromToday(libraryCode, today)
            closedDayDao().insertAll(days.filter { !it.date.isBefore(today) })
        }
    }

    /** メンバー削除に伴い、そのメンバーに属するローカルデータも削除する。 */
    @Transaction
    suspend fun deleteMemberAndLocalData(member: MemberEntity) {
        withTransaction {
            loanDao().deleteForMember(member.id)
            reservationDao().deleteForMember(member.id)
            shelfItemDao().deleteForMember(member.id)
            shelfDao().deleteForMember(member.id)
            userSummaryDao().deleteForMember(member.id)
            readingRecordDao().deleteForMember(member.id)
            readingRecordDao().deleteHistoryCheckpointsForMember(member.id)
            reservationPickupSubmissionDao().deleteForMember(member.id)
            memberDao().delete(member)
        }
    }

    /** ネットワーク処理完了後に、達成済みのカート由来項目だけをまとめて削除する。 */
    @Transaction
    suspend fun deleteReservationCartItems(ids: List<Long>) {
        if (ids.isEmpty()) return
        withTransaction { reservationCartDao().deleteByIds(ids) }
    }

    /** sortOrderを既存順序の末尾に安定して採番してメンバーを保存する。 */
    @Transaction
    suspend fun insertMemberAtEnd(member: MemberEntity): Long = withTransaction {
        memberDao().insert(member.copy(id = 0, sortOrder = memberDao().nextSortOrder()))
    }

    /**
     * 設定インポート(全置換)。docs/design/settings-export-import.md §6の対象テーブルを削除してから、
     * 検証済みの新データを単一トランザクションで投入する。呼び出し側([BackupImporter])が
     * 投入前に全件検証を終えている前提であり、ここでは検証を行わない。
     *
     * members.id と auto_reservation_rules.id は元の値のまま挿入する(MemberDao.insert /
     * AutoReservationDao.insertRule を直接呼ぶ。sortOrder再採番は行わない)。外部キーを満たすため
     * membersを最初に投入し、削除は逆順(子テーブルから先)に行う。
     *
     * §6.1: `loans` / `reservations` / `shelves` / `shelf_items` / `user_summaries` は
     * `MemberEntity` への外部キーを持たずカスケード削除されないため、移行対象でなくても
     * ここで明示的に全消去する(消さないと旧メンバーのデータが同じidの新メンバーへ残る)。
     * `auto_reservation_latest_run` / `auto_reservation_latest_items` / `sync_logs` も
     * 前端末の記録として意味を持たないため削除する。**`closed_days` と `new_arrivals` は
     * メンバー非依存であり削除しない。**
     */
    @Transaction
    suspend fun replaceBackupData(
        members: List<MemberEntity>,
        autoReservationRules: List<AutoReservationRuleEntity>,
        autoReservationTerms: List<AutoReservationTermEntity>,
        autoReservationControls: List<AutoReservationControlEntity>,
        readingRecords: List<ReadingRecordEntity>,
        readingHistoryCheckpoints: List<ReadingHistoryCheckpointEntity>,
        reservationCartItems: List<ReservationCartItemEntity>,
    ) {
        withTransaction {
            // 外部キーを満たすため、子テーブルから先に削除する。
            reservationCartDao().clearAll()
            autoReservationDao().clearTerms()
            autoReservationDao().clearRules()
            autoReservationDao().clearControls()
            readingRecordDao().clearAllRecords()
            readingRecordDao().clearAllHistoryCheckpoints()

            // §6.1: メンバー依存キャッシュ(外部キーを持たずカスケードされない)。
            loanDao().clearAll()
            reservationDao().clearAll()
            shelfItemDao().clearAll()
            shelfDao().clearAll()
            userSummaryDao().clearAll()
            autoReservationDao().clearLatestItems()
            autoReservationDao().clearLatestRun()
            syncLogDao().clearAll()
            // reservation_pickup_submissions は members への外部キーCASCADEで自動的に消える。

            memberDao().clearAll()

            // 外部キーを満たすため、membersを最初に投入する。idは元の値のまま挿入する。
            members.forEach { memberDao().insert(it) }
            autoReservationRules.forEach { autoReservationDao().insertRule(it) }
            if (autoReservationTerms.isNotEmpty()) autoReservationDao().insertTerms(autoReservationTerms)
            autoReservationControls.forEach { autoReservationDao().upsertControl(it) }
            readingRecordDao().upsertAll(readingRecords)
            readingRecordDao().upsertHistoryCheckpoints(readingHistoryCheckpoints)
            if (reservationCartItems.isNotEmpty()) reservationCartDao().insertAll(reservationCartItems)
        }
    }

    /**
     * バックアップのエクスポート専用。§7.1: 個別クエリを順に発行すると、その間に
     * `SyncWorker` 等のバックグラウンド処理が割り込み、自己矛盾したJSON
     * (例: `reservationCartItems.memberId` が `members` に存在しない)を書き出しうる。
     * 単一トランザクション内で全件読み出すことでこれを防ぐ。
     */
    @Transaction
    suspend fun readBackupSnapshot(): BackupSnapshot = withTransaction {
        val rules = autoReservationDao().getRules()
        BackupSnapshot(
            members = memberDao().getAll(),
            autoReservationRules = rules,
            autoReservationTerms = autoReservationDao().getTerms(rules.map { it.id }),
            autoReservationControls = autoReservationDao().getAllControls(),
            readingRecords = readingRecordDao().getAll(),
            readingHistoryCheckpoints = readingRecordDao().getAllHistoryCheckpoints(),
            reservationCartItems = reservationCartDao().getAll(),
        )
    }

    private fun ReservationEntity.notificationKey(): ReservationNotificationKey = ReservationNotificationKey(
        memberId = memberId,
        title = title,
        materialType = materialType,
        reservedDate = reservedDate,
    )

    private data class ReservationNotificationKey(
        val memberId: Long,
        val title: String,
        val materialType: String,
        val reservedDate: LocalDate,
    )
}

/**
 * [AppDatabase.readBackupSnapshot]の戻り値。単一トランザクション内で読み出した、
 * 相互に矛盾のない状態のバックアップ対象データ一式(docs/design/settings-export-import.md §7.1)。
 */
data class BackupSnapshot(
    val members: List<MemberEntity>,
    val autoReservationRules: List<AutoReservationRuleEntity>,
    val autoReservationTerms: List<AutoReservationTermEntity>,
    val autoReservationControls: List<AutoReservationControlEntity>,
    val readingRecords: List<ReadingRecordEntity>,
    val readingHistoryCheckpoints: List<ReadingHistoryCheckpointEntity>,
    val reservationCartItems: List<ReservationCartItemEntity>,
)
