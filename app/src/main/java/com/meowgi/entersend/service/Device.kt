package com.meowgi.entersend.service

import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.Field
import com.meowgi.entersend.core.NodeSnap
import com.meowgi.entersend.core.Pixels
import com.meowgi.entersend.core.WindowState

/** IME / action snapshot for Enter diagnostics (logging only). */
data class FieldImeDiag(
    val className: String?,
    val inputType: Int,
    val multiLine: Boolean,
    val imeActionId: Int?,
    val imeActionLabel: String?,
    val actions: List<String>,
    val hasImeEnterAction: Boolean,
    val extrasHint: String?,
)

/** What the service does to the screen. [A11yDevice] in production, a fake in tests. */
interface Device {
    fun focusedField(): Field?
    fun windowRoot(field: Field): NodeSnap?
    fun windowState(field: Field): WindowState?
    fun click(node: NodeSnap): Boolean
    fun setText(field: Field, text: String, cursor: Int): Boolean
    /** Current text of [field] ("" when it shows its hint), or null if the field is gone. */
    fun readText(field: Field): String?
    fun tap(x: Int, y: Int)
    /** Delivers Enter to the field as the IME would. */
    fun imeEnter(field: Field)
    /** Screenshot of [rect]; null if screenshots are unavailable. Callback on the main thread. */
    fun capture(rect: Box, callback: (Pixels?) -> Unit)
    fun vibrate()
    /** Restore input focus to the compose box after a successful send. */
    fun focus(field: Field): Boolean = false
    /**
     * Focus [field] for typing (auto-focus). Default: [focus]. Production may also try CLICK.
     */
    fun focusForTyping(field: Field): Boolean = focus(field)
    /**
     * Find the message compose field in [pkg], preferring [preferredViewId] when taught.
     * Null when nothing suitable is on screen.
     */
    fun findMessageField(pkg: String, preferredViewId: String?): Field? = null
    /** Short on-screen notice (debug toast or send-failed). No-op in headless tests unless recorded. */
    fun notice(message: String) {}
    /** Extra IME / action info for EnterDiagLog; default uses [Field] only. */
    fun fieldImeDiag(field: Field): FieldImeDiag {
        val multi = field.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        return FieldImeDiag(
            className = field.className,
            inputType = field.inputType,
            multiLine = multi,
            imeActionId = null,
            imeActionLabel = null,
            actions = emptyList(),
            hasImeEnterAction = false,
            extrasHint = null,
        )
    }
}
