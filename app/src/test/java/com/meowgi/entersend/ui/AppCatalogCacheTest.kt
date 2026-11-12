package com.meowgi.entersend.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Looper
import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.context
import com.meowgi.entersend.data.AppCatalog
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.CachedApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class AppCatalogCacheTest {
    private val app get() = context() as EnterSendApp
    private val direct = Executors.newSingleThreadExecutor()

    @Before fun setUp() {
        AppIconLoader.clear()
        install("com.whatsapp", "WhatsApp")
        install("org.telegram.messenger", "Telegram")
        MainActivity.prefs(app).edit().putBoolean(MainActivity.KEY_SETUP_DONE, true).apply()
    }

    @After fun tearDown() {
        app.db.close()
        direct.shutdownNow()
    }

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

    private fun <T> offMain(block: () -> T): T =
        direct.submit(Callable(block)).get()

    private fun settle() {
        repeat(4) {
            app.io.submit {}.get()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun secondOpenUsesCacheNotFullScan() {
        val catalog = AppCatalog(app, app.db.apps(), direct)
        offMain { catalog.fullScan(emptyList()) }
        assertEquals("scan", catalog.lastLoadSource)
        val firstMs = catalog.lastLoadMs
        assertTrue(firstMs >= 0)

        val again = offMain { catalog.open(emptyList()) }
        assertEquals("cache", catalog.lastLoadSource)
        assertEquals(2, again.size)
        // Cached open must be at least as fast as the scan that filled Room.
        assertTrue(
            "cache=${catalog.lastLoadMs} scan=$firstMs",
            catalog.lastLoadMs <= firstMs + 50,
        )
        assertTrue(again.all { it.icon == null })
    }

    @Test fun packageRemovedUpdatesCacheWithoutFullRescan() {
        val catalog = AppCatalog(app, app.db.apps(), direct)
        offMain { catalog.fullScan(emptyList()) }
        assertEquals(2, offMain { app.db.apps().count() })

        offMain { catalog.removePackage("com.whatsapp") }
        assertEquals(1, offMain { app.db.apps().count() })
        assertEquals(listOf("org.telegram.messenger"), catalog.memory.map { it.pkg })
    }

    @Test fun fromCacheMergesRulesWithoutPackageManagerIcons() {
        val cached = listOf(
            CachedApp("com.whatsapp", "WhatsApp", isSystem = false, versionCode = 10),
            CachedApp("com.android.systemui", "System UI", isSystem = true, versionCode = 1),
        )
        val rules = listOf(AppRule("com.whatsapp", lastAskedDay = 1))
        val rows = AppRows.fromCache(cached, rules, com.meowgi.entersend.core.SystemAppPolicy(app))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.icon == null })
        assertTrue(rows.first { it.pkg == "com.whatsapp" }.seen)
    }

    @Test fun searchFilterUsesInMemoryRowsAndStaysCorrectAfterRefresh() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        settle()
        a.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
            com.meowgi.entersend.R.id.bottom_nav,
        ).selectedItemId = com.meowgi.entersend.R.id.nav_apps
        settle()

        val search = a.findViewById<android.widget.EditText>(com.meowgi.entersend.R.id.search)
        search.setText("tele")
        // Debounce + off-main filter
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(150))
        settle()
        val list = a.findViewById<android.widget.ListView>(com.meowgi.entersend.R.id.apps)
        assertEquals(1, list.adapter.count)
        assertEquals("Telegram", (list.adapter.getItem(0) as AppRow).label)
        assertTrue(a.lastSearchMs >= 0)

        // Refresh catalog while query is active — results must stay correct.
        offMain { app.catalog.refresh(app.db.rules().all()) }
        a.findViewById<android.view.View>(com.meowgi.entersend.R.id.refresh_apps).performClick()
        settle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(150))
        settle()
        assertEquals(1, list.adapter.count)
        assertEquals("Telegram", (list.adapter.getItem(0) as AppRow).label)
    }

    @Test fun manualRefreshForcesScanSource() {
        val catalog = AppCatalog(app, app.db.apps(), direct)
        offMain { catalog.fullScan(emptyList()) }
        offMain { catalog.open(emptyList()) }
        assertEquals("cache", catalog.lastLoadSource)
        offMain { catalog.refresh(emptyList()) }
        assertEquals("scan", catalog.lastLoadSource)
        assertFalse(offMain { catalog.needsRescan() })
    }
}
