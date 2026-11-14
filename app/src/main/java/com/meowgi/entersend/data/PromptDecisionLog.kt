package com.meowgi.entersend.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Ring buffer of recent new-app prompt decisions for Settings → Diagnostics.
 * Persisted so Ömer can Copy/Share after reproducing a miss.
 */
class PromptDecisionLog private constructor(private val file: File) {
    data class Entry(
        val timeMs: Long,
        val pkg: String,
        val source: String,
        val decision: String,
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>(MAX + 1)

    init {
        load()
    }

    fun record(pkg: String, source: String, decision: String, timeMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            entries.addLast(Entry(timeMs, pkg, source, decision))
            while (entries.size > MAX) entries.removeFirst()
            saveLocked()
        }
        // Mirror into rolling DiagLog (log-only; never affects prompt behavior).
        try {
            com.meowgi.entersend.diag.DiagLog.i("prompt", "$pkg [$source] $decision")
        } catch (_: Exception) {
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) {
        entries.clear()
        saveLocked()
    }

    fun formatText(): String {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        return snapshot().joinToString("\n") { e ->
            "${fmt.format(Date(e.timeMs))}  ${e.pkg}  [${e.source}]  ${e.decision}"
        }.ifEmpty { "(no decisions yet)" }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.addLast(
                    Entry(
                        timeMs = o.getLong("t"),
                        pkg = o.getString("pkg"),
                        source = o.getString("src"),
                        decision = o.getString("dec"),
                    ),
                )
            }
            while (entries.size > MAX) entries.removeFirst()
        } catch (_: Exception) {
            entries.clear()
        }
    }

    private fun saveLocked() {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("t", e.timeMs)
                    .put("pkg", e.pkg)
                    .put("src", e.source)
                    .put("dec", e.decision),
            )
        }
        file.parentFile?.mkdirs()
        file.writeText(arr.toString())
    }

    companion object {
        const val MAX = 100
        private const val FILE = "prompt_decisions.json"

        @Volatile private var instance: PromptDecisionLog? = null

        fun get(context: Context): PromptDecisionLog {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: PromptDecisionLog(File(context.applicationContext.filesDir, FILE)).also {
                    instance = it
                }
            }
        }

        /** Test helper. */
        fun resetForTests(context: Context) {
            synchronized(this) {
                instance = null
                File(context.applicationContext.filesDir, FILE).delete()
            }
        }
    }
}
