package com.meowgi.entersend.service

import android.os.Handler
import android.view.KeyEvent
import com.meowgi.entersend.R
import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.NodeSnap
import com.meowgi.entersend.core.Pixels
import com.meowgi.entersend.core.PopupEdge
import com.meowgi.entersend.core.PopupPlacement
import com.meowgi.entersend.core.SendFinder
import com.meowgi.entersend.core.TapSpot
import com.meowgi.entersend.data.AppRule

/**
 * Two-step teach (Ömer's flow):
 * 1. No overlay — user taps the real send button. Usable node → save immediately + "Saved".
 * 2. If nothing usable within [STEP1_TIMEOUT_MS], show a bar. When the text box is non-empty
 *    again, show a transparent full-screen capture overlay; the tap is consumed (nothing sent).
 *    Screenshot is taken while the overlay is transparent, then a marker is drawn.
 *    Done (Alt+Y) saves; Retry moves the marker; Esc/Back/30s timeout cancel.
 */
class TeachFlow(
    private val device: Device,
    private val store: RuleStore,
    private val overlay: Overlay,
    private val main: Handler,
    private val today: () -> Long,
    private val versionOf: (String) -> Long?,
    private val strings: (Int) -> String = { "" },
) {
    private sealed interface State
    private data object Idle : State
    private class Listening(val pkg: String) : State
    private class RetryBar(val pkg: String) : State
    private class Capturing(val pkg: String, var markerX: Int? = null, var markerY: Int? = null, var crop: Pixels? = null) : State
    private class Confirming(val pkg: String, val rule: AppRule, val crop: Pixels?) : State // legacy confirm

    private var state: State = Idle
    private val onChoice: (Choice) -> Unit = ::handleChoice
    private val step1Token = Any()

    val isTeaching: Boolean
        get() = state is Listening || state is RetryBar || state is Capturing || state is Confirming

    val isCapturing: Boolean get() = state is Capturing

    /** Starts step 1: no overlay, wait for a real send click. */
    fun startTeaching(pkg: String) {
        clearTimeouts()
        overlay.hide()
        overlay.setTapListener(null)
        val listening = Listening(pkg)
        state = listening
        main.postAtTime({ if (state === listening) enterStep2(pkg) }, step1Token, android.os.SystemClock.uptimeMillis() + STEP1_TIMEOUT_MS)
        armSafetyTimeout(pkg)
    }

    fun offer(rule: AppRule) {
        // Prefer the notification path for new apps; keep a silent no-op if already asked/declined.
        if (state !is Idle || rule.teachDeclined || rule.lastAskedDay == today()) return
        startTeaching(rule.packageName)
        // Bookkeeping row only when we actually start teaching from a prompt — not for system defaults.
        store.markAsked(rule.packageName, today())
    }

    fun onKey(e: KeyEvent): Boolean {
        if (state is Idle || isModifier(e.keyCode)) return false
        return when {
            e.keyCode == KeyEvent.KEYCODE_ESCAPE || e.keyCode == KeyEvent.KEYCODE_BACK -> {
                finish(); true
            }
            (state is Capturing || state is Confirming) && e.isAltPressed && e.keyCode == KeyEvent.KEYCODE_Y -> {
                handleChoice(Choice.YES); true
            }
            state is Capturing && e.isAltPressed && e.keyCode == KeyEvent.KEYCODE_N -> {
                handleChoice(Choice.RETRY); true
            }
            else -> false
        }
    }

    fun onEnter(): Boolean {
        return isTeaching // pass Enter through to the app while teaching
    }

    fun onTextChanged(pkg: String) {
        val bar = state as? RetryBar ?: return
        if (bar.pkg != pkg) return
        val field = device.focusedField()?.takeIf { it.packageName == pkg } ?: return
        if (field.text.isNotBlank()) showCapture(pkg)
    }

    fun onClicked(pkg: String, node: NodeSnap) {
        when (val s = state) {
            is Listening -> {
                if (s.pkg != pkg || node.editable) return
                if (usableSend(node)) {
                    saveNodeRule(pkg, node)
                } else {
                    // First tap wasn't a readable send node — step 2.
                    enterStep2(pkg)
                }
            }
            is RetryBar, is Capturing -> Unit // capture overlay consumes the teach tap itself
            else -> Unit
        }
    }

    fun onMissedClick() {
        val s = state
        if (s is Listening) enterStep2(s.pkg)
    }

    /** Screen tap on the transparent capture overlay. Coordinates are screen pixels. */
    fun onCaptureTap(x: Int, y: Int) {
        val capturing = state as? Capturing ?: return
        val field = device.focusedField()?.takeIf { it.packageName == capturing.pkg }
        if (field == null || field.text.isBlank()) {
            // Never save a tap spot from an empty box — hint bar only, capture stays up.
            val edge = barEdge(field?.bounds, null)
            overlay.show(
                Card.Capture(null, null, strings(R.string.teach_need_text), edge, field?.bounds),
                onChoice,
            )
            return
        }
        val ws = device.windowState(field) ?: return
        if (!TapSpot.isSafe(x to y, ws, field)) return

        // Remove overlay so the crop isn't polluted, screenshot, then show marker.
        overlay.setTapListener(null)
        overlay.hide()
        val cropRect = TapSpot.cropBox(x, y, ws)
        device.capture(cropRect) { pixels ->
            if (state !is Capturing || (state as Capturing).pkg != capturing.pkg) return@capture
            capturing.markerX = x
            capturing.markerY = y
            capturing.crop = pixels
            android.util.Log.d("EnterSend", "capture saved raw=($x,$y)")
            val edge = barEdge(field.bounds, x to y)
            overlay.show(
                Card.Capture(x, y, strings(R.string.teach_capture_keys), edge, field.bounds),
                onChoice,
            )
            overlay.setTapListener { nx, ny -> onCaptureTap(nx, ny) } // Retry by tapping again
        }
    }

    fun forceClose() = finish()

    private fun enterStep2(pkg: String) {
        clearTimeouts()
        state = RetryBar(pkg)
        overlay.setTapListener(null)
        val field = device.focusedField()?.takeIf { it.packageName == pkg }
        // Type-something bar: WRAP_CONTENT at top (or bottom if field is top-half). Not full-screen,
        // not focusable — physical keyboard / taps outside still reach the app.
        overlay.show(Card.TeachingBar(strings(R.string.teach_step2), barEdge(field?.bounds, null)), onChoice)
        armSafetyTimeout(pkg)
        // Capture layer only once the focused box has text.
        if (field != null && field.text.isNotBlank()) showCapture(pkg)
    }

    private fun showCapture(pkg: String) {
        val capturing = Capturing(pkg)
        state = capturing
        val field = device.focusedField()?.takeIf { it.packageName == pkg }
        val edge = barEdge(field?.bounds, null)
        overlay.show(
            Card.Capture(null, null, strings(R.string.teach_tap_send_spot), edge, field?.bounds),
            onChoice,
        )
        overlay.setTapListener { x, y -> onCaptureTap(x, y) }
        armSafetyTimeout(pkg)
    }

    private fun barEdge(field: Box?, tap: Pair<Int, Int>?): PopupEdge {
        val f = field ?: device.focusedField()?.bounds
        val ws = device.focusedField()?.let { device.windowState(it) }
        val screenH = ws?.height ?: 720
        val status = ws?.statusBarBottom ?: 0
        val keyTop = ws?.keyboardTop ?: screenH
        return PopupPlacement.place(
            screenHeight = screenH,
            statusBarBottom = status,
            keyboardTop = keyTop,
            popupHeight = 160,
            field = f,
            tapSpot = tap,
        ).edge
    }

    private fun usableSend(node: NodeSnap): Boolean {
        if (!node.visible || !node.enabled || node.editable) return false
        if (node.clickable || node.parent?.clickable == true) {
            return SendFinder.looksLikeSend(node) ||
                node.viewId != null || node.desc != null || node.text != null || node.className != null
        }
        return false
    }

    private fun saveNodeRule(pkg: String, node: NodeSnap) {
        val field = device.focusedField()?.takeIf { it.packageName == pkg }
        val target = node
        val old = store.get(pkg)?.rule ?: AppRule(pkg)
        val rule = old.copy(
            mode = null,
            teachDeclined = false,
            successCount = 0,
            failureCount = 0,
            consecutiveFailures = 0,
            needsRecheck = false,
            taughtVersionCode = versionOf(pkg),
            fieldViewId = field?.viewId,
            sendViewId = target.viewId,
            sendText = target.text,
            sendDesc = target.desc,
            sendClassPath = target.classPath,
            // Clear tap-spot fields when a node rule is enough.
            tapX = null, tapY = null, tapDxDp = null, tapDyDp = null, tapFx = null, tapFy = null,
            cropPath = null, cropLeft = null, cropTop = null, cropRight = null, cropBottom = null,
            orientation = null, windowWidth = null, windowHeight = null, keyboardShown = null,
        )
        store.saveTaught(rule, null)
        state = Idle
        overlay.setTapListener(null)
        overlay.show(Card.Saved(target.desc ?: target.text, barEdge(field?.bounds, null)), onChoice)
        main.postDelayed({ if (state is Idle) overlay.hide() }, SAVED_MS)
        clearTimeouts()
    }

    private fun saveCapture(capturing: Capturing) {
        val x = capturing.markerX ?: return
        val y = capturing.markerY ?: return
        val crop = capturing.crop ?: return
        val field = device.focusedField()?.takeIf { it.packageName == capturing.pkg }
        if (field == null || field.text.isBlank()) return // never save from empty box
        val ws = device.windowState(field) ?: return
        val density = ws.density
        val old = store.get(capturing.pkg)?.rule ?: AppRule(capturing.pkg)
        val cropRect = TapSpot.cropBox(x, y, ws)
        val rule = old.copy(
            mode = null,
            teachDeclined = false,
            successCount = 0,
            failureCount = 0,
            consecutiveFailures = 0,
            needsRecheck = false,
            taughtVersionCode = versionOf(capturing.pkg),
            fieldViewId = field.viewId,
            sendViewId = null, sendText = null, sendDesc = null, sendClassPath = null,
            tapX = x, tapY = y,
            tapDxDp = TapSpot.dxDp(field.bounds, x, density),
            tapDyDp = TapSpot.dyDp(field.bounds, y, density),
            tapFx = TapSpot.fx(ws, x),
            tapFy = TapSpot.fy(ws, y),
            cropLeft = cropRect.left, cropTop = cropRect.top,
            cropRight = cropRect.right, cropBottom = cropRect.bottom,
            orientation = ws.orientation,
            windowWidth = ws.width,
            windowHeight = ws.height,
            keyboardShown = ws.keyboardShown,
        )
        store.saveTaught(rule, crop)
        finish()
    }

    private fun handleChoice(choice: Choice) {
        when (val s = state) {
            is Capturing -> when (choice) {
                Choice.YES -> saveCapture(s)
                Choice.RETRY -> {
                    s.markerX = null; s.markerY = null; s.crop = null
                    showCapture(s.pkg)
                }
                Choice.CLOSE, Choice.NO -> finish()
            }
            is RetryBar -> if (choice == Choice.CLOSE) finish()
            is Confirming -> when (choice) {
                Choice.YES -> { store.saveTaught(s.rule, s.crop); finish() }
                Choice.NO, Choice.RETRY -> startTeaching(s.pkg)
                Choice.CLOSE -> finish()
            }
            is Listening -> if (choice == Choice.CLOSE) finish()
            Idle -> Unit
        }
    }

    private val safetyRunnable = Runnable { if (isTeaching) finish() }

    private fun armSafetyTimeout(pkg: String) {
        main.removeCallbacks(safetyRunnable)
        main.postDelayed(safetyRunnable, SAFETY_TIMEOUT_MS)
    }

    private fun clearTimeouts() {
        main.removeCallbacks(safetyRunnable)
        main.removeCallbacksAndMessages(step1Token)
    }

    private fun finish() {
        clearTimeouts()
        state = Idle
        overlay.setTapListener(null)
        overlay.hide()
    }

    companion object {
        const val STEP1_TIMEOUT_MS = 2_000L
        const val SAFETY_TIMEOUT_MS = 30_000L
        const val SAVED_MS = 1_500L

        private val MODIFIERS = setOf(
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_SYM, KeyEvent.KEYCODE_FUNCTION, KeyEvent.KEYCODE_CAPS_LOCK,
        )

        fun isModifier(code: Int) = code in MODIFIERS
    }
}
