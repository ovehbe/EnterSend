package com.meowgi.entersend.data

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Ring buffer of detailed hardware-Enter traces for field debugging (0.3.10).
 * Settings → Share Enter log / Clear Enter log. Send behavior is unchanged.
 */
class EnterDiagLog private constructor(private val file: File) {
    private val lock = Any()
    private val entries = ArrayDeque<String>(MAX + 1)

    init {
        load()
    }

    fun record(block: String) {
        synchronized(lock) {
            entries.addLast(block)
            while (entries.size > MAX) entries.removeFirst()
            saveLocked()
        }
        // Mirror a short summary into rolling DiagLog (log-only).
        try {
            val summary = block.lineSequence().firstOrNull().orEmpty().take(240)
            com.meowgi.entersend.diag.DiagLog.i("enter", summary)
        } catch (_: Exception) {
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) {
        entries.clear()
        saveLocked()
    }

    fun formatText(): String {
        val snap = snapshot()
        if (snap.isEmpty()) return "(no Enter events yet — press Enter in a chat field with EnterSend on)"
        return snap.joinToString("\n\n")
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                entries.addLast(arr.getString(i))
            }
            while (entries.size > MAX) entries.removeFirst()
        } catch (_: Exception) {
            entries.clear()
        }
    }

    private fun saveLocked() {
        val arr = JSONArray()
        for (e in entries) arr.put(e)
        file.parentFile?.mkdirs()
        file.writeText(arr.toString())
    }

    companion object {
        const val MAX = 300
        private const val FILE = "enter_diag.json"

        @Volatile private var instance: EnterDiagLog? = null

        fun get(context: Context): EnterDiagLog {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: EnterDiagLog(File(context.applicationContext.filesDir, FILE)).also {
                    instance = it
                }
            }
        }

        fun resetForTests(context: Context) {
            synchronized(this) {
                instance = null
                File(context.applicationContext.filesDir, FILE).delete()
            }
        }

        fun stamp(timeMs: Long = System.currentTimeMillis()): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            return fmt.format(Date(timeMs))
        }
    }
}
