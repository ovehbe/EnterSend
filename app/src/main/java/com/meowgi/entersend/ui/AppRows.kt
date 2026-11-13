package com.meowgi.entersend.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.meowgi.entersend.core.AppLists
import com.meowgi.entersend.core.SystemAppPolicy
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.DisplayMode
import com.meowgi.entersend.data.Mode
import java.util.Locale

enum class Health { NONE, GOOD, MIXED, BAD }

class AppRow(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
    val rule: AppRule,
    /** Installed versionCode, null if unknown. */
    val versionCode: Long?,
    val mode: Mode,
    val isSystemApp: Boolean,
) {
    /** Instant UI update after a Settings write, before the background reload finishes. */
    fun withRule(rule: AppRule, mode: Mode = this.mode): AppRow =
        AppRow(pkg, label, icon, rule, versionCode, mode, isSystemApp)

    val blocked: Boolean get() = pkg in AppLists.NEVER

    /** EnterSend has data for this app (a real DB row with user-visible state). */
    val seen: Boolean
        get() = rule.isTaught || rule.mode != null || rule.lastAskedDay != null ||
            rule.successCount + rule.failureCount > 0

    val health: Health
        get() {
            val total = rule.successCount + rule.failureCount
            if (total == 0) return Health.NONE
            val rate = rule.successCount.toDouble() / total
            return when {
                rate >= 0.8 -> Health.GOOD
                rate >= 0.5 -> Health.MIXED
                else -> Health.BAD
            }
        }

    /** The app was updated since it was taught, or a tap-spot crop no longer matches. */
    val needsRecheck: Boolean
        get() = rule.needsRecheck ||
            (rule.isTaught && rule.taughtVersionCode != null && versionCode != null &&
                versionCode != rule.taughtVersionCode)

    val needsTeaching: Boolean
        get() {
            if (blocked || mode == Mode.OFF || mode == Mode.PASS_THROUGH) return false
            if (needsRecheck || health == Health.BAD) return true
            return !rule.isTaught && (rule.lastAskedDay != null || rule.failureCount > 0)
        }

    val displayMode: DisplayMode
        get() = when {
            blocked -> DisplayMode.NEVER
            mode == Mode.OFF -> DisplayMode.OFF
            mode == Mode.PASS_THROUGH -> DisplayMode.PASS_THROUGH
            rule.hasNodeRule -> DisplayMode.BUTTON
            rule.hasTapSpot -> DisplayMode.TAP_SPOT
            else -> DisplayMode.AUTO
        }
}

object AppRows {
    /**
     * Launchable apps plus apps with a saved rule. Touches the package manager; call off the
     * main thread. Exact 0.3.3 listing path — do not change the query or isSystem semantics.
     */
    fun load(context: Context, rules: List<AppRule>, policy: SystemAppPolicy = SystemAppPolicy(context)): List<AppRow> {
        val pm = context.packageManager
        val byPkg = rules.associateBy { it.packageName }
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        ).map { it.activityInfo.packageName }
        return (launchable + byPkg.keys).distinct().filter { it != context.packageName }.map { pkg ->
            val info = try {
                pm.getPackageInfo(pkg, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            val rule = byPkg[pkg] ?: AppRule(pkg)
            AppRow(
                pkg = pkg,
                label = info?.applicationInfo?.loadLabel(pm)?.toString() ?: pkg,
                icon = info?.applicationInfo?.loadIcon(pm),
                rule = rule,
                versionCode = info?.longVersionCode,
                mode = policy.resolveMode(rule),
                isSystemApp = policy.isSystemApp(pkg),
            )
        }
    }

    /**
     * Apps EnterSend has seen first, then alphabetical; narrowed by [query], [needsTeachingOnly],
     * and [showSystemApps] (system packages hidden unless checked). Exact 0.3.3 filter semantics.
     */
    fun filter(
        rows: List<AppRow>,
        query: CharSequence,
        needsTeachingOnly: Boolean,
        showSystemApps: Boolean = false,
    ): List<AppRow> {
        val q = query.trim().toString().lowercase(Locale.ROOT)
        return rows
            .filter { showSystemApps || !it.isSystemApp }
            .filter { !needsTeachingOnly || it.needsTeaching }
            .filter { q.isEmpty() || q in it.label.lowercase(Locale.ROOT) || q in it.pkg.lowercase(Locale.ROOT) }
            .sortedWith(compareBy({ !it.seen }, { it.label.lowercase(Locale.ROOT) }))
    }
}
