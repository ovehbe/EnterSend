package com.meowgi.entersend.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.meowgi.entersend.BuildConfig
import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.ServiceWatch
import com.meowgi.entersend.core.KeyGate
import com.meowgi.entersend.core.SystemAppPolicy
import com.meowgi.entersend.data.CropFiles
import java.time.LocalDate

class EnterSendService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val gate = KeyGate()
    private var enter: EnterHandler? = null
    private var device: Device? = null
    private var notifier: NewAppNotifier? = null
    internal var teach: TeachFlow? = null
        private set
    internal var store: RuleStore? = null
        private set

    override fun onServiceConnected() {
        // Drop any leftover teach overlay from a previous connection.
        teach?.forceClose()
        serviceInfo = serviceInfo.apply {
            eventTypes = eventTypes or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            flags = flags or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val app = application as EnterSendApp
        val a11y = A11yDevice(this).also { device = NoticeDevice(it, this) }
        val policy = SystemAppPolicy(this)
        attach(
            a11y,
            RuleStore(app.db.rules(), app.io, { main.post(it) }, CropFiles(filesDir)),
            CardOverlay(this),
            policy = policy,
        )
        notifier = NewAppNotifier(this, store!!, { LocalDate.now().toEpochDay() }, policy)
        instance = this
        ServiceWatch.updateNotice(this, running = true)
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
    ) {
        teach?.forceClose()
        this.device = device
        this.store = store
        this.notifier = notifier
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

    public override fun onKeyEvent(event: KeyEvent): Boolean {
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
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                store?.onForeground(pkg)
                val state = store?.get(pkg)
                if (state != null) notifier?.onAppSeen(pkg, state.rule)
                else {
                    // Still loading — ask again after a short delay once the rule is in memory.
                    main.postDelayed({
                        store?.get(pkg)?.let { notifier?.onAppSeen(pkg, it.rule) }
                    }, 200)
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> teach?.onTextChanged(pkg)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (teach?.isTeaching == true) {
                val source = event.source
                if (source != null) teach?.onClicked(pkg, A11yDevice.clickedSnap(source))
                else teach?.onMissedClick()
            }
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        var instance: EnterSendService? = null
            private set
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
