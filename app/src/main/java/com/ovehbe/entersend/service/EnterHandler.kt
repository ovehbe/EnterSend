package com.ovehbe.entersend.service

import android.os.Handler
import android.view.KeyEvent
import com.ovehbe.entersend.core.Box
import com.ovehbe.entersend.core.ChatFieldGuard
import com.ovehbe.entersend.core.CropMatch
import com.ovehbe.entersend.core.Field
import com.ovehbe.entersend.core.Pixels
import com.ovehbe.entersend.core.SendFinder
import com.ovehbe.entersend.core.insertNewline
import com.ovehbe.entersend.data.AppRule
import com.ovehbe.entersend.data.Mode

/**
 * Decides what a fresh Enter / Shift+Enter press does. Returns true when the key is consumed.
 * Runs on the main thread and only reads memory.
 */
class EnterHandler(
    private val device: Device,
    private val store: RuleStore,
    private val main: Handler,
    /** Called when a chat field has no working send rule, to offer teaching. */
    private val offerTeach: (AppRule) -> Unit,
) {
    /** Set while a send is waiting for the field to empty; further Enters are swallowed. */
    private var inFlight: Any? = null

    fun onEnter(e: KeyEvent): Boolean {
        if (e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return false
        val field = device.focusedField() ?: return false
        val state = store.get(field.packageName) ?: return false
        val rule = state.rule
        val mode = rule.effectiveMode
        if (mode == Mode.OFF || mode == Mode.PASS_THROUGH) return false
        if (!ChatFieldGuard.isChatLike(field, rule)) return false

        if (e.isShiftPressed) {
            val (text, cursor) = insertNewline(field.text, field.selStart, field.selEnd)
            device.setText(field, text, cursor)
            return true
        }
        if (field.text.isBlank()) return true
        if (inFlight != null) return true

        val root = device.windowRoot(field) ?: return false
        val learned = if (mode == Mode.LEARNED) SendFinder.learned(root, field, rule) else null
        val node = learned ?: SendFinder.auto(root, field)
        if (node != null) {
            if (!device.click(node)) return false
            verify(field)
            return true
        }
        if (mode == Mode.LEARNED && rule.hasTapSpot) return tapSpot(field, rule, state.crop)
        offerTeach(rule)
        return false
    }

    /**
     * Taps the saved spot only if orientation and window size still match (checked now, so a
     * mismatch passes the real Enter through) and a live screenshot of the spot still matches the
     * saved crop (checked asynchronously; a mismatch delivers Enter via the IME action instead).
     */
    private fun tapSpot(field: Field, rule: AppRule, crop: Pixels?): Boolean {
        val ws = device.windowState(field) ?: return false
        if (crop == null || ws.orientation != rule.orientation || ws.width != rule.windowWidth || ws.height != rule.windowHeight) {
            offerTeach(rule)
            return false
        }
        val token = Any()
        inFlight = token
        val rect = Box(rule.cropLeft!!, rule.cropTop!!, rule.cropRight!!, rule.cropBottom!!)
        device.capture(rect) { live ->
            if (inFlight !== token) return@capture
            if (live != null && CropMatch.matches(crop, live)) {
                device.tap(rule.tapX!!, rule.tapY!!)
                verify(field, token)
            } else {
                inFlight = null
                device.imeEnter(field)
                offerTeach(rule)
            }
        }
        return true
    }

    private fun verify(field: Field, token: Any = Any()) {
        inFlight = token
        check(field, token, 0)
    }

    private fun check(field: Field, token: Any, step: Int) {
        main.postDelayed({
            if (inFlight !== token) return@postDelayed
            val text = device.readText(field)
            when {
                text == null -> inFlight = null
                text.isBlank() -> {
                    inFlight = null
                    store.recordResult(field.packageName, true)
                    device.vibrate()
                }
                step == VERIFY_DELAYS_MS.lastIndex -> {
                    inFlight = null
                    store.recordResult(field.packageName, false)
                }
                else -> check(field, token, step + 1)
            }
        }, VERIFY_DELAYS_MS[step])
    }

    companion object {
        val VERIFY_DELAYS_MS = longArrayOf(150, 250, 500, 800)
    }
}
