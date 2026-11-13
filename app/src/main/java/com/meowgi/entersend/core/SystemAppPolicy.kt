package com.meowgi.entersend.core

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.Mode

/**
 * Runtime system-app rules. No database rows are written for defaults — only a manual
 * Settings override (or teach / ask bookkeeping the user triggered) creates a row.
 * Existing rows keep whatever [AppRule.mode] they already have.
 *
 * Always Off: SystemUI, current home, every enabled IME, Settings.
 * EnterSend itself is **not** always-off — the setup test chat needs Enter; non-chat fields
 * in our UI are still skipped by [ChatFieldGuard]. We never prompt to teach ourselves.
 *
 * All FLAG_SYSTEM / FLAG_UPDATED_SYSTEM_APP packages default Off and are never auto-prompted
 * (including launchable ones like stock Messages). The user can still enable them in Settings.
 * Only launcher-visible non-system apps are prompted once when opened without a config.
 */
class SystemAppPolicy(private val context: Context) {
    private val pm: PackageManager get() = context.packageManager

    fun isAlwaysOff(pkg: String): Boolean {
        // Not ourselves: SetupActivity's test compose box must receive Enter → send.
        if (isSystemUi(pkg)) return true
        if (pkg == homePackage()) return true
        if (pkg in enabledImePackages()) return true
        if (isSettingsApp(pkg)) return true
        return false
    }

    /** FLAG_SYSTEM (or updated-system) with no launcher activity. */
    fun isHiddenSystemApp(pkg: String): Boolean {
        if (isAlwaysOff(pkg)) return false // classified separately
        return hasSystemFlag(pkg) && !hasLauncherIcon(pkg)
    }

    fun hasSystemFlag(pkg: String): Boolean = try {
        val flags = pm.getApplicationInfo(pkg, 0).flags
        flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
            flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun isSystemApp(pkg: String): Boolean {
        if (isAlwaysOff(pkg) || isHiddenSystemApp(pkg)) return true
        return hasSystemFlag(pkg)
    }

    /** Defaults Off with no prompt — always-off list or any system-flagged package. */
    fun defaultsOff(pkg: String): Boolean = isAlwaysOff(pkg) || hasSystemFlag(pkg)

    fun mayPrompt(pkg: String): Boolean =
        pkg != context.packageName && hasLauncherIcon(pkg) && !defaultsOff(pkg)

    /**
     * Resolves the effective mode. A non-null [AppRule.mode] (manual override / prior row)
     * always wins; otherwise apply runtime defaults, then the usual Auto/Learned/Native rules.
     */
    fun resolveMode(rule: AppRule): Mode {
        rule.mode?.let { name ->
            return Mode.entries.firstOrNull { it.name == name } ?: Mode.AUTO
        }
        if (defaultsOff(rule.packageName)) return Mode.OFF
        return when {
            rule.packageName in AppLists.NATIVE_ENTER -> Mode.PASS_THROUGH
            rule.isTaught -> Mode.LEARNED
            else -> Mode.AUTO
        }
    }

    fun hasLauncherIcon(pkg: String): Boolean {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        return pm.queryIntentActivities(launch, 0).isNotEmpty()
    }

    fun homePackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }

    fun enabledImePackages(): Set<String> {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return emptySet()
        return imm.enabledInputMethodList.map { it.packageName }.toSet()
    }

    fun isSettingsApp(pkg: String): Boolean {
        if (pkg == "com.android.settings") return true
        return try {
            val intent = Intent(Settings.ACTION_SETTINGS)
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == pkg
        } catch (_: RuntimeException) {
            false
        }
    }

    fun isSystemUi(pkg: String): Boolean =
        pkg == "com.android.systemui" || pkg.endsWith(".systemui")
}
