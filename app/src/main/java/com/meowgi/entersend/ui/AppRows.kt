package com.meowgi.entersend.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.meowgi.entersend.core.AppLists
import com.meowgi.entersend.core.SystemAppPolicy
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.CachedApp
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
     * Full PackageManager scan used only when the Room cache is cold or packages changed.
     * Does **not** load icons.
     */
    fun scanPackages(context: Context, policy: SystemAppPolicy = SystemAppPolicy(context)): List<CachedApp> {
        val pm = context.packageManager
        val launchable = launchablePackages(context)
        return (launchable).distinct().filter { it != context.packageName }.mapNotNull { pkg ->
            scanOne(context, pkg, policy, pm)
        }
    }

    fun scanOne(
        context: Context,
        pkg: String,
        policy: SystemAppPolicy = SystemAppPolicy(context),
        pm: PackageManager = context.packageManager,
    ): CachedApp? {
        if (pkg == context.packageName) return null
        val info = try {
            pm.getPackageInfo(pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val label = info.applicationInfo?.loadLabel(pm)?.toString() ?: pkg
        return CachedApp(
            packageName = pkg,
            label = label,
            isSystem = policy.isSystemApp(pkg),
            versionCode = info.longVersionCode,
        )
    }

    fun launchablePackages(context: Context): List<String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        var resolved = pm.queryIntentActivities(intent, 0)
        // Some devices return an empty list with flags=0 right after an upgrade; MATCH_ALL
        // recovers without requiring QUERY_ALL_PACKAGES.
        if (resolved.isEmpty()) {
            resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        return resolved.map { it.activityInfo.packageName }
    }

    fun fromCache(
        cached: List<CachedApp>,
        rules: List<AppRule>,
        policy: SystemAppPolicy,
    ): List<AppRow> {
        val byPkg = rules.associateBy { it.packageName }
        val fromCache = cached.map { rowFromCached(it, byPkg[it.packageName] ?: AppRule(it.packageName), policy) }
        // Include rule-only packages that are no longer launchable (still show in list).
        val seen = fromCache.map { it.pkg }.toHashSet()
        val orphans = byPkg.values.filter { it.packageName !in seen }.map { rule ->
            AppRow(
                pkg = rule.packageName,
                label = rule.packageName,
                icon = null,
                rule = rule,
                versionCode = null,
                mode = policy.resolveMode(rule),
                isSystemApp = policy.isSystemApp(rule.packageName),
            )
        }
        return fromCache + orphans
    }

    fun rowFromCached(cached: CachedApp, rule: AppRule, policy: SystemAppPolicy): AppRow =
        AppRow(
            pkg = cached.packageName,
            label = cached.label,
            icon = null,
            rule = rule,
            versionCode = cached.versionCode,
            mode = policy.resolveMode(rule),
            // Live policy — never trust a stale cached isSystem flag to hide user apps.
            isSystemApp = policy.isSystemApp(cached.packageName),
        )

    /**
     * Legacy full load (icons included). Kept for older call sites/tests; Apps UI uses cache + lazy icons.
     */
    fun load(context: Context, rules: List<AppRule>, policy: SystemAppPolicy = SystemAppPolicy(context)): List<AppRow> {
        val pm = context.packageManager
        val byPkg = rules.associateBy { it.packageName }
        val launchable = launchablePackages(context)
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
     * Case-insensitive match on label and package, including substring and word/segment starts.
     * Pure string work — never touches PackageManager.
     */
    fun matchesQuery(label: String, pkg: String, query: CharSequence): Boolean {
        val q = query.trim().toString().lowercase(Locale.ROOT)
        if (q.isEmpty()) return true
        val labelL = label.lowercase(Locale.ROOT)
        val pkgL = pkg.lowercase(Locale.ROOT)
        if (q in labelL || q in pkgL) return true
        if (words(labelL).any { it.startsWith(q) }) return true
        if (pkgL.split('.').any { it.startsWith(q) }) return true
        return false
    }

    private fun words(s: String): List<String> =
        s.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /**
     * Apps EnterSend has seen first, then alphabetical; narrowed by [query], [needsTeachingOnly],
     * and [showSystemApps] (system packages hidden unless checked).
     * Operates only on the in-memory [rows] — no PackageManager.
     */
    fun filter(
        rows: List<AppRow>,
        query: CharSequence,
        needsTeachingOnly: Boolean,
        showSystemApps: Boolean = false,
    ): List<AppRow> {
        return rows
            .filter { showSystemApps || !it.isSystemApp }
            .filter { !needsTeachingOnly || it.needsTeaching }
            .filter { matchesQuery(it.label, it.pkg, query) }
            .sortedWith(compareBy({ !it.seen }, { it.label.lowercase(Locale.ROOT) }))
    }
}
