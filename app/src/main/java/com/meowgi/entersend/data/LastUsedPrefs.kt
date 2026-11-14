package com.meowgi.entersend.data

import android.content.Context

/**
 * Last-used app for the Home card (0.3.15). Persists the latest eligible package and a
 * user exclusion set. Kept in SharedPreferences (not Room) so schema stays stable.
 */
object LastUsedPrefs {
    private const val PREFS = "entersend_last_used"
    private const val KEY_LAST = "last_pkg"
    private const val KEY_EXCLUDED = "excluded_pkgs"

    fun lastPackage(context: Context): String? =
        prefs(context).getString(KEY_LAST, null)?.takeIf { it.isNotBlank() }

    fun setLastPackage(context: Context, pkg: String?) {
        val ed = prefs(context).edit()
        if (pkg.isNullOrBlank()) ed.remove(KEY_LAST) else ed.putString(KEY_LAST, pkg)
        ed.apply()
    }

    fun excluded(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_EXCLUDED, emptySet())?.toSet() ?: emptySet()

    fun isExcluded(context: Context, pkg: String): Boolean = pkg in excluded(context)

    fun setExcluded(context: Context, pkgs: Set<String>) {
        prefs(context).edit().putStringSet(KEY_EXCLUDED, HashSet(pkgs)).apply()
    }

    fun setExcluded(context: Context, pkg: String, excluded: Boolean) {
        val next = excluded(context).toMutableSet()
        if (excluded) next += pkg else next -= pkg
        setExcluded(context, next)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
