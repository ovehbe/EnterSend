package com.ovehbe.entersend.service

import com.ovehbe.entersend.core.Box
import com.ovehbe.entersend.core.Field
import com.ovehbe.entersend.core.NodeSnap
import com.ovehbe.entersend.core.Pixels
import com.ovehbe.entersend.core.WindowState

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
}
