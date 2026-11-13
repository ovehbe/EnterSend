package com.meowgi.entersend

import android.os.Looper
import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.Field
import com.meowgi.entersend.core.Pixels
import com.meowgi.entersend.core.TapSpot
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

/**
 * Real-device 0.3.10 log (app.cogwheel.conduit): field grows UPWARD (top moves, bottom ~fixed),
 * send button stays fixed bottom-right. Center/proportional resolve drifts up; absolute taught
 * point still hits. Numbers copied from the owner's Enter log.
 */
@RunWith(RobolectricTestRunner::class)
class ConduitUpwardGrowTapSpotTest {
    private lateinit var db: AppDb
    private val device = FakeDevice()
    private val pkg = "app.cogwheel.conduit"

    /** Taught crop / icon around absolute (660,631). */
    private val buttonCrop = solid(0xFF3366FF.toInt(), 80, 80)
    private val missCrop = solid(0xFF111111.toInt(), 80, 80)

    private val taughtTap = 660 to 631
    private val taughtCrop = Box(620, 591, 700, 671)
    private val ws = WindowState(1, 720, 720, keyboardShown = false, density = 1f)

    /** Single-line teach field from log. */
    private val field1 = Box(115, 604, 626, 650)

    /** 5-line field from log — top rose, bottom stayed ~648. */
    private val field5 = Box(115, 462, 626, 648)

    @Before fun setUp() {
        db = memoryDb()
        device.window = ws
    }

    @After fun tearDown() = db.close()

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private fun rule(): AppRule {
        val dx = TapSpot.dxDp(field1, taughtTap.first, 1f)
        val dyCenter = TapSpot.dyDp(field1, taughtTap.second, 1f)
        return AppRule(
            pkg,
            fieldViewId = null,
            tapX = taughtTap.first,
            tapY = taughtTap.second,
            tapDxDp = dx,
            tapDyDp = dyCenter, // as 0.3.10 taught (center-relative)
            tapFx = 660f / 720f,
            tapFy = 631f / 720f,
            cropPath = "/crops/conduit.png",
            cropLeft = taughtCrop.left,
            cropTop = taughtCrop.top,
            cropRight = taughtCrop.right,
            cropBottom = taughtCrop.bottom,
            orientation = 1,
            windowWidth = 720,
            windowHeight = 720,
            keyboardShown = false,
        )
    }

    /** Live pixels: icon only around the fixed button; drifted resolve sees miss. */
    private fun installLiveForFixedButton() {
        device.live = fullScreenWithButton()
        device.liveForRect = null
    }

    private fun fullScreenWithButton(): Pixels {
        val w = 720
        val h = 720
        val argb = IntArray(w * h) { 0xFF111111.toInt() }
        val crop = buttonCrop
        val l = taughtCrop.left
        val t = taughtCrop.top
        for (y in 0 until crop.height) for (x in 0 until crop.width) {
            val sx = l + x
            val sy = t + y
            if (sx in 0 until w && sy in 0 until h) {
                argb[sy * w + sx] = crop.argb[y * crop.width + x]
            }
        }
        return Pixels(w, h, argb)
    }

    @Test fun fiveLineFieldStillTapsFixedButtonNotDriftedCenter() {
        db.rules().upsert(rule())
        installLiveForFixedButton()
        device.field = Field(
            pkg, "a\nb\nc\nd\ne", 0, 0, CHAT_TYPE, false,
            null, null, field5,
        )
        device.root = window()

        // Sanity: 0.3.10-style center resolve drifts to ~559 (log).
        val drifted = TapSpot.resolve(rule(), device.field, ws, 1f, false)!!
        assertEquals(660, drifted.first)
        assertEquals(559, drifted.second)

        val s = service(device, db, FakeCrops(mapOf("/crops/conduit.png" to buttonCrop)))
        assertEquals(listOf(true, true), s.press(1000))
        assertTrue(
            "must tap near fixed taught button (660,631), taps=${device.taps}",
            device.taps.any { (x, y) -> kotlin.math.abs(x - 660) <= 5 && kotlin.math.abs(y - 631) <= 12 },
        )
        assertEquals(0, device.imeEnters)
        settle()
        assertEquals(1, db.rules().get(pkg)!!.successCount)
    }

    @Test fun singleLineStillSends() {
        db.rules().upsert(rule())
        installLiveForFixedButton()
        device.field = Field(
            pkg, "hello", 5, 5, CHAT_TYPE, false,
            null, null, field1,
        )
        device.root = window()
        val s = service(device, db, FakeCrops(mapOf("/crops/conduit.png" to buttonCrop)))
        assertEquals(listOf(true, true), s.press(1000))
        assertTrue(device.taps.isNotEmpty())
        assertEquals(0, device.imeEnters)
    }
}
