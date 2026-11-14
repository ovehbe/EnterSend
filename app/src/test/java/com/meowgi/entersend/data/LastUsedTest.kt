package com.meowgi.entersend.data

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import com.meowgi.entersend.BuildConfig
import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.context
import com.meowgi.entersend.core.SystemAppPolicy
import com.meowgi.entersend.service.EnterSendService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LastUsedTest {
    private val app get() = context() as EnterSendApp

    @Before fun setUp() {
        LastUsedPrefs.setLastPackage(app, null)
        LastUsedPrefs.setExcluded(app, emptySet())
    }

    @After fun tearDown() {
        LastUsedPrefs.setLastPackage(app, null)
        LastUsedPrefs.setExcluded(app, emptySet())
        app.db.close()
    }

    private fun install(pkg: String, label: String, system: Boolean = false) {
        val flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
        val appInfo = ApplicationInfo().apply {
            packageName = pkg
            nonLocalizedLabel = label
            this.flags = flags
        }
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply {
                packageName = pkg
                applicationInfo = appInfo
                longVersionCode = 1
            },
        )
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = pkg
                    name = "$pkg.Main"
                    applicationInfo = appInfo
                }
            },
        )
    }

    @Test fun pureEligibilityIgnoresOwnSystemAndExcluded() {
        assertTrue(
            LastUsed.isEligible(
                pkg = "com.whatsapp",
                ownPackage = "com.meowgi.entersend",
                isSystemApp = false,
                userExcluded = false,
            ),
        )
        assertFalse(
            LastUsed.isEligible(
                pkg = "com.meowgi.entersend",
                ownPackage = "com.meowgi.entersend",
                isSystemApp = false,
                userExcluded = false,
            ),
        )
        assertFalse(
            LastUsed.isEligible(
                pkg = "com.android.systemui",
                ownPackage = "com.meowgi.entersend",
                isSystemApp = true,
                userExcluded = false,
            ),
        )
        assertFalse(
            LastUsed.isEligible(
                pkg = "com.whatsapp",
                ownPackage = "com.meowgi.entersend",
                isSystemApp = false,
                userExcluded = true,
            ),
        )
        assertFalse(
            LastUsed.isEligible(
                pkg = "com.whatsapp",
                ownPackage = "com.meowgi.entersend",
                isSystemApp = false,
                userExcluded = false,
                installed = false,
            ),
        )
    }

    @Test fun prefsPersistLastAndExclusions() {
        LastUsedPrefs.setLastPackage(app, "com.whatsapp")
        assertEquals("com.whatsapp", LastUsedPrefs.lastPackage(app))
        LastUsedPrefs.setExcluded(app, "org.telegram.messenger", true)
        assertTrue(LastUsedPrefs.isExcluded(app, "org.telegram.messenger"))
        assertFalse(LastUsedPrefs.isExcluded(app, "com.whatsapp"))
        LastUsedPrefs.setExcluded(app, "org.telegram.messenger", false)
        assertFalse(LastUsedPrefs.isExcluded(app, "org.telegram.messenger"))
    }

    @Test fun recordIgnoresSystemEnterSendAndExcluded() {
        install("com.whatsapp", "WhatsApp")
        install("com.android.settings", "Settings", system = true)
        val policy = SystemAppPolicy(app)

        LastUsed.recordForeground(app, app.packageName, policy)
        assertNull(LastUsedPrefs.lastPackage(app))

        LastUsed.recordForeground(app, "com.android.settings", policy)
        assertNull(LastUsedPrefs.lastPackage(app))

        LastUsedPrefs.setExcluded(app, "com.whatsapp", true)
        LastUsed.recordForeground(app, "com.whatsapp", policy)
        assertNull(LastUsedPrefs.lastPackage(app))

        LastUsedPrefs.setExcluded(app, "com.whatsapp", false)
        LastUsed.recordForeground(app, "com.whatsapp", policy)
        assertEquals("com.whatsapp", LastUsedPrefs.lastPackage(app))
        assertEquals("com.whatsapp", LastUsed.current(app, policy))
    }

    @Test fun currentClearsWhenExcludedOrUninstalled() {
        install("com.whatsapp", "WhatsApp")
        val policy = SystemAppPolicy(app)
        LastUsedPrefs.setLastPackage(app, "com.whatsapp")
        assertEquals("com.whatsapp", LastUsed.current(app, policy))

        LastUsedPrefs.setExcluded(app, "com.whatsapp", true)
        assertNull(LastUsed.current(app, policy))
        assertNull(LastUsedPrefs.lastPackage(app))

        LastUsedPrefs.setExcluded(app, "com.whatsapp", false)
        LastUsedPrefs.setLastPackage(app, "com.gone.app")
        assertNull(LastUsed.current(app, policy))
        assertNull(LastUsedPrefs.lastPackage(app))
    }

    @Test fun serviceForegroundRecordsEligiblePackage() {
        install("com.whatsapp", "WhatsApp")
        val service = Robolectric.setupService(EnterSendService::class.java)
        // Drive package change the same way window-state updates do.
        val apply = EnterSendService::class.java.getDeclaredMethod(
            "applyForeground",
            String::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        apply.invoke(service, "com.whatsapp", "test")
        assertEquals("com.whatsapp", LastUsedPrefs.lastPackage(app))

        apply.invoke(service, app.packageName, "test")
        // Own package must not overwrite the last eligible app.
        assertEquals("com.whatsapp", LastUsedPrefs.lastPackage(app))
    }

    @Test fun versionIs0316() {
        assertEquals("0.3.16", BuildConfig.VERSION_NAME)
        assertEquals(23, BuildConfig.VERSION_CODE)
    }
}
