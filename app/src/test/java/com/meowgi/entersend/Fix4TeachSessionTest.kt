package com.meowgi.entersend

import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.WindowState
import com.meowgi.entersend.service.Card
import com.meowgi.entersend.service.CardOverlay
import com.meowgi.entersend.service.Choice
import com.meowgi.entersend.service.EnterSendService
import com.meowgi.entersend.service.TeachFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Fix4TeachSessionTest {
    private lateinit var db: com.meowgi.entersend.data.AppDb
    private val device = FakeDevice()
    private val overlay = FakeOverlay()
    private val pkg = "org.telegram.messenger"

    @Before fun setUp() {
        db = memoryDb()
        // Bottom field + send to the right, just above the IME (Ömer's Q25/Telegram layout).
        device.field = chatField("", pkg = pkg, viewId = "$pkg:id/chat_text", hint = null)
            .copy(bounds = Box(10, 520, 560, 600))
        device.window = WindowState(
            orientation = 1, width = 720, height = 720,
            keyboardShown = true, density = 2f,
            statusBarBottom = 48, keyboardTop = 620,
        )
        device.root = window()
        device.live = solid(0xFF2196F3.toInt(), 80, 80)
    }

    @After fun tearDown() = db.close()

    @Test fun newSessionAlwaysStartsAtStep1_afterCancelFromStep2() {
        val s = service(device, db, FakeCrops(), overlay)
        s.teach!!.startTeaching(pkg)
        assertTrue("fresh session is step 1", s.teach!!.isStep1)
        assertFalse(s.teach!!.isStep2Bar)
        assertFalse(s.teach!!.isCapturing)
        assertTrue(overlay.card is Card.TeachingBar) // step 1: short top bar

        // Reach step 2 via send evidence (box emptied).
        device.field = device.field!!.copy(text = "x")
        s.teach!!.onTextChanged(pkg)
        device.field = device.field!!.copy(text = "")
        s.teach!!.onTextChanged(pkg)
        assertTrue(s.teach!!.isStep2Bar || s.teach!!.isCapturing)
        assertFalse(s.teach!!.isStep1)

        // Cancel ends the session and clears everything.
        if (overlay.card is Card.TeachingBar) overlay.tap(Choice.CLOSE)
        else s.teach!!.forceClose()
        assertFalse(s.teach!!.isTeaching)
        assertNull(overlay.card)

        // New session must be step 1 again — never resume "Didn't catch that…".
        s.teach!!.startTeaching(pkg)
        assertTrue(s.teach!!.isStep1)
        assertFalse(s.teach!!.isStep2Bar)
        assertFalse(s.teach!!.isCapturing)
        assertTrue(overlay.card is Card.TeachingBar)
    }

    @Test fun escTimeoutAndRestartAlsoResetToStep1() {
        val s = service(device, db, FakeCrops(), overlay)
        s.teach!!.startTeaching(pkg)
        device.field = device.field!!.copy(text = "x")
        s.teach!!.onTextChanged(pkg)
        device.field = device.field!!.copy(text = "")
        s.teach!!.onTextChanged(pkg)
        assertFalse(s.teach!!.isStep1)

        // Esc
        s.press(1_000, code = android.view.KeyEvent.KEYCODE_ESCAPE)
        assertFalse(s.teach!!.isTeaching)

        s.teach!!.startTeaching(pkg)
        assertTrue(s.teach!!.isStep1)

        // Safety timeout
        device.field = device.field!!.copy(text = "x")
        s.teach!!.onTextChanged(pkg)
        device.field = device.field!!.copy(text = "")
        s.teach!!.onTextChanged(pkg)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TeachFlow.SAFETY_TIMEOUT_MS + 50))
        assertFalse(s.teach!!.isTeaching)

        s.teach!!.startTeaching(pkg)
        assertTrue(s.teach!!.isStep1)
        assertTrue(overlay.card is Card.TeachingBar)
    }

    @Test fun captureLayerInterceptsSendButtonRegion_notPassThrough() {
        val service = Robolectric.setupService(EnterSendService::class.java)
        val cardOverlay = CardOverlay(service)
        var captured: Pair<Int, Int>? = null
        var passedThrough = false
        cardOverlay.setTapListener { x, y -> captured = x to y }

        cardOverlay.show(Card.Capture(null, null, "Tap where the send button is")) {
            // Card buttons only — should not fire for a send-region screen tap.
            passedThrough = true
        }

        val wm = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java))
        assertEquals(1, wm.views.size)
        val lp = wm.views[0].layoutParams as WindowManager.LayoutParams

        // Must cover the full display and must not leave touch holes for the IME/send zone.
        assertTrue("capture width=${lp.width}", lp.width > 0)
        assertTrue("capture height=${lp.height}", lp.height > 0)
        assertEquals(0, lp.x)
        assertEquals(0, lp.y)
        assertTrue(lp.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
        assertTrue(lp.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0)
        assertEquals(0, lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        assertEquals(0, lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            assertEquals("fitInsetsTypes must be 0 so IME does not punch a hole", 0, lp.fitInsetsTypes)
        }

        // Send button: right of the field, bottom, just above the IME (Ömer's miss).
        // Use coordinates inside the actual overlay size (Robolectric display may not be 720).
        val sendX = (lp.width * 9 / 10).coerceAtLeast(1)
        val sendY = (lp.height * 8 / 10).coerceAtLeast(1)
        val root = wm.views[0]
        val t = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, sendX.toFloat(), sendY.toFloat(), 0)
        val up = MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_UP, sendX.toFloat(), sendY.toFloat(), 0)
        assertTrue(root.dispatchTouchEvent(down))
        assertTrue(root.dispatchTouchEvent(up))
        down.recycle()
        up.recycle()

        assertEquals("send-button tap must be captured", sendX to sendY, captured)
        assertEquals(sendX to sendY, cardOverlay.lastRawTap)
        assertFalse("send-button tap must not reach card choice / app", passedThrough)

        cardOverlay.hide()
    }

    @Test fun teachFlowCaptureConsumesSendButtonTap() {
        // Empty → step2 bar → type → capture → tap send coords → marker, no app click.
        device.clicks.clear()
        val s = service(device, db, FakeCrops(), overlay)
        s.teach!!.startTeaching(pkg)
        assertTrue(s.teach!!.isStep1)
        device.field = device.field!!.copy(text = "x")
        s.teach!!.onTextChanged(pkg)
        device.field = device.field!!.copy(text = "")
        s.teach!!.onTextChanged(pkg)
        device.field = device.field!!.copy(text = "merhaba")
        s.teach!!.promoteCaptureIfReady(pkg)
        assertTrue(s.teach!!.isCapturing)

        val sendX = 680
        val sendY = 580
        overlay.screenTap(sendX, sendY)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertEquals(sendX, (overlay.card as Card.Capture).markerX)
        assertEquals(sendY, (overlay.card as Card.Capture).markerY)
        assertTrue(device.clicks.isEmpty()) // never forwarded as an a11y click
    }
}
