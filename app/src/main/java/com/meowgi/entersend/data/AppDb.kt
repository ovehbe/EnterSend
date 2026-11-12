package com.meowgi.entersend.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema changes: bump [VERSION], add a Migration to [MIGRATIONS] and a test in AppDbMigrationTest
 * that upgrades a database filled with real v(N-1) data. Never enable destructive fallback.
 */
@Database(entities = [AppRule::class], version = AppDb.VERSION, exportSchema = true)
abstract class AppDb : RoomDatabase() {
    abstract fun rules(): RuleDao

    companion object {
        const val VERSION = 2
        const val NAME = "entersend.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_rules ADD COLUMN consecutiveFailures INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_rules ADD COLUMN needsRecheck INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_rules ADD COLUMN tapDxDp REAL")
                db.execSQL("ALTER TABLE app_rules ADD COLUMN tapDyDp REAL")
                db.execSQL("ALTER TABLE app_rules ADD COLUMN tapFx REAL")
                db.execSQL("ALTER TABLE app_rules ADD COLUMN tapFy REAL")
                db.execSQL("ALTER TABLE app_rules ADD COLUMN keyboardShown INTEGER")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun build(context: Context, name: String = NAME): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, name)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
