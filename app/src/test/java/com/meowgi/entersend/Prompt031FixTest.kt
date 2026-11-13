package com.meowgi.entersend

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.meowgi.entersend.core.SystemAppPolicy
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.PromptDecisionLog
import com.meowgi.entersend.data.PromptPrefs
import com.meowgi.entersend.service.EnterSendService
import com.meowgi.entersend.service.FakePromptBanner
import com.meowgi.entersend.service.ForegroundPackage
import com.meowgi.entersend.service.NewAppNotifier
import com.meowgi.entersend.service.RuleStore
import com.meowgi.entersend.ui.DiagnosticsActivity
import com.meowgi.entersend.ui.MainActivity
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
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
class Prompt031FixTest {
    private lateinit var db: com.meowgi.entersend.data.AppDb
    private val crops = FakeCrops()
    private var day = 20_000L
    private var now = 1_000_000L

    @Before fun setUp() {
        db = memoryDb()
        day = 20_000L
        now = 1_000_000L
        PromptDecisionLog.resetForTests(context())
        PromptPrefs.setPromptsEnabled(context(), true)
        context().getSharedPreferences("entersend_prompts", 0).edit().clear().apply()
        PromptPrefs.setPromptsEnabled(context(), true)
    }

    @After fun tearDown() {
        EnterSendService.clearInstanceForTests()
        db.close()
        PromptDecisionLog.resetForTests(context())
        context().getSharedPreferences("entersend_prompts", 0).edit().clear().apply()
    }

    @Test fun foregroundPrefersApplicationWindowOverImeEvent() {
        val resolved = ForegroundPackage.resolve(
            ownPackage = "com.meowgi.entersend",
            eventPackage = "com.android.inputmethod.latin",
            rootPackage = "com.example.chat",
            windows = listOf(
                ForegroundPackage.WindowHint("com.example.chat", AccessibilityWindowInfo.TYPE_APPLICATION, 2),
                ForegroundPackage.WindowHint(
                    "com.android.inputmethod.latin",
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                    5,
                ),
            ),
            isNoise = { it == "com.android.inputmethod.latin" || it.endsWith(".systemui") },
        )
        assertNotNull(resolved)
        assertEquals("com.example.chat", resolved!!.packageName)
        assertEquals("windows", resolved.source)
    }

    @Test fun foregroundPrefersActiveOverHigherLayerApplication() {
        val resolved = ForegroundPackage.resolve(
            ownPackage = "com.meowgi.entersend",
            eventPackage = "com.android.inputmethod.latin",
            rootPackage = "com.android.inputmethod.latin",
            windows = listOf(
                ForegroundPackage.WindowHint(
                    "com.example.wrongapp",
                    AccessibilityWindowInfo.TYPE_APPLICATION,
                    10,
                    active = false,
                    focused = false,
                ),
                ForegroundPackage.WindowHint(
                    "com.example.chat",
                    AccessibilityWindowInfo.TYPE_APPLICATION,
                    2,
                    active = true,
                    focused = true,
                ),
                ForegroundPackage.WindowHint(
                    "com.android.inputmethod.latin",
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                    20,
                ),
            ),
            isNoise = { it.contains("inputmethod") },
        )
        assertEquals("com.example.chat", resolved!!.packageName)
    }

    @Test fun foregroundIgnoresOwnOverlayAndFallsBackToRoot() {
        val resolved = ForegroundPackage.resolve(
            ownPackage = "com.meowgi.entersend",
            eventPackage = "com.meowgi.entersend",
            rootPackage = "com.example.chat",
            windows = listOf(
                ForegroundPackage.WindowHint(
                    "com.meowgi.entersend",
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                    9,
                ),
            ),
            isNoise = { false },
        )
        assertEquals("com.example.chat", resolved!!.packageName)
        assertEquals("root", resolved.source)
    }

    @Test fun imeOnlyEventResolvesNullSoSessionIsKept() {
        val resolved = ForegroundPackage.resolve(
            ownPackage = "com.meowgi.entersend",
            eventPackage = "com.android.inputmethod.latin",
            rootPackage = "com.android.inputmethod.latin",
            windows = emptyList(),
            isNoise = { it == "com.android.inputmethod.latin" },
        )
        assertNull(resolved)
    }

    @Config(sdk = [29])
    @Test fun serviceIgnoresImeEventWithoutClearingSession() {
        installLauncher("com.example.chat")
        val store = RuleStore(db.rules(), direct, direct, crops)
        val policy = SystemAppPolicy(context())
        val banner = FakePromptBanner()
        val notifier = NewAppNotifier(context(), store, { day }, policy, { now }, banner)
        val service = Robolectric.setupService(EnterSendService::class.java)
        service.attach(FakeDevice(), store, FakeOverlay(), { day }, notifier, policy, banner)
        service.foregroundProbe = {
            ForegroundPackage.Resolved("com.example.chat", "windows")
        }

        service.onAccessibilityEvent(
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                packageName = "com.example.chat"
            },
        )
        shadowOf(Looper.getMainLooper()).idle()
        // onLoaded + get() both call onAppSeen; last may be session-dedupe after a successful post.
        assertEquals(1, shadowOf(context().getSystemService(NotificationManager::class.java)).size())
        assertTrue(
            PromptDecisionLog.get(context()).snapshot().any { it.decision == "prompted:notification" },
        )

        // IME noise with probe returning null — must not clear session-dedupe.
        service.foregroundProbe = { null }
        context().getSystemService(NotificationManager::class.java).cancelAll()
        service.onAccessibilityEvent(
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                packageName = "com.android.inputmethod.latin"
            },
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            PromptDecisionLog.get(context()).snapshot().any { it.decision == "skipped:noise-window" },
        )

        // Same visit still held — direct call must session-dedupe.
        assertFalse(notifier.onAppSeen("com.example.chat", store.get("com.example.chat")!!.rule, "windows"))
        assertEquals("skipped:session-dedupe", notifier.lastDecision)
    }

    @Config(sdk = [29])
    @Test fun deletesLegacyChannelsAndCreatesFreshHigh() {
        val nm = context().getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                NewAppNotifier.CHANNEL_LEGACY,
                "legacy",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        nm.createNotificationChannel(
            NotificationChannel(
                NewAppNotifier.CHANNEL_LEGACY_HEADS,
                "heads",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        // Ensure we do not create CHANNEL at DEFAULT first (recreate would stick).
        assertNull(nm.getNotificationChannel(NewAppNotifier.CHANNEL))
        val store = RuleStore(db.rules(), direct, direct, crops)
        NewAppNotifier(context(), store, { day }).ensureChannel()
        assertNull(nm.getNotificationChannel(NewAppNotifier.CHANNEL_LEGACY))
        assertNull(nm.getNotificationChannel(NewAppNotifier.CHANNEL_LEGACY_HEADS))
        val ch = nm.getNotificationChannel(NewAppNotifier.CHANNEL)
        assertNotNull(ch)
        assertEquals(NewAppNotifier.CHANNEL, ch!!.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)
    }

    @Config(sdk = [29])
    @Test fun decisionLogRecordsPromptAndSkip() {
        installLauncher("com.example.chat")
        val store = RuleStore(db.rules(), direct, direct, crops)
        val log = PromptDecisionLog.get(context())
        val notifier = NewAppNotifier(
            context(), store, { day }, SystemAppPolicy(context()), { now }, log = log,
        )
        assertTrue(notifier.onAppSeen("com.example.chat", AppRule("com.example.chat"), source = "windows"))
        PromptPrefs.setPromptsEnabled(context(), false)
        notifier.onPackageLeft("com.example.chat")
        assertFalse(notifier.onAppSeen("com.example.chat", AppRule("com.example.chat"), source = "event"))
        val snap = log.snapshot()
        assertTrue(snap.any { it.decision == "prompted:notification" && it.source == "windows" })
        assertTrue(snap.any { it.decision == "skipped:prompts-off" })
        assertTrue(log.formatText().contains("com.example.chat"))
    }

    @Test fun focusRingHasNoStrokeAndNoSelectedState() {
        val parser = context().resources.getXml(R.drawable.focus_ring)
        val xml = buildString {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    append(parser.name).append(' ')
                    for (i in 0 until parser.attributeCount) {
                        append(parser.getAttributeName(i)).append('=')
                            .append(parser.getAttributeValue(i)).append(' ')
                    }
                }
                event = parser.next()
            }
        }
        assertFalse("focus_ring must not use a stroke outline: $xml", "stroke" in xml)
        assertFalse("focus_ring must not style state_selected: $xml", "state_selected" in xml)
        assertTrue("focus_ring must react to focus: $xml", "state_focused" in xml)
    }

    @Test fun themeDisablesDefaultFocusHighlight() {
        MainActivity.prefs(context()).edit().putBoolean(MainActivity.KEY_SETUP_DONE, true).apply()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val tv = a.theme.obtainStyledAttributes(intArrayOf(android.R.attr.defaultFocusHighlightEnabled))
        assertFalse(
            "android:defaultFocusHighlightEnabled must be false to kill the system orange ring",
            tv.getBoolean(0, true),
        )
        tv.recycle()
    }

    @Test fun diagnosticsScreenShowsLogAndActions() {
        PromptDecisionLog.get(context()).record("com.example.chat", "windows", "prompted:notification", 1L)
        val a = Robolectric.buildActivity(DiagnosticsActivity::class.java).setup().get()
        val text = a.findViewById<android.widget.TextView>(R.id.diag_log).text.toString()
        assertTrue(text.contains("com.example.chat"))
        assertTrue(text.contains("prompted:notification"))
        assertNotNull(a.findViewById(R.id.diag_copy))
        assertNotNull(a.findViewById(R.id.diag_share))
        assertEquals(19, BuildConfig.VERSION_CODE)
        assertEquals("0.3.12", BuildConfig.VERSION_NAME)
    }

    private fun installLauncher(pkg: String) {
        val ai = ApplicationInfo().apply { packageName = pkg }
        shadowOf(context().packageManager).installPackage(
            PackageInfo().apply { packageName = pkg; applicationInfo = ai },
        )
        val ri = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.Main"
                applicationInfo = ai
            }
        }
        shadowOf(context().packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg),
            ri,
        )
        shadowOf(context().packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            ri,
        )
    }
}
