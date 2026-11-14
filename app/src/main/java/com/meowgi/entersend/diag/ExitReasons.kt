package com.meowgi.entersend.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Reads [ActivityManager.getHistoricalProcessExitReasons] (API 30+) and logs crash / ANR /
 * low-memory exits. Also formats a shareable text section.
 */
object ExitReasons {
    fun logAtStartup(context: Context, limit: Int = 8) {
        if (Build.VERSION.SDK_INT < 30) {
            DiagLog.i("exit", "historical exit reasons unavailable (sdk<30)")
            return
        }
        try {
            val text = format(context, limit)
            DiagLog.i("exit", "historical exits:\n$text")
        } catch (t: Exception) {
            DiagLog.e("exit", "failed to read exit reasons", t)
        }
    }

    fun format(context: Context, limit: Int = 8): String {
        if (Build.VERSION.SDK_INT < 30) return "(API 30+ only)"
        val am = context.getSystemService(ActivityManager::class.java) ?: return "(no ActivityManager)"
        val list = am.getHistoricalProcessExitReasons(context.packageName, 0, limit)
        if (list.isNullOrEmpty()) return "(no historical exits)"
        return buildString {
            for (info in list) {
                appendLine("--- exit ---")
                appendLine("timestamp=${info.timestamp}")
                appendLine("reason=${reasonName(info.reason)} (${info.reason})")
                appendLine("status=${info.status} importance=${info.importance}")
                appendLine("description=${info.description}")
                appendLine("process=${info.processName} pid=${info.pid}")
                if (info.reason == ApplicationExitInfo.REASON_ANR ||
                    info.reason == ApplicationExitInfo.REASON_CRASH ||
                    info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                ) {
                    val trace = readTrace(info)
                    if (trace != null) {
                        appendLine("trace:")
                        appendLine(trace.take(12_000))
                    }
                }
                appendLine()
            }
        }
    }

    private fun readTrace(info: ApplicationExitInfo): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val stream = info.traceInputStream ?: return null
            stream.use { input ->
                val out = ByteArrayOutputStream()
                input.copyTo(out)
                out.toString(Charset.defaultCharset().name())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        else -> "UNKNOWN"
    }
}
