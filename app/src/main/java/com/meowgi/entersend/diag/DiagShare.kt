package com.meowgi.entersend.diag

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.meowgi.entersend.R
import com.meowgi.entersend.data.EnterDiagLog
import com.meowgi.entersend.data.PromptDecisionLog
import java.io.File

/** Builds a single shareable diagnostic bundle and launches ACTION_SEND via FileProvider. */
object DiagShare {
    const val AUTHORITY_SUFFIX = ".diagfileprovider"

    fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

    fun buildBundle(context: Context): File {
        DiagLog.flushForTests(500)
        val dir = File(context.cacheDir, DiagLog.DIR).also { it.mkdirs() }
        val out = File(dir, "entersend-diag.txt")
        out.writeText(compose(context))
        return out
    }

    fun compose(context: Context): String = buildString {
        appendLine("=== build / device ===")
        append(DiagLog.deviceHeader())
        appendLine()
        appendLine("=== historical process exits ===")
        appendLine(ExitReasons.format(context))
        appendLine()
        appendLine("=== crash files ===")
        val crashes = CrashHandler.listCrashFiles(context)
        if (crashes.isEmpty()) {
            appendLine("(none)")
        } else {
            for (f in crashes) {
                appendLine("--- ${f.name} ---")
                appendLine(f.readText().take(20_000))
                appendLine()
            }
        }
        appendLine("=== rolling DiagLog ===")
        val logs = DiagLog.files(context)
        if (logs.isEmpty()) {
            appendLine("(empty)")
        } else {
            for (f in logs) {
                appendLine("--- ${f.name} (${f.length()} bytes) ---")
                appendLine(f.readText())
            }
        }
        appendLine()
        appendLine("=== PromptDecisionLog ===")
        appendLine(PromptDecisionLog.get(context).formatText())
        appendLine()
        appendLine("=== EnterDiagLog ===")
        appendLine(EnterDiagLog.get(context).formatText())
    }

    fun share(context: Context) {
        try {
            val file = buildBundle(context)
            val uri: Uri = FileProvider.getUriForFile(context, authority(context), file)
            val intent = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diag_logs_subject))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.diag_logs_subject))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.share_diag_logs)),
            )
            DiagLog.i("share", "shared bundle bytes=${file.length()}")
        } catch (t: Exception) {
            DiagLog.e("share", "share failed", t)
            throw t
        }
    }

    fun clearAll(context: Context) {
        CrashHandler.listCrashFiles(context).forEach { it.delete() }
        DiagLog.clear(context)
        // Keep EnterDiagLog / PromptDecisionLog as their own Clear buttons; only diag files here.
    }
}
