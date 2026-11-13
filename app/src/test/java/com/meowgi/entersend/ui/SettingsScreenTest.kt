package com.meowgi.entersend.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.view.View
import android.widget.ListView
import android.widget.TextView
import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.R
import com.meowgi.entersend.context
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.Mode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    private val app get() = context() as EnterSendApp
    private val dao get() = app.db.rules()

    @Before fun setUp() {
        install("com.whatsapp", "WhatsApp", version = 11)
        MainActivity.prefs(app).edit().putBoolean(MainActivity.KEY_SETUP_DONE, true).apply()
    }

    @After fun tearDown() = app.db.close()

    private fun install(pkg: String, label: String, version: Long) {
        val appInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label }
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply { packageName = pkg; applicationInfo = appInfo; longVersionCode = version },
        )
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.Main"; applicationInfo = appInfo } },
        )
    }

    /** Lets background work and the UI callbacks it posts finish. */
    private fun settle() {
        repeat(3) {
            app.io.submit {}.get()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun <T> io(block: () -> T): T = app.io.submit<T> { block() }.get()

    private fun open(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().get().also { settle() }

    private fun firstRowStatus(a: MainActivity): String {
        val list = a.findViewById<ListView>(R.id.apps)
        val v = list.adapter.getView(0, null, list)
        return v.findViewById<TextView>(R.id.app_status).text.toString()
    }

    @Test fun firstRunOpensSetup() {
        MainActivity.prefs(app).edit().clear().apply()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val started = generateSequence { shadowOf(a).nextStartedActivity }.toList()
        assertTrue(started.any { it.component?.className == SetupActivity::class.java.name })
    }

    @Test fun redBannerWhenServiceIsOff() {
        val a = open()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.banner).visibility)
    }

    @Test fun listShowsAppsWithMode() {
        val a = open()
        val list = a.findViewById<ListView>(R.id.apps)
        assertEquals(1, list.adapter.count)
        assertEquals("WhatsApp", (list.adapter.getItem(0) as AppRow).label)
        assertTrue(firstRowStatus(a).startsWith(a.getString(R.string.mode_auto)))
    }

    @Test fun updatedAppShowsNeedsRecheck() {
        io { dao.upsert(AppRule("com.whatsapp", sendViewId = "com.whatsapp:id/send", taughtVersionCode = 10)) }
        val a = open()
        assertTrue(firstRowStatus(a).contains(a.getString(R.string.needs_recheck)))
    }

    @Test fun choosingOffFromTheAppMenuIsSaved() {
        val a = open()
        val list = a.findViewById<ListView>(R.id.apps)
        list.performItemClick(list, 0, 0)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        // Teach, Auto, Pass-through, Off
        shadowOf(dialog).clickOnItem(3)
        settle()
        assertEquals(Mode.OFF.name, io { dao.get("com.whatsapp") }!!.mode)
        assertTrue(firstRowStatus(a).startsWith(a.getString(R.string.mode_off)))
    }

    @Test fun resetForgetsTheApp() {
        io { dao.upsert(AppRule("com.whatsapp", sendDesc = "Send", successCount = 3)) }
        val a = open()
        val list = a.findViewById<ListView>(R.id.apps)
        list.performItemClick(list, 0, 0)
        // Re-teach, Auto, Learned, Pass-through, Off, Auto-focus, Reset
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(6)
        settle()
        assertNull(io { dao.get("com.whatsapp") })
    }

    @Test fun searchAndNeedsTeachingFilter() {
        install("org.telegram.messenger", "Telegram", version = 1)
        io { dao.upsert(AppRule("org.telegram.messenger", lastAskedDay = 1)) }
        val a = open()
        val list = a.findViewById<ListView>(R.id.apps)
        assertEquals(2, list.adapter.count)

        a.findViewById<android.widget.CheckBox>(R.id.needs_teaching).isChecked = true
        settle()
        assertEquals(1, list.adapter.count)
        assertEquals("Telegram", (list.adapter.getItem(0) as AppRow).label)

        a.findViewById<android.widget.CheckBox>(R.id.needs_teaching).isChecked = false
        settle()
        a.findViewById<android.widget.EditText>(R.id.search).setText("whats")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(150))
        settle()
        assertEquals(1, list.adapter.count)
        a.findViewById<android.widget.EditText>(R.id.search).setText("zzz")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(150))
        settle()
        assertEquals(0, list.adapter.count)
        assertEquals(a.getString(R.string.no_apps_match), a.findViewById<TextView>(R.id.empty).text.toString())
    }
}
