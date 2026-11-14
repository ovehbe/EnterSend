package com.meowgi.entersend.diag

import android.content.Context
import android.os.Build
import com.meowgi.entersend.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rolling diagnostic log (0.3.17). Two files under filesDir/diag/, ~2 MB each (~4 MB total).
 * All writes happen on a dedicated daemon thread; [i]/[w]/[e] never throw or block the caller
 * beyond a brief queue offer.
 */
object DiagLog {
    const val DIR = "diag"
    const val CURRENT = "diag.log"
    const val PREVIOUS = "diag.log.1"
    const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    private const val QUEUE_CAP = 512
    private const val RECENT_RING = 200

    private val ready = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<String>(QUEUE_CAP)
    private val recent = ArrayDeque<String>(RECENT_RING + 1)
    private val recentLock = Any()
    private var dir: File? = null
    private var writer: Thread? = null

    @Volatile private var dropCount = 0

    fun init(context: Context) {
        if (ready.getAndSet(true)) return
        val d = File(context.applicationContext.filesDir, DIR).also { it.mkdirs() }
        dir = d
        writer = Thread({ drainLoop() }, "EnterSend-DiagLog").apply {
            isDaemon = true
            start()
        }
        i("diag", "init version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
            "sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER}/${Build.MODEL}")
    }

    fun i(tag: String, msg: String) = enqueue("I", tag, msg)
    fun w(tag: String, msg: String) = enqueue("W", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val body = if (t == null) msg else "$msg\n${t.stackTraceToString()}"
        enqueue("E", tag, body)
    }

    /** Last [n] lines still in memory (also mirrored into the rotating files). */
    fun recentLines(n: Int = RECENT_RING): List<String> = synchronized(recentLock) {
        recent.toList().takeLast(n.coerceAtLeast(0))
    }

    fun clear(context: Context) {
        queue.clear()
        synchronized(recentLock) { recent.clear() }
        val d = File(context.applicationContext.filesDir, DIR)
        try {
            File(d, CURRENT).writeText("")
            File(d, PREVIOUS).delete()
        } catch (_: Exception) {
        }
        dropCount = 0
        i("diag", "cleared")
    }

    fun files(context: Context): List<File> {
        val d = File(context.applicationContext.filesDir, DIR)
        return listOf(File(d, PREVIOUS), File(d, CURRENT)).filter { it.exists() && it.length() > 0 }
    }

    fun crashDir(context: Context): File =
        File(context.applicationContext.filesDir, DIR).also { it.mkdirs() }

    fun resetForTests(context: Context) {
        ready.set(false)
        queue.clear()
        synchronized(recentLock) { recent.clear() }
        writer = null
        dir = null
        dropCount = 0
        val d = File(context.applicationContext.filesDir, DIR)
        d.listFiles()?.forEach { it.delete() }
        d.delete()
        // Re-init so subsequent test code can log.
        init(context)
    }

    private fun enqueue(level: String, tag: String, msg: String) {
        try {
            if (!ready.get()) return
            val line = "${stamp()} $level/$tag ${msg.replace('\n', '⏎')}"
            synchronized(recentLock) {
                recent.addLast(line)
                while (recent.size > RECENT_RING) recent.removeFirst()
            }
            if (!queue.offer(line)) {
                dropCount++
            }
        } catch (_: Exception) {
        }
    }

    private fun drainLoop() {
        while (true) {
            try {
                val first = queue.poll(1, TimeUnit.SECONDS) ?: continue
                val batch = ArrayList<String>(32)
                batch.add(first)
                queue.drainTo(batch, 31)
                appendBatch(batch)
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun appendBatch(lines: List<String>) {
        val d = dir ?: return
        try {
            d.mkdirs()
            val cur = File(d, CURRENT)
            RandomAccessFile(cur, "rw").use { raf ->
                raf.seek(raf.length())
                for (line in lines) {
                    raf.writeBytes(line)
                    raf.writeBytes("\n")
                }
            }
            if (cur.length() >= MAX_FILE_BYTES) rotate(d, cur)
        } catch (_: Exception) {
        }
    }

    private fun rotate(d: File, cur: File) {
        try {
            val prev = File(d, PREVIOUS)
            if (prev.exists()) prev.delete()
            if (!cur.renameTo(prev)) {
                prev.writeBytes(cur.readBytes())
                cur.writeText("")
            } else {
                File(d, CURRENT).writeText("")
            }
        } catch (_: Exception) {
        }
    }

    /** Force pending queue to disk (tests). Drains synchronously so tests need not race the writer. */
    fun flushForTests(timeoutMs: Long = 2000) {
        val batch = ArrayList<String>(QUEUE_CAP)
        queue.drainTo(batch)
        if (batch.isNotEmpty()) appendBatch(batch)
        // Also wait briefly in case the writer grabbed items first.
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtMost(500)
        while (queue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            queue.drainTo(batch)
            if (batch.isNotEmpty()) {
                appendBatch(batch)
                batch.clear()
            }
        }
    }

    fun stamp(timeMs: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return fmt.format(Date(timeMs))
    }

    fun deviceHeader(): String = buildString {
        appendLine("EnterSend ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("fingerprint=${Build.FINGERPRINT}")
    }
}
