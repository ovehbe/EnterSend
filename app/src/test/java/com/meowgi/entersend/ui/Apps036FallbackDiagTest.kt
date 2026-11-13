package com.meowgi.entersend.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.R
import com.meowgi.entersend.context
import com.meowgi.entersend.core.SystemAppPolicy
import com.meowgi.entersend.data.AppCatalog
import com.meowgi.entersend.data.AppsListDiagnostics
import com.meowgi.entersend.data.PromptDecisionLog
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
class Apps036FallbackDiagTest {
    private val app get() = context() as EnterSendApp
    private val direct = Executors.newSingleThreadExecutor()

    @Before fun setUp() {
        AppIconLoader.clear()
        PromptDecisionLog.resetForTests(app)
        install("com.whatsapp", "WhatsApp", system = false)
        install("com.oem.chat", "OemChat", system = true) // Unihertz-style FLAG_SYSTEM launcher
        MainActivity.prefs(app).edit().putBoolean(MainActivity.KEY_SETUP_DONE, true).apply()
    }

    @After fun tearDown() {
        app.db.close()
        direct.shutdownNow()
    }

    private fun install(pkg: String, label: String, system: Boolean) {
        val appInfo = ApplicationInfo().apply {
            packageName = pkg
            nonLocalizedLabel = label
            flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
        }
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply {
                packageName = pkg
                applicationInfo = appInfo
                longVersionCode = 1
            },
        )
        val ri = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.Main"
                applicationInfo = appInfo
            }
        }
        // Package-scoped + unscoped — matches SystemAppPolicy.hasLauncherIcon + AppRows.
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg),
            ri,
        )
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            ri,
        )
    }

    private fun <T> offMain(block: () -> T): T = direct.submit(Callable(block)).get()

    private fun settle() {
        repeat(5) {
            app.io.submit {}.get()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun oemSystemLauncherVisibleUnderDefaultFilter() {
        val policy = SystemAppPolicy(app)
        assertFalse(policy.hideFromAppsList("com.oem.chat"))
        val rows = AppRows.loadExact033(app, emptyList(), policy)
        val visible = AppRows.filter(rows, "", false, false)
        assertTrue(visible.any { it.pkg == "com.whatsapp" })
        assertTrue(visible.any { it.pkg == "com.oem.chat" })
    }

    @Test fun emptyCacheFallsBackTo033Listing() {
        offMain { app.db.apps().clear() }
        val catalog = AppCatalog(app, app.db.apps(), direct, SystemAppPolicy(app))
        val rows = offMain { catalog.open(emptyList(), forceScan = true) }
        assertTrue(rows.any { it.pkg == "com.whatsapp" })
        assertTrue(
            catalog.lastLoadSource == "scan" ||
                catalog.lastLoadSource == "fallback" ||
                catalog.lastLoadSource == "033",
        )
        val line = catalog.lastDiagnostics.line()
        assertTrue(line.startsWith("scan="))
        assertTrue("launcher=" in line)
        assertTrue("shown=" in line)
        assertTrue(catalog.lastDiagnostics.shown >= 1)
    }

    @Test fun appsPageShowsDiagLineAndOemChat() {
        offMain {
            app.db.apps().clear()
            app.catalog.refresh(app.db.rules().all())
        }
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        settle()
        a.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
            .selectedItemId = R.id.nav_apps
        settle()
        val diag = a.findViewById<android.widget.TextView>(R.id.apps_diag).text.toString()
        assertTrue("diag=$diag", diag.contains("scan="))
        assertTrue(diag.contains("launcher="))
        assertTrue(diag.contains("shown="))
        assertTrue(diag.contains("err="))
        val list = a.findViewById<android.widget.ListView>(R.id.apps)
        val labels = (0 until list.adapter.count).map { (list.adapter.getItem(it) as AppRow).label }
        assertTrue(labels.contains("WhatsApp"))
        assertTrue(labels.contains("OemChat"))

        val log = PromptDecisionLog.get(app).snapshot()
        assertTrue(log.any { it.pkg == AppsListDiagnostics.LOG_PKG && it.decision.contains("scan=") })
    }
}
