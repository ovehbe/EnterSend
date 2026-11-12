package com.meowgi.entersend

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.meowgi.entersend.core.SystemAppPolicy
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.Mode
import com.meowgi.entersend.data.PromptDecisionLog
import com.meowgi.entersend.data.PromptPrefs
import com.meowgi.entersend.service.EnterSendService
import com.meowgi.entersend.service.FakePromptBanner
import com.meowgi.entersend.service.NewAppNotifier
import com.meowgi.entersend.service.NotifyActionReceiver
import com.meowgi.entersend.service.RuleStore
import com.meowgi.entersend.ui.PromptActivity
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

@RunWith(RobolectricTestRunner::class)
class PromptReliabilityTest {
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
        db.close()
        PromptDecisionLog.resetForTests(context())
        context().getSharedPreferences("entersend_prompts", 0).edit().clear().apply()
    }

    @Config(sdk = [29])
    @Test fun undecidedAppPromptsEveryNewForegroundVisit() {
        installLauncher("com.example.chat")
        val store = RuleStore(db.rules(), direct, direct, crops)
        val notifier = NewAppNotifier(context(), store, { day }, SystemAppPolicy(context()), { now })
        val nm = shadowOf(context().getSystemService(NotificationManager::class.java))

        assertTrue(notifier.onAppSeen("com.example.chat", AppRule("com.example.chat")))
        assertEquals(1, nm.size())
        assertEquals(NewAppNotifier.Result.NOTIFIED, notifier.lastResult)

        // Same visit (activity change) — no spam.
        context().getSystemService(NotificationManager::class.java).cancelAll()
        assertFalse(notifier.onAppSeen("com.example.chat", store.get("com.example.chat")!!.rule))
        assertEquals(0, nm.size())

        // Leave and re-enter — still undecided → prompt again (no once-per-day burn).
        notifier.onPackageLeft("com.example.chat")
        day++
        assertTrue(notifier.onAppSeen("com.example.chat", store.get("com.example.chat")!!.rule))
        assertEquals(1, nm.size())
    }

    @Config(sdk = [29])
    @Test fun decidedSnoozedAndDontAskDoNotPrompt() {
        installLauncher("com.example.chat")
        val store = RuleStore(db.rules(), direct, direct, crops)
        val policy = SystemAppPolicy(context())
        val notifier = NewAppNotifier(context(), store, { day }, policy, { now })
        val nm = shadowOf(context().getSystemService(NotificationManager::class.java))

        store.setOff("com.example.chat")
        notifier.onPackageLeft("com.example.chat")
        assertFalse(notifier.onAppSeen("com.example.chat", store.get("com.example.chat")!!.rule))
        assertEquals(0, nm.size())

        store.clearMemory()
        db.rules().delete("com.example.chat")
        store.decline("com.example.chat")
        notifier.onPackageLeft("com.example.chat")
        assertFalse(notifier.onAppSeen("com.example.chat", store.get("com.example.chat")!!.rule))

        db.rules().delete("com.example.chat")
        store.clearMemory()
        PromptPrefs.snooze(context(), "com.example.chat", now + PromptPrefs.SNOOZE_MS)
        notifier.onPackageLeft("com.example.chat")
        assertFalse(notifier.onAppSeen("com.example.chat", AppRule("com.example.chat")))
        assertEquals(0, nm.size())

        // After snooze expires, prompts again.
        now += PromptPrefs.SNOOZE_MS + 1
        notifier.onPackageLeft("com.example.chat")
        assertTrue(notifier.onAppSeen("com.example.chat", AppRule("com.example.chat")))
    }

    @Config(sdk = [29])
    @Test fun globalPromptsOffSkips() {
        installLauncher("com.example.chat")
        PromptPrefs.setPromptsEnabled(context(), false)
        val store = RuleStore(db.rules(), direct, direct, crops)
        val notifier = NewAppNotifier(context(), store, { day }, SystemAppPolicy(context()), { now })
        assertFalse(notifier.onAppSeen("com.example.chat", AppRule("com.example.chat")))
        assertEquals(0, shadowOf(context().getSystemService(NotificationManager::class.java)).size())
    }

    @Config(sdk = [33])
    @Test fun deniedNotificationsShowBannerFallback() {
        installLauncher("com.example.chat")
        val store = RuleStore(db.rules(), direct, direct, crops)
        val banner = FakePromptBanner()
        val notifier = NewAppNotifier(
            context(), store, { day }, SystemAppPolicy(context()), { now }, banner,
        )
        assertTrue(notifier.onAppSeen("com.example.chat", AppRule("com.example.chat")))
        assertEquals(NewAppNotifier.Result.BANNER, notifier.lastResult)
        assertEquals("com.example.chat", banner.last!!.pkg)
        assertEquals(0, shadowOf(context().getSystemService(NotificationManager::class.java)).size())
    }

    @Config(sdk = [29])
    @Test fun windowStateEventTriggersPromptViaService() {
        installLauncher("com.example.chat")
        val store = RuleStore(db.rules(), direct, direct, crops)
        val policy = SystemAppPolicy(context())
        val banner = FakePromptBanner()
        val notifier = NewAppNotifier(context(), store, { day }, policy, { now }, banner)
        val service = Robolectric.setupService(EnterSendService::class.java)
        service.attach(FakeDevice(), store, FakeOverlay(), { day }, notifier, policy, banner)

        fun fire() {
            service.onAccessibilityEvent(
                AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                    packageName = "com.example.chat"
                },
            )
            shadowOf(Looper.getMainLooper()).idle()
        }

        fire()
        assertEquals(1, shadowOf(context().getSystemService(NotificationManager::class.java)).size())

        // Leave to launcher package then return.
        service.onAccessibilityEvent(
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                packageName = "com.android.launcher3"
            },
        )
        context().getSystemService(NotificationManager::class.java).cancelAll()
        fire()
        assertEquals(1, shadowOf(context().getSystemService(NotificationManager::class.java)).size())
    }

    @Config(sdk = [29])
    @Test fun popupActionsPersistLiveState() {
        installLauncher("com.example.chat")
        val store = RuleStore(db.rules(), direct, direct, crops)
        val service = Robolectric.setupService(EnterSendService::class.java)
        service.attach(FakeDevice(), store, FakeOverlay())

        // Snooze via notification action.
        NotifyActionReceiver().onReceive(
            context(),
            Intent(NotifyActionReceiver.ACTION_LATER).putExtra(NotifyActionReceiver.EXTRA_PACKAGE, "com.example.chat"),
        )
        assertTrue(PromptPrefs.isSnoozed(context(), "com.example.chat", System.currentTimeMillis()))
        PromptPrefs.clearSnooze(context(), "com.example.chat")
        assertFalse(PromptPrefs.isSnoozed(context(), "com.example.chat", System.currentTimeMillis()))

        // Don't-ask / ask-again (same RuleStore paths PromptActivity + Apps use).
        store.decline("com.example.chat")
        assertTrue(store.get("com.example.chat")!!.rule.teachDeclined)
        assertFalse(
            NewAppNotifier(context(), store, { day }, SystemAppPolicy(context()), { now })
                .shouldPrompt("com.example.chat", store.get("com.example.chat")!!.rule),
        )
        store.clearDeclined("com.example.chat")
        assertFalse(store.get("com.example.chat")!!.rule.teachDeclined)
        assertTrue(
            NewAppNotifier(context(), store, { day }, SystemAppPolicy(context()), { now })
                .shouldPrompt("com.example.chat", store.get("com.example.chat")!!.rule),
        )

        // Pass-through (Enable with default Enter).
        store.putLocal(AppRule("com.example.chat", mode = Mode.PASS_THROUGH.name))
        assertEquals(Mode.PASS_THROUGH.name, store.get("com.example.chat")!!.rule.mode)
        assertFalse(
            NewAppNotifier(context(), store, { day }, SystemAppPolicy(context()), { now })
                .shouldPrompt("com.example.chat", store.get("com.example.chat")!!.rule),
        )

        // Off via notification action (uses EnterSendService.instance.store when present).
        store.putLocal(AppRule("com.example.chat"))
        service.store!!.setOff("com.example.chat")
        assertEquals(Mode.OFF.name, service.store!!.get("com.example.chat")!!.rule.mode)

        // PromptActivity exposes all keyboard-reachable actions.
        val a = Robolectric.buildActivity(
            PromptActivity::class.java,
            Intent(context(), PromptActivity::class.java)
                .putExtra(NotifyActionReceiver.EXTRA_PACKAGE, "com.example.chat"),
        ).setup().get()
        for (id in listOf(
            com.meowgi.entersend.R.id.prompt_teach,
            com.meowgi.entersend.R.id.prompt_later,
            com.meowgi.entersend.R.id.prompt_passthrough,
            com.meowgi.entersend.R.id.prompt_off,
            com.meowgi.entersend.R.id.prompt_dont_ask,
        )) {
            val v = a.findViewById<android.view.View>(id)
            assertNotNull(v)
            assertTrue(a.resources.getResourceEntryName(id), v.isClickable)
        }
    }

    @Config(sdk = [29])
    @Test fun channelIsHighImportance() {
        installLauncher("com.example.chat")
        val store = RuleStore(db.rules(), direct, direct, crops)
        NewAppNotifier(context(), store, { day }).post("com.example.chat")
        val ch = context().getSystemService(NotificationManager::class.java)
            .getNotificationChannel(NewAppNotifier.CHANNEL)
        assertNotNull(ch)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch!!.importance)
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
