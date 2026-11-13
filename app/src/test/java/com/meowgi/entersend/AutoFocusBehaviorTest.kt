package com.meowgi.entersend

import com.meowgi.entersend.core.SystemAppPolicy
import com.meowgi.entersend.data.AppDb
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.AutoFocusPrefs
import com.meowgi.entersend.data.EnterDiagLog
import com.meowgi.entersend.service.AutoFocusController
import com.meowgi.entersend.service.RuleStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutoFocusBehaviorTest {
    private lateinit var db: AppDb
    private val device = FakeDevice()
    private val pkg = "com.example.chat"
    private val fieldId = "$pkg:id/input"
    private lateinit var store: RuleStore
    private lateinit var ctrl: AutoFocusController

    @Before fun setUp() {
        db = memoryDb()
        EnterDiagLog.resetForTests(context())
        context().getSharedPreferences("entersend_autofocus", 0).edit().clear().apply()
        store = RuleStore(db.rules(), direct, direct, FakeCrops())
        device.messageFields = listOf(
            chatField("", pkg = pkg, viewId = fieldId, hint = "Message"),
        )
        device.field = null
        ctrl = AutoFocusController(
            device = device,
            store = store,
            policy = SystemAppPolicy(context()),
            enabledFor = { AutoFocusPrefs.enabledFor(context(), it) },
            diag = EnterDiagLog.get(context()),
        )
    }

    @After fun tearDown() {
        db.close()
        EnterDiagLog.resetForTests(context())
    }

    private fun teachRule() = AppRule(
        pkg,
        fieldViewId = fieldId,
        sendDesc = "Send", // node rule → isTaught without needing a crop file on disk
    )

    private fun enableGlobal() = AutoFocusPrefs.setGlobalEnabled(context(), true)

    private fun installTaught() {
        val rule = teachRule()
        db.rules().upsert(rule)
        store.putLocal(rule)
    }

    @Test fun defaultOffDoesNotFocus() {
        installTaught()
        assertEquals(false, AutoFocusPrefs.globalEnabled(context()))
        ctrl.onAppearance(pkg, "a1")
        assertTrue(device.focuses.isEmpty())
    }

    @Test fun focusesOncePerAppearance() {
        enableGlobal()
        installTaught()
        ctrl.onAppearance(pkg, "win-1")
        assertEquals(1, device.focuses.size)
        assertEquals(fieldId, device.focuses[0].viewId)
        ctrl.onAppearance(pkg, "win-1")
        assertEquals("must not focus again for the same appearance", 1, device.focuses.size)
    }

    @Test fun newAppearanceMayFocusAgain() {
        enableGlobal()
        installTaught()
        ctrl.onAppearance(pkg, "win-1")
        device.field = null
        device.focuses.clear()
        ctrl.onAppearance(pkg, "win-2")
        assertEquals(1, device.focuses.size)
    }

    @Test fun doesNotReStealAfterUserMovedFocus() {
        enableGlobal()
        installTaught()
        ctrl.onAppearance(pkg, "win-1")
        assertEquals(1, device.focuses.size)
        // User taps elsewhere: clear compose focus.
        device.field = null
        device.focuses.clear()
        ctrl.onAppearance(pkg, "win-1")
        assertTrue("must not steal focus back on same appearance", device.focuses.isEmpty())
    }

    @Test fun noOpWhenEditableAlreadyFocused() {
        enableGlobal()
        installTaught()
        device.field = chatField("hi", pkg = pkg, viewId = fieldId)
        ctrl.onAppearance(pkg, "win-1")
        assertTrue(device.focuses.isEmpty())
        // Same appearance later still no-op even if focus was lost after the skip.
        device.field = null
        ctrl.onAppearance(pkg, "win-1")
        assertTrue(device.focuses.isEmpty())
    }

    @Test fun noOpWhenFieldMissing() {
        enableGlobal()
        installTaught()
        device.messageFields = emptyList()
        device.root = null
        ctrl.onAppearance(pkg, "win-1")
        assertTrue(device.focuses.isEmpty())
        val log = EnterDiagLog.get(context()).formatText()
        assertTrue(log.contains("auto-focus"))
        assertTrue(log.contains("field-not-found") || log.contains("skip"))
    }

    @Test fun perAppOnOverridesGlobalOff() {
        AutoFocusPrefs.setGlobalEnabled(context(), false)
        AutoFocusPrefs.setOverride(context(), pkg, AutoFocusPrefs.Override.ON)
        installTaught()
        ctrl.onAppearance(pkg, "win-1")
        assertEquals(1, device.focuses.size)
    }

    @Test fun perAppOffOverridesGlobalOn() {
        enableGlobal()
        AutoFocusPrefs.setOverride(context(), pkg, AutoFocusPrefs.Override.OFF)
        installTaught()
        ctrl.onAppearance(pkg, "win-1")
        assertTrue(device.focuses.isEmpty())
    }

    @Test fun skipsAppsThatAreNotSetUp() {
        enableGlobal()
        // No taught rule in store.
        ctrl.onAppearance(pkg, "win-1")
        assertTrue(device.focuses.isEmpty())
    }

    @Test fun skipsOffModeEvenWhenTaught() {
        enableGlobal()
        val rule = teachRule().copy(mode = "OFF")
        db.rules().upsert(rule)
        store.putLocal(rule)
        ctrl.onAppearance(pkg, "win-1")
        assertTrue(device.focuses.isEmpty())
    }
}
