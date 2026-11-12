package com.meowgi.entersend.core

import com.meowgi.entersend.data.AppRule
import kotlin.math.roundToInt

/** Resolves and validates a taught tap coordinate against the live field / window. */
object TapSpot {
    /** Crop size around the tap point used when teaching and when checking before a send. */
    const val CROP_RADIUS_PX = 40

    fun density(dpi: Int): Float = dpi / 160f

    fun dxDp(field: Box, tapX: Int, density: Float): Float =
        (tapX - field.right) / density

    fun dyDp(field: Box, tapY: Int, density: Float): Float =
        (tapY - field.centerY) / density

    fun fx(window: WindowState, tapX: Int): Float =
        if (window.width == 0) 0f else tapX.toFloat() / window.width

    fun fy(window: WindowState, tapY: Int): Float =
        if (window.height == 0) 0f else tapY.toFloat() / window.height

    fun cropBox(tapX: Int, tapY: Int, window: WindowState): Box {
        val r = CROP_RADIUS_PX
        return Box(
            (tapX - r).coerceAtLeast(0),
            (tapY - r).coerceAtLeast(0),
            (tapX + r).coerceAtMost(window.width),
            (tapY + r).coerceAtMost(window.height),
        )
    }

    /**
     * Live tap point for [rule], or null if anchors don't match the current field/window.
     * Prefers the field-relative dp anchor; falls back to window fractions; then legacy absolute.
     */
    fun resolve(rule: AppRule, field: Field?, window: WindowState, density: Float, keyboardShown: Boolean): Pair<Int, Int>? {
        if (field != null && rule.tapDxDp != null && rule.tapDyDp != null) {
            val x = field.bounds.right + (rule.tapDxDp * density).roundToInt()
            val y = field.bounds.centerY + (rule.tapDyDp * density).roundToInt()
            return x to y
        }
        if (field == null &&
            rule.tapFx != null && rule.tapFy != null &&
            rule.orientation == window.orientation &&
            rule.windowWidth == window.width &&
            rule.keyboardShown == keyboardShown
        ) {
            return (rule.tapFx * window.width).roundToInt() to (rule.tapFy * window.height).roundToInt()
        }
        // Upgraded v1 absolute spot: only when the whole window still matches.
        if (rule.tapX != null && rule.tapY != null &&
            rule.orientation == window.orientation &&
            rule.windowWidth == window.width &&
            rule.windowHeight == window.height
        ) {
            return rule.tapX to rule.tapY
        }
        return null
    }

    /** Point must be inside the window and outside the focused text box. */
    fun isSafe(point: Pair<Int, Int>, window: WindowState, field: Field?): Boolean {
        val (x, y) = point
        if (x !in 0 until window.width || y !in 0 until window.height) return false
        if (field != null && field.bounds.contains(x, y)) return false
        return true
    }
}
