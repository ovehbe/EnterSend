package com.meowgi.entersend.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
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
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.ConfigJson
import com.meowgi.entersend.data.Mode
import com.meowgi.entersend.data.ProfileStore
import com.meowgi.entersend.service.EnterSendService
import java.io.File

/** Settings: per-app modes, health, teaching, profiles, and config export/import. */
class MainActivity : Activity() {
    private val app get() = application as EnterSendApp
    private lateinit var adapter: AppListAdapter
    private lateinit var search: EditText
    private lateinit var needsOnly: CheckBox
    private lateinit var showSystem: CheckBox
    private lateinit var empty: TextView
    private lateinit var profileLabel: TextView
    private lateinit var profiles: ProfileStore
    private var rows: List<AppRow>? = null
    private var exportAllProfiles = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = prefs(this)
        if (!prefs.getBoolean(KEY_SETUP_DONE, false)) startActivity(Intent(this, SetupActivity::class.java))

        profiles = ProfileStore(this)
        app.io.execute { profiles.ensureDefault(app.db.rules()) }

        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.banner_fix).setOnClickListener { ServiceWatch.openAccessibilitySettings(this) }
        findViewById<View>(R.id.banner_notifications_fix).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        findViewById<View>(R.id.open_setup).setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        findViewById<View>(R.id.profiles_button).setOnClickListener { showProfilesMenu() }
        findViewById<View>(R.id.export_button).setOnClickListener { showExportMenu() }
        findViewById<View>(R.id.import_button).setOnClickListener { startImport() }
        profileLabel = findViewById(R.id.profile_label)
        handleTeachIntent(intent)
        search = findViewById(R.id.search)
        needsOnly = findViewById(R.id.needs_teaching)
        showSystem = findViewById(R.id.show_system_apps)
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
        showSystem.setOnCheckedChangeListener { _, _ -> applyFilter() }
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
        refreshProfileLabel()
        reload()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT -> app.io.execute { exportTo(uri) }
            REQ_IMPORT -> app.io.execute { importFrom(uri) }
        }
    }

    private fun handleTeachIntent(intent: Intent?) {
        val pkg = intent?.getStringExtra(com.meowgi.entersend.service.NotifyActionReceiver.EXTRA_PACKAGE) ?: return
        teach(pkg)
    }

    private fun refreshProfileLabel() {
        profileLabel.text = getString(R.string.profile_active, profiles.activeName())
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
        adapter.submit(
            if (all == null) {
                emptyList()
            } else {
                AppRows.filter(all, search.text, needsOnly.isChecked, showSystem.isChecked)
            },
        )
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
                AppRule(row.pkg)
            }
            else -> write(row.pkg) {
                val dao = app.db.rules()
                dao.ensure(row.pkg)
                val updated = dao.get(row.pkg)!!.copy(mode = action.mode!!.name)
                dao.upsert(updated)
                updated
            }
        }
    }

    /**
     * Persists on the IO thread, then pushes the new rule into the live [RuleStore] so the
     * accessibility service applies the change immediately (no restart).
     */
    private fun write(pkg: String, block: () -> AppRule?) {
        app.io.execute {
            val rule = block() ?: AppRule(pkg)
            runOnUiThread {
                EnterSendService.instance?.store?.putLocal(rule)
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

    private fun showProfilesMenu() {
        val items = arrayOf(
            getString(R.string.profile_save_as),
            getString(R.string.profile_switch),
            getString(R.string.profile_rename),
            getString(R.string.profile_delete),
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.profiles_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> saveProfileAs()
                    1 -> switchProfile()
                    2 -> renameProfile()
                    3 -> deleteProfile()
                }
            }
            .show()
    }

    private fun saveProfileAs() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = getString(R.string.profile_name_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.profile_save_as)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { return@setPositiveButton }
                app.io.execute {
                    profiles.saveCurrentAs(name, app.db.rules())
                    runOnUiThread {
                        refreshProfileLabel()
                        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun switchProfile() {
        val list = profiles.list()
        val labels = list.map {
            if (it.id == profiles.activeId()) getString(R.string.current_choice, it.name) else it.name
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.profile_switch)
            .setItems(labels) { _, which ->
                val meta = list[which]
                if (meta.id == profiles.activeId()) return@setItems
                app.io.execute {
                    profiles.switchTo(meta.id, app.db.rules(), EnterSendService.instance?.store)
                    runOnUiThread {
                        refreshProfileLabel()
                        reload()
                        Toast.makeText(this, getString(R.string.profile_switched, meta.name), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun renameProfile() {
        val list = profiles.list()
        val labels = list.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.profile_rename)
            .setItems(labels) { _, which ->
                val meta = list[which]
                val input = EditText(this).apply {
                    setText(meta.name)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.profile_rename)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val name = input.text.toString().trim().ifEmpty { return@setPositiveButton }
                        profiles.rename(meta.id, name)
                        refreshProfileLabel()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .show()
    }

    private fun deleteProfile() {
        val list = profiles.list()
        if (list.size <= 1) {
            Toast.makeText(this, R.string.profile_cannot_delete_last, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.profile_delete)
            .setItems(labels) { _, which ->
                val meta = list[which]
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.profile_delete_confirm, meta.name))
                    .setPositiveButton(R.string.profile_delete) { _, _ ->
                        app.io.execute {
                            try {
                                profiles.delete(meta.id, app.db.rules(), EnterSendService.instance?.store)
                                runOnUiThread {
                                    refreshProfileLabel()
                                    reload()
                                }
                            } catch (e: IllegalStateException) {
                                runOnUiThread {
                                    Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .show()
    }

    private fun showExportMenu() {
        val items = arrayOf(
            getString(R.string.export_all_profiles),
            getString(R.string.export_active_profile),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.export_config)
            .setItems(items) { _, which ->
                exportAllProfiles = which == 0
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "entersend-config.json"),
                    REQ_EXPORT,
                )
            }
            .show()
    }

    private fun startImport() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json"),
            REQ_IMPORT,
        )
    }

    private fun exportTo(uri: Uri) {
        try {
            val bundle = profiles.exportBundle(app.db.rules(), exportAllProfiles)
            contentResolver.openOutputStream(uri)?.use { ConfigJson.write(bundle, it) }
                ?: throw IllegalStateException("Cannot open output")
            runOnUiThread { Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, getString(R.string.export_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importFrom(uri: Uri) {
        try {
            val bundle = contentResolver.openInputStream(uri)?.use { ConfigJson.read(it) }
                ?: throw ConfigJson.FormatException("Cannot open file")
            profiles.importBundle(bundle, app.db.rules(), EnterSendService.instance?.store)
            runOnUiThread {
                refreshProfileLabel()
                reload()
                Toast.makeText(this, R.string.import_done, Toast.LENGTH_SHORT).show()
            }
        } catch (e: ConfigJson.FormatException) {
            runOnUiThread {
                Toast.makeText(this, getString(R.string.import_bad_file, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, getString(R.string.import_bad_file, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
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
        private const val REQ_EXPORT = 41
        private const val REQ_IMPORT = 42

        fun prefs(context: Context) = context.getSharedPreferences("entersend", Context.MODE_PRIVATE)
    }
}
