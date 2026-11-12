package com.meowgi.entersend.ui

import android.Manifest
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
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.meowgi.entersend.BuildConfig
import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.R
import com.meowgi.entersend.ServiceWatch
import com.meowgi.entersend.core.SystemAppPolicy
import com.google.android.material.materialswitch.MaterialSwitch
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.ConfigJson
import com.meowgi.entersend.data.Mode
import com.meowgi.entersend.data.ProfileStore
import com.meowgi.entersend.data.PromptPrefs
import com.meowgi.entersend.service.EnterSendService
import java.io.File

/**
 * Material 3 shell: Home status, Apps list, Profiles, Settings.
 * Service / teach / export / profile semantics are unchanged — this is UI structure only.
 */
class MainActivity : AppCompatActivity() {
    private val app get() = application as EnterSendApp
    private lateinit var adapter: AppListAdapter
    private lateinit var profileAdapter: ProfileListAdapter
    private lateinit var search: EditText
    private lateinit var needsOnly: CheckBox
    private lateinit var showSystem: CheckBox
    private lateinit var empty: TextView
    private lateinit var profileLabel: TextView
    private lateinit var pager: ViewFlipper
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var profiles: ProfileStore
    private var rows: List<AppRow>? = null
    private var exportAllProfiles = true
    private val policy by lazy { SystemAppPolicy(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = prefs(this)
        if (!prefs.getBoolean(KEY_SETUP_DONE, false)) startActivity(Intent(this, SetupActivity::class.java))

        profiles = ProfileStore(this)
        app.io.execute { profiles.ensureDefault(app.db.rules()) }

        setContentView(R.layout.activity_main)
        pager = findViewById(R.id.pager)
        bottomNav = findViewById(R.id.bottom_nav)
        profileLabel = findViewById(R.id.profile_label)

        findViewById<View>(R.id.banner_fix).setOnClickListener { ServiceWatch.openAccessibilitySettings(this) }
        findViewById<View>(R.id.banner_notifications_fix).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        findViewById<View>(R.id.open_setup).setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        findViewById<View>(R.id.settings_open_setup).setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        findViewById<View>(R.id.open_diagnostics).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        findViewById<View>(R.id.home_goto_apps).setOnClickListener { selectTab(1) }
        findViewById<View>(R.id.status_action).setOnClickListener { ServiceWatch.openAccessibilitySettings(this) }
        findViewById<View>(R.id.profiles_button).setOnClickListener { selectTab(2) }
        findViewById<View>(R.id.export_button).setOnClickListener { showExportMenu() }
        findViewById<View>(R.id.import_button).setOnClickListener { startImport() }
        findViewById<View>(R.id.profile_save_as).setOnClickListener { saveProfileAs() }
        findViewById<View>(R.id.profile_rename).setOnClickListener { renameProfile() }
        findViewById<View>(R.id.profile_delete).setOnClickListener { deleteProfile() }

        findViewById<TextView>(R.id.settings_version).text =
            getString(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        findViewById<MaterialSwitch>(R.id.prompts_enabled).apply {
            isChecked = PromptPrefs.promptsEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                PromptPrefs.setPromptsEnabled(this@MainActivity, checked)
            }
        }

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

        profileAdapter = ProfileListAdapter(this) { meta -> switchToProfile(meta) }
        findViewById<ListView>(R.id.profiles_list).adapter = profileAdapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        needsOnly.setOnCheckedChangeListener { _, _ -> applyFilter() }
        showSystem.setOnCheckedChangeListener { _, _ -> applyFilter() }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> pager.displayedChild = 0
                R.id.nav_apps -> pager.displayedChild = 1
                R.id.nav_profiles -> {
                    pager.displayedChild = 2
                    refreshProfiles()
                }
                R.id.nav_settings -> pager.displayedChild = 3
            }
            true
        }
        // Subtle focus state layer on bottom-nav items for D-pad / Tab.
        bottomNav.post {
            for (i in 0 until bottomNav.menu.size()) {
                val itemView = (bottomNav.getChildAt(0) as? ViewGroup)?.getChildAt(i)
                itemView?.foreground = getDrawable(R.drawable.focus_ring)
                itemView?.isFocusable = true
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTeachIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStatusUi()
        ServiceWatch.updateNotice(this, ServiceWatch.isRunning())
        refreshProfileLabel()
        refreshProfiles()
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

    private fun selectTab(index: Int) {
        pager.displayedChild = index
        bottomNav.selectedItemId = when (index) {
            1 -> R.id.nav_apps
            2 -> R.id.nav_profiles
            3 -> R.id.nav_settings
            else -> R.id.nav_home
        }
        if (index == 2) refreshProfiles()
    }

    private fun refreshStatusUi() {
        val running = ServiceWatch.isRunning()
        findViewById<View>(R.id.banner).visibility = if (running) View.GONE else View.VISIBLE
        val notifDenied = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        findViewById<View>(R.id.banner_notifications).visibility = if (notifDenied) View.VISIBLE else View.GONE

        val card = findViewById<View>(R.id.status_card)
        val title = findViewById<TextView>(R.id.status_title)
        val body = findViewById<TextView>(R.id.status_body)
        val action = findViewById<MaterialButton>(R.id.status_action)
        if (running) {
            card.setBackgroundResource(R.drawable.status_card_bg)
            title.setText(R.string.status_on_title)
            body.setText(R.string.status_on_body)
            action.visibility = View.GONE
        } else {
            card.setBackgroundResource(R.drawable.status_card_bg_off)
            title.setText(R.string.status_off_title)
            body.setText(R.string.status_off_body)
            action.visibility = View.VISIBLE
            action.setText(R.string.turn_on)
        }
    }

    private fun handleTeachIntent(intent: Intent?) {
        val pkg = intent?.getStringExtra(com.meowgi.entersend.service.NotifyActionReceiver.EXTRA_PACKAGE) ?: return
        teach(pkg)
    }

    private fun refreshProfileLabel() {
        profileLabel.text = getString(R.string.profile_active, profiles.activeName())
    }

    private fun refreshProfiles() {
        val list = profiles.list()
        profileAdapter.submit(list, profiles.activeId())
        findViewById<View>(R.id.profiles_empty).visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun reload() {
        app.io.execute {
            val loaded = AppRows.load(this, app.db.rules().all(), policy)
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
            AlertDialog.Builder(this)
                .setTitle(row.label)
                .setMessage(R.string.blocked_app)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val actions = buildList {
            add(if (row.rule.isTaught) Action.RETEACH else Action.TEACH)
            add(Action.AUTO)
            if (row.rule.isTaught) add(Action.LEARNED)
            add(Action.PASS_THROUGH)
            add(Action.OFF)
            if (row.rule.teachDeclined) add(Action.ASK_AGAIN)
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
            Action.ASK_AGAIN -> {
                PromptPrefs.clearSnooze(this, row.pkg)
                val updated = row.rule.copy(teachDeclined = false)
                applyOptimistic(row.pkg, updated, policy.resolveMode(updated))
                write(row.pkg) {
                    val dao = app.db.rules()
                    dao.ensure(row.pkg)
                    dao.clearDeclined(row.pkg)
                    dao.get(row.pkg)!!.copy(teachDeclined = false)
                }
            }
            Action.RESET -> {
                // Instant empty state in the list, then persist.
                PromptPrefs.clearSnooze(this, row.pkg)
                val cleared = AppRule(row.pkg)
                applyOptimistic(row.pkg, cleared, policy.resolveMode(cleared))
                write(row.pkg) {
                    row.rule.cropPath?.let { File(it).delete() }
                    app.db.rules().delete(row.pkg)
                    cleared
                }
            }
            else -> {
                val updated = row.rule.copy(mode = action.mode!!.name)
                applyOptimistic(row.pkg, updated, action.mode)
                write(row.pkg) {
                    val dao = app.db.rules()
                    dao.ensure(row.pkg)
                    val saved = dao.get(row.pkg)!!.copy(mode = action.mode.name)
                    dao.upsert(saved)
                    saved
                }
            }
        }
    }

    /** Immediate list + service update; [write] still persists on the IO thread. */
    private fun applyOptimistic(pkg: String, rule: AppRule, mode: Mode) {
        EnterSendService.instance?.store?.putLocal(rule)
        rows = rows?.map { if (it.pkg == pkg) it.withRule(rule, mode) else it }
        applyFilter()
    }

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
            selectTab(0)
            return
        }
        if (launch == null) {
            Toast.makeText(this, R.string.cannot_open_app, Toast.LENGTH_LONG).show()
            return
        }
        service.teach!!.startTeaching(pkg)
        startActivity(launch)
    }

    private fun saveProfileAs() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = getString(R.string.profile_name_hint)
            foreground = getDrawable(R.drawable.focus_ring)
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
                        refreshProfiles()
                        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun switchToProfile(meta: ProfileStore.Meta) {
        if (meta.id == profiles.activeId()) return
        app.io.execute {
            profiles.switchTo(meta.id, app.db.rules(), EnterSendService.instance?.store)
            runOnUiThread {
                refreshProfileLabel()
                refreshProfiles()
                reload()
                Toast.makeText(this, getString(R.string.profile_switched, meta.name), Toast.LENGTH_SHORT).show()
            }
        }
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
                    foreground = getDrawable(R.drawable.focus_ring)
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.profile_rename)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val name = input.text.toString().trim().ifEmpty { return@setPositiveButton }
                        profiles.rename(meta.id, name)
                        refreshProfileLabel()
                        refreshProfiles()
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
                                    refreshProfiles()
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
                @Suppress("DEPRECATION")
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
        @Suppress("DEPRECATION")
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
                refreshProfiles()
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
        ASK_AGAIN(R.string.action_ask_again),
        RESET(R.string.action_reset),
    }

    companion object {
        const val KEY_SETUP_DONE = "setup_done"
        private const val REQ_EXPORT = 41
        private const val REQ_IMPORT = 42

        fun prefs(context: Context) = context.getSharedPreferences("entersend", Context.MODE_PRIVATE)
    }
}
