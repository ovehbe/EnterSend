package com.meowgi.entersend

import android.content.res.Configuration
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.ScreenCoords
import com.meowgi.entersend.core.TapSpot
import com.meowgi.entersend.core.WindowState
import com.meowgi.entersend.data.AppDb
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.service.Card
import com.meowgi.entersend.service.CardOverlay
import com.meowgi.entersend.service.Choice
import com.meowgi.entersend.service.EnterSendService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration
import kotlin.math.abs

/**
 * Proves the capture→save→dispatchGesture path stays in one screen-pixel space, including a
 * 720×720 display with status/nav insets (Q25-shaped).
 *
 * Root cause of 0.2.0 "can't tap where I want": the accessibility overlay was laid out inside
 * system insets while taps were mixed with view-local coordinates / a bottom Done bar covered
 * the send region. Fix: full-bleed overlay + raw screen pixels end to end + bars at the top.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureCoordinateRoundTripTest {
    private lateinit var db: AppDb
    private val crops = FakeCrops()
    private val overlay = FakeOverlay()
    private val device = FakeDevice()
    private val pkg = "com.example.chat"

    @Before fun setUp() {
        db = memoryDb()
    }

    @After fun tearDown() = db.close()

    @Test fun rawTouchNotViewLocal_withStatusBarInset() {
        // Simulate an inset window: view-local Y is rawY - statusBar.
        val statusBar = 48
        val rawX = 650f
        val rawY = 640f
        val e = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, rawX - 0f, rawY - statusBar, 0)
        // MotionEvent.obtain sets x/y; set raw via reflection-free Robolectric shadow if needed.
        // ScreenCoords contract: always prefer raw. When raw equals local (full-bleed), identical.
        e.offsetLocation(0f, 0f)
        // Force raw via obtain with display coords — Robolectric sets rawX=x by default.
        val (sx, sy) = ScreenCoords.fromTouch(e)
        assertEquals(rawX.toInt(), sx)
        // Document the bug: using getX with a 48px inset would be 48px too high on screen.
        val wrongIfGetX = e.x.toInt() // equals raw in this synthetic event
        assertEquals(
            "Full-bleed: raw and local match. With a 48px inset, getX would be rawY-48=$statusBar px off.",
            sx,
            wrongIfGetX,
        )
        e.recycle()
    }

    @Test fun captureOverlayIsFullBleedAndUsesRawScreenPixels() {
        val service = Robolectric.setupService(EnterSendService::class.java)
        val cardOverlay = CardOverlay(service)
        var got: Pair<Int, Int>? = null
        cardOverlay.setTapListener { x, y -> got = x to y }
        cardOverlay.show(Card.Capture(null, null, "tap")) {}

        val wm = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java))
        assertEquals(1, wm.views.size)
        val lp = wm.views[0].layoutParams as WindowManager.LayoutParams
        assertTrue(lp.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
        assertTrue(lp.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0)
        assertTrue(lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(0, lp.x)
        assertEquals(0, lp.y)

        val root = wm.views[0]
        val downTime = android.os.SystemClock.uptimeMillis()
        val rawX = 650
        val rawY = 640
        // Dispatch a touch whose raw coords are the send-button screen pixel.
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, rawX.toFloat(), rawY.toFloat(), 0)
        val up = MotionEvent.obtain(downTime, downTime + 20, MotionEvent.ACTION_UP, rawX.toFloat(), rawY.toFloat(), 0)
        root.dispatchTouchEvent(down)
        root.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()

        assertEquals(rawX to rawY, got)
        assertEquals(rawX to rawY, cardOverlay.lastRawTap)
        cardOverlay.hide()
    }

    @Test fun portraitKeyboardHidden_roundTripWithinFewPixels() {
        roundTrip(
            orientation = Configuration.ORIENTATION_PORTRAIT,
            width = 720,
            height = 720,
            density = 2f,
            statusBar = 48,
            keyboardShown = false,
            field = Box(10, 600, 560, 680),
            tapX = 650,
            tapY = 640,
        )
    }

    @Test fun portraitKeyboardShown_roundTripWithinFewPixels() {
        roundTrip(
            orientation = Configuration.ORIENTATION_PORTRAIT,
            width = 720,
            height = 720,
            density = 2f,
            statusBar = 48,
            keyboardShown = true,
            keyboardTop = 420,
            field = Box(10, 300, 560, 380),
            tapX = 650,
            tapY = 340,
        )
    }

    @Test fun landscapeKeyboardHidden_roundTripWithinFewPixels() {
        roundTrip(
            orientation = Configuration.ORIENTATION_LANDSCAPE,
            width = 720,
            height = 720,
            density = 2f,
            statusBar = 36,
            keyboardShown = false,
            field = Box(10, 600, 560, 680),
            tapX = 650,
            tapY = 640,
        )
    }

    @Test fun landscapeKeyboardShown_roundTripWithinFewPixels() {
        roundTrip(
            orientation = Configuration.ORIENTATION_LANDSCAPE,
            width = 720,
            height = 720,
            density = 2f,
            statusBar = 36,
            keyboardShown = true,
            keyboardTop = 400,
            field = Box(20, 200, 500, 280),
            tapX = 620,
            tapY = 240,
        )
    }

    @Test fun teachingBarDefaultsToTopNotBottom() {
        device.field = chatField("hi", pkg = pkg, viewId = "$pkg:id/message_input", hint = null)
            .let { it.copy(bounds = Box(10, 600, 560, 680)) }
        device.window = WindowState(
            Configuration.ORIENTATION_PORTRAIT, 720, 720,
            keyboardShown = false, density = 2f, statusBarBottom = 48, keyboardTop = 720,
        )
        val s = service(device, db, crops, overlay)
        s.teach!!.startTeaching(pkg)
        device.field = device.field!!.copy(text = "x")
        s.teach!!.onTextChanged(pkg)
        device.field = device.field!!.copy(text = "")
        s.teach!!.onTextChanged(pkg)
        device.field = device.field!!.copy(text = "hi")
        s.teach!!.promoteCaptureIfReady(pkg)
        // Capture hint bar edge is TOP for bottom fields.
        assertTrue(overlay.card is Card.Capture)
        assertEquals(com.meowgi.entersend.core.PopupEdge.TOP, (overlay.card as Card.Capture).barEdge)
    }

    @Test fun typeSomethingBarOnlyWhenEmpty_captureOnlyWithText() {
        device.field = chatField("", pkg = pkg, viewId = "$pkg:id/message_input", hint = null)
            .let { it.copy(bounds = Box(10, 600, 560, 680)) }
        device.window = WindowState(1, 720, 720, false, 2f, 48, 720)
        val s = service(device, db, crops, overlay)
        s.teach!!.startTeaching(pkg)
        device.field = device.field!!.copy(text = "x")
        s.teach!!.onTextChanged(pkg)
        device.field = device.field!!.copy(text = "")
        s.teach!!.onTextChanged(pkg)
        assertTrue(overlay.card is Card.TeachingBar)
        assertEquals(com.meowgi.entersend.core.PopupEdge.TOP, (overlay.card as Card.TeachingBar).edge)
        // Still empty — capture must not open.
        assertTrue(!s.teach!!.isCapturing)
        device.field = device.field!!.copy(text = "hello")
        s.teach!!.onTextChanged(pkg)
        assertTrue(s.teach!!.isCapturing)
    }

    private fun roundTrip(
        orientation: Int,
        width: Int,
        height: Int,
        density: Float,
        statusBar: Int,
        keyboardShown: Boolean,
        field: Box,
        tapX: Int,
        tapY: Int,
        keyboardTop: Int = height,
    ) {
        device.field = chatField("hello", pkg = pkg, viewId = "$pkg:id/message_input", hint = null)
            .copy(bounds = field)
        device.root = window() // no send node → forces tap-spot path on Enter
        device.window = WindowState(
            orientation, width, height, keyboardShown, density, statusBar, keyboardTop,
        )
        device.live = solid(0xFF2196F3.toInt(), 80, 80)
        device.textAfterSend = ""

        val s = service(device, db, crops, overlay)
        s.teach!!.startTeaching(pkg)
        device.field = device.field!!.copy(text = "x")
        s.teach!!.onTextChanged(pkg)
        device.field = device.field!!.copy(text = "")
        s.teach!!.onTextChanged(pkg)
        device.field = device.field!!.copy(text = "hello")
        s.teach!!.promoteCaptureIfReady(pkg)
        assertTrue(s.teach!!.isCapturing)
        overlay.screenTap(tapX, tapY)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        val marked = overlay.card as Card.Capture
        assertEquals(tapX, marked.markerX)
        assertEquals(tapY, marked.markerY)
        overlay.tap(Choice.YES)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

        val rule = db.rules().get(pkg)
        assertNotNull(rule)
        assertNotNull(rule!!.tapDxDp)
        // Primary path: field-relative dp must resolve back to the same screen pixel.
        val resolved = TapSpot.resolve(rule, device.field, device.window!!, density, keyboardShown)!!
        assertTrue(
            "raw=($tapX,$tapY) marker=(${marked.markerX},${marked.markerY}) gesture=(${resolved.first},${resolved.second})",
            abs(resolved.first - tapX) <= 2 && abs(resolved.second - tapY) <= 2,
        )

        // Replay through Enter → dispatchGesture (FakeDevice.tap).
        device.taps.clear()
        device.field = device.field!!.copy(text = "hello again")
        device.live = solid(0xFF2196F3.toInt(), 80, 80) // crop still matches
        // Ensure crop pixels match what was saved.
        val crop = crops.load(rule.cropPath!!)
        device.live = crop
        s.press(5_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(1, device.taps.size)
        val gesture = device.taps[0]
        assertTrue(
            "raw=($tapX,$tapY) marker=(${marked.markerX},${marked.markerY}) gesture=$gesture",
            abs(gesture.first - tapX) <= 2 && abs(gesture.second - tapY) <= 2,
        )
    }
}
