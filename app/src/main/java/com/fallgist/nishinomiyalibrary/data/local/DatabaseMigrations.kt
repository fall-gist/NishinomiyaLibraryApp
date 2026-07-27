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
}
