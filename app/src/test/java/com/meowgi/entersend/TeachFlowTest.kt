package com.meowgi.entersend

import android.os.Looper
import android.view.KeyEvent
import com.meowgi.entersend.core.NodeSnap
import com.meowgi.entersend.data.AppDb
import com.meowgi.entersend.service.Card
import com.meowgi.entersend.service.Choice
import com.meowgi.entersend.service.EnterSendService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** Legacy key-scheme checks kept against the two-step teach flow. */
@RunWith(RobolectricTestRunner::class)
class TeachFlowTest {
    private val pkg = "com.example.chat"
    private lateinit var db: AppDb
    private val device = FakeDevice()
    private val overlay = FakeOverlay()
    private val crops = FakeCrops()
    private var t = 10_000L

    @Before fun setUp() {
        db = memoryDb()
        device.field = chatField("hello", pkg = pkg, viewId = "$pkg:id/message_input", hint = null)
        device.root = window()
        device.live = solid(0xFF2196F3.toInt())
    }

    @After fun tearDown() = db.close()

    private fun start(): EnterSendService = service(device, db, crops, overlay)
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun EnterSendService.tap(code: Int, meta: Int = 0): List<Boolean> {
        t += 1000
        return press(t, code = code, meta = meta)
    }

    @Test fun escCancelsCapture() {
        val s = start()
        s.teach!!.startTeaching(pkg)
        s.teach!!.onMissedClick()
        s.teach!!.onTextChanged(pkg)
        assertTrue(s.teach!!.isCapturing)
        assertEquals(listOf(true, true), s.tap(KeyEvent.KEYCODE_ESCAPE))
        assertFalse(s.teach!!.isTeaching)
        assertNull(overlay.card)
    }

    @Test fun plainLettersPassThroughWhileListening() {
        val s = start()
        s.teach!!.startTeaching(pkg)
        assertEquals(listOf(false, false), s.tap(KeyEvent.KEYCODE_Y))
        assertTrue(s.teach!!.isTeaching)
    }

    @Test fun altYSavesCapture() {
        val s = start()
        s.teach!!.startTeaching(pkg)
        s.teach!!.onMissedClick()
        s.teach!!.onTextChanged(pkg)
        overlay.screenTap(650, 640)
        idle(50)
        assertEquals(listOf(true, true), s.tap(KeyEvent.KEYCODE_Y, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON))
        assertNotNull(db.rules().get(pkg)?.tapDxDp)
    }

    @Test fun cancelOnRetryBar() {
        device.field = chatField("", pkg = pkg, viewId = "$pkg:id/message_input", hint = null)
        val s = start()
        s.teach!!.startTeaching(pkg)
        s.teach!!.onMissedClick()
        assertTrue("card=${overlay.card}", overlay.card is Card.TeachingBar)
        overlay.tap(Choice.CLOSE)
        assertFalse(s.teach!!.isTeaching)
    }

    private fun assertNotNull(value: Any?) = org.junit.Assert.assertNotNull(value)
}
