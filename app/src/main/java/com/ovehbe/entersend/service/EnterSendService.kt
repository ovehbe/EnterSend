package com.ovehbe.entersend.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.ovehbe.entersend.EnterSendApp
import com.ovehbe.entersend.core.KeyGate
import com.ovehbe.entersend.data.CropFiles

class EnterSendService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val gate = KeyGate()
    private var enter: EnterHandler? = null
    internal var store: RuleStore? = null
        private set

    override fun onServiceConnected() {
        val app = application as EnterSendApp
        attach(
            A11yDevice(this),
            RuleStore(app.db.rules(), app.io, { main.post(it) }, CropFiles::load),
        )
    }

    /** Wires the service to a device and store; tests call this with fakes. */
    internal fun attach(device: Device, store: RuleStore) {
        this.store = store
        enter = EnterHandler(device, store, main)
    }

    public override fun onKeyEvent(event: KeyEvent): Boolean {
        val enter = enter ?: return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> gate.filter(event) { enter.onEnter(event) }
            else -> false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { store?.onForeground(it) }
        }
    }

    override fun onInterrupt() = Unit
}
