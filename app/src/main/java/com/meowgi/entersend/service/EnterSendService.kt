package com.meowgi.entersend.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.meowgi.entersend.BuildConfig
import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.ServiceWatch
import com.meowgi.entersend.core.KeyGate
import com.meowgi.entersend.core.SystemAppPolicy
import com.meowgi.entersend.data.CropFiles
import com.meowgi.entersend.data.EnterDiagLog
import com.meowgi.entersend.data.PromptDecisionLog
import java.time.LocalDate

class EnterSendService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val gate = KeyGate()
    private var enter: EnterHandler? = null
    private var device: Device? = null
    private var notifier: NewAppNotifier? = null
    private var policy: SystemAppPolicy? = null
    private var lastWindowPkg: String? = null
    private var chooser: PromptChooser? = null
    private var usage: UsageForeground? = null
    private var screenReceiver: BroadcastReceiver? = null
    private val usagePoll = object : Runnable {
        override fun run() {
            crossCheckUsage("usage-poll")
            main.postDelayed(this, USAGE_POLL_MS)
        }
    }
    internal var teach: TeachFlow? = null
        private set
    internal var store: RuleStore? = null
        private set

    /** Test override for window/root resolution. */
    internal var foregroundProbe: (() -> ForegroundPackage.Resolved?)? = null

    /** Test override for interactive window list (prompt foreground only). */
    internal var windowsForTest: List<ForegroundPackage.WindowHint>? = null

    /** Test override for rootInActiveWindow package (prompt foreground only). */
    internal var rootPackageForTest: String? = null

    override fun onServiceConnected() {
        // Drop any leftover teach overlay from a previous connection.
        teach?.forceClose()
        chooser?.hide()
        serviceInfo = serviceInfo.apply {
            eventTypes = eventTypes or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            flags = flags or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val app = application as EnterSendApp
        val a11y = A11yDevice(this).also { device = NoticeDevice(it, this) }
        val policy = SystemAppPolicy(this)
        val rules = RuleStore(app.db.rules(), app.io, { main.post(it) }, CropFiles(filesDir))
        // Cache every stored rule before Enter can fire — no optimistic default over Off.
        rules.preloadAll()
        val banner = A11yPromptBanner(this, main)
        val promptChooser = A11yPromptChooser(this, { store }, { teach }, main)
        attach(
            a11y,
            rules,
            CardOverlay(this),
            policy = policy,
            banner = banner,
            chooser = promptChooser,
        )
        instance = this
        ServiceWatch.updateNotice(this, running = true)
        // Ensure HIGH channel exists and legacy DEFAULT is gone as soon as a11y connects.
        notifier?.ensureChannel()
        main.removeCallbacks(usagePoll)
        main.post(usagePoll)
        registerScreenOn()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopped()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopped()
        super.onDestroy()
    }

    private fun stopped() {
        main.removeCallbacks(usagePoll)
        unregisterScreenOn()
        chooser?.hide()
        teach?.forceClose()
        if (instance === this) instance = null
        ServiceWatch.updateNotice(this, running = false)
    }

    private fun registerScreenOn() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_ON) {
                    crossCheckUsage("screen-on")
                }
            }
        }
        registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        screenReceiver = receiver
    }

    private fun unregisterScreenOn() {
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        screenReceiver = null
    }

    internal fun attach(
        device: Device,
        store: RuleStore,
        overlay: Overlay,
        today: () -> Long = { LocalDate.now().toEpochDay() },
        notifier: NewAppNotifier? = null,
        policy: SystemAppPolicy = SystemAppPolicy(this),
        banner: PromptBanner? = null,
        chooser: PromptChooser? = null,
    ) {
        teach?.forceClose()
        this.device = device
        this.store = store
        this.policy = policy
        this.usage = UsageForeground(this)
        // Unit tests / re-attach: ensure DB rows are cached (no-op if already preloaded).
        store.preloadAll()
        this.chooser = chooser ?: A11yPromptChooser(this, { this.store }, { this.teach }, main)
        val prompt = notifier ?: NewAppNotifier(
            this, store, today, policy, banner = banner,
        )
        this.notifier = prompt
        // Prompt once the rule finishes loading — avoids the race where WINDOW_STATE fires
        // while RuleStore.refresh is still in flight and get() returns null.
        store.setOnLoaded { pkg, state ->
            if (pkg == lastWindowPkg) prompt.onAppSeen(pkg, state.rule, source = "rule-loaded")
        }
        val teach = TeachFlow(device, store, overlay, main, today, ::versionOf) { getString(it) }
        this.teach = teach
        val log = PromptDecisionLog.get(this)
        val enterDiag = EnterDiagLog.get(this)
        enter = EnterHandler(
            device, store, main,
            offerTeach = { rule ->
                if (policy.mayPrompt(rule.packageName) && policy.resolveMode(rule) != com.meowgi.entersend.data.Mode.OFF) {
                    teach.offer(rule)
                }
            },
            debug = BuildConfig.DEBUG,
            strings = { getString(it) },
            resolveMode = policy::resolveMode,
            // Enter always uses focused-input package — never lastWindowPkg / prompt foreground.
            onDecision = { pkg, ruleLabel, consumed ->
                log.record(
                    pkg,
                    "enter",
                    if (consumed) "consumed rule=$ruleLabel" else "not-consumed rule=$ruleLabel",
                )
                android.util.Log.d(
                    "EnterSend",
                    "Enter pkg=$pkg rule=$ruleLabel consumed=$consumed",
                )
            },
            diag = enterDiag,
        )
    }

    fun showPromptChooser(pkg: String) {
        chooser?.show(pkg)
    }

    internal fun chooserOrNull(): PromptChooser? = chooser

    /** Test / screen-on / a11y hook: Usage Access MOVE_TO_FOREGROUND cross-check. */
    internal fun crossCheckUsageForTests(source: String = "usage") = crossCheckUsage(source)

    public override fun onKeyEvent(event: KeyEvent): Boolean {
        if (chooser?.onKey(event) == true) return true
        val enter = enter ?: return false
        val teach = teach ?: return false
        return gate.filter(event) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> !teach.onEnter() && enter.onEnter(event)
                KeyEvent.KEYCODE_BACK -> if (teach.isTeaching) {
                    teach.onKey(event)
                    true
                } else {
                    false
                }
                else -> teach.onKey(event)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> onForegroundEvent(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            -> event.packageName?.toString()?.let {
                // Warm Enter rules for the field's package (incl. our Setup screen).
                store?.warm(it)
                teach?.onTextChanged(it)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                event.packageName?.toString()?.let { teach?.onContentChanged(it) }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (teach?.isTeaching == true) {
                val pkg = event.packageName?.toString()
                if (pkg != null) {
                    val source = event.source
                    if (source != null) teach?.onClicked(pkg, A11yDevice.clickedSnap(source))
                    else teach?.onMissedClick()
                }
            }
        }
        // Every a11y event: optional Usage Access cross-check (when granted + enabled).
        crossCheckUsage("usage/" + eventSource(event.eventType))
    }

    private fun onForegroundEvent(event: AccessibilityEvent) {
        val eventPkg = event.packageName?.toString()
        // Enter warm for our own package (Setup test chat) without stealing prompt session.
        if (eventPkg == packageName) {
            store?.onForeground(packageName)
            PromptDecisionLog.get(this).record(
                eventPkg,
                eventSource(event.eventType) + "/self-warm",
                "enter-warm:self",
            )
        }
        // Also warm whatever package owns the focused input — Enter must not depend on prompt FG.
        try {
            device?.focusedField()?.packageName?.let { focused ->
                if (focused.isNotBlank()) store?.warm(focused)
            }
        } catch (_: Exception) {
        }

        val resolved = resolveForeground(eventPkg) ?: run {
            // IME / overlay / SystemUI / self — keep previous prompt app; never clear.
            if (!eventPkg.isNullOrBlank() && eventPkg != packageName) {
                PromptDecisionLog.get(this).record(
                    eventPkg,
                    eventSource(event.eventType) + "/ignored",
                    "skipped:noise-window",
                )
            }
            return
        }
        applyForeground(resolved.packageName, "a11y/" + resolved.source)
    }

    private fun crossCheckUsage(source: String) {
        val u = usage ?: return
        // Prefer toggle+permission; if Home card auto-enabled, toggle is on once granted.
        val pkg = u.latestForegroundPackage() ?: return
        if (pkg == lastWindowPkg) return
        applyForeground(pkg, source)
    }

    private fun applyForeground(pkg: String, source: String) {
        // Debounce: only act when the package actually changes.
        if (pkg == lastWindowPkg) return
        val previous = lastWindowPkg
        if (previous != null) {
            notifier?.onPackageLeft(previous)
        }
        lastWindowPkg = pkg
        // Prompt path only — never feeds EnterHandler's package selection.
        store?.onForeground(pkg)
        store?.get(pkg)?.let { notifier?.onAppSeen(pkg, it.rule, source = source) }
    }

    private fun resolveForeground(eventPkg: String?): ForegroundPackage.Resolved? {
        // When a test probe is installed, its null means "noise / keep previous" — do not fall through.
        foregroundProbe?.let { return it() }
        val pol = policy ?: SystemAppPolicy(this)
        // IME / SystemUI are not the app the user opened — ignore them as foreground.
        val isNoise: (String) -> Boolean = { p ->
            pol.isSystemUi(p) ||
                p in pol.enabledImePackages() ||
                "inputmethod" in p ||
                p.endsWith(".ime")
        }
        val windows = windowHints()
        val rootPkg = rootPackageForTest ?: try {
            rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            null
        }
        val base = ForegroundPackage.resolve(
            ownPackage = packageName,
            eventPackage = eventPkg,
            rootPackage = rootPkg,
            windows = windows,
            isNoise = isNoise,
        ) ?: return null
        val source = when (base.source) {
            "windows" -> "windows"
            "root" -> "root"
            else -> "event"
        }
        return ForegroundPackage.Resolved(base.packageName, source)
    }

    private fun windowHints(): List<ForegroundPackage.WindowHint> {
        windowsForTest?.let { return it }
        val list = try {
            windows
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return list.mapNotNull { w ->
            val root = try {
                w.root
            } catch (_: Exception) {
                null
            } ?: return@mapNotNull null
            val pkg = try {
                root.packageName?.toString()
            } catch (_: Exception) {
                null
            } ?: return@mapNotNull null
            val type = try {
                w.type
            } catch (_: Exception) {
                AccessibilityWindowInfo.TYPE_APPLICATION
            }
            val layer = try {
                w.layer
            } catch (_: Exception) {
                0
            }
            val active = try {
                w.isActive
            } catch (_: Exception) {
                false
            }
            val focused = try {
                w.isFocused
            } catch (_: Exception) {
                false
            }
            try {
                root.recycle()
            } catch (_: Exception) {
            }
            // Still record IME/overlay/system rows so resolve can ignore them without
            // treating a missing APPLICATION list as "use IME root".
            ForegroundPackage.WindowHint(pkg, type, layer, active = active, focused = focused)
        }
    }

    private fun eventSource(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "windows-changed"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window-state"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "content-changed"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "clicked"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "text-changed"
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "selection-changed"
        else -> "a11y"
    }

    override fun onInterrupt() = Unit

    companion object {
        var instance: EnterSendService? = null
            private set
        private const val USAGE_POLL_MS = 2_500L

        /** Unit tests only. */
        internal fun clearInstanceForTests() {
            instance = null
        }

        /** Unit tests only — attach without onServiceConnected. */
        internal fun setInstanceForTests(service: EnterSendService?) {
            instance = service
        }
    }

    private fun versionOf(pkg: String): Long? = try {
        packageManager.getPackageInfo(pkg, 0).longVersionCode
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}

/** Adds toast/notice on top of a real [A11yDevice]. */
private class NoticeDevice(
    private val inner: A11yDevice,
    private val service: AccessibilityService,
) : Device by inner {
    override fun notice(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
        }
        android.util.Log.d("EnterSend", message)
    }
}
