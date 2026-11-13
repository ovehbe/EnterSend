package com.meowgi.entersend.core

import com.meowgi.entersend.data.AppRule
import kotlin.math.roundToInt

/** Resolves and validates a taught tap coordinate against the live field / window. */
object TapSpot {
    /** Crop size around the tap point used when teaching and when checking before a send. */
    const val CROP_RADIUS_PX = 40

    data class Candidate(val name: String, val point: Pair<Int, Int>)

    fun density(dpi: Int): Float = dpi / 160f

    fun dxDp(field: Box, tapX: Int, density: Float): Float =
        (tapX - field.right) / density

    /** Legacy: dp from the field's vertical center (pre-0.3.11 teaches). */
    fun dyDp(field: Box, tapY: Int, density: Float): Float =
        (tapY - field.centerY) / density

    /** Dp from the nearest vertical edge (bottom if tap in lower half, else top). */
    fun dyNearestEdgeDp(field: Box, tapY: Int, density: Float): Float =
        if (tapY >= field.centerY) {
            (tapY - field.bottom) / density
        } else {
            (tapY - field.top) / density
        }

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
     * Prefer [candidates] + crop matching for send — this single point can drift when the field grows.
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
        if (rule.tapX != null && rule.tapY != null &&
            rule.orientation == window.orientation &&
            rule.windowWidth == window.width &&
            rule.windowHeight == window.height
        ) {
            return rule.tapX to rule.tapY
        }
        return null
    }

    /**
     * Ordered tap candidates for crop-gated send (0.3.11).
     * (a) nearest-edge anchors (right + bottom/top),
     * (b) absolute taught point,
     * (c) legacy proportional / center-relative [resolve].
     */
    fun candidates(
        rule: AppRule,
        field: Field?,
        window: WindowState,
        density: Float,
        keyboardShown: Boolean,
    ): List<Candidate> {
        val out = ArrayList<Candidate>(6)
        fun add(name: String, point: Pair<Int, Int>?) {
            if (point == null || !isOnScreen(point, window)) return
            if (out.any { it.point == point }) return
            out += Candidate(name, point)
        }

        // (a) Edge-anchored: X from field right + dxDp; Y from bottom then top + dyDp.
        // 0.3.11+ teach stores nearest-edge dy; older center-dy rules miss crop here then hit (b).
        if (field != null && rule.tapDxDp != null && rule.tapDyDp != null) {
            val x = field.bounds.right + (rule.tapDxDp * density).roundToInt()
            val dyPx = (rule.tapDyDp * density).roundToInt()
            add("edge-bottom-right", x to (field.bounds.bottom + dyPx))
            add("edge-top-right", x to (field.bounds.top + dyPx))
        }

        // (b) Original absolute taught point when the window geometry still matches.
        if (rule.tapX != null && rule.tapY != null &&
            rule.orientation == window.orientation &&
            rule.windowWidth == window.width &&
            rule.windowHeight == window.height
        ) {
            add("absolute", rule.tapX to rule.tapY)
        }

        // (c) Current proportional / center-relative resolve.
        add("proportional", resolve(rule, field, window, density, keyboardShown))

        return out
    }

    /**
     * On-screen check only. Do **not** reject points inside [Field.bounds] — in Telegram and
     * similar apps the compose row's accessibility bounds include the send button, so that
     * rejection silently dropped every teach tap on send.
     */
    fun isOnScreen(point: Pair<Int, Int>, window: WindowState): Boolean {
        val (x, y) = point
        return x in 0 until window.width && y in 0 until window.height
    }

    /** @deprecated use [isOnScreen]; field-bounds rejection was the Q25 teach bug. */
    fun isSafe(point: Pair<Int, Int>, window: WindowState, field: Field?): Boolean =
        isOnScreen(point, window)
}
