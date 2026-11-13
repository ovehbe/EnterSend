package com.meowgi.entersend

import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.WindowState
import com.meowgi.entersend.data.AppDb
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.Mode
import com.meowgi.entersend.service.EnterSendService
import com.meowgi.entersend.service.RuleStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.ArrayDeque

/** Drives key events through EnterSendService.onKeyEvent, the real accessibility input path. */
@RunWith(RobolectricTestRunner::class)
class EnterKeyServiceTest {
    private lateinit var db: AppDb
    private val device = FakeDevice()

    @Before fun setUp() {
        db = memoryDb()
    }

    @After fun tearDown() = db.close()

    private fun start(crops: Map<String, com.meowgi.entersend.core.Pixels> = emptyMap()): EnterSendService =
        service(device, db, FakeCrops(crops))

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private fun rule(pkg: String) = db.rules().get(pkg)!!

    // --- Enter sends ---

    @Test fun enterInChatFieldClicksSendAndCountsSuccess() {
        device.field = chatField("hello")
        device.root = window(sendButton())
        val s = start()

        assertEquals(listOf(true, true), s.press(1000))
        assertEquals(1, device.clicks.size)
        settle()
        assertEquals(1, rule("com.whatsapp").successCount)
        assertEquals(1, device.vibrations)
    }

    @Test fun sendThatLeavesTextIsCountedAsFailureWithoutVibration() {
        device.field = chatField("hello")
        device.root = window(sendButton())
        device.textAfterSend = null
        val s = start()

        s.press(1000)
        settle()
        assertEquals(0, rule("com.whatsapp").successCount)
        assertEquals(1, rule("com.whatsapp").failureCount)
        assertEquals(0, device.vibrations)
    }

    @Test fun turkishSendButtonIsFound() {
        device.field = chatField("merhaba", pkg = "org.telegram.messenger", viewId = null, hint = "Mesaj")
        device.root = window(sendButton(viewId = null, desc = "Gönder"))
        start().press(1000)
        assertEquals(1, device.clicks.size)
    }

    @Test fun noSendButtonPassesEnterThrough() {
        device.field = chatField("hello")
        device.root = window(sendButton(viewId = "com.whatsapp:id/voice_note_btn", desc = "Voice message"))
        assertEquals(listOf(false, false), start().press(1000))
        assertTrue(device.clicks.isEmpty())
    }

    @Test fun ctrlEnterPassesThrough() {
        device.field = chatField("hello")
        device.root = window(sendButton())
        val consumed = start().press(1000, meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        assertEquals(listOf(false, false), consumed)
        assertTrue(device.clicks.isEmpty())
    }

    @Test fun otherKeysAreNeverConsumed() {
        device.field = chatField("hello")
        device.root = window(sendButton())
        assertEquals(listOf(false, false), start().press(1000, code = KeyEvent.KEYCODE_Y))
    }

    // --- Shift+Enter ---

    @Test fun shiftEnterInsertsNewlineAtCursorReplacingSelectionAndKeepsEmoji() {
        val text = "hi 😀 there"
        device.field = chatField(text, selStart = 5, selEnd = 6) // selects the space after the emoji
        device.root = window(sendButton())
        val consumed = start().press(1000, meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)

        assertEquals(listOf(true, true), consumed)
        assertEquals("hi 😀\nthere", device.field!!.text)
        assertEquals(6, device.field!!.selStart)
        assertTrue(device.clicks.isEmpty())
    }

    // --- Guards ---

    @Test fun emptyFieldSwallowsEnterAndTapsNothing() {
        device.field = chatField("")
        device.root = window(sendButton(desc = "Send"))
        assertEquals(listOf(true, true), start().press(1000))
        assertTrue(device.clicks.isEmpty())
    }

    @Test fun whitespaceOnlyFieldSwallowsEnter() {
        device.field = chatField("  \n ")
        device.root = window(sendButton())
        assertEquals(listOf(true, true), start().press(1000))
        assertTrue(device.clicks.isEmpty())
    }

    @Test fun searchBarInChatAppPassesThrough() {
        device.field = chatField("bob", viewId = "com.whatsapp:id/search_src_text", hint = "Search…")
        device.root = window(sendButton())
        assertEquals(listOf(false, false), start().press(1000))
        assertTrue(device.clicks.isEmpty())
    }

    @Test fun turkishSearchHintPassesThrough() {
        device.field = chatField("ali", pkg = "org.telegram.messenger", viewId = null, hint = "Ara")
        device.root = window(sendButton())
        assertEquals(listOf(false, false), start().press(1000))
    }

    @Test fun passwordFieldPassesThrough() {
        device.field = chatField(
            "secret",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            isPassword = true,
        )
        device.root = window(sendButton())
        assertEquals(listOf(false, false), start().press(1000))
    }

    @Test fun browserUrlBarPassesThrough() {
        device.field = chatField(
            "example.com",
            pkg = "com.android.chrome",
            viewId = "com.android.chrome:id/url_bar",
            hint = "Search or type URL",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        device.root = window(sendButton(viewId = null))
        assertEquals(listOf(false, false), start().press(1000))
    }

    @Test fun notesAndEmailAppsPassThroughEvenWithMessageLikeFields() {
        for (pkg in listOf("com.google.android.keep", "com.google.android.gm")) {
            device.field = chatField("text", pkg = pkg, viewId = "$pkg:id/compose_message", hint = "Compose email")
            device.root = window(sendButton(viewId = null))
            val s = start()
            assertEquals(pkg, listOf(false, false), s.press(1000))
        }
        assertTrue(device.clicks.isEmpty())
    }

    @Test fun unknownAppWithoutChatSignalsPassesThrough() {
        device.field = chatField("text", pkg = "com.example.todo", viewId = "com.example.todo:id/title", hint = "Title")
        device.root = window(sendButton(viewId = null))
        assertEquals(listOf(false, false), start().press(1000))
    }

    @Test fun unknownAppAfterPreloadUsesDefaultAndSends() {
        // Preload done, no DB row → default AppRule is OK (Setup / undecided chat).
        device.field = chatField("hello")
        device.root = window(sendButton())
        val store = RuleStore(db.rules(), direct, direct, FakeCrops())
        store.preloadAll()
        val s = Robolectric.setupService(EnterSendService::class.java).apply {
            attach(device, store, FakeOverlay())
        }
        assertEquals(listOf(true, true), s.press(1000))
        assertEquals(1, device.clicks.size)
    }

    @Test fun offAppPendingLoadPassesThrough() {
        // Stored Off must never be overwritten by an optimistic default while loading.
        db.rules().upsert(AppRule("com.whatsapp", mode = Mode.OFF.name))
        val pending = ArrayDeque<Runnable>()
        val store = RuleStore(db.rules(), { pending.addLast(it) }, direct, FakeCrops())
        store.preloadAll()
        while (pending.isNotEmpty()) pending.removeFirst().run() // preload finishes — Off cached
        store.dropFromMemoryForTests("com.whatsapp")
        // Start a refresh that never completes.
        store.refresh("com.whatsapp")
        assertTrue(pending.isNotEmpty())
        device.field = chatField("hello")
        device.root = window(sendButton())
        val s = Robolectric.setupService(EnterSendService::class.java).apply {
            attach(device, store, FakeOverlay())
        }
        assertEquals(listOf(false, false), s.press(1000))
        assertTrue(device.clicks.isEmpty())
    }

    // --- Native-Enter apps and modes ---

    @Test fun nativeEnterAppPassesThroughByDefault() {
        device.field = chatField("hi", pkg = "com.discord", viewId = null)
        device.root = window(sendButton(viewId = null))
        assertEquals(listOf(false, false), start().press(1000))
        assertTrue(device.clicks.isEmpty())
    }

    @Test fun nativeEnterAppWithAutoOverrideSends() {
        db.rules().upsert(AppRule("com.discord", mode = Mode.AUTO.name))
        device.field = chatField("hi", pkg = "com.discord", viewId = null)
        device.root = window(sendButton(viewId = null))
        assertEquals(listOf(true, true), start().press(1000))
        assertEquals(1, device.clicks.size)
    }

    @Test fun offModePassesThroughIncludingShiftEnter() {
        db.rules().upsert(AppRule("com.whatsapp", mode = Mode.OFF.name))
        device.field = chatField("hi")
        device.root = window(sendButton())
        val s = start()
        assertEquals(listOf(false, false), s.press(1000))
        assertEquals(listOf(false, false), s.press(2000, meta = KeyEvent.META_SHIFT_ON))
        assertEquals("hi", device.field!!.text)
    }

    // --- Never send twice ---

    @Test fun holdingEnterSendsOnce() {
        device.field = chatField("hello")
        device.root = window(sendButton())
        device.textAfterSend = null // the app is slow to clear the field
        val s = start()

        assertTrue(s.onKeyEvent(key(KeyEvent.ACTION_DOWN, 1000)))
        for (i in 1..20) assertTrue(s.onKeyEvent(key(KeyEvent.ACTION_DOWN, 1000, 1000 + 400 + i * 50L, repeat = i)))
        assertTrue(s.onKeyEvent(key(KeyEvent.ACTION_UP, 1000, 2500)))
        assertEquals(1, device.clicks.size)
    }

    @Test fun deviceMinusOneCopyOfEachEventIsSwallowedAndSendsOnce() {
        device.field = chatField("hello")
        device.root = window(sendButton())
        device.textAfterSend = null
        val s = start()

        assertTrue(s.onKeyEvent(key(KeyEvent.ACTION_DOWN, 1000)))
        assertTrue(s.onKeyEvent(key(KeyEvent.ACTION_DOWN, 1015, deviceId = VIRTUAL)))
        assertTrue(s.onKeyEvent(key(KeyEvent.ACTION_UP, 1000, 1080)))
        assertTrue(s.onKeyEvent(key(KeyEvent.ACTION_UP, 1095, 1095, deviceId = VIRTUAL)))
        assertEquals(1, device.clicks.size)
    }

    @Test fun zeroDurationCopyAfterKeyUpIsSwallowed() {
        device.field = chatField("hello")
        device.root = window(sendButton())
        val s = start()

        s.press(1000)
        assertTrue(s.onKeyEvent(key(KeyEvent.ACTION_DOWN, 1095, deviceId = VIRTUAL)))
        assertTrue(s.onKeyEvent(key(KeyEvent.ACTION_UP, 1095, 1095, deviceId = VIRTUAL)))
        assertEquals(1, device.clicks.size)
    }

    @Test fun copiesOfAPassedThroughPressAlsoPassThrough() {
        device.field = chatField("bob", viewId = "com.whatsapp:id/search_src_text", hint = "Search")
        device.root = window(sendButton())
        val s = start()

        assertFalse(s.onKeyEvent(key(KeyEvent.ACTION_DOWN, 1000)))
        assertFalse(s.onKeyEvent(key(KeyEvent.ACTION_DOWN, 1015, deviceId = VIRTUAL)))
        assertFalse(s.onKeyEvent(key(KeyEvent.ACTION_UP, 1000, 1080)))
        assertFalse(s.onKeyEvent(key(KeyEvent.ACTION_UP, 1095, 1095, deviceId = VIRTUAL)))
    }

    @Test fun syntheticEnterWithoutRealPressIsNeverActedOn() {
        device.field = chatField("hello")
        device.root = window(sendButton())
        val s = start()

        assertFalse(s.onKeyEvent(key(KeyEvent.ACTION_DOWN, 1000, deviceId = VIRTUAL)))
        assertFalse(s.onKeyEvent(key(KeyEvent.ACTION_UP, 1000, 1000, deviceId = VIRTUAL)))
        assertTrue(device.clicks.isEmpty())
    }

    @Test fun realPressRightAfterItsCopyIsTreatedAsTheSamePress() {
        device.field = chatField("hello")
        device.root = window(sendButton())
        val s = start()

        assertFalse(s.onKeyEvent(key(KeyEvent.ACTION_DOWN, 1000, deviceId = VIRTUAL)))
        assertFalse(s.onKeyEvent(key(KeyEvent.ACTION_DOWN, 1015)))
        assertFalse(s.onKeyEvent(key(KeyEvent.ACTION_UP, 1015, 1090)))
        assertTrue(device.clicks.isEmpty())
    }

    @Test fun duplicateRealDownEventDoesNotSendTwice() {
        device.field = chatField("hello")
        device.root = window(sendButton())
        device.textAfterSend = null
        val s = start()

        assertTrue(s.onKeyEvent(key(KeyEvent.ACTION_DOWN, 1000)))
        assertTrue(s.onKeyEvent(key(KeyEvent.ACTION_DOWN, 1000)))
        assertEquals(1, device.clicks.size)
    }

    @Test fun secondPressWhileSendIsUnconfirmedIsSwallowed() {
        device.field = chatField("hello")
        device.root = window(sendButton())
        device.textAfterSend = null
        val s = start()

        s.press(1000)
        assertEquals(listOf(true, true), s.press(1300))
        assertEquals(1, device.clicks.size)
        settle()
        s.press(9000)
        assertEquals(2, device.clicks.size)
    }

    // --- Learned rule ---

    @Test fun learnedRuleWinsOverAutoDetect() {
        db.rules().upsert(AppRule("com.whatsapp", sendViewId = "com.whatsapp:id/fab", taughtVersionCode = 1))
        val taught = sendButton(viewId = "com.whatsapp:id/fab", desc = "Post", bounds = Box(610, 520, 700, 590))
        device.field = chatField("hello")
        device.root = window(sendButton(), taught)
        start().press(1000)
        assertEquals(listOf(taught), device.clicks)
    }

    // --- Saved tap spot ---

    private val crop = solid(0xFF2196F3.toInt())
    private fun tapSpotRule() = AppRule(
        "com.example.chat",
        fieldViewId = "com.example.chat:id/input",
        tapX = 650, tapY = 640,
        cropPath = "/crops/a.png", cropLeft = 626, cropTop = 616, cropRight = 674, cropBottom = 664,
        orientation = 1, windowWidth = 720, windowHeight = 720,
    )

    private fun tapSpotScene() {
        db.rules().upsert(tapSpotRule())
        device.field = chatField("hello", pkg = "com.example.chat", viewId = "com.example.chat:id/input", hint = null)
        device.root = window() // custom-drawn send button: no node to find
    }

    @Test fun tapSpotTapsWhenLiveScreenshotMatches() {
        tapSpotScene()
        device.live = solid(0xFF2196F3.toInt())
        assertEquals(listOf(true, true), start(mapOf("/crops/a.png" to crop)).press(1000))
        assertEquals(listOf(650 to 640), device.taps)
        settle()
        assertEquals(1, rule("com.example.chat").successCount)
    }

    @Test fun tapSpotDoesNotTapWhenLiveScreenshotDiffers() {
        tapSpotScene()
        device.live = solid(0xFF4CAF50.toInt()) // the button now looks different (e.g. a mic)
        start(mapOf("/crops/a.png" to crop)).press(1000)
        assertTrue(device.taps.isEmpty())
        assertEquals(1, device.imeEnters)
    }

    @Test fun tapSpotDoesNotTapWhenScreenshotUnavailable() {
        tapSpotScene()
        device.live = null
        start(mapOf("/crops/a.png" to crop)).press(1000)
        assertTrue(device.taps.isEmpty())
        assertEquals(1, device.imeEnters)
    }

    @Test fun tapSpotPassesThroughWhenOrientationChanged() {
        tapSpotScene()
        device.live = crop
        device.window = WindowState(2, 720, 720, false, 1f)
        assertEquals(listOf(false, false), start(mapOf("/crops/a.png" to crop)).press(1000))
        assertEquals(0, device.captures)
        assertTrue(device.taps.isEmpty())
    }

    @Test fun tapSpotPassesThroughWhenWindowSizeChanged() {
        tapSpotScene()
        device.live = crop
        device.window = WindowState(1, 720, 400, false, 1f) // split screen
        assertEquals(listOf(false, false), start(mapOf("/crops/a.png" to crop)).press(1000))
        assertTrue(device.taps.isEmpty())
    }

    @Test fun tapSpotPassesThroughWhenCropFileMissing() {
        tapSpotScene()
        device.live = crop
        assertEquals(listOf(false, false), start().press(1000))
        assertTrue(device.taps.isEmpty())
    }
}
