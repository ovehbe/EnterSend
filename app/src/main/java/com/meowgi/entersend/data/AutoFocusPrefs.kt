package com.meowgi.entersend.data

import android.content.Context

/**
 * Optional auto-focus of the taught message field when a set-up app comes to the foreground
 * (0.3.12). Global default OFF; per-app Default / On / Off override.
 * Kept in SharedPreferences (not Room / export) so schema stays stable.
 */
object AutoFocusPrefs {
    private const val PREFS = "entersend_autofocus"
    private const val KEY_GLOBAL = "auto_focus_enabled"
    private const val KEY_OVERRIDE_PREFIX = "override_"

    enum class Override {
        /** Follow the global setting. */
        DEFAULT,
        ON,
        OFF,
    }

    /** Global switch. Off by default. */
    fun globalEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GLOBAL, false)

    fun setGlobalEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GLOBAL, enabled).apply()
    }

    fun override(context: Context, pkg: String): Override {
        val raw = prefs(context).getString(KEY_OVERRIDE_PREFIX + pkg, null) ?: return Override.DEFAULT
        return Override.entries.firstOrNull { it.name == raw } ?: Override.DEFAULT
    }

    fun setOverride(context: Context, pkg: String, value: Override) {
        val ed = prefs(context).edit()
        if (value == Override.DEFAULT) ed.remove(KEY_OVERRIDE_PREFIX + pkg)
        else ed.putString(KEY_OVERRIDE_PREFIX + pkg, value.name)
        ed.apply()
    }

    /** Effective on/off for [pkg]: per-app override wins over the global default. */
    fun enabledFor(context: Context, pkg: String): Boolean = when (override(context, pkg)) {
        Override.ON -> true
        Override.OFF -> false
        Override.DEFAULT -> globalEnabled(context)
    }

    fun clearOverride(context: Context, pkg: String) = setOverride(context, pkg, Override.DEFAULT)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
