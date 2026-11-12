package com.meowgi.entersend

import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.meowgi.entersend.core.ScreenCoords
import com.meowgi.entersend.service.Card
import com.meowgi.entersend.service.CardOverlay
import com.meowgi.entersend.service.EnterSendService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl

/**
 * Device evidence (Ömer, Q25 720×720, Telegram): marker appeared ~80–100 px above and ~30–50 px
 * right of the real tap, flush to the right edge. Robolectric's getLocationOnScreen is usually
 * (0,0), which hid the bug. The failing geometry was:
 *
 *   margins = raw - getLocationOnScreen()
 *
 * When locationOnScreen reports a non-zero origin on a full-bleed overlay (status bar / cutout /
 * FLAG_LAYOUT_NO_LIMITS quirks), subtracting it shifts the marker up (positive locY) and right
 * (negative locX). Fix: margins = raw - markerRadius, no location adjustment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Fix4MarkerPlacementTest {

    @Test fun subtractingFakeLocationOnScreenReproducesOmerOffset() {
        val rawX = 650
        val rawY = 640
        // Observed-class offset: ~40 px right, ~90 px above.
        val lyingLocX = -40
        val lyingLocY = 90
        val wrongX = rawX - lyingLocX // 690 — toward the right edge on 720
        val wrongY = rawY - lyingLocY // 550 — above the tap
        assertEquals(690, wrongX)
        assertEquals(550, wrongY)
        assertTrue(wrongX > rawX && wrongY < rawY)
        // Correct full-bleed margins (centre of 48 px marker):
        val (mx, my) = ScreenCoords.markerMargins(rawX, rawY)
        assertEquals(rawX - 24, mx)
        assertEquals(rawY - 24, my)
        assertFalse(mx == wrongX - 24 && my == wrongY - 24)
    }

    @Test fun realCardOverlayMarkerMarginsEqualRawMinusRadius_notLocationAdjusted() {
        val service = Robolectric.setupService(EnterSendService::class.java)
        val overlay = CardOverlay(service)
        val tapX = 650
        val tapY = 640

        // First show a waiting capture and deliver a raw tap (records lastRawTap).
        var got: Pair<Int, Int>? = null
        overlay.setTapListener { x, y -> got = x to y }
        overlay.show(Card.Capture(null, null, "tap")) {}
        val wm = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java))
        val root = wm.views[0]
        val t = android.os.SystemClock.uptimeMillis()
        val up = MotionEvent.obtain(t, t + 10, MotionEvent.ACTION_UP, tapX.toFloat(), tapY.toFloat(), 0)
        root.dispatchTouchEvent(up)
        up.recycle()
        assertEquals(tapX to tapY, got)
        assertEquals(tapX to tapY, overlay.lastRawTap)

        // Save card with marker at the same screen pixel.
        overlay.show(Card.Capture(tapX, tapY, "save")) {}
        shadowOf(Looper.getMainLooper()).idle()

        val expected = ScreenCoords.markerMargins(tapX, tapY)
        assertEquals("margins must be raw screen px minus radius", expected, overlay.lastMarkerMargins)
        assertEquals(tapX to tapY, overlay.lastMarkerScreen)

        // Inspect the real FrameLayout child placed by WindowManager.
        val captureRoot = wm.views[0] as FrameLayout
        val marker = captureRoot.getChildAt(0)
        val lp = marker.layoutParams as FrameLayout.LayoutParams
        assertEquals(ScreenCoords.MARKER_SIZE_PX, lp.width)
        assertEquals(ScreenCoords.MARKER_SIZE_PX, lp.height)
        assertEquals(expected.first, lp.leftMargin)
        assertEquals(expected.second, lp.topMargin)
        assertEquals(Gravity.TOP or Gravity.START, lp.gravity)

        // Full-bleed window still required.
        val wlp = captureRoot.layoutParams as WindowManager.LayoutParams
        assertTrue(wlp.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
        assertTrue(wlp.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0)
        assertEquals(0, wlp.x)
        assertEquals(0, wlp.y)
        // Explicit full-display pixels (not MATCH_PARENT) so R+ fit-insets cannot shrink the window.
        assertTrue("width=${wlp.width}", wlp.width > 0)
        assertTrue("height=${wlp.height}", wlp.height > 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            assertEquals(0, wlp.fitInsetsTypes)
        }
        assertEquals(0, wlp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        assertEquals(0, wlp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        assertNotNull(overlay.lastDebugLine)
        assertTrue(overlay.lastDebugLine!!.contains("mk=$tapX,$tapY"))
        assertTrue(overlay.lastDebugLine!!.contains("dens="))
        overlay.hide()
    }

    @Test fun teachPromotesToCaptureAsSoonAsTextIsPresent() {
        val db = memoryDb()
        try {
            val device = FakeDevice()
            val overlay = FakeOverlay()
            val pkg = "org.telegram.messenger"
            device.field = chatField("", pkg = pkg, viewId = "$pkg:id/chat_text", hint = null)
            device.window = com.meowgi.entersend.core.WindowState(1, 720, 720, false, 2f, 48, 720)
            val s = service(device, db, FakeCrops(), overlay)
            s.teach!!.startTeaching(pkg)
            s.teach!!.onMissedClick()
            assertTrue(overlay.card is Card.TeachingBar)
            assertFalse(s.teach!!.isCapturing)

            // Text arrives without a TEXT_CHANGED delivery — content-changed / poll must promote.
            device.field = device.field!!.copy(text = "merhaba")
            s.teach!!.onContentChanged(pkg)
            assertTrue(s.teach!!.isCapturing)
            assertTrue(overlay.card is Card.Capture)
            assertEquals(null, (overlay.card as Card.Capture).markerX)

            // Tap is consumed by capture listener, not passed to the app.
            overlay.screenTap(680, 640)
            assertEquals(680, (overlay.card as? Card.Capture)?.markerX)
        } finally {
            db.close()
        }
    }
}
