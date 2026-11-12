package com.meowgi.entersend.data

import android.content.Context

/**
 * Prompt policy prefs (kept out of Room / export JSON so schema stays stable).
 * - Global switch to silence all new-app prompts.
 * - Per-package snooze deadline (ms since epoch); 0 = not snoozed.
 */
object PromptPrefs {
    private const val PREFS = "entersend_prompts"
    private const val KEY_ENABLED = "prompts_enabled"
    private const val KEY_SNOOZE_PREFIX = "snooze_"

    /** Default "Remind me later" snooze: 1 hour. */
    const val SNOOZE_MS = 60L * 60L * 1000L

    fun promptsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setPromptsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun snoozeUntil(context: Context, pkg: String): Long =
        prefs(context).getLong(KEY_SNOOZE_PREFIX + pkg, 0L)

    fun isSnoozed(context: Context, pkg: String, nowMs: Long): Boolean {
        val until = snoozeUntil(context, pkg)
        return until > nowMs
    }

    fun snooze(context: Context, pkg: String, untilMs: Long) {
        prefs(context).edit().putLong(KEY_SNOOZE_PREFIX + pkg, untilMs).apply()
    }

    fun clearSnooze(context: Context, pkg: String) {
        prefs(context).edit().remove(KEY_SNOOZE_PREFIX + pkg).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
