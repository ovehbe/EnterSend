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

    // Cached across a policy instance — Apps list walks every package and previously
    // re-resolved HOME / IME / Settings on each call (very expensive on real devices).
    private val homePkg: String? by lazy { resolveHomePackage() }
    private val imePkgs: Set<String> by lazy { resolveEnabledImePackages() }
    private val settingsPkg: String? by lazy { resolveSettingsPackage() }

    fun isAlwaysOff(pkg: String): Boolean {
        // Not ourselves: SetupActivity's test compose box must receive Enter → send.
        if (isSystemUi(pkg)) return true
        if (pkg == homePkg) return true
        if (pkg in imePkgs) return true
        if (pkg == "com.android.settings" || pkg == settingsPkg) return true
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

    /**
     * Apps-list visibility (0.3.6+). On some OEM ROMs (e.g. Unihertz) launchable chat apps
     * live under /system or /product and carry FLAG_SYSTEM — hiding every FLAG_SYSTEM package
     * yields an empty Apps page. Only hide always-off packages and non-launchable system junk;
     * launchable FLAG_SYSTEM apps stay visible (prompt/mode defaults still use [defaultsOff]).
     */
    fun hideFromAppsList(pkg: String): Boolean {
        if (isAlwaysOff(pkg)) return true
        return hasSystemFlag(pkg) && !hasLauncherIcon(pkg)
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

    fun homePackage(): String? = homePkg

    fun enabledImePackages(): Set<String> = imePkgs

    fun isSettingsApp(pkg: String): Boolean =
        pkg == "com.android.settings" || pkg == settingsPkg

    private fun resolveHomePackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return try {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun resolveEnabledImePackages(): Set<String> {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return emptySet()
        return try {
            imm.enabledInputMethodList.map { it.packageName }.toSet()
        } catch (_: RuntimeException) {
            emptySet()
        }
    }

    private fun resolveSettingsPackage(): String? = try {
        val intent = Intent(Settings.ACTION_SETTINGS)
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    } catch (_: RuntimeException) {
        null
    }

    fun isSystemUi(pkg: String): Boolean =
        pkg == "com.android.systemui" || pkg.endsWith(".systemui")
}
