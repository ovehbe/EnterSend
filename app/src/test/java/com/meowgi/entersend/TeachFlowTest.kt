package com.meowgi.entersend

import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.NodeSnap
import com.meowgi.entersend.core.Pixels
import com.meowgi.entersend.data.AppDb
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.service.Card
import com.meowgi.entersend.service.Choice
import com.meowgi.entersend.service.EnterSendService
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class TeachFlowTest {
    private val pkg = "com.example.chat"
    private val blue = 0xFF2196F3.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private lateinit var db: AppDb
    private val device = FakeDevice()
    private val overlay = FakeOverlay()
    private val crops = FakeCrops()
    private var day = 20_000L
    private var t = 10_000L

    /** Custom-drawn send button: no id, no label, so auto-detect can't find it. */
    private val customSend = NodeSnap(className = "android.view.View", clickable = true, bounds = SEND_BOX)

    @Before fun setUp() {
        db = memoryDb()
        device.field = chatField("hello", pkg = pkg, viewId = "$pkg:id/message_input", hint = null)
        device.root = window(customSend)
        device.textAfterSend = null
        // Full-screen screenshot: white with a blue send button.
        device.live = Pixels(720, 720, IntArray(720 * 720) { i -> if (SEND_BOX.contains(i % 720, i / 720)) blue else white })
    }

    @After fun tearDown() = db.close()

    private fun start(): EnterSendService = service(device, db, crops, overlay, today = { day })

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun EnterSendService.tap(code: Int, meta: Int = 0): List<Boolean> {
        t += 1000
        return press(t, code = code, meta = meta)
    }

    private fun EnterSendService.enter() = tap(KeyEvent.KEYCODE_ENTER)
    private fun EnterSendService.altKey(code: Int) = tap(code, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)

    private fun EnterSendService.textChanged() = onAccessibilityEvent(
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED).apply { packageName = pkg },
    )

    private fun rule() = db.rules().get(pkg)

    // --- Ask card ---

    @Test fun noSendFoundPassesEnterAndAsksAtMostOncePerDay() {
        val s = start()
        assertEquals(listOf(false, false), s.enter())
        assertEquals(Card.Ask(reteach = false), overlay.card)

        s.tap(KeyEvent.KEYCODE_ESCAPE)
        s.enter()
        assertNull(overlay.card)
        assertEquals(1, overlay.shown.size)

        day++
        s.enter()
        assertEquals(Card.Ask(reteach = false), overlay.card)
    }

    @Test fun askCardAutoDismissesAfterEightSecondsAsAskLater() {
        val s = start()
        s.enter()
        idle(7_000)
        assertNotNull(overlay.card)
        idle(1_500)
        assertNull(overlay.card)
        assertFalse(rule()!!.teachDeclined)
        assertEquals(day, rule()!!.lastAskedDay)
    }

    @Test fun altNIsRememberedAcrossRestarts() {
        val s = start()
        s.enter()
        assertEquals(listOf(true, true), s.altKey(KeyEvent.KEYCODE_N))
        assertNull(overlay.card)
        assertTrue(rule()!!.teachDeclined)

        day += 3
        val afterReboot = start()
        afterReboot.enter()
        assertNull(overlay.card)
    }

    @Test fun typingYAndNWhileCardIsUpTypesTheLettersAndHidesTheCard() {
        val s = start()
        s.enter()
        assertEquals(listOf(false, false), s.tap(KeyEvent.KEYCODE_N))
        assertNull(overlay.card)
        assertFalse(rule()!!.teachDeclined)

        day++
        s.enter()
        assertEquals(listOf(false, false), s.tap(KeyEvent.KEYCODE_Y))
        assertNull(overlay.card)
        assertFalse(overlay.shown.any { it is Card.Teaching })
    }

    @Test fun shiftedLettersAlsoPassThrough() {
        val s = start()
        s.enter()
        assertEquals(listOf(false, false), s.tap(KeyEvent.KEYCODE_Y, KeyEvent.META_SHIFT_ON))
        assertNull(overlay.card)
    }

    @Test fun modifierAloneKeepsTheCard() {
        val s = start()
        s.enter()
        assertEquals(listOf(false, false), s.tap(KeyEvent.KEYCODE_ALT_LEFT))
        assertNotNull(overlay.card)
    }

    @Test fun escClosesAsAskLater() {
        val s = start()
        s.enter()
        assertEquals(listOf(true, true), s.tap(KeyEvent.KEYCODE_ESCAPE))
        assertNull(overlay.card)
        assertFalse(rule()!!.teachDeclined)
    }

    @Test fun lettersAreNeverConsumedWhenNoCardIsUp() {
        val s = start()
        assertEquals(listOf(false, false), s.altKey(KeyEvent.KEYCODE_Y))
        assertEquals(listOf(false, false), s.tap(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test fun deviceMinusOneCopyOfAltYIsSwallowedToo() {
        val s = start()
        s.enter()
        val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        assertTrue(s.onKeyEvent(key(KeyEvent.ACTION_DOWN, 50_000, code = KeyEvent.KEYCODE_Y, meta = alt)))
        assertTrue(s.onKeyEvent(key(KeyEvent.ACTION_DOWN, 50_015, code = KeyEvent.KEYCODE_Y, meta = alt, deviceId = VIRTUAL)))
        assertTrue(s.onKeyEvent(key(KeyEvent.ACTION_UP, 50_000, 50_080, code = KeyEvent.KEYCODE_Y, meta = alt)))
        assertEquals(Card.Teaching(), overlay.card)
    }

    // --- Teaching ---

    private fun teachUpToConfirm(s: EnterSendService) {
        s.enter()
        s.altKey(KeyEvent.KEYCODE_Y)
        assertEquals(Card.Teaching(), overlay.card)
        s.textChanged()
        idle(500)
        s.teach!!.onClicked(pkg, customSend)
    }

    @Test fun teachingSavesTheTappedButtonOnlyAfterYes() {
        val s = start()
        teachUpToConfirm(s)

        val confirm = overlay.card as Card.Confirm
        assertEquals(SEND_BOX.width, confirm.crop!!.width)
        assertEquals(blue, confirm.crop!!.argb[0])
        assertNull(rule()!!.cropPath)

        assertEquals(listOf(true, true), s.altKey(KeyEvent.KEYCODE_Y))
        assertNull(overlay.card)
        val saved = rule()!!
        assertEquals("android.widget.FrameLayout>android.view.View", saved.sendClassPath)
        assertEquals(SEND_BOX.centerX, saved.tapX)
        assertEquals(SEND_BOX.left, saved.cropLeft)
        assertEquals(1, saved.orientation)
        assertEquals(720, saved.windowWidth)
        assertEquals("$pkg:id/message_input", saved.fieldViewId)
        assertNotNull(crops.files[saved.cropPath])
    }

    @Test fun taughtRuleIsUsedForTheNextEnter() {
        val s = start()
        teachUpToConfirm(s)
        s.altKey(KeyEvent.KEYCODE_Y)

        device.textAfterSend = ""
        assertEquals(listOf(true, true), s.enter())
        assertEquals(listOf(customSend), device.clicks)
    }

    @Test fun noAtConfirmRetriesTeachingWithoutSaving() {
        val s = start()
        teachUpToConfirm(s)
        assertEquals(listOf(true, true), s.altKey(KeyEvent.KEYCODE_N))
        assertEquals(Card.Teaching(), overlay.card)
        assertNull(rule()!!.sendClassPath)
        assertFalse(rule()!!.teachDeclined)
    }

    @Test fun touchButtonsWorkLikeKeys() {
        val s = start()
        s.enter()
        overlay.tap(Choice.YES)
        assertEquals(Card.Teaching(), overlay.card)
        idle(500)
        s.teach!!.onClicked(pkg, customSend)
        overlay.tap(Choice.YES)
        assertNotNull(rule()!!.sendClassPath)
    }

    @Test fun tappingTheTextFieldWhileTeachingIsIgnored() {
        val s = start()
        s.enter()
        s.altKey(KeyEvent.KEYCODE_Y)
        s.teach!!.onClicked(pkg, NodeSnap(className = "android.widget.EditText", editable = true, clickable = true, bounds = FIELD_BOX))
        assertEquals(Card.Teaching(), overlay.card)
    }

    @Test fun enterAndTypingPassThroughWhileTeachingAndEscCancels() {
        val s = start()
        s.enter()
        s.altKey(KeyEvent.KEYCODE_Y)
        assertEquals(listOf(false, false), s.tap(KeyEvent.KEYCODE_H))
        assertEquals(listOf(false, false), s.enter())
        assertEquals(Card.Teaching(), overlay.card)
        assertTrue(device.clicks.isEmpty())

        assertEquals(listOf(true, true), s.tap(KeyEvent.KEYCODE_ESCAPE))
        assertNull(overlay.card)
    }

    @Test fun failedTapSpotCheckOffersReteach() {
        db.rules().upsert(
            AppRule(
                pkg, tapX = 655, tapY = 640, cropPath = "/crops/old.png",
                cropLeft = 610, cropTop = 600, cropRight = 700, cropBottom = 680,
                orientation = 1, windowWidth = 720, windowHeight = 720,
            ),
        )
        crops.files["/crops/old.png"] = solid(blue, SEND_BOX.width, SEND_BOX.height)
        device.root = window() // the button node is gone, so the tap spot is the only route
        device.live = solid(0xFF4CAF50.toInt(), SEND_BOX.width, SEND_BOX.height)

        start().enter()
        assertTrue(device.taps.isEmpty())
        assertEquals(Card.Ask(reteach = true), overlay.card)
    }

    @Test fun reteachReplacesTheOldCropFile() {
        db.rules().upsert(AppRule(pkg, sendViewId = "$pkg:id/gone", cropPath = "/crops/old.png"))
        crops.files["/crops/old.png"] = solid(blue)
        val s = start()
        teachUpToConfirm(s)
        s.altKey(KeyEvent.KEYCODE_Y)
        assertFalse("/crops/old.png" in crops.files)
        assertTrue(rule()!!.cropPath in crops.files)
        assertNull(rule()!!.sendViewId)
    }

    @Test fun cropIsClippedToTheScreen() {
        val edge = Box(680, 600, 760, 680)
        assertEquals(40, device.live!!.crop(edge)!!.width)
    }
}
