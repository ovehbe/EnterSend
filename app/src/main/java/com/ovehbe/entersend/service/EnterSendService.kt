package com.ovehbe.entersend.service

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.content.Intent
import com.ovehbe.entersend.EnterSendApp
import com.ovehbe.entersend.ServiceWatch
import com.ovehbe.entersend.core.KeyGate
import com.ovehbe.entersend.data.CropFiles
import java.time.LocalDate

class EnterSendService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val gate = KeyGate()
    private var enter: EnterHandler? = null
    internal var teach: TeachFlow? = null
        private set
    internal var store: RuleStore? = null
        private set

    override fun onServiceConnected() {
        val app = application as EnterSendApp
        attach(
            A11yDevice(this),
            RuleStore(app.db.rules(), app.io, { main.post(it) }, CropFiles(filesDir)),
            CardOverlay(this),
        )
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
        if (instance === this) instance = null
        ServiceWatch.updateNotice(this, running = false)
    }

    /** Wires the service to a device, store and overlay; tests call this with fakes. */
    internal fun attach(
        device: Device,
        store: RuleStore,
        overlay: Overlay,
        today: () -> Long = { LocalDate.now().toEpochDay() },
    ) {
        val teach = TeachFlow(device, store, overlay, main, today, ::versionOf)
        this.store = store
        this.teach = teach
        enter = EnterHandler(device, store, main) { teach.offer(it) }
    }

    public override fun onKeyEvent(event: KeyEvent): Boolean {
        val enter = enter ?: return false
        val teach = teach ?: return false
        return gate.filter(event) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> !teach.onEnter() && enter.onEnter(event)
                else -> teach.onKey(event)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> store?.onForeground(pkg)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> teach?.onTextChanged(pkg)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (teach?.isTeaching == true) {
                event.source?.let { teach?.onClicked(pkg, A11yDevice.clickedSnap(it)) }
            }
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        /** The connected service, or null when it is off or was killed. Main thread only. */
        var instance: EnterSendService? = null
            private set
    }

    private fun versionOf(pkg: String): Long? = try {
        packageManager.getPackageInfo(pkg, 0).longVersionCode
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
