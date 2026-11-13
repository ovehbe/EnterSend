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
import com.meowgi.entersend.data.AutoFocusPrefs

/**
 * Two-step teach (Ömer's flow):
 * 1. Top bar "Type a message and tap send". Usable send node → save immediately + "Saved".
 *    No timer — stay here until send evidence.
 * 2. After evidence that a send happened with no usable click (box went non-empty→empty, or a
 *    click on a node [usableSend] rejects): step-2 bar, then full-screen capture once the box
 *    has text again. Capture taps are authoritative (no field-bounds reject).
 *
 * 0.3.13: after a successful send teach, optional auto-focus offer. Standalone
 * [startAutoFocusTeach] when enabling auto-focus with 0/many editables.
 */
class TeachFlow(
    private val device: Device,
    private val store: RuleStore,
    private val overlay: Overlay,
    private val main: Handler,
    private val today: () -> Long,
    private val versionOf: (String) -> Long?,
    private val strings: (Int) -> String = { "" },
    private val autoFocusContext: android.content.Context? = null,
) {
    private sealed interface State
    private data object Idle : State
    private class Listening(val pkg: String) : State
    private class RetryBar(val pkg: String) : State
    private class Capturing(val pkg: String, var markerX: Int? = null, var markerY: Int? = null, var crop: Pixels? = null) : State
    private class Confirming(val pkg: String, val rule: AppRule, val crop: Pixels?) : State // legacy confirm
    /** Optional: "Also auto-focus…? Tap it, or Skip." after send teach. */
    private class AutoFocusOffer(val pkg: String) : State
    /** Standalone: "Open the app and tap the text field" when enabling auto-focus. */
    private class AutoFocusTeach(val pkg: String) : State

    private var state: State = Idle
    private val onChoice: (Choice) -> Unit = ::handleChoice
    private val promoteToken = Any()
    /** Step 1: compose box was non-empty at least once this session (for empty-after-send evidence). */
    private var step1SawText = false

    val isTeaching: Boolean
        get() = state is Listening || state is RetryBar || state is Capturing || state is Confirming ||
            state is AutoFocusOffer || state is AutoFocusTeach

    val isCapturing: Boolean get() = state is Capturing

    /** Step 1: waiting for a real send tap. Every new session starts here. */
    val isStep1: Boolean get() = state is Listening

    /** Step 2 instruction bar ("Didn't catch that…"). */
    val isStep2Bar: Boolean get() = state is RetryBar

    val isAutoFocusOffer: Boolean get() = state is AutoFocusOffer
    val isAutoFocusTeach: Boolean get() = state is AutoFocusTeach

    /**
     * Starts a fresh teach session at step 1. Clears any prior step, timers, pending capture and
     * marker — notification, Settings re-teach, Cancel/Esc/timeout and service restart all go
     * through here so a session never resumes in step 2.
     */
    fun startTeaching(pkg: String) {
        resetSession()
        state = Listening(pkg)
        step1SawText = false
        val field = device.focusedField()?.takeIf { it.packageName == pkg }
        if (field != null && field.text.isNotBlank()) step1SawText = true
        // Short top bar — not a timer. Step 2 only on send evidence.
        overlay.show(Card.TeachingBar(strings(R.string.teach_step1), barEdge(field?.bounds, null)), onChoice)
        armSafetyTimeout(pkg)
    }

    fun offer(rule: AppRule) {
        if (rule.teachDeclined || rule.lastAskedDay == today()) return
        if (state !is Idle) resetSession()
        startTeaching(rule.packageName)
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
        // Send-button teach: don't let EnterHandler consume Enter (pass through to the app).
        // Auto-focus offer/teach must not block EnterSend's normal Enter → send path.
        return when (state) {
            is AutoFocusOffer, is AutoFocusTeach, Idle -> false
            else -> isTeaching
        }
    }

    fun onTextChanged(pkg: String) {
        watchStep1Text(pkg)
        promoteCaptureIfReady(pkg)
    }

    fun onContentChanged(pkg: String) {
        watchStep1Text(pkg)
        promoteCaptureIfReady(pkg)
    }

    fun onClicked(pkg: String, node: NodeSnap) {
        when (val s = state) {
            is Listening -> {
                if (s.pkg != pkg || node.editable) return
                if (usableSend(node)) {
                    saveNodeRule(pkg, node)
                } else {
                    // Click arrived on a node we cannot use as send — step 2.
                    enterStep2(pkg)
                }
            }
            is RetryBar -> promoteCaptureIfReady(s.pkg)
            is Capturing -> Unit
            is AutoFocusOffer -> if (s.pkg == pkg && node.editable) saveAutoFocusTarget(pkg, node)
            is AutoFocusTeach -> if (s.pkg == pkg && node.editable) saveAutoFocusTarget(pkg, node)
            else -> Unit
        }
    }

    /** Enable auto-focus teach when the Apps sheet toggle finds 0 or many editables. */
    fun startAutoFocusTeach(pkg: String) {
        resetSession()
        state = AutoFocusTeach(pkg)
        val field = device.focusedField()?.takeIf { it.packageName == pkg }
        overlay.show(
            Card.TeachingBar(strings(R.string.autofocus_teach), barEdge(field?.bounds, null)),
            onChoice,
        )
        armSafetyTimeout(pkg)
    }

    fun onMissedClick() {
        when (val s = state) {
            is Listening -> {
                // Null click source alone is not enough; need send evidence (had text, now empty).
                val field = device.focusedField()?.takeIf { it.packageName == s.pkg }
                if (step1SawText && (field == null || field.text.isBlank())) enterStep2(s.pkg)
            }
            is RetryBar -> promoteCaptureIfReady(s.pkg)
            else -> Unit
        }
    }

    fun promoteCaptureIfReady(pkg: String? = null) {
        val bar = state as? RetryBar ?: return
        if (pkg != null && bar.pkg != pkg) return
        val field = device.focusedField()?.takeIf { it.packageName == bar.pkg } ?: return
        if (field.text.isNotBlank()) showCapture(bar.pkg)
    }

    /** Screen tap on the transparent capture overlay. Coordinates are screen pixels. */
    fun onCaptureTap(x: Int, y: Int) {
        val capturing = state as? Capturing ?: return
        val field = device.focusedField()?.takeIf { it.packageName == capturing.pkg }
        if (field == null || field.text.isBlank()) {
            val edge = barEdge(field?.bounds, null)
            overlay.show(
                Card.Capture(null, null, strings(R.string.teach_need_text), edge, field?.bounds),
                onChoice,
            )
            return
        }
        val ws = device.windowState(field)
        if (ws == null) {
            showCaptureReject(capturing.pkg, field.bounds, strings(R.string.teach_tap_rejected_window))
            return
        }
        // Authoritative teach tap: on-screen only. Never reject because field.bounds contains the point.
        if (!TapSpot.isOnScreen(x to y, ws)) {
            showCaptureReject(capturing.pkg, field.bounds, strings(R.string.teach_tap_off_screen))
            return
        }

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
            overlay.setTapListener { nx, ny -> onCaptureTap(nx, ny) }
        }
    }

    fun forceClose() = resetSession()

    private fun resetSession() {
        clearTimeouts()
        state = Idle
        step1SawText = false
        overlay.setTapListener(null)
        overlay.hide()
    }

    /** Box went non-empty → empty while listening = send with no usable click node. */
    private fun watchStep1Text(pkg: String) {
        val listening = state as? Listening ?: return
        if (listening.pkg != pkg) return
        val field = device.focusedField()?.takeIf { it.packageName == pkg }
        when {
            field != null && field.text.isNotBlank() -> step1SawText = true
            step1SawText && (field == null || field.text.isBlank()) -> enterStep2(pkg)
        }
    }

    private fun enterStep2(pkg: String) {
        clearTimeouts()
        state = RetryBar(pkg)
        overlay.setTapListener(null)
        val field = device.focusedField()?.takeIf { it.packageName == pkg }
        overlay.show(Card.TeachingBar(strings(R.string.teach_step2), barEdge(field?.bounds, null)), onChoice)
        armSafetyTimeout(pkg)
        armPromotePoll(pkg)
        promoteCaptureIfReady(pkg)
    }

    private fun armPromotePoll(pkg: String) {
        main.postAtTime(
            {
                if (state is RetryBar && (state as RetryBar).pkg == pkg) {
                    promoteCaptureIfReady(pkg)
                    if (state is RetryBar) armPromotePoll(pkg)
                }
            },
            promoteToken,
            android.os.SystemClock.uptimeMillis() + PROMOTE_POLL_MS,
        )
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

    private fun showCaptureReject(pkg: String, fieldBounds: Box?, reason: String) {
        val edge = barEdge(fieldBounds, null)
        overlay.show(Card.Capture(null, null, reason, edge, fieldBounds), onChoice)
        overlay.setTapListener { x, y -> onCaptureTap(x, y) }
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
            tapX = null, tapY = null, tapDxDp = null, tapDyDp = null, tapFx = null, tapFy = null,
            cropPath = null, cropLeft = null, cropTop = null, cropRight = null, cropBottom = null,
            orientation = null, windowWidth = null, windowHeight = null, keyboardShown = null,
        )
        store.saveTaught(rule, null)
        clearTimeouts()
        overlay.setTapListener(null)
        state = Idle
        overlay.show(Card.Saved(target.desc ?: target.text, barEdge(field?.bounds, null)), onChoice)
        main.postDelayed({
            if (state !is Idle) return@postDelayed
            if (autoFocusContext != null) offerAutoFocus(pkg)
            else overlay.hide()
        }, SAVED_MS)
    }

    private fun saveCapture(capturing: Capturing) {
        val x = capturing.markerX ?: return
        val y = capturing.markerY ?: return
        val crop = capturing.crop ?: return
        val field = device.focusedField()?.takeIf { it.packageName == capturing.pkg }
        if (field == null || field.text.isBlank()) return
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
            // Nearest vertical edge (bottom/top) so multiline growth that keeps the bottom fixed
            // still resolves; absolute tapX/tapY remain as a crop-gated fallback.
            tapDyDp = TapSpot.dyNearestEdgeDp(field.bounds, y, density),
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
        if (autoFocusContext != null) offerAutoFocus(capturing.pkg)
        else finish()
    }

    /** Visible for unit tests. */
    internal fun offerAutoFocus(pkg: String) {
        clearTimeouts()
        state = AutoFocusOffer(pkg)
        val field = device.focusedField()?.takeIf { it.packageName == pkg }
        overlay.setTapListener(null)
        overlay.show(
            Card.TeachingBar(
                strings(R.string.autofocus_offer),
                barEdge(field?.bounds, null),
                showSkip = true,
            ),
            onChoice,
        )
        armSafetyTimeout(pkg)
    }

    private fun saveAutoFocusTarget(pkg: String, node: NodeSnap) {
        val ctx = autoFocusContext ?: return finish()
        val ws = device.windowStateForPackage(pkg)
            ?: device.focusedField()?.let { device.windowState(it) }
        val w = ws?.width ?: 720
        val h = ws?.height ?: 720
        val target = AutoFocusPrefs.buildTarget(
            viewId = node.viewId,
            className = node.className,
            boundsLeft = node.bounds.left,
            boundsTop = node.bounds.top,
            boundsRight = node.bounds.right,
            boundsBottom = node.bounds.bottom,
            windowWidth = w,
            windowHeight = h,
            tapFallback = false,
        )
        AutoFocusPrefs.setTarget(ctx, pkg, target)
        AutoFocusPrefs.setEnabled(ctx, pkg, true)
        clearTimeouts()
        state = Idle
        overlay.setTapListener(null)
        overlay.show(Card.Saved(strings(R.string.autofocus_saved), barEdge(node.bounds, null)), onChoice)
        main.postDelayed({ if (state is Idle) overlay.hide() }, SAVED_MS)
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
            is AutoFocusOffer -> when (choice) {
                Choice.NO, Choice.CLOSE -> finish() // Skip or Cancel — leave autofocus off
                else -> Unit
            }
            is AutoFocusTeach -> if (choice == Choice.CLOSE) {
                // Cancel without enabling.
                autoFocusContext?.let { AutoFocusPrefs.setEnabled(it, s.pkg, false) }
                finish()
            }
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
        main.removeCallbacksAndMessages(promoteToken)
    }

    private fun finish() = resetSession()

    companion object {
        const val SAFETY_TIMEOUT_MS = 30_000L
        const val SAVED_MS = 1_500L
        const val PROMOTE_POLL_MS = 150L

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
