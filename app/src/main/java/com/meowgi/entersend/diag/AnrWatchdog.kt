package com.meowgi.entersend.diag

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight main-thread watchdog: posts to the main looper every [intervalMs]; if the
 * runnable is not handled within [stallMs], dumps stacks once per stall (log only).
 */
class AnrWatchdog(
    private val mainLooper: Looper = Looper.getMainLooper(),
    private val stallMs: Long = 4_000L,
    private val intervalMs: Long = 1_000L,
    private val sleeper: (Long) -> Unit = { ms ->
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    },
    /** Wall clock for stall timing — injectable so Robolectric tests need not advance SystemClock. */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onStall: (String) -> Unit = { dump -> DiagLog.w("anr", dump) },
) {
    private val running = AtomicBoolean(false)
    private val dumpedThisStall = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            DiagLog.i("anr", "watchdog started stallMs=$stallMs intervalMs=$intervalMs")
            while (running.get()) {
                try {
                    tick()
                } catch (_: Exception) {
                }
            }
        }, "EnterSend-AnrWatch").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    /** One poll cycle — public for tests. Returns true if a stall dump was emitted. */
    fun tick(): Boolean {
        val handled = AtomicBoolean(false)
        try {
            Handler(mainLooper).post { handled.set(true) }
        } catch (_: Exception) {
            return false
        }
        val start = nowMs()
        while (!handled.get() && nowMs() - start < stallMs) {
            sleeper(50)
            if (!running.get() && thread != null) break
        }
        var dumped = false
        if (!handled.get()) {
            if (dumpedThisStall.compareAndSet(false, true)) {
                onStall(buildDump())
                dumped = true
            }
        } else {
            dumpedThisStall.set(false)
        }
        val elapsed = nowMs() - start
        if (elapsed < intervalMs) sleeper(intervalMs - elapsed)
        return dumped
    }

    private fun buildDump(): String = buildString {
        appendLine("MAIN THREAD STALL >${stallMs}ms")
        appendLine("--- main ---")
        val main = mainLooper.thread
        appendLine("thread=${main.name} state=${main.state}")
        for (el in main.stackTrace) appendLine("  at $el")
        appendLine("--- all threads ---")
        try {
            for ((t, stack) in Thread.getAllStackTraces()) {
                appendLine("thread=${t.name} id=${t.id} state=${t.state}")
                for (el in stack.take(40)) appendLine("  at $el")
            }
        } catch (_: Exception) {
            appendLine("(getAllStackTraces failed)")
        }
    }

    companion object {
        @Volatile private var instance: AnrWatchdog? = null

        fun startDefault() {
            if (instance != null) return
            synchronized(this) {
                if (instance != null) return
                AnrWatchdog().also {
                    instance = it
                    it.start()
                }
            }
        }

        fun resetForTests() {
            instance?.stop()
            instance = null
        }
    }
}
