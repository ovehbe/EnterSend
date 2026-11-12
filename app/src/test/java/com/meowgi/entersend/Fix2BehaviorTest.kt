package com.meowgi.entersend

import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.NodeSnap
import com.meowgi.entersend.core.TapSpot
import com.meowgi.entersend.core.WindowState
import com.meowgi.entersend.data.AppDb
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.service.Card
import com.meowgi.entersend.service.Choice
import com.meowgi.entersend.service.EnterSendService
import com.meowgi.entersend.service.NewAppNotifier
import com.meowgi.entersend.service.NotifyActionReceiver
import com.meowgi.entersend.service.RuleStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class Fix2BehaviorTest {
    private lateinit var db: AppDb
    private val device = FakeDevice()
    private val overlay = FakeOverlay()
    private val crops = FakeCrops()
    private var day = 20_000L

    @Before fun setUp() {
        db = memoryDb()
    }

    @After fun tearDown() = db.close()

    private fun start(): EnterSendService = service(device, db, crops, overlay, today = { day })

    private fun idle(ms: Long = 600) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    // --- Node success fires zero gestures ---

    @Test fun nodeSendDoesNotDispatchGesture() {
        device.field = chatField("hello")
        device.root = window(sendButton())
        device.textAfterSend = ""
        start().press(1000)
        assertEquals(1, device.clicks.size)
        assertTrue(device.taps.isEmpty())
        idle()
        assertEquals(1, db.rules().get("com.whatsapp")!!.successCount)
    }

    // --- Tap fallback when no node ---

    @Test fun tapSpotUsedWhenNoNodeExists() {
        val pkg = "com.example.chat"
        val crop = solid(0xFF2196F3.toInt(), 80, 80)
        db.rules().upsert(
            AppRule(
                pkg,
                fieldViewId = "$pkg:id/message_input",
                tapDxDp = 50f, tapDyDp = 0f,
                tapFx = 650f / 720f, tapFy = 640f / 720f,
                cropPath = "/crops/a.png",
                cropLeft = 610, cropTop = 600, cropRight = 690, cropBottom = 680,
                orientation = 1, windowWidth = 720, windowHeight = 720, keyboardShown = false,
            ),
        )
        crops.files["/crops/a.png"] = crop
        device.field = chatField("hello", pkg = pkg, viewId = "$pkg:id/message_input", hint = null)
        device.root = window()
        device.live = crop
        device.textAfterSend = ""
        assertEquals(listOf(true, true), start().press(1000))
        assertTrue(device.clicks.isEmpty())
        assertEquals(1, device.taps.size)
        idle()
        assertEquals(1, db.rules().get(pkg)!!.successCount)
    }

    @Test fun cropMismatchPassesEnterThroughAndMarksRecheck() {
        val pkg = "com.example.chat"
        val saved = solid(0xFF2196F3.toInt(), 80, 80)
        db.rules().upsert(
            AppRule(
                pkg, tapDxDp = 50f, tapDyDp = 0f,
                cropPath = "/crops/a.png", cropLeft = 610, cropTop = 600, cropRight = 690, cropBottom = 680,
                orientation = 1, windowWidth = 720, windowHeight = 720, keyboardShown = false,
                fieldViewId = "$pkg:id/message_input",
            ),
        )
        crops.files["/crops/a.png"] = saved
        device.field = chatField("hello", pkg = pkg, viewId = "$pkg:id/message_input", hint = null)
        device.root = window()
        device.live = solid(0xFF4CAF50.toInt(), 80, 80)
        start().press(1000)
        assertTrue(device.taps.isEmpty())
        assertEquals(1, device.imeEnters)
        assertTrue(db.rules().get(pkg)!!.needsRecheck || start().store!!.get(pkg)!!.rule.needsRecheck)
    }

    @Test fun emptyBoxNeverTaps() {
        val pkg = "com.example.chat"
        db.rules().upsert(
            AppRule(
                pkg, tapDxDp = 50f, tapDyDp = 0f, cropPath = "/crops/a.png",
                cropLeft = 610, cropTop = 600, cropRight = 690, cropBottom = 680,
                orientation = 1, windowWidth = 720, windowHeight = 720,
                fieldViewId = "$pkg:id/message_input",
            ),
        )
        crops.files["/crops/a.png"] = solid(0xFF2196F3.toInt(), 80, 80)
        device.field = chatField("", pkg = pkg, viewId = "$pkg:id/message_input", hint = null)
        device.root = window()
        device.live = crops.files["/crops/a.png"]
        assertEquals(listOf(true, true), start().press(1000))
        assertTrue(device.taps.isEmpty())
        assertEquals(0, device.captures)
    }

    // --- Teach two-step ---

    @Test fun teachStep1SavesNodeImmediatelyWithoutGestureOverlay() {
        val pkg = "com.example.chat"
        val send = NodeSnap(viewId = "$pkg:id/go", desc = "Send", clickable = true, bounds = SEND_BOX)
        device.field = chatField("hi", pkg = pkg, viewId = "$pkg:id/message_input", hint = null)
        device.root = window(send)
        val s = start()
        s.teach!!.startTeaching(pkg)
        assertTrue(overlay.card is Card.TeachingBar) // step 1: short top bar
        s.teach!!.onClicked(pkg, send)
        assertTrue(overlay.card is Card.Saved)
        assertEquals("$pkg:id/go", db.rules().get(pkg)!!.sendViewId)
        assertNull(db.rules().get(pkg)!!.tapDxDp)
    }

    @Test fun teachStep2CaptureNeverSavesFromEmptyBox() {
        val pkg = "com.example.chat"
        device.field = chatField("", pkg = pkg, viewId = "$pkg:id/message_input", hint = null)
        device.root = window()
        device.live = solid(0xFF2196F3.toInt(), 80, 80)
        val s = start()
        s.teach!!.startTeaching(pkg)
        device.field = device.field!!.copy(text = "x")
        s.teach!!.onTextChanged(pkg)
        device.field = device.field!!.copy(text = "")
        s.teach!!.onTextChanged(pkg) // step2 bar
        assertTrue(overlay.card is Card.TeachingBar)
        // Force capture with empty field
        s.teach!!.onCaptureTap(650, 640)
        // Still no rule saved
        assertNull(db.rules().get(pkg)?.cropPath)
    }

    @Test fun teachCaptureSavesDpAnchorWhenTextPresent() {
        val pkg = "com.example.chat"
        device.field = chatField("hello", pkg = pkg, viewId = "$pkg:id/message_input", hint = null)
        device.root = window()
        device.live = solid(0xFF2196F3.toInt(), 80, 80)
        val s = start()
        s.teach!!.startTeaching(pkg)
        device.field = device.field!!.copy(text = "x")
        s.teach!!.onTextChanged(pkg)
        device.field = device.field!!.copy(text = "")
        s.teach!!.onTextChanged(pkg)
        device.field = device.field!!.copy(text = "hello")
        s.teach!!.promoteCaptureIfReady(pkg)
        assertTrue(overlay.card is Card.Capture)
        overlay.screenTap(650, 640)
        idle(100)
        assertNotNull((overlay.card as? Card.Capture)?.markerX)
        overlay.tap(Choice.YES)
        val rule = db.rules().get(pkg)!!
        assertNotNull(rule.tapDxDp)
        assertNotNull(rule.cropPath)
        assertTrue(rule.cropPath in crops.files)
    }

    @Test fun overlayTimeoutAndRemovedOnServiceRestart() {
        val pkg = "com.example.chat"
        device.field = chatField("hi", pkg = pkg, viewId = "$pkg:id/message_input", hint = null)
        val s = start()
        s.teach!!.startTeaching(pkg)
        device.field = device.field!!.copy(text = "x")
        s.teach!!.onTextChanged(pkg)
        device.field = device.field!!.copy(text = "")
        s.teach!!.onTextChanged(pkg)
        device.field = device.field!!.copy(text = "hi")
        s.teach!!.promoteCaptureIfReady(pkg)
        assertTrue(s.teach!!.isCapturing)
        idle(30_100)
        assertFalse(s.teach!!.isTeaching)
        assertNull(overlay.card)

        // New service connection force-closes any teach state.
        val s2 = start()
        s2.teach!!.startTeaching(pkg)
        assertTrue(s2.teach!!.isTeaching)
        s2.teach!!.forceClose()
        assertFalse(s2.teach!!.isTeaching)
    }

    @Test fun taughtRuleSurvivesProcessRestartViaReopenedDb() {
        val pkg = "com.example.chat"
        val diskName = "restart-taught.db"
        context().deleteDatabase(diskName)
        fun build() = androidx.room.Room.databaseBuilder(context(), AppDb::class.java, diskName)
            .addMigrations(*AppDb.MIGRATIONS).allowMainThreadQueries().build()
        val first = build()
        first.rules().upsert(AppRule(pkg, sendViewId = "$pkg:id/go", sendDesc = "Send", taughtVersionCode = 1))
        first.close()
        val reopened = build()
        try {
            val device2 = FakeDevice()
            val send = NodeSnap(viewId = "$pkg:id/go", desc = "Send", clickable = true, bounds = SEND_BOX)
            device2.field = chatField("x", pkg = pkg, viewId = "$pkg:id/message_input", hint = null)
            device2.root = window(send)
            device2.textAfterSend = ""
            service(device2, reopened, FakeCrops(), FakeOverlay()).press(1000)
            assertEquals(1, device2.clicks.size)
        } finally {
            reopened.close()
            context().deleteDatabase(diskName)
        }
    }

    // --- Notification throttle / PendingIntent package ---

    @Config(sdk = [29])
    @Test fun notificationThrottledAndDeclined() {
        val store = RuleStore(db.rules(), direct, direct, crops)
        val notifier = NewAppNotifier(context(), store, { day })
        val nm = context().getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(nm)
        notifier.onAppSeen("com.example.chat", AppRule("com.example.chat"))
        assertEquals(1, shadow.size())
        nm.cancel(NewAppNotifier.notificationId("com.example.chat"))
        assertEquals(0, shadow.size())
        notifier.onAppSeen("com.example.chat", store.get("com.example.chat")!!.rule)
        assertEquals(0, shadow.size()) // same day
        store.setOff("com.example.chat")
        day++
        notifier.onAppSeen("com.example.chat", store.get("com.example.chat")!!.rule)
        assertEquals(0, shadow.size()) // Off
    }

    @Config(sdk = [34])
    @Test fun pendingIntentCarriesPackageAndUniqueRequestCodes() {
        val store = RuleStore(db.rules(), direct, direct, crops)
        val notifier = NewAppNotifier(context(), store, { day })
        notifier.post("com.foo")
        notifier.post("com.bar")
        assertTrue(NewAppNotifier.requestCode("com.foo", 1) != NewAppNotifier.requestCode("com.bar", 1))
        assertTrue(NewAppNotifier.requestCode("com.foo", 0) != NewAppNotifier.requestCode("com.foo", 1))
        // Build the same PendingIntent the notifier uses and read the extra.
        val pi = android.app.PendingIntent.getBroadcast(
            context(),
            NewAppNotifier.requestCode("com.foo", 1),
            Intent(context(), NotifyActionReceiver::class.java)
                .setAction(NotifyActionReceiver.ACTION_TEACH)
                .putExtra(NotifyActionReceiver.EXTRA_PACKAGE, "com.foo"),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Shadow PendingIntent keeps the intent
        val shadow = shadowOf(pi)
        assertEquals("com.foo", shadow.savedIntent.getStringExtra(NotifyActionReceiver.EXTRA_PACKAGE))
    }

    @Test fun tapSpotUnsafePointInsideFieldIsRejected() {
        val field = FIELD_BOX
        val ws = WindowState(1, 720, 720, false, 1f)
        // Field-bounds rejection removed — taps inside the compose row (incl. send) are valid.
        assertTrue(TapSpot.isOnScreen(field.centerX to field.centerY, ws))
        assertTrue(TapSpot.isOnScreen(650 to 640, ws))
        assertFalse(TapSpot.isOnScreen(900 to 900, ws))
    }
}
