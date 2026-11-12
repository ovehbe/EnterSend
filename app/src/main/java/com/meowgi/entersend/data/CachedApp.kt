package com.meowgi.entersend.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Launchable / known package metadata cached for the Apps page (no icons). */
@Entity(tableName = "cached_apps")
data class CachedApp(
    @PrimaryKey val packageName: String,
    val label: String,
    val isSystem: Boolean = false,
    val versionCode: Long? = null,
)
