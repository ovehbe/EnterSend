package com.meowgi.entersend.diag

import android.content.Context
import android.os.Build
import com.meowgi.entersend.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Chains [Thread.setDefaultUncaughtExceptionHandler] and writes a crash file under filesDir/diag/.
 * Never replaces behavior beyond logging; always forwards to the previous handler.
 */
object CrashHandler {
    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val app = context.applicationContext
        DiagLog.init(app)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashFile(app, thread, throwable)
            } catch (_: Exception) {
            }
            try {
                previous?.uncaughtException(thread, throwable)
            } catch (_: Exception) {
            }
        }
        DiagLog.i("crash", "handler installed (chained=${previous != null})")
    }

    /** Test helper: allow re-install after reset. */
    fun resetForTests() {
        installed.set(false)
    }

    fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable): File {
        val dir = DiagLog.crashDir(context)
        val name = "crash-${fileStamp()}.txt"
        val file = File(dir, name)
        val body = buildString {
            appendLine("=== EnterSend crash ===")
            appendLine("time=${fullStamp()}")
            appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("sdk=${Build.VERSION.SDK_INT} ${Build.VERSION.RELEASE}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("thread=${thread.name} id=${thread.id} state=${thread.state}")
            appendLine()
            appendLine(throwable.stackTraceToString())
            appendLine()
            appendLine("=== last DiagLog lines ===")
            for (line in DiagLog.recentLines(80)) appendLine(line)
        }
        file.writeText(body)
        DiagLog.e("crash", "uncaught on ${thread.name}: ${throwable.javaClass.simpleName}: ${throwable.message}")
        return file
    }

    fun listCrashFiles(context: Context): List<File> {
        val dir = File(context.applicationContext.filesDir, DiagLog.DIR)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    private fun fullStamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
}
