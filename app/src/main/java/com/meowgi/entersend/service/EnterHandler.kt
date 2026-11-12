package com.meowgi.entersend.service

import android.os.Handler
import android.view.KeyEvent
import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.ChatFieldGuard
import com.meowgi.entersend.core.CropMatch
import com.meowgi.entersend.core.Field
import com.meowgi.entersend.core.Pixels
import com.meowgi.entersend.core.SendFinder
import com.meowgi.entersend.core.TapSpot
import com.meowgi.entersend.core.insertNewline
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.Mode

/**
 * Decides what a fresh Enter / Shift+Enter press does. Returns true when the key is consumed.
 * Order: learned node → auto-detect → coordinate tap → pass through. One attempt per press.
 */
class EnterHandler(
    private val device: Device,
    private val store: RuleStore,
    private val main: Handler,
    private val offerTeach: (AppRule) -> Unit,
    private val debug: Boolean = false,
    private val strings: (Int) -> String = { "" },
    private val resolveMode: (AppRule) -> Mode = { it.effectiveMode },
) {
    private var inFlight: Any? = null

    fun onEnter(e: KeyEvent): Boolean {
        if (e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return false
        val field = device.focusedField() ?: return false
        val state = store.get(field.packageName) ?: return false
        val rule = state.rule
        val mode = resolveMode(rule)
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
        val learned = if (rule.hasNodeRule) SendFinder.learned(root, field, rule) else null
        val node = learned ?: SendFinder.auto(root, field)
        if (node != null) {
            if (!device.click(node)) return false
            if (debug) device.notice(strings(com.meowgi.entersend.R.string.debug_send_button))
            verify(field, viaTap = false)
            return true
        }

        if (rule.hasTapSpot) {
            if (rule.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                offerTeach(rule)
                return false
            }
            return tapSpot(field, rule, state.crop)
        }

        offerTeach(rule)
        return false
    }

    private fun tapSpot(field: Field, rule: AppRule, crop: Pixels?): Boolean {
        val ws = device.windowState(field) ?: return false
        val point = TapSpot.resolve(rule, field, ws, ws.density, ws.keyboardShown)
        if (point == null || !TapSpot.isSafe(point, ws, field)) {
            store.markNeedsRecheck(rule.packageName)
            offerTeach(rule)
            return false
        }
        if (crop == null || rule.cropLeft == null) {
            offerTeach(rule)
            return false
        }
        val token = Any()
        inFlight = token
        val rect = Box(rule.cropLeft, rule.cropTop!!, rule.cropRight!!, rule.cropBottom!!)
        device.capture(rect) { live ->
            if (inFlight !== token) return@capture
            if (live != null && CropMatch.matches(crop, live)) {
                android.util.Log.d(
                    "EnterSend",
                    "gesture screen=(${point.first},${point.second}) saved=(${rule.tapX},${rule.tapY})",
                )
                device.tap(point.first, point.second)
                if (debug) device.notice(strings(com.meowgi.entersend.R.string.debug_send_tap))
                verify(field, viaTap = true, token = token)
            } else {
                inFlight = null
                store.markNeedsRecheck(rule.packageName)
                device.imeEnter(field) // key was already consumed; re-deliver as IME Enter
                offerTeach(rule)
            }
        }
        return true
    }

    private fun verify(field: Field, viaTap: Boolean, token: Any = Any()) {
        inFlight = token
        // Success = box empty within ~500 ms.
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
                else -> {
                    inFlight = null
                    store.recordResult(field.packageName, false)
                    device.notice(strings(com.meowgi.entersend.R.string.send_failed))
                    val failures = store.get(field.packageName)?.rule?.consecutiveFailures ?: 0
                    if (viaTap && failures >= MAX_CONSECUTIVE_FAILURES) {
                        offerTeach(store.get(field.packageName)?.rule ?: AppRule(field.packageName))
                    }
                }
            }
        }, VERIFY_MS)
    }

    companion object {
        const val VERIFY_MS = 500L
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
