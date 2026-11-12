package com.ovehbe.entersend.service

import android.os.Handler
import android.view.KeyEvent
import com.ovehbe.entersend.core.Box
import com.ovehbe.entersend.core.NodeSnap
import com.ovehbe.entersend.core.Pixels
import com.ovehbe.entersend.core.WindowState
import com.ovehbe.entersend.data.AppRule

/**
 * Ask card -> teaching bar -> "Is this the send button?" -> saved rule.
 *
 * Keys while the ask or confirm card is up: Alt+Y = yes, Alt+N = no, Esc = close.
 * Plain letters always reach the app; typing while the ask card is up hides it ("ask later").
 */
class TeachFlow(
    private val device: Device,
    private val store: RuleStore,
    private val overlay: Overlay,
    private val main: Handler,
    private val today: () -> Long,
    private val versionOf: (String) -> Long?,
) {
    private sealed interface State
    private data object Idle : State
    private class Asking(val pkg: String) : State
    private class Teaching(val pkg: String) : State {
        var shot: Pixels? = null
    }
    private class Confirming(val pkg: String, val rule: AppRule, val crop: Pixels?) : State

    private var state: State = Idle

    val isTeaching get() = state is Teaching || state is Confirming

    /** Called when Enter found no working rule in a chat field of [rule]'s app. */
    fun offer(rule: AppRule) {
        if (state !is Idle || rule.teachDeclined || rule.lastAskedDay == today()) return
        val asking = Asking(rule.packageName)
        state = asking
        store.markAsked(rule.packageName, today())
        overlay.show(Card.Ask(reteach = rule.isTaught), ::onChoice)
        main.postDelayed({ if (state === asking) finish() }, ASK_TIMEOUT_MS)
    }

    /** Starts teaching directly (from Settings or after "yes"). */
    fun startTeaching(pkg: String) {
        val teaching = Teaching(pkg)
        state = teaching
        overlay.show(Card.Teaching, ::onChoice)
        main.postDelayed({ captureFor(teaching) }, SHOT_DELAY_MS)
        main.postDelayed({ if (state === teaching) finish() }, TEACH_TIMEOUT_MS)
    }

    /** Fresh key press other than Enter. Returns true to consume it. */
    fun onKey(e: KeyEvent): Boolean {
        if (state is Idle || isModifier(e.keyCode)) return false
        val answerable = state is Asking || state is Confirming
        return when {
            e.keyCode == KeyEvent.KEYCODE_ESCAPE -> { onChoice(Choice.CLOSE); true }
            answerable && e.isAltPressed && e.keyCode == KeyEvent.KEYCODE_Y -> { onChoice(Choice.YES); true }
            answerable && e.isAltPressed && e.keyCode == KeyEvent.KEYCODE_N -> { onChoice(Choice.NO); true }
            else -> {
                if (state is Asking) finish()
                false
            }
        }
    }

    /** Enter was pressed: hides the ask card. Returns true if Enter must pass through untouched. */
    fun onEnter(): Boolean {
        if (state is Asking) finish()
        return isTeaching
    }

    fun onTextChanged(pkg: String) {
        val teaching = state as? Teaching ?: return
        if (teaching.pkg != pkg) return
        main.removeCallbacksAndMessages(teaching)
        main.postAtTime({ captureFor(teaching) }, teaching, android.os.SystemClock.uptimeMillis() + SHOT_DELAY_MS)
    }

    /** The user tapped [node] in [pkg] while the teaching bar was up. */
    fun onClicked(pkg: String, node: NodeSnap) {
        val teaching = state as? Teaching ?: return
        if (teaching.pkg != pkg || node.editable) return
        val field = device.focusedField()?.takeIf { it.packageName == pkg }
        val window: WindowState? = field?.let(device::windowState)
        val crop = teaching.shot?.crop(node.bounds)
        val b = crop?.let { node.bounds.clipTo(teaching.shot!!) }
        val old = store.get(pkg)?.rule ?: AppRule(pkg)
        val rule = old.copy(
            mode = null,
            teachDeclined = false,
            successCount = 0,
            failureCount = 0,
            taughtVersionCode = versionOf(pkg),
            fieldViewId = field?.viewId,
            sendViewId = node.viewId,
            sendText = node.text,
            sendDesc = node.desc,
            sendClassPath = node.classPath,
            tapX = node.bounds.centerX.takeIf { b != null && window != null },
            tapY = node.bounds.centerY.takeIf { b != null && window != null },
            cropLeft = b?.left, cropTop = b?.top, cropRight = b?.right, cropBottom = b?.bottom,
            orientation = window?.orientation,
            windowWidth = window?.width,
            windowHeight = window?.height,
        )
        state = Confirming(pkg, rule, crop)
        overlay.show(Card.Confirm(crop, node.desc ?: node.text), ::onChoice)
    }

    private fun onChoice(choice: Choice) {
        when (val s = state) {
            is Asking -> when (choice) {
                Choice.YES -> startTeaching(s.pkg)
                Choice.NO -> { store.decline(s.pkg); finish() }
                Choice.CLOSE -> finish()
            }
            is Teaching -> if (choice == Choice.CLOSE) finish()
            is Confirming -> when (choice) {
                Choice.YES -> { store.saveTaught(s.rule, s.crop); finish() }
                Choice.NO -> startTeaching(s.pkg)
                Choice.CLOSE -> finish()
            }
            Idle -> Unit
        }
    }

    private fun captureFor(teaching: Teaching) {
        if (state !== teaching) return
        device.capture(FULL_SCREEN) { shot -> if (shot != null) teaching.shot = shot }
    }

    private fun finish() {
        state = Idle
        overlay.hide()
    }

    private fun Box.clipTo(p: Pixels) =
        Box(left.coerceIn(0, p.width), top.coerceIn(0, p.height), right.coerceIn(0, p.width), bottom.coerceIn(0, p.height))

    companion object {
        const val ASK_TIMEOUT_MS = 8_000L
        const val TEACH_TIMEOUT_MS = 120_000L
        const val SHOT_DELAY_MS = 400L
        private val FULL_SCREEN = Box(0, 0, Int.MAX_VALUE, Int.MAX_VALUE)

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
