package com.meowgi.entersend

import android.os.Looper
import android.view.KeyEvent
import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.ChatFieldGuard
import com.meowgi.entersend.core.NodeSnap
import com.meowgi.entersend.core.SystemAppPolicy
import com.meowgi.entersend.core.TapSpot
import com.meowgi.entersend.core.WindowState
import com.meowgi.entersend.data.AppDb
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.Mode
import com.meowgi.entersend.service.Card
import com.meowgi.entersend.service.Choice
import com.meowgi.entersend.service.TeachFlow
import com.meowgi.entersend.ui.SetupActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Fix5 root causes (behavior, not geometry):
 * 1. Capture/runtime must accept taps inside field bounds (Telegram row includes send).
 * 2. No step 2 without send evidence — even after 10 s.
 * 3. EnterSend self-off must not block the setup test chat.
 * Plus: restore focus to the compose box after a successful send.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Fix5BehaviorTest {
    private lateinit var db: AppDb
    private val device = FakeDevice()
    private val overlay = FakeOverlay()
    private val crops = FakeCrops()
    private val pkg = "org.telegram.messenger"

    /** Telegram-style row: field bounds cover the send button on the right. */
    private val rowField = Box(10, 520, 700, 600)
    private val sendInRow = 680 to 560 // inside rowField

    @Before fun setUp() {
        db = memoryDb()
        device.field = chatField("hello", pkg = pkg, viewId = "$pkg:id/chat_text", hint = null)
            .copy(bounds = rowField)
        device.window = WindowState(1, 720, 720, true, 2f, 48, 620)
        device.root = window() // no readable send node → tap-spot path
        device.live = solid(0xFF2196F3.toInt(), 80, 80)
        device.textAfterSend = ""
    }

    @After fun tearDown() = db.close()

    private fun start() = service(device, db, crops, overlay)
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun intoCapture(s: com.meowgi.entersend.service.EnterSendService) {
        s.teach!!.startTeaching(pkg)
        assertTrue(s.teach!!.isStep1)
        // Send evidence: box empties after having text.
        device.field = device.field!!.copy(text = "hello")
        s.teach!!.onTextChanged(pkg)
        device.field = device.field!!.copy(text = "")
        s.teach!!.onTextChanged(pkg)
        assertTrue(s.teach!!.isStep2Bar || s.teach!!.isCapturing)
        device.field = device.field!!.copy(text = "again")
        s.teach!!.promoteCaptureIfReady(pkg)
        assertTrue(s.teach!!.isCapturing)
    }

    @Test fun captureTapInsideFieldBoundsIsSavedAndReplayed() {
        val s = start()
        intoCapture(s)
        overlay.screenTap(sendInRow.first, sendInRow.second)
        idle(50)
        val marked = overlay.card as Card.Capture
        assertEquals(sendInRow.first, marked.markerX)
        assertEquals(sendInRow.second, marked.markerY)
        overlay.tap(Choice.YES)
        idle(50)

        val rule = db.rules().get(pkg)!!
        assertNotNull(rule.tapDxDp)
        device.live = crops.load(rule.cropPath!!)
        device.field = device.field!!.copy(text = "next")
        device.taps.clear()
        s.press(9_000)
        idle(100)
        assertEquals(1, device.taps.size)
        assertEquals(sendInRow.first, device.taps[0].first)
        assertEquals(sendInRow.second, device.taps[0].second)
    }

    @Test fun noStep2WithoutSendEvidenceEvenAfterTenSeconds() {
        val s = start()
        s.teach!!.startTeaching(pkg)
        assertTrue(s.teach!!.isStep1)
        assertTrue(overlay.card is Card.TeachingBar)
        assertEquals(context().getString(R.string.teach_step1), (overlay.card as Card.TeachingBar).message)

        idle(10_000)
        assertTrue("still step 1 after 10s", s.teach!!.isStep1)
        assertFalse(s.teach!!.isStep2Bar)
        assertFalse(s.teach!!.isCapturing)
    }

    @Test fun step2StartsAfterBoxEmptiesWithNoUsableClick() {
        val s = start()
        s.teach!!.startTeaching(pkg)
        device.field = device.field!!.copy(text = "merhaba")
        s.teach!!.onTextChanged(pkg)
        assertTrue(s.teach!!.isStep1)

        device.field = device.field!!.copy(text = "")
        s.teach!!.onTextChanged(pkg)
        assertTrue(s.teach!!.isStep2Bar)
        assertEquals(context().getString(R.string.teach_step2), (overlay.card as Card.TeachingBar).message)
    }

    @Test fun step2StartsAfterClickOnUnusableNode() {
        device.field = device.field!!.copy(text = "") // stay on step-2 bar (no immediate capture)
        val s = start()
        s.teach!!.startTeaching(pkg)
        val junk = NodeSnap(
            viewId = null, text = null, desc = null, className = "android.view.View",
            clickable = false, bounds = Box(0, 0, 10, 10),
        )
        s.teach!!.onClicked(pkg, junk)
        assertFalse(s.teach!!.isStep1)
        assertTrue(s.teach!!.isStep2Bar)
    }

    @Test fun enterOnSetupTestScreenSends() {
        val policy = SystemAppPolicy(context())
        assertFalse(
            "EnterSend itself must not be always-off (setup test chat)",
            policy.isAlwaysOff(context().packageName),
        )
        assertEquals(Mode.AUTO, policy.resolveMode(AppRule(context().packageName)))
        assertFalse(policy.mayPrompt(context().packageName))

        // Setup compose box looks chat-like and EnterHandler must not be blocked by mode OFF.
        val activity = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        val input = activity.findViewById<android.widget.EditText>(R.id.message_input)
        val setupField = chatField(
            "test msg",
            pkg = context().packageName,
            viewId = "${context().packageName}:id/message_input",
            hint = null,
        ).copy(
            bounds = Box(10, 400, 500, 480),
            inputType = input.inputType,
        )
        assertTrue(ChatFieldGuard.isChatLike(setupField, AppRule(context().packageName)))

        device.field = setupField
        device.root = window(
            NodeSnap(
                viewId = "${context().packageName}:id/send_button",
                desc = "Send",
                clickable = true,
                bounds = Box(510, 400, 700, 480),
            ),
        )
        device.textAfterSend = ""
        val s = service(device, db, crops, overlay)
        // Policy-aware attach is used by service() → EnterHandler resolveMode.
        assertEquals(listOf(true, true), s.press(3_000))
        idle(EnterHandlerVerify)
        assertEquals(1, device.clicks.size)
    }

    @Test fun successfulSendRestoresFocusToComposeBox() {
        val send = NodeSnap(
            viewId = "$pkg:id/send", desc = "Send", clickable = true, bounds = Box(610, 520, 700, 600),
        )
        device.root = window(send)
        device.field = chatField("hello", pkg = pkg).copy(bounds = Box(10, 520, 600, 600))
        device.textAfterSend = ""
        val s = start()
        s.press(4_000)
        idle(600)
        assertEquals(1, device.clicks.size)
        assertTrue(device.focuses.isNotEmpty())
        assertEquals(pkg, device.focuses.last().packageName)
    }

    @Test fun offScreenCaptureTapShowsReasonNotSilent() {
        val s = start()
        intoCapture(s)
        overlay.screenTap(900, 900) // outside 720×720
        assertTrue(overlay.card is Card.Capture)
        val status = (overlay.card as Card.Capture).status
        assertNotNull(status)
        assertTrue(status!!.contains("off-screen") || status.contains("ekran"))
        assertNullMarker(overlay.card as Card.Capture)
    }

    private fun assertNullMarker(c: Card.Capture) {
        assertEquals(null, c.markerX)
        assertEquals(null, c.markerY)
    }

    companion object {
        private const val EnterHandlerVerify = 600L
    }
}
