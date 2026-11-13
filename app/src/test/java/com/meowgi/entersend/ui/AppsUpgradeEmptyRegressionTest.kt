package com.meowgi.entersend.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.view.View
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.R
import com.meowgi.entersend.context
import com.meowgi.entersend.core.SystemAppPolicy
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
 * 0.3.8: Apps listing is exact 0.3.3 [AppRows.load] — Room `cached_apps` may stay empty after
 * v2→v3 upgrade and must not affect the list. Spinner blocks the page until the swap.
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
        repeat(8) {
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

    @Test fun afterV2toV3MigrationAppRowsLoadShowsNonSystemApps() {
        val db = migrateV2ToV3EmptyCache()
        try {
            val rules = offMain { db.rules().all() }
            assertTrue(rules.any { it.packageName == "com.whatsapp" })
            // Table may stay unused — listing must not depend on it.
            assertEquals(0, offMain { db.apps().count() })

            val rows = offMain { AppRows.load(context(), rules, SystemAppPolicy(context())) }
            val visible = AppRows.filter(rows, "", needsTeachingOnly = false, showSystemApps = false)
            assertTrue(
                "upgrade AppRows.load must show user apps, got labels=${visible.map { it.label }}",
                visible.any { it.pkg == "com.whatsapp" },
            )
            assertTrue(visible.any { it.pkg == "org.telegram.messenger" })
            assertTrue(visible.none { it.pkg == "com.android.chrome" })
            // cached_apps still unused after load
            assertEquals(0, offMain { db.apps().count() })
        } finally {
            db.close()
        }
    }

    @Test fun mainActivityShowsAppsAndHidesSpinner() {
        offMain { app.db.apps().clear() }
        assertEquals(0, offMain { app.db.apps().count() })

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val overlay = activity.findViewById<View>(R.id.apps_loading)
        // Spinner should have been shown during load; after settle it is gone and list is full.
        settle()
        assertEquals(View.GONE, overlay.visibility)

        activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
            .selectedItemId = R.id.nav_apps
        settle()

        val list = activity.findViewById<android.widget.ListView>(R.id.apps)
        assertTrue(
            "Apps must list user launchers; count=${list.adapter.count}",
            list.adapter.count >= 2,
        )

        activity.findViewById<View>(R.id.refresh_apps).performClick()
        // Overlay visible while refresh runs (may already be gone if io is fast — settle either way).
        settle()
        assertEquals(View.GONE, overlay.visibility)
        assertTrue(list.adapter.count >= 2)
    }

    @Test fun freshInstallStillShowsApps() {
        offMain { app.db.apps().clear() }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        settle()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.apps_loading).visibility)
        activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
            .selectedItemId = R.id.nav_apps
        settle()
        val list = activity.findViewById<android.widget.ListView>(R.id.apps)
        assertTrue(list.adapter.count >= 2)
    }

    @Test fun ruleOnlyAppAppearsEvenWithoutLauncher() {
        // 0.3.3: launchable + packages with a saved rule.
        val rules = listOf(AppRule("com.orphan.rule", successCount = 1))
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply {
                packageName = "com.orphan.rule"
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.orphan.rule"
                    nonLocalizedLabel = "Orphan"
                    flags = 0
                }
                longVersionCode = 1
            },
        )
        val rows = offMain { AppRows.load(context(), rules, SystemAppPolicy(context())) }
        assertTrue(rows.any { it.pkg == "com.orphan.rule" })
    }
}
