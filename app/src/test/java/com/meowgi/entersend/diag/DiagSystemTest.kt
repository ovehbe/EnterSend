package com.meowgi.entersend.diag

import android.os.Looper
import com.meowgi.entersend.BuildConfig
import com.meowgi.entersend.context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class DiagSystemTest {
    private val app get() = context()

    @Before fun setUp() {
        CrashHandler.resetForTests()
        AnrWatchdog.resetForTests()
        DiagLog.resetForTests(app)
    }

    @After fun tearDown() {
        AnrWatchdog.resetForTests()
        CrashHandler.resetForTests()
        DiagLog.resetForTests(app)
    }

    @Test fun crashHandlerWritesFileAndChains() {
        val chained = AtomicBoolean(false)
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> chained.set(true) }
        CrashHandler.install(app)
        val boom = RuntimeException("diag-test-boom")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
        assertTrue("previous handler must be chained", chained.get())
        val files = CrashHandler.listCrashFiles(app)
        assertTrue(files.isNotEmpty())
        val text = files.first().readText()
        assertTrue(text.contains("diag-test-boom"))
        assertTrue(text.contains(BuildConfig.VERSION_NAME))
        assertTrue(text.contains("=== last DiagLog lines ==="))
    }

    @Test fun watchdogDetectsSimulatedMainThreadStall() {
        ShadowLooper.pauseMainLooper()
        val dump = AtomicReference<String?>(null)
        var fakeNow = 1_000L
        val dog = AnrWatchdog(
            mainLooper = Looper.getMainLooper(),
            stallMs = 200,
            intervalMs = 50,
            sleeper = { ms -> fakeNow += ms },
            nowMs = { fakeNow },
            onStall = { dump.set(it) },
        )
        // Do not start background thread — call tick once with main looper paused.
        val stalled = dog.tick()
        assertTrue(stalled)
        val text = dump.get()
        assertTrue(text != null && text.contains("MAIN THREAD STALL"))
        assertTrue(text!!.contains("all threads"))
        assertTrue(text.contains("thread="))
        // Second tick while still stalled should not dump again (once per stall).
        assertFalse(dog.tick())
    }

    @Test fun rotationCapsTotalSize() {
        DiagLog.init(app)
        // Write enough to exceed one file and rotate into the previous slot.
        val chunk = "x".repeat(4_000)
        repeat(800) { i -> DiagLog.i("rot", "n=$i $chunk") }
        DiagLog.flushForTests(8_000)
        val files = DiagLog.files(app)
        assertTrue("expected rotated files, got $files", files.isNotEmpty())
        val total = files.sumOf { it.length() }
        // Two files × 2 MB + a little slack for the in-flight append before rotate.
        assertTrue("total=$total", total <= DiagLog.MAX_FILE_BYTES * 2 + 128_000)
        val cur = File(File(app.filesDir, DiagLog.DIR), DiagLog.CURRENT)
        assertTrue(cur.exists())
        assertTrue("cur=${cur.length()}", cur.length() <= DiagLog.MAX_FILE_BYTES + 128_000)
    }

    @Test fun shareBundleContainsExpectedParts() {
        DiagLog.init(app)
        DiagLog.i("sharetest", "hello-bundle")
        CrashHandler.writeCrashFile(app, Thread.currentThread(), RuntimeException("share-crash"))
        DiagLog.flushForTests(2_000)
        val text = DiagShare.compose(app)
        assertTrue(text.contains("=== build / device ==="))
        assertTrue(text.contains(BuildConfig.VERSION_NAME))
        assertTrue(text.contains("=== historical process exits ==="))
        assertTrue(text.contains("=== crash files ==="))
        assertTrue(text.contains("share-crash"))
        assertTrue(text.contains("=== rolling DiagLog ==="))
        assertTrue(text.contains("hello-bundle") || text.contains("sharetest"))
        assertTrue(text.contains("=== PromptDecisionLog ==="))
        assertTrue(text.contains("=== EnterDiagLog ==="))
        val file = DiagShare.buildBundle(app)
        assertTrue(file.exists() && file.length() > 100)
    }

    @Test fun versionIs0318() {
        assertEquals("0.3.18", BuildConfig.VERSION_NAME)
        assertEquals(25, BuildConfig.VERSION_CODE)
    }
}
