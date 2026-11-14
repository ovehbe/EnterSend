package com.meowgi.entersend.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.R
import com.meowgi.entersend.context
import com.meowgi.entersend.data.LastUsedPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowAlertDialog
import java.lang.reflect.Field
import java.lang.reflect.Method

@RunWith(RobolectricTestRunner::class)
class LastUsedCardTest {
    private val app get() = context() as EnterSendApp

    @Before fun setUp() {
        install("com.whatsapp", "WhatsApp")
        install("org.telegram.messenger", "Telegram")
        MainActivity.prefs(app).edit().putBoolean(MainActivity.KEY_SETUP_DONE, true).apply()
        LastUsedPrefs.setLastPackage(app, null)
        LastUsedPrefs.setExcluded(app, emptySet())
        AppRows.fullLoadCountForTests = 0
    }

    @After fun tearDown() {
        LastUsedPrefs.setLastPackage(app, null)
        LastUsedPrefs.setExcluded(app, emptySet())
        AppRows.fullLoadCountForTests = 0
        app.db.close()
    }

    private fun rowsField(): Field =
        MainActivity::class.java.getDeclaredField("rows").apply { isAccessible = true }

    private fun showActionsForPackage(a: MainActivity, pkg: String) {
        val m: Method = MainActivity::class.java.getDeclaredMethod("showActionsForPackage", String::class.java)
        m.isAccessible = true
        m.invoke(a, pkg)
    }

    private fun showActions(a: MainActivity, row: AppRow) {
        val m: Method = MainActivity::class.java.getDeclaredMethod("showActions", AppRow::class.java)
        m.isAccessible = true
        m.invoke(a, row)
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

    private fun settle() {
        repeat(3) {
            app.io.submit {}.get()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun open(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().get().also { settle() }

    @Test fun emptyStateWhenNoLastUsed() {
        val a = open()
        assertEquals(View.GONE, a.findViewById<View>(R.id.last_used_card).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.last_used_empty).visibility)
    }

    @Test fun cardShowsLabelAndOpensSameSheetAsApps() {
        LastUsedPrefs.setLastPackage(app, "com.whatsapp")
        val a = open()
        val card = a.findViewById<View>(R.id.last_used_card)
        assertEquals(View.VISIBLE, card.visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.last_used_empty).visibility)
        assertEquals("WhatsApp", a.findViewById<TextView>(R.id.last_used_label).text.toString())
        assertTrue(card.contentDescription.toString().contains("WhatsApp"))

        card.performClick()
        settle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertEquals("WhatsApp", shadowOf(dialog).title.toString())
        // Same first action as Apps row: Teach
        assertTrue(shadowOf(dialog).items!!.any { it.toString().contains(a.getString(R.string.action_teach)) })
    }

    @Test fun settingsOpensExcludeList() {
        val a = open()
        a.findViewById<View>(R.id.exclude_from_recent).performClick()
        val started = shadowOf(a).nextStartedActivity
        assertEquals(ExcludeRecentActivity::class.java.name, started.component?.className)
    }

    @Test fun excludeListPersistsCheckbox() {
        val a = Robolectric.buildActivity(ExcludeRecentActivity::class.java).setup().visible().get()
        settle()
        val list = a.findViewById<ListView>(R.id.exclude_list)
        assertTrue(list.adapter.count >= 1)
        // Alphabetical: Telegram then WhatsApp — click WhatsApp by label search of full list.
        val whatsIdx = (0 until list.adapter.count).first {
            (list.adapter.getItem(it) as ExcludeRecentActivity.Entry).pkg == "com.whatsapp"
        }
        list.performItemClick(list.getChildAt(whatsIdx) ?: list, whatsIdx, 0)
        assertTrue(LastUsedPrefs.isExcluded(app, "com.whatsapp"))
        assertFalse(LastUsedPrefs.isExcluded(app, "org.telegram.messenger"))
    }

    @Test fun cardTapWithNullRowsOpensSheetWithoutFullLoad() {
        LastUsedPrefs.setLastPackage(app, "com.whatsapp")
        val a = open()
        settle()
        // Simulate the race: Home visible but Apps list not cached yet.
        rowsField().set(a, null)
        val loadsBefore = AppRows.fullLoadCountForTests
        ShadowAlertDialog.reset()

        a.findViewById<View>(R.id.last_used_card).performClick()
        settle()

        assertEquals(
            "card tap must not trigger AppRows.load",
            loadsBefore,
            AppRows.fullLoadCountForTests,
        )
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertEquals("WhatsApp", shadowOf(dialog).title.toString())
    }

    @Test fun showActionsAfterOnStopDoesNotCrashOrShowDialog() {
        LastUsedPrefs.setLastPackage(app, "com.whatsapp")
        val controller: ActivityController<MainActivity> =
            Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val a = controller.get()
        settle()
        val row = AppRows.loadOne(app, "com.whatsapp")!!
        ShadowAlertDialog.reset()
        controller.pause().stop()
        settle()

        // Must not throw BadTokenException / WindowManager crash.
        showActions(a, row)
        settle()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())

        // Stale async card path after stop also drops.
        rowsField().set(a, null)
        val loadsBefore = AppRows.fullLoadCountForTests
        showActionsForPackage(a, "com.whatsapp")
        settle()
        assertEquals(loadsBefore, AppRows.fullLoadCountForTests)
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test fun excludeSearchFiltersAndSavesCorrectPackageWhileFiltered() {
        val a = Robolectric.buildActivity(ExcludeRecentActivity::class.java).setup().visible().get()
        settle()
        val list = a.findViewById<ListView>(R.id.exclude_list)
        assertEquals(2, list.adapter.count)

        a.findViewById<EditText>(R.id.exclude_search).setText("tele")
        settle()
        assertEquals(1, list.adapter.count)
        val shown = list.adapter.getItem(0) as ExcludeRecentActivity.Entry
        assertEquals("org.telegram.messenger", shown.pkg)
        assertEquals("Telegram", shown.label)

        list.performItemClick(list.getChildAt(0) ?: list, 0, 0)
        assertTrue(LastUsedPrefs.isExcluded(app, "org.telegram.messenger"))
        assertFalse(LastUsedPrefs.isExcluded(app, "com.whatsapp"))

        // Package-name match also works; checkbox state survives filter clear.
        a.findViewById<EditText>(R.id.exclude_search).setText("whatsapp")
        settle()
        assertEquals(1, list.adapter.count)
        assertEquals("com.whatsapp", (list.adapter.getItem(0) as ExcludeRecentActivity.Entry).pkg)

        a.findViewById<EditText>(R.id.exclude_search).setText("")
        settle()
        assertEquals(2, list.adapter.count)
        assertTrue(LastUsedPrefs.isExcluded(app, "org.telegram.messenger"))
        assertFalse(LastUsedPrefs.isExcluded(app, "com.whatsapp"))
    }
}
