package com.meowgi.entersend.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.R
import com.meowgi.entersend.ServiceWatch
import com.meowgi.entersend.data.Mode
import com.meowgi.entersend.service.EnterSendService
import java.io.File

/** Settings: per-app modes, health and teaching. */
class MainActivity : Activity() {
    private val app get() = application as EnterSendApp
    private lateinit var adapter: AppListAdapter
    private lateinit var search: EditText
    private lateinit var needsOnly: CheckBox
    private lateinit var empty: TextView
    private var rows: List<AppRow>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = prefs(this)
        if (!prefs.getBoolean(KEY_SETUP_DONE, false)) startActivity(Intent(this, SetupActivity::class.java))

        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.banner_fix).setOnClickListener { ServiceWatch.openAccessibilitySettings(this) }
        findViewById<View>(R.id.banner_notifications_fix).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        findViewById<View>(R.id.open_setup).setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        handleTeachIntent(intent)
        search = findViewById(R.id.search)
        needsOnly = findViewById(R.id.needs_teaching)
        empty = findViewById(R.id.empty)
        adapter = AppListAdapter(this)
        findViewById<ListView>(R.id.apps).apply {
            adapter = this@MainActivity.adapter
            emptyView = empty
            setOnItemClickListener { _, _, position, _ -> showActions(this@MainActivity.adapter.getItem(position)) }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        needsOnly.setOnCheckedChangeListener { _, _ -> applyFilter() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTeachIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val running = ServiceWatch.isRunning()
        findViewById<View>(R.id.banner).visibility = if (running) View.GONE else View.VISIBLE
        val notifDenied = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        findViewById<View>(R.id.banner_notifications).visibility = if (notifDenied) View.VISIBLE else View.GONE
        ServiceWatch.updateNotice(this, running)
        reload()
    }

    private fun handleTeachIntent(intent: Intent?) {
        val pkg = intent?.getStringExtra(com.meowgi.entersend.service.NotifyActionReceiver.EXTRA_PACKAGE) ?: return
        teach(pkg)
    }

    private fun reload() {
        app.io.execute {
            val loaded = AppRows.load(this, app.db.rules().all())
            runOnUiThread {
                rows = loaded
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val all = rows
        empty.setText(if (all == null) R.string.loading_apps else R.string.no_apps_match)
        adapter.submit(if (all == null) emptyList() else AppRows.filter(all, search.text, needsOnly.isChecked))
    }

    private fun showActions(row: AppRow) {
        if (row.blocked) {
            AlertDialog.Builder(this).setTitle(row.label).setMessage(R.string.blocked_app).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val actions = buildList {
            add(if (row.rule.isTaught) Action.RETEACH else Action.TEACH)
            add(Action.AUTO)
            if (row.rule.isTaught) add(Action.LEARNED)
            add(Action.PASS_THROUGH)
            add(Action.OFF)
            if (row.seen) add(Action.RESET)
        }
        val labels = actions.map { a ->
            val text = getString(a.label)
            if (a.mode == row.mode) getString(R.string.current_choice, text) else text
        }
        AlertDialog.Builder(this)
            .setTitle(row.label)
            .setItems(labels.toTypedArray()) { _, which -> run(actions[which], row) }
            .show()
    }

    private fun run(action: Action, row: AppRow) {
        when (action) {
            Action.TEACH, Action.RETEACH -> teach(row.pkg)
            Action.RESET -> write(row.pkg) {
                row.rule.cropPath?.let { File(it).delete() }
                app.db.rules().delete(row.pkg)
            }
            else -> write(row.pkg) {
                val dao = app.db.rules()
                dao.ensure(row.pkg)
                dao.upsert(dao.get(row.pkg)!!.copy(mode = action.mode!!.name))
            }
        }
    }

    private fun write(pkg: String, block: () -> Unit) {
        app.io.execute {
            block()
            runOnUiThread {
                EnterSendService.instance?.store?.refresh(pkg)
                reload()
            }
        }
    }

    private fun teach(pkg: String) {
        val service = EnterSendService.instance
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (service == null || service.teach == null) {
            Toast.makeText(this, R.string.turn_on_first, Toast.LENGTH_LONG).show()
            findViewById<View>(R.id.banner).visibility = View.VISIBLE
            return
        }
        if (launch == null) {
            Toast.makeText(this, R.string.cannot_open_app, Toast.LENGTH_LONG).show()
            return
        }
        service.teach!!.startTeaching(pkg)
        startActivity(launch)
    }

    private enum class Action(val label: Int, val mode: Mode? = null) {
        TEACH(R.string.action_teach),
        RETEACH(R.string.action_reteach),
        AUTO(R.string.mode_auto, Mode.AUTO),
        LEARNED(R.string.mode_learned, Mode.LEARNED),
        PASS_THROUGH(R.string.mode_pass_through, Mode.PASS_THROUGH),
        OFF(R.string.mode_off, Mode.OFF),
        RESET(R.string.action_reset),
    }

    companion object {
        const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"

        fun prefs(context: Context) = context.getSharedPreferences("entersend", Context.MODE_PRIVATE)
    }
}
