package com.meowgi.entersend

import android.os.Looper
import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.WindowState
import com.meowgi.entersend.data.AppDb
import com.meowgi.entersend.data.AppRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class TapSpotScreenshotNullTest {
    private lateinit var db: AppDb
    private val device = FakeDevice()
    private val crop = solid(0xFF2196F3.toInt(), 80, 80)

    @Before fun setUp() {
        db = memoryDb()
        device.window = WindowState(1, 720, 720, false, 1f)
    }

    @After fun tearDown() = db.close()

    @Test fun nullScreenshotRetriesThenDoesNotImeEnter() {
        db.rules().upsert(
            AppRule(
                "com.example.chat",
                tapDxDp = 50f, tapDyDp = 0f,
                tapX = 650, tapY = 640,
                cropPath = "/crops/a.png",
                cropLeft = 610, cropTop = 600, cropRight = 690, cropBottom = 680,
                orientation = 1, windowWidth = 720, windowHeight = 720, keyboardShown = false,
                fieldViewId = "com.example.chat:id/input",
            ),
        )
        device.field = chatField("hi", pkg = "com.example.chat", viewId = "com.example.chat:id/input", hint = null)
        device.root = window()
        device.live = crop
        device.nullCapturesRemaining = 1 // first full-screen capture null; retry succeeds
        val s = service(device, db, FakeCrops(mapOf("/crops/a.png" to crop)))
        assertEquals(listOf(true, true), s.press(1000))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertTrue("retry should eventually tap, taps=${device.taps}", device.taps.isNotEmpty())
        assertEquals(0, device.imeEnters)
    }

    @Test fun nullScreenshotTwiceSwallowsWithoutNewline() {
        db.rules().upsert(
            AppRule(
                "com.example.chat",
                tapDxDp = 50f, tapDyDp = 0f,
                tapX = 650, tapY = 640,
                cropPath = "/crops/a.png",
                cropLeft = 610, cropTop = 600, cropRight = 690, cropBottom = 680,
                orientation = 1, windowWidth = 720, windowHeight = 720, keyboardShown = false,
                fieldViewId = "com.example.chat:id/input",
            ),
        )
        device.field = chatField("hi", pkg = "com.example.chat", viewId = "com.example.chat:id/input", hint = null)
        device.root = window()
        device.live = crop
        device.nullCapturesRemaining = 99
        val s = service(device, db, FakeCrops(mapOf("/crops/a.png" to crop)))
        assertEquals(listOf(true, true), s.press(1000))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(device.taps.isEmpty())
        assertEquals("must not imeEnter on throttled screenshot", 0, device.imeEnters)
    }
}
