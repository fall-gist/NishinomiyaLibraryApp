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
}
