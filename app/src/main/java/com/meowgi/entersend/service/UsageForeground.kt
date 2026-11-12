package com.meowgi.entersend.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import com.meowgi.entersend.data.PromptPrefs

/**
 * Optional PACKAGE_USAGE_STATS backup for foreground detection.
 * Off by default — user enables in Settings and grants Usage Access.
 */
class UsageForeground(private val context: Context) {
    fun hasPermission(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isEnabled(): Boolean = PromptPrefs.usageFallbackEnabled(context) && hasPermission()

    /** Most recent ACTIVITY_RESUMED package in the last [windowMs], or null. */
    fun latestResumedPackage(windowMs: Long = 5_000L, now: Long = System.currentTimeMillis()): String? {
        if (!isEnabled()) return null
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val events = usm.queryEvents(now - windowMs, now) ?: return null
        val event = UsageEvents.Event()
        var latestPkg: String? = null
        var latestTime = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp >= latestTime) {
                latestTime = event.timeStamp
                latestPkg = event.packageName
            }
        }
        return latestPkg?.takeIf { it != context.packageName }
    }

    companion object {
        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
