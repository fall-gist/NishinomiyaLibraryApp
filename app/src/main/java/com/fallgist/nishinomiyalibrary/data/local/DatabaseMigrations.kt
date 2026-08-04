package com.fallgist.nishinomiyalibrary.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    /**
     * v1には本棚番号・名称が存在しないため、既存項目は未同期本棚（番号0・名称空）として保存する。
     * 次回の利用者同期で実サイトの本棚情報に全置換される。
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE shelf_items RENAME TO shelf_items_v1")
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS shelves (" +
                    "memberId INTEGER NOT NULL, shelfNo INTEGER NOT NULL, name TEXT NOT NULL, " +
                    "PRIMARY KEY(memberId, shelfNo))",
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS shelf_items (" +
                    "memberId INTEGER NOT NULL, shelfNo INTEGER NOT NULL, tilcod TEXT NOT NULL, " +
                    "title TEXT NOT NULL, memo TEXT NOT NULL, registeredDate TEXT NOT NULL, " +
                    "PRIMARY KEY(memberId, shelfNo, tilcod))",
            )
            database.execSQL(
                "INSERT INTO shelves(memberId, shelfNo, name) " +
                    "SELECT DISTINCT memberId, 0, '' FROM shelf_items_v1",
            )
            database.execSQL(
                "INSERT INTO shelf_items(memberId, shelfNo, tilcod, title, memo, registeredDate) " +
                    "SELECT memberId, 0, tilcod, title, memo, registeredDate FROM shelf_items_v1",
            )
            database.execSQL("DROP TABLE shelf_items_v1")
        }
    }

    /** v3で読書記録の永続蓄積と、現在貸出のタイトルコードを追加する。 */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE loans ADD COLUMN tilcod TEXT NOT NULL DEFAULT ''")
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS reading_records (" +
                    "memberId INTEGER NOT NULL, tilcod TEXT NOT NULL, title TEXT NOT NULL, " +
                    "loanDate TEXT NOT NULL, library TEXT NOT NULL, titleNormalized TEXT NOT NULL, " +
                    "PRIMARY KEY(memberId, tilcod, loanDate))",
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS index_reading_records_memberId ON reading_records(memberId)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_reading_records_tilcod ON reading_records(tilcod)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_reading_records_titleNormalized ON reading_records(titleNormalized)")
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS reading_history_checkpoints (" +
                    "memberId INTEGER NOT NULL, tilcod TEXT NOT NULL, loanDate TEXT NOT NULL, " +
                    "PRIMARY KEY(memberId, tilcod, loanDate))",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_reading_history_checkpoints_memberId " +
                    "ON reading_history_checkpoints(memberId)",
            )
        }
    }

    /** v4で全ジャンル統合の新着資料テーブルを追加する。 */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS new_arrivals (" +
                    "tilcod TEXT NOT NULL, title TEXT NOT NULL, volume TEXT NOT NULL, " +
                    "author TEXT NOT NULL, publisher TEXT NOT NULL, publishedYearMonth TEXT NOT NULL, " +
                    "classification TEXT NOT NULL, lendable INTEGER, " +
                    "PRIMARY KEY(tilcod))",
            )
        }
    }

    /** v5で予約に書誌詳細リンク用のタイトルコードを追加する。 */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE reservations ADD COLUMN tilcod TEXT NOT NULL DEFAULT ''")
        }
    }

    /** v6でアプリ独自の予約カートを追加する。 */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS reservation_cart_items (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "memberId INTEGER NOT NULL, tilcod TEXT NOT NULL, title TEXT NOT NULL, " +
                    "writerLine TEXT, addedAtEpochMillis INTEGER NOT NULL, " +
                    "FOREIGN KEY(memberId) REFERENCES members(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS index_reservation_cart_items_memberId ON reservation_cart_items(memberId)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reservation_cart_items_memberId_tilcod ON reservation_cart_items(memberId, tilcod)")
        }
    }

    /** v7で予約に取消ボタン(yoykCancel)由来の予約コードを追加する。 */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE reservations ADD COLUMN cancelCode TEXT NOT NULL DEFAULT ''")
        }
    }

    /** v8で新着キーワード自動予約の設定・制御・表示履歴と送信館記録を追加する。 */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS auto_reservation_rules (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, enabled INTEGER NOT NULL, sortOrder INTEGER NOT NULL)",
            )
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_auto_reservation_rules_sortOrder ON auto_reservation_rules(sortOrder)")
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS auto_reservation_terms (" +
                    "ruleId INTEGER NOT NULL, kind TEXT NOT NULL, sortOrder INTEGER NOT NULL, original TEXT NOT NULL, normalized TEXT NOT NULL, " +
                    "PRIMARY KEY(ruleId, kind, sortOrder), " +
                    "FOREIGN KEY(ruleId) REFERENCES auto_reservation_rules(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS index_auto_reservation_terms_ruleId ON auto_reservation_terms(ruleId)")
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS auto_reservation_controls (" +
                    "tilcod TEXT NOT NULL, firstCandidateDate TEXT NOT NULL, expiresOn TEXT NOT NULL, status TEXT NOT NULL, preparedMemberId INTEGER, " +
                    "PRIMARY KEY(tilcod))",
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS auto_reservation_latest_run (" +
                    "id INTEGER NOT NULL, runId INTEGER NOT NULL, completedAtEpochMillis INTEGER NOT NULL, summaryJson TEXT NOT NULL, acknowledged INTEGER NOT NULL, " +
                    "PRIMARY KEY(id))",
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS auto_reservation_latest_items (" +
                    "runId INTEGER NOT NULL, tilcod TEXT NOT NULL, title TEXT NOT NULL, matchedRulesJson TEXT NOT NULL, attemptedMembersJson TEXT NOT NULL, outcome TEXT NOT NULL, " +
                    "PRIMARY KEY(runId, tilcod))",
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS reservation_pickup_submissions (" +
                    "memberId INTEGER NOT NULL, tilcod TEXT NOT NULL, pickupLibraryCode TEXT NOT NULL, origin TEXT NOT NULL, " +
                    "PRIMARY KEY(memberId, tilcod), " +
                    "FOREIGN KEY(memberId) REFERENCES members(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS index_reservation_pickup_submissions_memberId ON reservation_pickup_submissions(memberId)")
        }
    }

    /**
     * v9で貸出延長の表示用可否フラグ`extendable`を追加する。
     * 送信に使う延長コード(renewalCode)はRoomへ保存しない(`docs/design/loan-extension.md` §4.1)。
     * 既存インストールは次回同期で実値が入るため、既定はfalseでよい(`tilcod`追加時と同じ流儀)。
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE loans ADD COLUMN extendable INTEGER NOT NULL DEFAULT 0")
        }
    }
}
