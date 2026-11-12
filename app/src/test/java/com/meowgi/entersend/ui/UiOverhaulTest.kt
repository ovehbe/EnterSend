package com.meowgi.entersend.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.widget.ListView
import android.widget.ViewFlipper
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.meowgi.entersend.BuildConfig
import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.R
import com.meowgi.entersend.context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
class UiOverhaulTest {
    private val app get() = context() as EnterSendApp

    @Before fun setUp() {
        install("com.whatsapp", "WhatsApp")
        MainActivity.prefs(app).edit().putBoolean(MainActivity.KEY_SETUP_DONE, true).apply()
    }

    @After fun tearDown() = app.db.close()

    private fun install(pkg: String, label: String) {
        val appInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label }
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply { packageName = pkg; applicationInfo = appInfo; longVersionCode = 1 },
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

    private fun settle() {
        repeat(3) {
            app.io.submit {}.get()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun open(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().get().also { settle() }

    @Test fun bottomNavSwitchesFourPages() {
        val a = open()
        val pager = a.findViewById<ViewFlipper>(R.id.pager)
        val nav = a.findViewById<BottomNavigationView>(R.id.bottom_nav)
        assertEquals(0, pager.displayedChild)

        nav.selectedItemId = R.id.nav_apps
        assertEquals(1, pager.displayedChild)
        assertEquals(1, a.findViewById<ListView>(R.id.apps).adapter.count)

        nav.selectedItemId = R.id.nav_profiles
        assertEquals(2, pager.displayedChild)

        nav.selectedItemId = R.id.nav_settings
        assertEquals(3, pager.displayedChild)
        assertTrue(
            a.findViewById<android.widget.TextView>(R.id.settings_version).text
                .contains(BuildConfig.VERSION_NAME),
        )
    }

    @Test fun versionIs031() {
        assertEquals("0.3.1", BuildConfig.VERSION_NAME)
        assertEquals(8, BuildConfig.VERSION_CODE)
    }

    @Test fun optimisticModeChangeUpdatesListWithoutWaitingForReload() {
        val a = open()
        val list = a.findViewById<ListView>(R.id.apps)
        list.performItemClick(list, 0, 0)
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(3) // Off
        val row = list.adapter.getItem(0) as AppRow
        assertEquals(com.meowgi.entersend.data.Mode.OFF, row.mode)
        settle()
    }

    @Test fun bottomNavFocusableForKeyboard() {
        val a = open()
        val nav = a.findViewById<BottomNavigationView>(R.id.bottom_nav)
        assertTrue(nav.menu.findItem(R.id.nav_home).isEnabled)
        assertTrue(nav.menu.findItem(R.id.nav_apps).isEnabled)
        assertTrue(nav.menu.findItem(R.id.nav_profiles).isEnabled)
        assertTrue(nav.menu.findItem(R.id.nav_settings).isEnabled)
    }
}
