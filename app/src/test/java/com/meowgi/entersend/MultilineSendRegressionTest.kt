package com.meowgi.entersend

import android.os.Looper
import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.Field
import com.meowgi.entersend.core.NodeSnap
import com.meowgi.entersend.core.SendFinder
import com.meowgi.entersend.core.TapSpot
import com.meowgi.entersend.core.WindowState
import com.meowgi.entersend.data.AppDb
import com.meowgi.entersend.data.AppRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Owner bug: after Shift+Enter grows a multiline compose box, Enter stops sending even though
 * the send control is still visible — saved absolute crop / tap coords no longer hit the button.
 */
@RunWith(RobolectricTestRunner::class)
class MultilineSendRegressionTest {
    private lateinit var db: AppDb
    private val device = FakeDevice()
    private val pkg = "com.example.chat"
    private val crop = solid(0xFF2196F3.toInt(), 80, 80)
    private val bg = solid(0xFF111111.toInt(), 80, 80)

    @Before fun setUp() {
        db = memoryDb()
    }

    @After fun tearDown() = db.close()

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    /** Single-line teach geometry: field row at y 600–680, send to the right. */
    private val taughtField = Box(10, 600, 600, 680)
    private val taughtTap = 650 to 640
    private val taughtCrop = Box(610, 600, 690, 680)

    /**
     * After a few newlines the compose row moves up: field taller / higher, send moves with it.
     * Field-relative dp anchors still resolve; absolute cropLeft/Top do not.
     */
    private val grownField = Box(10, 480, 600, 680)
    private val grownTapX: Int get() = grownField.right + 50 // tapDxDp=50, density=1
    private val grownTapY: Int get() = grownField.centerY // tapDyDp=0
    private val grownCrop: Box get() = TapSpot.cropBox(grownTapX, grownTapY, WindowState(1, 720, 720, false, 1f))

    private fun tapSpotRule() = AppRule(
        pkg,
        fieldViewId = "$pkg:id/input",
        tapDxDp = 50f,
        tapDyDp = 0f,
        tapX = taughtTap.first,
        tapY = taughtTap.second,
        cropPath = "/crops/a.png",
        cropLeft = taughtCrop.left,
        cropTop = taughtCrop.top,
        cropRight = taughtCrop.right,
        cropBottom = taughtCrop.bottom,
        orientation = 1,
        windowWidth = 720,
        windowHeight = 720,
        keyboardShown = false,
    )

    @Test fun tapSpotSendsAfterMultilineGrowsWhenCropCheckedAtLivePoint() {
        db.rules().upsert(tapSpotRule())
        device.window = WindowState(1, 720, 720, keyboardShown = false, density = 1f)
        device.field = Field(
            pkg, "line1\nline2\nline3", 0, 0, CHAT_TYPE, false,
            "$pkg:id/input", null, grownField,
        )
        device.root = window() // no a11y send node — tap-spot only
        // Icon only visible around the *moved* button; taught absolute crop sees background.
        device.liveForRect = { rect ->
            if (rect.left == grownCrop.left && rect.top == grownCrop.top &&
                rect.right == grownCrop.right && rect.bottom == grownCrop.bottom
            ) {
                crop
            } else {
                bg
            }
        }

        val s = service(device, db, FakeCrops(mapOf("/crops/a.png" to crop)))
        assertEquals(listOf(true, true), s.press(1000))
        assertEquals(
            "must tap the live field-relative point, not the taught absolute coords",
            listOf(grownTapX to grownTapY),
            device.taps,
        )
        assertEquals(0, device.imeEnters)
        assertTrue(
            "crop must be captured around the live tap, not saved cropLeft/Top",
            device.captureRects.any {
                it.left == grownCrop.left && it.top == grownCrop.top &&
                    it.right == grownCrop.right && it.bottom == grownCrop.bottom
            },
        )
        settle()
        assertEquals(1, db.rules().get(pkg)!!.successCount)
    }

    @Test fun shiftEnterStillInsertsNewlineWhenMultiline() {
        db.rules().upsert(tapSpotRule())
        device.field = Field(
            pkg, "hello", 5, 5, CHAT_TYPE, false,
            "$pkg:id/input", null, grownField,
        )
        device.root = window()
        val s = service(device, db, FakeCrops(mapOf("/crops/a.png" to crop)))
        assertEquals(listOf(true, true), s.press(1000, meta = android.view.KeyEvent.META_SHIFT_ON))
        assertEquals("hello\n", device.field!!.text)
        assertTrue(device.taps.isEmpty())
        assertTrue(device.clicks.isEmpty())
    }

    @Test fun learnedClassPathFindsButtonAfterItMovedWithMultiline() {
        val rule = AppRule(
            pkg,
            sendClassPath = "android.widget.FrameLayout>android.view.View",
            tapX = taughtTap.first,
            tapY = taughtTap.second,
        )
        val moved = NodeSnap(
            className = "android.view.View",
            clickable = true,
            bounds = Box(610, 500, 700, 580), // no longer contains taught tap (650,640)
        )
        val other = NodeSnap(className = "android.view.View", clickable = true, bounds = Box(0, 0, 80, 80))
        val tree = NodeSnap(
            className = "android.widget.FrameLayout",
            bounds = Box(0, 0, 720, 720),
            children = listOf(other, moved),
        )
        val field = Field(pkg, "a\nb\nc", 0, 0, CHAT_TYPE, false, null, null, grownField)
        val found = SendFinder.learned(tree, field, rule)
        assertNotNull("classPath rule must resolve by identity near the field, not fixed tap coords", found)
        assertEquals(moved, found)
    }

    @Test fun offStillPassesEnterThroughAfterMultiline() {
        db.rules().upsert(tapSpotRule().copy(mode = "OFF"))
        device.field = Field(
            pkg, "a\nb", 0, 0, CHAT_TYPE, false,
            "$pkg:id/input", null, grownField,
        )
        device.root = window(
            NodeSnap(viewId = "$pkg:id/send", desc = "Send", clickable = true, bounds = Box(610, 500, 700, 580)),
        )
        assertEquals(listOf(false, false), service(device, db).press(1000))
        assertTrue(device.taps.isEmpty())
        assertTrue(device.clicks.isEmpty())
    }
}
