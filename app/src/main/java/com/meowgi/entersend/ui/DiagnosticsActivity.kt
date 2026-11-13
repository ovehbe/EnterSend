package com.meowgi.entersend.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.meowgi.entersend.BuildConfig
import com.meowgi.entersend.R
import com.meowgi.entersend.data.EnterDiagLog
import com.meowgi.entersend.data.PromptDecisionLog
import com.meowgi.entersend.service.NewAppNotifier

/** Settings → Diagnostics: prompt decisions + Enter press log. */
class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var logView: TextView
    private lateinit var enterLogView: TextView
    private val log get() = PromptDecisionLog.get(this)
    private val enterLog get() = EnterDiagLog.get(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        logView = findViewById(R.id.diag_log)
        enterLogView = findViewById(R.id.enter_diag_log)
        findViewById<android.view.View>(R.id.diag_copy).setOnClickListener { copy() }
        findViewById<android.view.View>(R.id.diag_share).setOnClickListener { share() }
        findViewById<android.view.View>(R.id.diag_clear).setOnClickListener {
            log.clear()
            refresh()
        }
        findViewById<android.view.View>(R.id.enter_log_share).setOnClickListener { shareEnter() }
        findViewById<android.view.View>(R.id.enter_log_clear).setOnClickListener {
            enterLog.clear()
            Toast.makeText(this, R.string.enter_log_cleared, Toast.LENGTH_SHORT).show()
            refresh()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val header = buildString {
            append("EnterSend ").append(BuildConfig.VERSION_NAME)
            append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("channel=").append(NewAppNotifier.CHANNEL)
            append(" legacy=").append(NewAppNotifier.CHANNEL_LEGACY).append("\n\n")
        }
        logView.text = header + log.formatText()
        enterLogView.text = enterLog.formatText()
    }

    private fun copy() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("EnterSend prompt log", logView.text))
        Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "EnterSend prompt log")
                    .putExtra(Intent.EXTRA_TEXT, logView.text.toString()),
                getString(R.string.diagnostics_share),
            ),
        )
    }

    private fun shareEnter() {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.enter_log_subject))
                    .putExtra(Intent.EXTRA_TEXT, enterLog.formatText()),
                getString(R.string.enter_log_share),
            ),
        )
    }
}
