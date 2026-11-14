package com.meowgi.entersend.data

import android.content.Context
import android.content.pm.PackageManager
import com.meowgi.entersend.core.SystemAppPolicy

/**
 * Eligibility + record/read for the Home "Last used app" card.
 * System apps / launcher / IMEs use [SystemAppPolicy.isSystemApp] (covers always-off list).
 */
object LastUsed {
    fun isEligible(
        pkg: String,
        ownPackage: String,
        isSystemApp: Boolean,
        userExcluded: Boolean,
        installed: Boolean = true,
    ): Boolean {
        if (!installed) return false
        if (pkg == ownPackage) return false
        if (isSystemApp) return false
        if (userExcluded) return false
        return true
    }

    fun isEligible(context: Context, pkg: String, policy: SystemAppPolicy = SystemAppPolicy(context)): Boolean =
        isEligible(
            pkg = pkg,
            ownPackage = context.packageName,
            isSystemApp = policy.isSystemApp(pkg),
            userExcluded = LastUsedPrefs.isExcluded(context, pkg),
            installed = isInstalled(context, pkg),
        )

    /** Record [pkg] when it becomes foreground, if eligible. */
    fun recordForeground(context: Context, pkg: String, policy: SystemAppPolicy = SystemAppPolicy(context)) {
        if (!isEligible(context, pkg, policy)) return
        LastUsedPrefs.setLastPackage(context, pkg)
    }

    /**
     * Current last-used package for the card, or null if missing / now ineligible.
     * Clears the stored value when it is no longer usable.
     */
    fun current(context: Context, policy: SystemAppPolicy = SystemAppPolicy(context)): String? {
        val pkg = LastUsedPrefs.lastPackage(context) ?: return null
        if (!isEligible(context, pkg, policy)) {
            LastUsedPrefs.setLastPackage(context, null)
            return null
        }
        return pkg
    }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
