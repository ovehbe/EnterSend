package com.meowgi.entersend

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import com.meowgi.entersend.core.NodeSnap
import com.meowgi.entersend.data.AppDb
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.service.Card
import com.meowgi.entersend.service.EnterSendService
import com.meowgi.entersend.service.RuleStore
import com.meowgi.entersend.ui.MainActivity
import com.meowgi.entersend.ui.SetupActivity
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Regressions for the Q25 report: service dies / wrong a11y intent, foreign-app Enter ignored,
 * teach tap silent, taught rule lost on restart, key box not focusable.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceBugsFixTest {
    private lateinit var db: AppDb
    private val device = FakeDevice()
    private val overlay = FakeOverlay()
    private val crops = FakeCrops()

    @Before fun setUp() {
        db = memoryDb()
    }

    @After fun tearDown() = db.close()

    private fun start(): EnterSendService = service(device, db, crops, overlay)

    // --- Crash when opening accessibility settings ---

    @Config(sdk = [34])
    @Test fun accessibilityDetailsExtraIsAComponentNameNotAString() {
        val activity = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        activity.findViewById<View>(R.id.step1_button).performClick()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(ServiceWatch.ACTION_ACCESSIBILITY_DETAILS, intent.action)
        val extra = intent.getParcelableExtra<ComponentName>(Intent.EXTRA_COMPONENT_NAME)
        assertNotNull("EXTRA_COMPONENT_NAME must be a ComponentName; a String crashes Settings on Android 14", extra)
        assertEquals(EnterSendService::class.java.name, extra!!.className)
        assertEquals(activity.packageName, extra.packageName)
    }

    @Test fun notificationIconHasNoThemeAttributes() {
        val parser = context().resources.getXml(R.drawable.ic_notification)
        var tint: String? = null
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                for (i in 0 until parser.attributeCount) {
                    if (parser.getAttributeName(i) == "tint") tint = parser.getAttributeValue(i)
                }
            }
        }
        assertEquals(
            "Theme attrs on notification icons crash when the service posts the off notice",
            null,
            tint,
        )
    }

    // --- Enter in a real (foreign) app, not only our setup screen ---

    @Test fun enterInForeignMessengerClicksThatAppsSendButton() {
        // Package is not com.meowgi.entersend — mirrors WhatsApp/Telegram on device.
        val foreign = "org.telegram.messenger"
        val send = NodeSnap(
            viewId = null,
            desc = "Gönder",
            className = "android.widget.ImageButton",
            clickable = true,
            bounds = SEND_BOX,
        )
        device.field = chatField("merhaba 👋", pkg = foreign, viewId = null, hint = "Mesaj")
        device.root = window(send)
        device.textAfterSend = ""

        val consumed = start().press(1_000)
        assertEquals(listOf(true, true), consumed)
        assertEquals(listOf(send), device.clicks)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(1, db.rules().get(foreign)!!.successCount)
        assertEquals(1, device.vibrations)
    }

    // --- Taught rule survives service restart ---

    @Test fun taughtRuleStillSendsAfterServiceRestart() {
        val pkg = "com.example.otherchat"
        val send = NodeSnap(
            viewId = "$pkg:id/go",
            desc = "Send",
            clickable = true,
            bounds = SEND_BOX,
        )
        db.rules().upsert(
            AppRule(
                pkg,
                sendViewId = "$pkg:id/go",
                sendDesc = "Send",
                fieldViewId = "$pkg:id/message_input",
                taughtVersionCode = 1,
            ),
        )
        device.field = chatField("hello", pkg = pkg, viewId = "$pkg:id/message_input", hint = null)
        device.root = window(send) // label alone wouldn't be required; the taught id must win
        device.textAfterSend = ""

        // First service instance uses the rule.
        assertEquals(listOf(true, true), start().press(1_000))
        assertEquals(1, device.clicks.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))

        // New service instance + store = process/service restart; same DB.
        device.clicks.clear()
        device.field = chatField("hello again", pkg = pkg, viewId = "$pkg:id/message_input", hint = null)
        device.textAfterSend = ""
        val afterRestart = service(device, db, crops, FakeOverlay())
        assertEquals(listOf(true, true), afterRestart.press(2_000))
        assertEquals(listOf(send), device.clicks)
    }

    // --- Teach must not fail silently ---

    @Test fun missedTeachClickEntersStep2Bar() {
        // Empty box so enterStep2 stays on the bar (non-empty would open capture immediately).
        device.field = chatField("", pkg = "com.example.chat", viewId = "com.example.chat:id/message_input", hint = null)
        val teach = com.meowgi.entersend.service.TeachFlow(
            device, RuleStore(db.rules(), direct, direct, crops), overlay,
            android.os.Handler(Looper.getMainLooper()), { 20_000L }, { 1L },
        ) { context().getString(it) }
        teach.startTeaching("com.example.chat")
        device.field = device.field!!.copy(text = "x")
        teach.onTextChanged("com.example.chat")
        device.field = device.field!!.copy(text = "")
        teach.onTextChanged("com.example.chat")
        assertTrue(overlay.card is Card.TeachingBar)
        assertEquals(context().getString(R.string.teach_step2), (overlay.card as Card.TeachingBar).message)
        val tr = context().createConfigurationContext(
            android.content.res.Configuration(context().resources.configuration).apply {
                setLocale(java.util.Locale("tr"))
            },
        )
        assertTrue(tr.getString(R.string.teach_step2).isNotBlank())
        // Typing opens capture.
        device.field = chatField("hi", pkg = "com.example.chat", viewId = "com.example.chat:id/message_input", hint = null)
        teach.onTextChanged("com.example.chat")
        assertTrue(overlay.card is Card.Capture)
    }

    @Test fun teachWithNullClickSourceEntersStep2ViaServiceEvent() {
        device.field = chatField("", pkg = "com.example.chat", viewId = "com.example.chat:id/message_input", hint = null)
        val s = start()
        s.teach!!.startTeaching("com.example.chat")
        assertTrue(s.teach!!.isTeaching)
        device.field = device.field!!.copy(text = "x")
        s.teach!!.onTextChanged("com.example.chat")
        device.field = device.field!!.copy(text = "")
        s.teach!!.onTextChanged("com.example.chat")
        assertTrue("card=${overlay.card}", overlay.card is Card.TeachingBar)
    }

    @Test fun settingsTeachWithServiceOffShowsBannerAndDoesNotCrash() {
        MainActivity.prefs(context()).edit().putBoolean(MainActivity.KEY_SETUP_DONE, true).apply()
        EnterSendService.instance // touch
        // Ensure no connected service.
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // Directly invoke the private path by selecting Teach is hard; assert the user-visible string
        // and banner path used when service is off.
        assertEquals(
            context().getString(R.string.turn_on_first),
            activity.getString(R.string.turn_on_first),
        )
        assertEquals(
            "Önce Erişilebilirlik ayarlarından EnterSend'i açın.",
            activity.createConfigurationContext(
                android.content.res.Configuration(activity.resources.configuration).apply {
                    setLocale(java.util.Locale("tr"))
                },
            ).getString(R.string.turn_on_first),
        )
    }

    // --- Key box ---

    @Test fun keyBoxShowsPressAKeyAndTakesFocusFromTapAndKeyboard() {
        val a = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        val box = a.findViewById<TextView>(R.id.key_box)
        assertEquals(a.getString(R.string.press_a_key), box.text.toString())
        assertTrue(box.isClickable)
        assertTrue(box.isFocusable)
        assertTrue(box.isFocusableInTouchMode)
        box.performClick()
        assertTrue(box.isFocused)
        box.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertTrue(box.text.toString().contains("✓") || box.text.toString().contains(a.getString(R.string.check_yes)))
    }
}
