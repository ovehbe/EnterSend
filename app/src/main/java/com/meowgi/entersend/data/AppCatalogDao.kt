package com.meowgi.entersend.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface AppCatalogDao {
    @Query("SELECT * FROM cached_apps")
    fun all(): List<CachedApp>

    @Query("SELECT packageName FROM cached_apps")
    fun packageNames(): List<String>

    @Query("SELECT COUNT(*) FROM cached_apps")
    fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(app: CachedApp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(apps: List<CachedApp>)

    @Query("DELETE FROM cached_apps WHERE packageName = :pkg")
    fun delete(pkg: String)

    @Query("DELETE FROM cached_apps")
    fun clear()

    @Transaction
    fun replaceAll(apps: List<CachedApp>) {
        clear()
        if (apps.isNotEmpty()) upsertAll(apps)
    }
}
