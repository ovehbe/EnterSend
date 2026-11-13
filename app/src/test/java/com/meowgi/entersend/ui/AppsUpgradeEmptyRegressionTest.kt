package com.meowgi.entersend.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.R
import com.meowgi.entersend.context
import com.meowgi.entersend.core.SystemAppPolicy
import com.meowgi.entersend.data.AppCatalog
import com.meowgi.entersend.data.AppDb
import com.meowgi.entersend.data.AppRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 0.3.4 upgrade regression: v2→v3 creates an empty `cached_apps` table. Opening Apps
 * (and Refresh) must still show installed non-system launcher apps — never a stuck
 * "No apps match." empty state.
 */
@RunWith(RobolectricTestRunner::class)
class AppsUpgradeEmptyRegressionTest {
    private val app get() = context() as EnterSendApp
    private val direct = Executors.newSingleThreadExecutor()
    private val dbName = "upgrade-empty-apps.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDb::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Before fun setUp() {
        AppIconLoader.clear()
        installUserApp("com.whatsapp", "WhatsApp")
        installUserApp("org.telegram.messenger", "Telegram")
        // System-flagged launchable app — must stay hidden when "show system" is off.
        installSystemApp("com.android.chrome", "Chrome")
        MainActivity.prefs(app).edit().putBoolean(MainActivity.KEY_SETUP_DONE, true).apply()
    }

    @After fun tearDown() {
        app.db.close()
        context().deleteDatabase(dbName)
        direct.shutdownNow()
    }

    private fun installUserApp(pkg: String, label: String) {
        val appInfo = ApplicationInfo().apply {
            packageName = pkg
            nonLocalizedLabel = label
            flags = 0
        }
        install(pkg, appInfo)
    }

    private fun installSystemApp(pkg: String, label: String) {
        val appInfo = ApplicationInfo().apply {
            packageName = pkg
            nonLocalizedLabel = label
            flags = ApplicationInfo.FLAG_SYSTEM
        }
        install(pkg, appInfo)
    }

    private fun install(pkg: String, appInfo: ApplicationInfo) {
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

    private fun <T> offMain(block: () -> T): T =
        direct.submit(Callable(block)).get()

    private fun settle() {
        repeat(5) {
            app.io.submit {}.get()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Build a real v2 DB, migrate to v3 → empty cached_apps (the upgrade situation). */
    private fun migrateV2ToV3EmptyCache(): AppDb {
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL(
                "INSERT INTO app_rules (packageName, mode, teachDeclined, successCount, failureCount, " +
                    "consecutiveFailures, needsRecheck) VALUES ('com.whatsapp', NULL, 0, 1, 0, 0, 0)",
            )
        }
        helper.runMigrationsAndValidate(dbName, 3, true, AppDb.MIGRATION_2_3).close()
        val db = AppDb.build(context(), dbName)
        assertEquals(0, offMain { db.apps().count() })
        return db
    }

    @Test fun emptyDaoAlwaysNeedsRescanEvenWithWarmMemoryAndAdvancedSeq() {
        val db = migrateV2ToV3EmptyCache()
        try {
            context().getSharedPreferences("entersend_apps_catalog", 0)
                .edit().putInt("pkg_seq", 999_999).apply()
            val catalog = AppCatalog(context(), db.apps(), direct, SystemAppPolicy(context()))
            // 0.3.4 failure mode: rule orphans warm memory while cached_apps stays empty.
            offMain { catalog.loadCached(listOf(AppRule("com.whatsapp", successCount = 1))) }
            assertTrue(catalog.memory.isNotEmpty())
            assertEquals(0, offMain { db.apps().count() })
            assertTrue(
                "empty cached_apps must force rescan even when memory looks warm",
                offMain { catalog.needsRescan() },
            )
        } finally {
            db.close()
        }
    }

    @Test fun afterV2toV3MigrationOpenShowsNonSystemApps() {
        val db = migrateV2ToV3EmptyCache()
        try {
            // Simulate 0.3.4 sticky failure: sequence advanced after an empty/failed scan.
            context().getSharedPreferences("entersend_apps_catalog", 0)
                .edit().putInt("pkg_seq", 999_999).apply()

            val catalog = AppCatalog(context(), db.apps(), direct, SystemAppPolicy(context()))
            // Warm memory with orphans only (pre-fix open path).
            offMain { catalog.loadCached(db.rules().all()) }
            val rows = offMain { catalog.open(db.rules().all()) }

            val visible = AppRows.filter(rows, "", needsTeachingOnly = false, showSystemApps = false)
            assertTrue(
                "upgrade open must show user apps, got labels=${visible.map { it.label }} source=${catalog.lastLoadSource}",
                visible.any { it.pkg == "com.whatsapp" },
            )
            assertTrue(visible.any { it.pkg == "org.telegram.messenger" })
            assertTrue(visible.none { it.pkg == "com.android.chrome" })
            assertTrue(offMain { db.apps().count() } >= 2)
            assertTrue(
                catalog.lastLoadSource == "scan" || catalog.lastLoadSource == "fallback",
            )
        } finally {
            db.close()
        }
    }

    @Test fun refreshRepopulatesAfterEmptyCache() {
        val db = migrateV2ToV3EmptyCache()
        try {
            val catalog = AppCatalog(context(), db.apps(), direct, SystemAppPolicy(context()))
            offMain { catalog.loadCached(emptyList()) }
            assertEquals(0, catalog.memory.size)

            val refreshed = offMain { catalog.refresh(db.rules().all()) }
            val visible = AppRows.filter(refreshed, "", false, false)
            assertTrue(visible.size >= 2)
            assertTrue(
                catalog.lastLoadSource == "scan" || catalog.lastLoadSource == "fallback",
            )
            assertTrue(offMain { db.apps().count() } >= 2)
        } finally {
            db.close()
        }
    }

    @Test fun mainActivityAppsTabNotStuckOnEmptyAfterUpgradeSeed() {
        // Use the real app DB: clear cache to simulate post-migration emptiness.
        offMain {
            app.db.apps().clear()
            app.catalog.loadCached(emptyList())
        }
        assertEquals(0, offMain { app.db.apps().count() })

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        settle()
        // Paint the sticky-empty state that 0.3.4 ensureAppsShown treated as "loaded".
        val rowsField = MainActivity::class.java.getDeclaredField("rows")
        rowsField.isAccessible = true
        rowsField.set(activity, emptyList<AppRow>())
        activity.findViewById<android.widget.ListView>(R.id.apps).let { list ->
            (list.adapter as AppListAdapter).submit(emptyList())
        }

        activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
            .selectedItemId = R.id.nav_apps
        settle()

        val list = activity.findViewById<android.widget.ListView>(R.id.apps)
        assertTrue(
            "Apps tab must not stay empty after upgrade-style empty cache; count=${list.adapter.count}",
            list.adapter.count >= 2,
        )

        // Refresh must keep / restore the list.
        activity.findViewById<android.view.View>(R.id.refresh_apps).performClick()
        settle()
        assertTrue(list.adapter.count >= 2)
    }

    @Test fun freshInstallStillShowsApps() {
        offMain {
            app.db.apps().clear()
            app.catalog.refresh(app.db.rules().all())
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        settle()
        activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
            .selectedItemId = R.id.nav_apps
        settle()
        val list = activity.findViewById<android.widget.ListView>(R.id.apps)
        assertTrue(list.adapter.count >= 2)
    }
}
