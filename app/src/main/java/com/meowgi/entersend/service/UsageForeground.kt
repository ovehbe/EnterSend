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
 * Optional PACKAGE_USAGE_STATS backup for prompt foreground detection.
 * Cross-checks MOVE_TO_FOREGROUND / ACTIVITY_RESUMED when permission is granted.
 */
class UsageForeground(private val context: Context) {
    fun hasPermission(): Boolean {
        permissionOverrideForTests?.let { return it }
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isEnabled(): Boolean = PromptPrefs.usageFallbackEnabled(context) && hasPermission()

    /**
     * Most recent move-to-foreground / activity-resumed package in [windowMs], or null.
     * Only when Usage Access is granted (and the Settings toggle is on, or [requireToggle] is false).
     */
    fun latestForegroundPackage(
        windowMs: Long = 5_000L,
        now: Long = System.currentTimeMillis(),
        requireToggle: Boolean = true,
    ): String? {
        if (requireToggle) {
            if (!isEnabled()) return null
        } else if (!hasPermission()) {
            return null
        }
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val events = usm.queryEvents(now - windowMs, now) ?: return null
        val event = UsageEvents.Event()
        var latestPkg: String? = null
        var latestTime = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = event.eventType
            val isFg = type == UsageEvents.Event.ACTIVITY_RESUMED ||
                type == UsageEvents.Event.MOVE_TO_FOREGROUND
            if (isFg && event.timeStamp >= latestTime) {
                latestTime = event.timeStamp
                latestPkg = event.packageName
            }
        }
        return latestPkg?.takeIf { it != context.packageName }
    }

    /** @deprecated use [latestForegroundPackage] */
    fun latestResumedPackage(windowMs: Long = 5_000L, now: Long = System.currentTimeMillis()): String? =
        latestForegroundPackage(windowMs, now)

    companion object {
        /** Unit tests only. */
        var permissionOverrideForTests: Boolean? = null

        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
