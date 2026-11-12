package com.meowgi.entersend.core

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SystemAppPolicyTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var policy: SystemAppPolicy

    @Before fun setUp() {
        policy = SystemAppPolicy(context)
    }

    @Test fun enterSendItselfIsAlwaysOff() {
        assertTrue(policy.isAlwaysOff(context.packageName))
        assertEquals(Mode.OFF, policy.resolveMode(AppRule(context.packageName)))
    }

    @Test fun systemUiAlwaysOffNoPrompt() {
        install("com.android.systemui", system = true, launcher = false)
        assertTrue(policy.isAlwaysOff("com.android.systemui"))
        assertFalse(policy.mayPrompt("com.android.systemui"))
        assertEquals(Mode.OFF, policy.resolveMode(AppRule("com.android.systemui")))
    }

    @Test fun settingsAlwaysOff() {
        install("com.android.settings", system = true, launcher = true)
        assertTrue(policy.isAlwaysOff("com.android.settings"))
        assertFalse(policy.mayPrompt("com.android.settings"))
    }

    @Test fun hiddenSystemAppDefaultsOffWithoutRow() {
        install("com.android.providers.downloads", system = true, launcher = false)
        assertTrue(policy.isHiddenSystemApp("com.android.providers.downloads"))
        assertTrue(policy.defaultsOff("com.android.providers.downloads"))
        assertFalse(policy.mayPrompt("com.android.providers.downloads"))
        // No mode column → still Off via runtime policy.
        assertEquals(Mode.OFF, policy.resolveMode(AppRule("com.android.providers.downloads")))
    }

    @Test fun launchableSystemAppBehavesNormallyIncludingMessages() {
        install("com.google.android.apps.messaging", system = true, updated = true, launcher = true)
        assertFalse(policy.defaultsOff("com.google.android.apps.messaging"))
        assertTrue(policy.mayPrompt("com.google.android.apps.messaging"))
        assertEquals(Mode.AUTO, policy.resolveMode(AppRule("com.google.android.apps.messaging")))
    }

    @Test fun existingModeRowWinsOverRuntimeDefault() {
        install("com.android.providers.downloads", system = true, launcher = false)
        val overridden = AppRule("com.android.providers.downloads", mode = Mode.AUTO.name)
        assertEquals(Mode.AUTO, policy.resolveMode(overridden))
    }

    @Test fun userAppUnchanged() {
        install("com.whatsapp", system = false, launcher = true)
        assertFalse(policy.isSystemApp("com.whatsapp"))
        assertEquals(Mode.AUTO, policy.resolveMode(AppRule("com.whatsapp")))
        assertEquals(
            Mode.LEARNED,
            policy.resolveMode(AppRule("com.whatsapp", sendViewId = "id/send")),
        )
    }

    private fun install(pkg: String, system: Boolean, launcher: Boolean, updated: Boolean = false) {
        val ai = ApplicationInfo().apply {
            packageName = pkg
            flags = 0
            if (system) flags = flags or ApplicationInfo.FLAG_SYSTEM
            if (updated) flags = flags or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        }
        val pi = PackageInfo().apply {
            packageName = pkg
            applicationInfo = ai
        }
        val shadow = shadowOf(context.packageManager)
        shadow.installPackage(pi)
        if (launcher) {
            val ri = ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = pkg
                    name = "$pkg.Main"
                    applicationInfo = ai
                }
            }
            shadow.addResolveInfoForIntent(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg),
                ri,
            )
            // Also match the unscoped launcher query used by AppRows / hasLauncherIcon.
            shadow.addResolveInfoForIntent(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                ri,
            )
        }
    }
}
