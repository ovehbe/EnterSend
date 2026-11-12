package com.meowgi.entersend.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.meowgi.entersend.R
import com.meowgi.entersend.ServiceWatch

/**
 * First run: 1) turn on the service, 2) press Enter and Shift+Enter, 3) a test chat box whose
 * Send button EnterSend should press on Enter.
 */
class SetupActivity : Activity() {
    private var enterSeen = false
    private var shiftEnterSeen = false
    internal var sent = false
        private set
    internal var newline = false
        private set

    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        input = findViewById(R.id.message_input)

        // Ask for teach-prompt notifications during first-run setup (Android 13+).
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        findViewById<View>(R.id.step1_button).setOnClickListener { ServiceWatch.openAccessibilitySettings(this) }

        findViewById<View>(R.id.key_box).apply {
            setOnClickListener { requestFocus() }
            setOnKeyListener { _, code, e ->
                if (code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_NUMPAD_ENTER) return@setOnKeyListener false
                if (e.action == KeyEvent.ACTION_DOWN) {
                    if (e.isShiftPressed) shiftEnterSeen = true else enterSeen = true
                    refresh()
                }
                true
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) {
                if ('\n' in s) {
                    newline = true
                    refresh()
                }
            }
        })

        findViewById<View>(R.id.send_button).setOnClickListener {
            val text = input.text.toString()
            if (text.isBlank()) return@setOnClickListener
            findViewById<TextView>(R.id.sent_log).apply {
                visibility = View.VISIBLE
                append(if (length() == 0) text else "\n—\n$text")
            }
            input.setText("")
            sent = true
            refresh()
        }

        findViewById<View>(R.id.done).setOnClickListener {
            MainActivity.prefs(this).edit().putBoolean(MainActivity.KEY_SETUP_DONE, true).apply()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        mark(R.id.step1_check, ServiceWatch.isRunning())
        mark(R.id.step2_check, enterSeen && shiftEnterSeen)
        findViewById<TextView>(R.id.key_box).text = when {
            enterSeen || shiftEnterSeen -> getString(
                R.string.step2_status,
                getString(if (enterSeen) R.string.check_yes else R.string.check_no),
                getString(if (shiftEnterSeen) R.string.check_yes else R.string.check_no),
            )
            else -> getString(R.string.press_a_key)
        }
        mark(R.id.step3_check, sent && newline)
        findViewById<TextView>(R.id.step3_status).text = getString(
            R.string.step3_status,
            getString(if (sent) R.string.check_yes else R.string.check_no),
            getString(if (newline) R.string.check_yes else R.string.check_no),
        )
    }

    private fun mark(id: Int, done: Boolean) {
        findViewById<TextView>(id).apply {
            setText(if (done) R.string.check_done else R.string.check_pending)
            setTextColor(getColor(if (done) R.color.health_good else R.color.health_none))
        }
    }
}
