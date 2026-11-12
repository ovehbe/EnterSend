package com.meowgi.entersend.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.meowgi.entersend.core.AppLists
import com.meowgi.entersend.data.AppRule
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
) {
    val mode: Mode get() = rule.effectiveMode
    val blocked: Boolean get() = pkg in AppLists.NEVER

    /** EnterSend has data for this app. */
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
}

object AppRows {
    /** Launchable apps plus apps with a saved rule. Touches the package manager; call off the main thread. */
    fun load(context: Context, rules: List<AppRule>): List<AppRow> {
        val pm = context.packageManager
        val byPkg = rules.associateBy { it.packageName }
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).map { it.activityInfo.packageName }
        return (launchable + byPkg.keys).distinct().filter { it != context.packageName }.map { pkg ->
            val info = try {
                pm.getPackageInfo(pkg, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            AppRow(
                pkg = pkg,
                label = info?.applicationInfo?.loadLabel(pm)?.toString() ?: pkg,
                icon = info?.applicationInfo?.loadIcon(pm),
                rule = byPkg[pkg] ?: AppRule(pkg),
                versionCode = info?.longVersionCode,
            )
        }
    }

    /** Apps EnterSend has seen first, then alphabetical; narrowed by [query] and [needsTeachingOnly]. */
    fun filter(rows: List<AppRow>, query: CharSequence, needsTeachingOnly: Boolean): List<AppRow> {
        val q = query.trim().toString().lowercase(Locale.ROOT)
        return rows
            .filter { !needsTeachingOnly || it.needsTeaching }
            .filter { q.isEmpty() || q in it.label.lowercase(Locale.ROOT) || q in it.pkg.lowercase(Locale.ROOT) }
            .sortedWith(compareBy({ !it.seen }, { it.label.lowercase(Locale.ROOT) }))
    }
}
