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
 * Always Off: SystemUI, current home, every enabled IME, Settings, EnterSend itself.
 * Off by default, no notification: FLAG_SYSTEM packages with no launcher icon.
 * Everything with a launcher icon behaves normally (system or not) — Google Messages, stock SMS.
 */
class SystemAppPolicy(private val context: Context) {
    private val pm: PackageManager get() = context.packageManager

    fun isAlwaysOff(pkg: String): Boolean {
        if (pkg == context.packageName) return true
        if (isSystemUi(pkg)) return true
        if (pkg == homePackage()) return true
        if (pkg in enabledImePackages()) return true
        if (isSettingsApp(pkg)) return true
        return false
    }

    /** FLAG_SYSTEM (or updated-system) with no launcher activity. */
    fun isHiddenSystemApp(pkg: String): Boolean {
        if (isAlwaysOff(pkg)) return false // classified separately
        val flags = try {
            pm.getApplicationInfo(pkg, 0).flags
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        val system = flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
            flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        return system && !hasLauncherIcon(pkg)
    }

    fun isSystemApp(pkg: String): Boolean {
        if (isAlwaysOff(pkg) || isHiddenSystemApp(pkg)) return true
        return try {
            val flags = pm.getApplicationInfo(pkg, 0).flags
            flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Defaults Off with no prompt — always-off list or hidden system app. */
    fun defaultsOff(pkg: String): Boolean = isAlwaysOff(pkg) || isHiddenSystemApp(pkg)

    fun mayPrompt(pkg: String): Boolean = !defaultsOff(pkg)

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
