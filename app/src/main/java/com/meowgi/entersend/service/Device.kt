package com.meowgi.entersend.service

import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.Field
import com.meowgi.entersend.core.NodeSnap
import com.meowgi.entersend.core.Pixels
import com.meowgi.entersend.core.WindowState

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
