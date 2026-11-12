package com.ovehbe.entersend.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RuleDao {
    @Query("SELECT * FROM app_rules WHERE packageName = :pkg")
    fun get(pkg: String): AppRule?

    @Query("SELECT * FROM app_rules")
    fun all(): List<AppRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(rule: AppRule)

    @Query("INSERT OR IGNORE INTO app_rules (packageName, teachDeclined, successCount, failureCount) VALUES (:pkg, 0, 0, 0)")
    fun ensure(pkg: String)

    @Query("UPDATE app_rules SET successCount = successCount + 1 WHERE packageName = :pkg")
    fun addSuccess(pkg: String)

    @Query("UPDATE app_rules SET failureCount = failureCount + 1 WHERE packageName = :pkg")
    fun addFailure(pkg: String)

    @Query("UPDATE app_rules SET lastAskedDay = :day WHERE packageName = :pkg")
    fun setLastAsked(pkg: String, day: Long)

    @Query("UPDATE app_rules SET teachDeclined = 1 WHERE packageName = :pkg")
    fun setDeclined(pkg: String)

    @Query("DELETE FROM app_rules WHERE packageName = :pkg")
    fun delete(pkg: String)
}
