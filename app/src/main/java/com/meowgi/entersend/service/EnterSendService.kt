package com.meowgi.entersend.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
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
    private val usagePoll = object : Runnable {
        override fun run() {
            pollUsageForeground()
            main.postDelayed(this, USAGE_POLL_MS)
        }
    }
    internal var teach: TeachFlow? = null
        private set
    internal var store: RuleStore? = null
        private set

    /** Test override for window/root resolution. */
    internal var foregroundProbe: (() -> ForegroundPackage.Resolved?)? = null

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
        chooser?.hide()
        teach?.forceClose()
        if (instance === this) instance = null
        ServiceWatch.updateNotice(this, running = false)
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
        )
    }

    fun showPromptChooser(pkg: String) {
        chooser?.show(pkg)
    }

    internal fun chooserOrNull(): PromptChooser? = chooser

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
            -> {
                val pkg = event.packageName?.toString() ?: return
                teach?.onTextChanged(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                teach?.onContentChanged(pkg)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (teach?.isTeaching == true) {
                val pkg = event.packageName?.toString() ?: return
                val source = event.source
                if (source != null) teach?.onClicked(pkg, A11yDevice.clickedSnap(source))
                else teach?.onMissedClick()
            }
        }
    }

    private fun onForegroundEvent(event: AccessibilityEvent) {
        val eventPkg = event.packageName?.toString()
        val resolved = resolveForeground(eventPkg) ?: run {
            // IME / overlay / our banner — keep previous app; log noise for diagnostics.
            if (!eventPkg.isNullOrBlank()) {
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

    private fun pollUsageForeground() {
        val u = usage ?: return
        if (!u.isEnabled()) return
        val pkg = u.latestResumedPackage() ?: return
        if (pkg == lastWindowPkg) return
        applyForeground(pkg, "usage")
    }

    private fun applyForeground(pkg: String, source: String) {
        val previous = lastWindowPkg
        if (previous != null && previous != pkg) {
            notifier?.onPackageLeft(previous)
        }
        lastWindowPkg = pkg
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
        val rootPkg = try {
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
            try {
                root.recycle()
            } catch (_: Exception) {
            }
            ForegroundPackage.WindowHint(pkg, type, layer)
        }
    }

    private fun eventSource(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "windows-changed"
        else -> "window-state"
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
