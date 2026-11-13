package com.meowgi.entersend

import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.SystemAppPolicy
import com.meowgi.entersend.data.AutoFocusPrefs
import com.meowgi.entersend.data.EnterDiagLog
import com.meowgi.entersend.service.AutoFocusController
import com.meowgi.entersend.service.Card
import com.meowgi.entersend.service.Choice
import com.meowgi.entersend.service.RuleStore
import com.meowgi.entersend.service.TeachFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class AutoFocusBehaviorTest {
    private val device = FakeDevice()
    private val pkg = "com.example.chat"
    private val fieldId = "$pkg:id/input"
    private lateinit var ctrl: AutoFocusController
    private var teachRequests = 0

    @Before fun setUp() {
        EnterDiagLog.resetForTests(context())
        context().getSharedPreferences("entersend_autofocus", 0).edit().clear().apply()
        device.messageFields = listOf(
            chatField("", pkg = pkg, viewId = fieldId, hint = "Message"),
        )
        device.field = null
        device.focusSucceeds = true
        device.clickFieldSucceeds = true
        teachRequests = 0
        ctrl = AutoFocusController(
            device = device,
            policy = SystemAppPolicy(context()),
            isEnabled = { AutoFocusPrefs.isEnabled(context(), it) },
            loadTarget = { AutoFocusPrefs.target(context(), it) },
            saveTarget = { p, t -> AutoFocusPrefs.setTarget(context(), p, t) },
            diag = EnterDiagLog.get(context()),
            requestTeach = { teachRequests++ },
        )
    }

    @After fun tearDown() {
        EnterDiagLog.resetForTests(context())
    }

    private fun enable() = AutoFocusPrefs.setEnabled(context(), pkg, true)

    private fun saveIdentity(
        viewId: String? = fieldId,
        className: String? = "android.widget.EditText",
        box: Box = FIELD_BOX,
        tapFallback: Boolean = false,
    ) {
        AutoFocusPrefs.setTarget(
            context(),
            pkg,
            AutoFocusPrefs.buildTarget(
                viewId, className,
                box.left, box.top, box.right, box.bottom,
                720, 720, tapFallback,
            ),
        )
    }

    @Test fun defaultOffDoesNotFocus() {
        assertFalse(AutoFocusPrefs.isEnabled(context(), pkg))
        ctrl.onAppearance(pkg, "a1")
        assertTrue(device.focuses.isEmpty())
    }

    @Test fun migrationOnBecomesEnabledElseOff() {
        // Simulate 0.3.12 prefs before migrate.
        val p = context().getSharedPreferences("entersend_autofocus", 0)
        p.edit()
            .putBoolean("auto_focus_enabled", true)
            .putString("override_com.keep.on", "ON")
            .putString("override_com.keep.off", "OFF")
            .putString("override_com.keep.default", "DEFAULT")
            .commit()
        AutoFocusPrefs.migrateIfNeeded(context())
        assertTrue(AutoFocusPrefs.isEnabled(context(), "com.keep.on"))
        assertFalse(AutoFocusPrefs.isEnabled(context(), "com.keep.off"))
        assertFalse(AutoFocusPrefs.isEnabled(context(), "com.keep.default"))
        assertFalse(AutoFocusPrefs.isEnabled(context(), "com.other"))
        assertNull(p.getString("override_com.keep.on", null))
        assertFalse(p.contains("auto_focus_enabled"))
    }

    @Test fun focusesOncePerAppearance() {
        enable()
        saveIdentity()
        ctrl.onAppearance(pkg, "win-1")
        assertEquals(1, device.focuses.size)
        assertEquals(fieldId, device.focuses[0].viewId)
        ctrl.onAppearance(pkg, "win-1")
        assertEquals(1, device.focuses.size)
    }

    @Test fun doesNotReStealAfterUserMovedFocus() {
        enable()
        saveIdentity()
        ctrl.onAppearance(pkg, "win-1")
        assertEquals(1, device.focuses.size)
        device.field = null
        device.focuses.clear()
        ctrl.onAppearance(pkg, "win-1")
        assertTrue(device.focuses.isEmpty())
    }

    @Test fun noOpWhenAlreadyFocused() {
        enable()
        saveIdentity()
        device.field = chatField("hi", pkg = pkg, viewId = fieldId)
        ctrl.onAppearance(pkg, "win-1")
        assertTrue(device.focuses.isEmpty())
    }

    @Test fun autoDetectSingleFieldSavesAndFocuses() {
        enable()
        // No saved identity — sole editable heuristic.
        assertNull(AutoFocusPrefs.target(context(), pkg))
        assertTrue(ctrl.enableWithDetect(pkg))
        assertNotNull(AutoFocusPrefs.target(context(), pkg))
        assertEquals(fieldId, AutoFocusPrefs.target(context(), pkg)!!.viewId)
        assertEquals(0, teachRequests)
        ctrl.onAppearance(pkg, "w")
        assertEquals(1, device.focuses.size)
    }

    @Test fun severalFieldsRequestTeach() {
        enable()
        device.messageFields = listOf(
            chatField("", pkg = pkg, viewId = "$pkg:id/a"),
            chatField("", pkg = pkg, viewId = "$pkg:id/b"),
        )
        assertFalse(ctrl.enableWithDetect(pkg))
        assertEquals(1, teachRequests)
        assertNull(AutoFocusPrefs.target(context(), pkg))
    }

    @Test fun savedIdentityMatchedByViewId() {
        enable()
        saveIdentity(viewId = fieldId)
        device.messageFields = listOf(
            chatField("", pkg = pkg, viewId = "$pkg:id/other", hint = "Search"),
            chatField("", pkg = pkg, viewId = fieldId, hint = "Message"),
        )
        ctrl.onAppearance(pkg, "w")
        assertEquals(fieldId, device.focuses.single().viewId)
    }

    @Test fun tapFallbackOnlyAfterFocusAndClickFail() {
        enable()
        saveIdentity(tapFallback = false)
        device.focusSucceeds = false
        device.clickFieldSucceeds = false
        ctrl.onAppearance(pkg, "w")
        assertTrue(device.focuses.isEmpty())
        assertTrue(device.fieldClicks.isEmpty())
        assertEquals(1, device.taps.size)
        assertEquals(FIELD_BOX.centerX, device.taps[0].first)
        assertEquals(FIELD_BOX.centerY, device.taps[0].second)
        assertTrue(AutoFocusPrefs.target(context(), pkg)!!.tapFallback)
    }

    @Test fun tapFallbackModeUsesTapDirectly() {
        enable()
        saveIdentity(tapFallback = true)
        device.focusSucceeds = true // would succeed, but fallback mode skips to tap
        ctrl.onAppearance(pkg, "w")
        assertTrue(device.focuses.isEmpty())
        assertEquals(1, device.taps.size)
    }

    @Test fun optionalTeachStepSkipLeavesOff() {
        val overlay = FakeOverlay()
        val db = memoryDb()
        val store = RuleStore(db.rules(), direct, direct, FakeCrops())
        val teach = TeachFlow(
            device, store, overlay, android.os.Handler(Looper.getMainLooper()),
            today = { 20_000L },
            versionOf = { 1L },
            strings = { id -> context().getString(id) },
            autoFocusContext = context(),
        )
        teach.offerAutoFocus(pkg)
        assertTrue(teach.isAutoFocusOffer)
        assertTrue((overlay.card as Card.TeachingBar).showSkip)
        overlay.tap(Choice.NO)
        assertFalse(AutoFocusPrefs.isEnabled(context(), pkg))
        assertFalse(teach.isTeaching)
        db.close()
    }

    @Test fun optionalTeachStepTapEnables() {
        val overlay = FakeOverlay()
        val db = memoryDb()
        val store = RuleStore(db.rules(), direct, direct, FakeCrops())
        val teach = TeachFlow(
            device, store, overlay, android.os.Handler(Looper.getMainLooper()),
            today = { 20_000L },
            versionOf = { 1L },
            strings = { id -> context().getString(id) },
            autoFocusContext = context(),
        )
        teach.offerAutoFocus(pkg)
        val node = com.meowgi.entersend.core.NodeSnap(
            viewId = fieldId,
            className = "android.widget.EditText",
            editable = true,
            bounds = FIELD_BOX,
        )
        teach.onClicked(pkg, node)
        assertTrue(AutoFocusPrefs.isEnabled(context(), pkg))
        assertEquals(fieldId, AutoFocusPrefs.target(context(), pkg)!!.viewId)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600))
        db.close()
    }
}
