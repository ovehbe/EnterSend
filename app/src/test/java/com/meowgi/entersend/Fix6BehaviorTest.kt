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
import com.meowgi.entersend.data.ConfigJson
import com.meowgi.entersend.data.Mode
import com.meowgi.entersend.data.ProfileStore
import com.meowgi.entersend.service.EnterSendService
import com.meowgi.entersend.service.NewAppNotifier
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class Fix6BehaviorTest {
    private lateinit var db: com.meowgi.entersend.data.AppDb
    private val crops = FakeCrops()
    private var day = 20_000L

    @Before fun setUp() {
        db = memoryDb()
        day = 20_000L
        ProfileStore(context()).let { /* clear prefs between tests via clear */ }
        context().getSharedPreferences(ProfileStore.PREFS, 0).edit().clear().apply()
    }

    @After fun tearDown() {
        db.close()
        context().getSharedPreferences(ProfileStore.PREFS, 0).edit().clear().apply()
        FileCrops.cleanup()
    }

    // --- 1. New-app prompt on simulated WINDOW_STATE_CHANGED ---

    @Config(sdk = [29])
    @Test fun windowStatePromptsOnceForUnconfiguredLauncherUserApp() {
        installLauncher("com.example.chat", system = false)
        val store = RuleStore(db.rules(), direct, direct, crops)
        val policy = SystemAppPolicy(context())
        val notifier = NewAppNotifier(context(), store, { day }, policy)
        store.setOnLoaded { pkg, state -> notifier.onAppSeen(pkg, state.rule) }

        val service = Robolectric.setupService(EnterSendService::class.java)
        service.attach(FakeDevice(), store, FakeOverlay(), { day }, notifier, policy)
        store.setOnLoaded { pkg, state -> notifier.onAppSeen(pkg, state.rule) }

        val nm = context().getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(nm)
        assertEquals(0, shadow.size())

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.example.chat"
        }
        service.onAccessibilityEvent(event)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, shadow.size())
        assertEquals(day, store.get("com.example.chat")!!.rule.lastAskedDay)

        // Same day — no second prompt.
        nm.cancelAll()
        service.onAccessibilityEvent(event)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, shadow.size())
    }

    @Config(sdk = [29])
    @Test fun systemAppNeverPromptedOnWindowState() {
        installLauncher("com.google.android.apps.messaging", system = true)
        val store = RuleStore(db.rules(), direct, direct, crops)
        val policy = SystemAppPolicy(context())
        val notifier = NewAppNotifier(context(), store, { day }, policy)
        val service = Robolectric.setupService(EnterSendService::class.java)
        service.attach(FakeDevice(), store, FakeOverlay(), { day }, notifier, policy)

        service.onAccessibilityEvent(
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                packageName = "com.google.android.apps.messaging"
            },
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, shadowOf(context().getSystemService(NotificationManager::class.java)).size())
    }

    @Config(sdk = [33])
    @Test fun missingNotificationPermissionDoesNotBurnAskDay() {
        installLauncher("com.example.chat", system = false)
        val store = RuleStore(db.rules(), direct, direct, crops)
        val notifier = NewAppNotifier(context(), store, { day }, SystemAppPolicy(context()))
        // SDK 33 without granted POST_NOTIFICATIONS → canPost false
        assertFalse(notifier.onAppSeen("com.example.chat", AppRule("com.example.chat")))
        assertNull(store.get("com.example.chat")?.rule?.lastAskedDay)
        assertNull(db.rules().get("com.example.chat")?.lastAskedDay)
    }

    // --- 2. Live config: Settings write visible to service without restart ---

    @Test fun putLocalAppliesModeWithoutRestart() {
        val store = RuleStore(db.rules(), direct, direct, crops)
        store.putLocal(AppRule("com.whatsapp", mode = Mode.AUTO.name))
        assertEquals(Mode.AUTO.name, store.get("com.whatsapp")!!.rule.mode)

        // Simulate Settings toggling Off while a refresh is "in flight" (generation wins).
        db.rules().upsert(AppRule("com.whatsapp", mode = Mode.OFF.name))
        store.putLocal(AppRule("com.whatsapp", mode = Mode.OFF.name))
        assertEquals(Mode.OFF.name, store.get("com.whatsapp")!!.rule.mode)

        // Concurrent refresh of stale AUTO must not clobber OFF.
        store.refresh("com.whatsapp")
        assertEquals(Mode.OFF.name, store.get("com.whatsapp")!!.rule.mode)
    }

    @Test fun refreshWhileLoadingAppliesLatestDbRow() {
        val store = RuleStore(db.rules(), direct, direct, crops)
        db.rules().upsert(AppRule("com.whatsapp", mode = Mode.AUTO.name))
        store.refresh("com.whatsapp")
        db.rules().upsert(AppRule("com.whatsapp", mode = Mode.OFF.name))
        store.refresh("com.whatsapp") // second refresh must not be skipped
        assertEquals(Mode.OFF.name, store.get("com.whatsapp")!!.rule.mode)
    }

    // --- 3. Export → import round-trip ---

    @Test fun exportImportRoundTripEquality() {
        val pixels = solid(0xFF112233.toInt(), 16, 12)
        val png = ProfileStore.pixelsToPng(pixels)
        val rule = AppRule(
            packageName = "com.whatsapp",
            mode = Mode.LEARNED.name,
            tapDxDp = 12f,
            tapDyDp = -4f,
            cropPath = null,
            cropLeft = 600,
            cropTop = 600,
            cropRight = 680,
            cropBottom = 680,
            orientation = 1,
            windowWidth = 720,
            windowHeight = 1280,
            sendDesc = "Send",
        )
        val bundle = ConfigJson.Bundle(
            ConfigJson.SCHEMA_VERSION,
            "default",
            listOf(
                ConfigJson.ProfileSnapshot(
                    "default",
                    "Default",
                    listOf(ConfigJson.RuleSnapshot(rule, png)),
                ),
            ),
        )

        val bytes = ByteArrayOutputStream().also { ConfigJson.write(bundle, it) }.toByteArray()
        val read = ConfigJson.read(ByteArrayInputStream(bytes))
        assertEquals(bundle.schemaVersion, read.schemaVersion)
        assertEquals(bundle.activeProfileId, read.activeProfileId)
        assertEquals(1, read.profiles.size)
        val exported = bundle.profiles[0].rules[0]
        val imported = read.profiles[0].rules[0]
        assertEquals(exported.rule, imported.rule)
        assertNotNull(imported.cropPng)
        assertTrue(png.contentEquals(imported.cropPng))

        // Import into Room + disk crops; service store must see the restored tap spot.
        val diskCrops = com.meowgi.entersend.data.CropFiles(context().filesDir)
        val diskStore = RuleStore(db.rules(), direct, direct, diskCrops)
        val profiles = ProfileStore(context())
        profiles.importBundle(read, db.rules(), diskStore)
        val restored = db.rules().get("com.whatsapp")
        assertNotNull(restored)
        assertEquals(Mode.LEARNED.name, restored!!.mode)
        assertEquals(12f, restored.tapDxDp)
        assertNotNull(restored.cropPath)
        assertTrue(java.io.File(restored.cropPath!!).exists())
        assertEquals("Send", restored.sendDesc)
        assertEquals(Mode.LEARNED.name, diskStore.get("com.whatsapp")!!.rule.mode)

        // Re-export from ProfileStore (file still present) and compare rule fields + crop bytes.
        profiles.saveActive(db.rules())
        val again = profiles.exportBundle(db.rules(), allProfiles = false)
        val round = again.profiles[0].rules[0]
        assertEquals(12f, round.rule.tapDxDp)
        assertNotNull(round.cropPng)
        assertEquals(png.size, round.cropPng!!.size)
    }

    @Test fun importRejectsBadSchema() {
        val bad = """{"schemaVersion":99,"activeProfileId":"x","profiles":[{"id":"x","name":"X","rules":[]}]}"""
        try {
            ConfigJson.read(ByteArrayInputStream(bad.toByteArray()))
            throw AssertionError("expected FormatException")
        } catch (e: ConfigJson.FormatException) {
            assertTrue(e.message!!.contains("schemaVersion"))
        }
    }

    // --- 4. Profile switch changes active tap spot ---

    @Test fun profileSwitchChangesActiveTapSpot() {
        val diskCrops = com.meowgi.entersend.data.CropFiles(context().filesDir)
        val profiles = ProfileStore(context())
        val cropA = solid(0xFFAA0000.toInt(), 8, 8)
        val cropB = solid(0xFF00BB00.toInt(), 8, 8)
        val pathA = diskCrops.save("com.whatsapp", cropA)

        db.rules().upsert(
            AppRule(
                packageName = "com.whatsapp",
                mode = Mode.LEARNED.name,
                tapDxDp = 10f,
                tapDyDp = 0f,
                cropPath = pathA,
                cropLeft = 1,
                cropTop = 1,
                cropRight = 9,
                cropBottom = 9,
                orientation = 1,
                windowWidth = 720,
                windowHeight = 1280,
            ),
        )
        profiles.ensureDefault(db.rules())
        profiles.saveActive(db.rules())
        val tall = profiles.saveCurrentAs("Tall keyboard", db.rules())

        // Mutate working set to a different tap spot and save as second profile.
        db.rules().upsert(
            AppRule(
                packageName = "com.whatsapp",
                mode = Mode.LEARNED.name,
                tapDxDp = 40f,
                tapDyDp = -20f,
                cropPath = diskCrops.save("com.whatsapp", cropB),
                cropLeft = 2,
                cropTop = 2,
                cropRight = 10,
                cropBottom = 10,
                orientation = 1,
                windowWidth = 720,
                windowHeight = 1280,
            ),
        )
        val short = profiles.saveCurrentAs("Short keyboard", db.rules())
        val store = RuleStore(db.rules(), direct, direct, diskCrops)
        store.putLocal(db.rules().get("com.whatsapp")!!)
        assertEquals(40f, store.get("com.whatsapp")!!.rule.tapDxDp)

        profiles.switchTo(tall.id, db.rules(), store)
        assertEquals(tall.id, profiles.activeId())
        assertEquals(10f, store.get("com.whatsapp")!!.rule.tapDxDp)
        assertEquals(10f, db.rules().get("com.whatsapp")!!.tapDxDp)

        profiles.switchTo(short.id, db.rules(), store)
        assertEquals(40f, store.get("com.whatsapp")!!.rule.tapDxDp)
    }

    private fun installLauncher(pkg: String, system: Boolean) {
        val ai = ApplicationInfo().apply {
            packageName = pkg
            flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
        }
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

    /** Helper so tearDown can wipe leftover crop dirs from ProfileStore imports. */
    private object FileCrops {
        fun cleanup() {
            com.meowgi.entersend.data.CropFiles.dir(context().filesDir).listFiles()?.forEach { it.delete() }
            java.io.File(context().filesDir, "profiles").listFiles()?.forEach { it.delete() }
        }
    }
}
