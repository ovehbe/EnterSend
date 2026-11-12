package com.meowgi.entersend.core

import kotlin.math.max
import kotlin.math.sqrt

data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom

    /** Gap between two boxes in px; 0 when they touch or overlap. */
    fun distanceTo(o: Box): Double {
        val dx = max(0, max(o.left - right, left - o.right)).toDouble()
        val dy = max(0, max(o.top - bottom, top - o.bottom)).toDouble()
        return sqrt(dx * dx + dy * dy)
    }
}

/** The focused editable field. [text] is "" while the field shows its hint. */
data class Field(
    val packageName: String,
    val text: String,
    val selStart: Int,
    val selEnd: Int,
    val inputType: Int,
    val isPassword: Boolean,
    val viewId: String?,
    val hint: String?,
    val bounds: Box,
    val ref: Any? = null,
)

/** Snapshot of one accessibility node; [ref] is the live node in production. */
class NodeSnap(
    val viewId: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val className: String? = null,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val editable: Boolean = false,
    val bounds: Box,
    val children: List<NodeSnap> = emptyList(),
    val ref: Any? = null,
) {
    var parent: NodeSnap? = null
        private set

    init {
        children.forEach { it.parent = this }
    }

    val classPath: String? get() = className?.let { c -> parent?.className?.let { "$it>$c" } ?: c }

    fun flatten(): List<NodeSnap> = listOf(this) + children.flatMap { it.flatten() }
}

data class WindowState(
    val orientation: Int,
    /** Full display size in screen pixels (same space as raw touch / gesture / node bounds). */
    val width: Int,
    val height: Int,
    val keyboardShown: Boolean = false,
    /** Display density (dpi/160), used to convert dp anchors to pixels. */
    val density: Float = 1f,
    /** Y just below the status bar / cutout, in screen pixels. */
    val statusBarBottom: Int = 0,
    /** Y of the IME top, or [height] when the keyboard is hidden. */
    val keyboardTop: Int = height,
)

class Pixels(val width: Int, val height: Int, val argb: IntArray) {
    /** The part of this image inside [box]; null if they don't overlap. */
    fun crop(box: Box): Pixels? {
        val l = box.left.coerceIn(0, width)
        val t = box.top.coerceIn(0, height)
        val r = box.right.coerceIn(0, width)
        val b = box.bottom.coerceIn(0, height)
        if (r <= l || b <= t) return null
        val out = IntArray((r - l) * (b - t))
        for (y in t until b) System.arraycopy(argb, y * width + l, out, (y - t) * (r - l), r - l)
        return Pixels(r - l, b - t, out)
    }
}
