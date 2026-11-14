package com.meowgi.entersend

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
import com.meowgi.entersend.service.UsageForeground
import com.meowgi.entersend.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * 0.3.2 regressions vs b499c65:
 * - Enter must use focused-input package, warmed only when prompt foreground resolves the chat app.
 * - Prompt must prefer isActive/isFocused TYPE_APPLICATION over highest-layer APPLICATION
 *   when IME + overlay windows are present.
 *
 * [enterSendsWithImeAndOverlayWindowsPresent] and
 * [promptFiresForUndecidedAppDespiteImeAndOverlay] FAIL under b499c65's highest-layer
 * APPLICATION picker and PASS with active/focused preference.
 */
@Config(sdk = [29])
@RunWith(RobolectricTestRunner::class)
class Prompt032RegressionTest {
    private lateinit var db: com.meowgi.entersend.data.AppDb
    private val crops = FakeCrops()
    private var day = 20_000L
    private var now = 1_000_000L

    /** Queues RuleStore IO so loads complete only after [flushIo] (device async race). */
    private val pending = ArrayDeque<Runnable>()
    private val delayedIo = Executor { pending.addLast(it) }

    @Before fun setUp() {
        db = memoryDb()
        day = 20_000L
        now = 1_000_000L
        pending.clear()
        PromptDecisionLog.resetForTests(context())
        context().getSharedPreferences("entersend_prompts", 0).edit().clear().apply()
        PromptPrefs.setPromptsEnabled(context(), true)
    }

    @After fun tearDown() {
        UsageForeground.permissionOverrideForTests = null
        EnterSendService.clearInstanceForTests()
        db.close()
        PromptDecisionLog.resetForTests(context())
        context().getSharedPreferences("entersend_prompts", 0).edit().clear().apply()
    }

    private fun flushIo() {
        while (pending.isNotEmpty()) pending.removeFirst().run()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Window list that breaks b499c65 (highest APPLICATION layer = wrongapp) but is correct
     * with isActive/isFocused preference (chat).
     */
    private fun noisyWindows() = listOf(
        ForegroundPackage.WindowHint(
            "com.example.wrongapp",
            AccessibilityWindowInfo.TYPE_APPLICATION,
            layer = 10,
            active = false,
            focused = false,
        ),
        ForegroundPackage.WindowHint(
            CHAT,
            AccessibilityWindowInfo.TYPE_APPLICATION,
            layer = 2,
            active = true,
            focused = true,
        ),
        ForegroundPackage.WindowHint(
            "com.android.inputmethod.latin",
            AccessibilityWindowInfo.TYPE_INPUT_METHOD,
            layer = 20,
        ),
        ForegroundPackage.WindowHint(
            "com.meowgi.entersend",
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
            layer = 30,
        ),
    )

    /** Documents the b499c65 algorithm picking the wrong package (RED baseline). */
    @Test fun b499c65HighestLayerWouldPickWrongApp() {
        val apps = noisyWindows().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val oldPick = apps.maxByOrNull { it.layer }!!.packageName
        assertEquals(
            "b499c65 highest-layer APPLICATION must pick wrongapp (RED baseline)",
            "com.example.wrongapp",
            oldPick,
        )
        val fixed = ForegroundPackage.resolve(
            ownPackage = "com.meowgi.entersend",
            eventPackage = "com.android.inputmethod.latin",
            rootPackage = "com.android.inputmethod.latin",
            windows = noisyWindows(),
            isNoise = { it.contains("inputmethod") || it.endsWith(".systemui") },
        )
        assertEquals(CHAT, fixed!!.packageName)
        assertNotEquals(oldPick, fixed.packageName)
    }

    @Test fun enterSendsWithImeAndOverlayWindowsPresent() {
        installLauncher(CHAT)
        installLauncher("com.example.wrongapp")
        // Async RuleStore: chat rule is only available after onForeground(chat) flushes.
        // Auto-detect send button — no DB seed required.
        val store = RuleStore(db.rules(), delayedIo, direct, crops)
        val policy = SystemAppPolicy(context())
        val banner = FakePromptBanner()
        val notifier = NewAppNotifier(context(), store, { day }, policy, { now }, banner)
        val device = FakeDevice().apply {
            field = chatField("hello", pkg = CHAT, viewId = "$CHAT:id/entry", hint = "Message")
            root = window(sendButton(viewId = "$CHAT:id/send", desc = "Send"))
        }
        val service = Robolectric.setupService(EnterSendService::class.java)
        service.attach(device, store, FakeOverlay(), { day }, notifier, policy, banner)
        service.windowsForTest = noisyWindows()
        service.rootPackageForTest = "com.android.inputmethod.latin"

        service.onAccessibilityEvent(
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED).apply {
                packageName = "com.android.inputmethod.latin"
            },
        )
        flushIo()

        // Focused-input package is chat; must be warmed by correct prompt foreground.
        val consumed = service.press(2_000)
        assertEquals("Enter must be consumed for chat with IME/overlay present", listOf(true, true), consumed)
        assertEquals(1, device.clicks.size)

        val enterLogs = PromptDecisionLog.get(context()).snapshot().filter { it.source == "enter" }
        assertTrue(
            "Diagnostics must log Enter package/rule/consumed",
            enterLogs.any { it.pkg == CHAT && it.decision.contains("consumed") },
        )
    }

    @Test fun promptFiresForUndecidedAppDespiteImeAndOverlay() {
        installLauncher(CHAT)
        installLauncher("com.example.wrongapp")
        val store = RuleStore(db.rules(), delayedIo, direct, crops)
        val policy = SystemAppPolicy(context())
        val banner = FakePromptBanner()
        val notifier = NewAppNotifier(context(), store, { day }, policy, { now }, banner)
        val service = Robolectric.setupService(EnterSendService::class.java)
        service.attach(FakeDevice(), store, FakeOverlay(), { day }, notifier, policy, banner)
        service.windowsForTest = noisyWindows()
        service.rootPackageForTest = "com.android.inputmethod.latin"

        service.onAccessibilityEvent(
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                packageName = "com.android.inputmethod.latin"
            },
        )
        flushIo()

        assertEquals(1, shadowOf(context().getSystemService(NotificationManager::class.java)).size())
        assertTrue(
            PromptDecisionLog.get(context()).snapshot().any {
                it.pkg == CHAT && it.decision.startsWith("prompted:")
            },
        )
        assertFalse(
            PromptDecisionLog.get(context()).snapshot().any {
                it.pkg == "com.example.wrongapp" && it.decision.startsWith("prompted:")
            },
        )
    }

    @Test fun enterIgnoresWrongPromptForegroundPackage() {
        installLauncher(CHAT)
        val store = RuleStore(db.rules(), direct, direct, crops)
        store.putLocal(
            AppRule(
                packageName = CHAT,
                mode = "LEARNED",
                sendViewId = "$CHAT:id/send",
                sendDesc = "Send",
            ),
        )
        val device = FakeDevice().apply {
            field = chatField("hi", pkg = CHAT, viewId = "$CHAT:id/entry", hint = "Message")
            root = window(sendButton(viewId = "$CHAT:id/send", desc = "Send"))
        }
        val service = Robolectric.setupService(EnterSendService::class.java)
        service.attach(device, store, FakeOverlay())
        // Prompt path thinks wrongapp is foreground — Enter must still use focused chat.
        service.foregroundProbe = { ForegroundPackage.Resolved("com.example.wrongapp", "windows") }
        service.onAccessibilityEvent(
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED).apply {
                packageName = "com.example.wrongapp"
            },
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true, true), service.press(3_000))
        assertEquals(1, device.clicks.size)
    }

    @Test fun homeShowsUsageAccessCardWhenNotGranted() {
        UsageForeground.permissionOverrideForTests = false
        MainActivity.prefs(context()).edit().putBoolean(MainActivity.KEY_SETUP_DONE, true).apply()
        PromptPrefs.setUsageFallbackEnabled(context(), false)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val card = a.findViewById<android.view.View>(R.id.usage_access_card)
        assertNotNull(card)
        assertEquals(android.view.View.VISIBLE, card.visibility)

        // Once granted, Home auto-enables the backup and hides the card.
        UsageForeground.permissionOverrideForTests = true
        a.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        val again = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(android.view.View.GONE, again.findViewById<android.view.View>(R.id.usage_access_card).visibility)
        assertTrue(PromptPrefs.usageFallbackEnabled(context()))

        assertEquals("0.3.17", BuildConfig.VERSION_NAME)
        assertEquals(24, BuildConfig.VERSION_CODE)
    }

    @Test fun noiseDoesNotClearCurrentApp() {
        val resolved = ForegroundPackage.resolve(
            ownPackage = "com.meowgi.entersend",
            eventPackage = "com.android.inputmethod.latin",
            rootPackage = "com.android.inputmethod.latin",
            windows = listOf(
                ForegroundPackage.WindowHint(
                    "com.android.inputmethod.latin",
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                    5,
                ),
                ForegroundPackage.WindowHint(
                    "com.meowgi.entersend",
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                    9,
                ),
            ),
            isNoise = { it.contains("inputmethod") },
        )
        assertEquals(null, resolved)
    }

    @Test fun eventPackageBeatsImeRootLike024() {
        // 0.2.4 used event.packageName; when windows are empty/late and root is IME,
        // the WINDOW_STATE event still names the chat app.
        val resolved = ForegroundPackage.resolve(
            ownPackage = "com.meowgi.entersend",
            eventPackage = CHAT,
            rootPackage = "com.android.inputmethod.latin",
            windows = emptyList(),
            isNoise = { it.contains("inputmethod") },
        )
        assertEquals(CHAT, resolved!!.packageName)
        assertEquals("event", resolved.source)
    }

    @Test fun enterWorksForOwnPackageWithoutPromptWarm() {
        val store = RuleStore(db.rules(), delayedIo, direct, crops)
        store.preloadAll()
        flushIo()
        val device = FakeDevice().apply {
            field = chatField(
                "setup",
                pkg = context().packageName,
                viewId = "${context().packageName}:id/message_input",
                hint = "Type a test message",
            )
            root = window(
                sendButton(
                    viewId = "${context().packageName}:id/send_button",
                    desc = "Send",
                ),
            )
        }
        val service = Robolectric.setupService(EnterSendService::class.java)
        service.attach(device, store, FakeOverlay())
        // Prompt path ignores self — must not prevent Setup Enter from sending.
        service.foregroundProbe = { null }
        assertEquals(listOf(true, true), service.press(4_000))
        assertEquals(1, device.clicks.size)
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

    companion object {
        private const val CHAT = "com.example.chat"
    }
}
