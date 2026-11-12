package com.meowgi.entersend.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * Schema changes: bump [VERSION], add a Migration to [MIGRATIONS] and a test in AppDbMigrationTest
 * that upgrades a database filled with real v(N-1) data. Never enable destructive fallback.
 */
@Database(entities = [AppRule::class], version = AppDb.VERSION, exportSchema = true)
abstract class AppDb : RoomDatabase() {
    abstract fun rules(): RuleDao

    companion object {
        const val VERSION = 1
        const val NAME = "entersend.db"

        val MIGRATIONS: Array<Migration> = arrayOf()

        fun build(context: Context, name: String = NAME): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, name)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
