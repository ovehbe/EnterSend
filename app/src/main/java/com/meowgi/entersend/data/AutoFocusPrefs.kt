package com.meowgi.entersend.data

import android.content.Context
import org.json.JSONObject

/**
 * Per-app auto-focus (0.3.13). Off by default for every app.
 * Stores an optional field identity (viewId / className / relative bounds) and whether
 * gesture-tap fallback is required because a11y focus/click failed.
 */
object AutoFocusPrefs {
    private const val PREFS = "entersend_autofocus"
    private const val KEY_GLOBAL_LEGACY = "auto_focus_enabled"
    private const val KEY_OVERRIDE_PREFIX = "override_"
    private const val KEY_ENABLED_PREFIX = "enabled_"
    private const val KEY_TARGET_PREFIX = "target_"
    private const val KEY_MIGRATED = "migrated_v13"

    /** Saved message-field identity for matching at runtime. */
    data class Target(
        val viewId: String?,
        val className: String?,
        /** Relative to window: 0..1. */
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        /** True when ACTION_FOCUS and ACTION_CLICK both failed; use gesture tap. */
        val tapFallback: Boolean = false,
    ) {
        val centerXFrac: Float get() = (left + right) / 2f
        val centerYFrac: Float get() = (top + bottom) / 2f
    }

    /** Call once from the service/UI so 0.3.12 prefs become per-app on/off. */
    fun migrateIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        val ed = p.edit()
        // Any package that had Override.ON stays on; Default/Off/global → off.
        for (key in p.all.keys) {
            if (!key.startsWith(KEY_OVERRIDE_PREFIX)) continue
            val pkg = key.removePrefix(KEY_OVERRIDE_PREFIX)
            val raw = p.getString(key, null)
            if (raw == "ON") ed.putBoolean(KEY_ENABLED_PREFIX + pkg, true)
            ed.remove(key)
        }
        ed.remove(KEY_GLOBAL_LEGACY)
        ed.putBoolean(KEY_MIGRATED, true)
        ed.apply()
    }

    fun isEnabled(context: Context, pkg: String): Boolean {
        migrateIfNeeded(context)
        return prefs(context).getBoolean(KEY_ENABLED_PREFIX + pkg, false)
    }

    fun setEnabled(context: Context, pkg: String, enabled: Boolean) {
        migrateIfNeeded(context)
        prefs(context).edit().putBoolean(KEY_ENABLED_PREFIX + pkg, enabled).apply()
    }

    fun target(context: Context, pkg: String): Target? {
        migrateIfNeeded(context)
        val raw = prefs(context).getString(KEY_TARGET_PREFIX + pkg, null) ?: return null
        return try {
            val o = JSONObject(raw)
            Target(
                viewId = o.optString("viewId").takeIf { it.isNotEmpty() && it != "null" },
                className = o.optString("className").takeIf { it.isNotEmpty() && it != "null" },
                left = o.getDouble("left").toFloat(),
                top = o.getDouble("top").toFloat(),
                right = o.getDouble("right").toFloat(),
                bottom = o.getDouble("bottom").toFloat(),
                tapFallback = o.optBoolean("tapFallback", false),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun setTarget(context: Context, pkg: String, target: Target?) {
        migrateIfNeeded(context)
        val ed = prefs(context).edit()
        if (target == null) {
            ed.remove(KEY_TARGET_PREFIX + pkg)
        } else {
            ed.putString(
                KEY_TARGET_PREFIX + pkg,
                JSONObject()
                    .put("viewId", target.viewId)
                    .put("className", target.className)
                    .put("left", target.left.toDouble())
                    .put("top", target.top.toDouble())
                    .put("right", target.right.toDouble())
                    .put("bottom", target.bottom.toDouble())
                    .put("tapFallback", target.tapFallback)
                    .toString(),
            )
        }
        ed.apply()
    }

    fun clear(context: Context, pkg: String) {
        migrateIfNeeded(context)
        prefs(context).edit()
            .remove(KEY_ENABLED_PREFIX + pkg)
            .remove(KEY_TARGET_PREFIX + pkg)
            .apply()
    }

    fun buildTarget(
        viewId: String?,
        className: String?,
        boundsLeft: Int,
        boundsTop: Int,
        boundsRight: Int,
        boundsBottom: Int,
        windowWidth: Int,
        windowHeight: Int,
        tapFallback: Boolean = false,
    ): Target {
        val w = windowWidth.coerceAtLeast(1).toFloat()
        val h = windowHeight.coerceAtLeast(1).toFloat()
        return Target(
            viewId = viewId,
            className = className,
            left = boundsLeft / w,
            top = boundsTop / h,
            right = boundsRight / w,
            bottom = boundsBottom / h,
            tapFallback = tapFallback,
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
