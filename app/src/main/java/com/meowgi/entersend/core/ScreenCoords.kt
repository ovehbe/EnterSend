package com.meowgi.entersend.core

import android.view.MotionEvent

/**
 * One screen-pixel coordinate space end to end: overlay tap → marker → saved rule → dispatchGesture.
 *
 * On a full-bleed accessibility overlay (LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS, x=0,y=0), the
 * capture view's layout origin is the screen origin. Marker margins must be the raw screen
 * coordinates directly — do **not** subtract [View.getLocationOnScreen], which on real devices
 * can still report a non-zero origin (status bar / cutout / NO_LIMITS quirks) and shifts the
 * marker up and sideways. Robolectric usually returns (0,0), which hid this bug in unit tests.
 *
 * Touches: always [MotionEvent.getRawX]/[MotionEvent.getRawY] (same space as
 * getBoundsInScreen and GestureDescription).
 */
object ScreenCoords {
    const val MARKER_SIZE_PX = 48

    fun fromTouch(e: MotionEvent): Pair<Int, Int> = e.rawX.toInt() to e.rawY.toInt()

    /**
     * FrameLayout margins so a [MARKER_SIZE_PX] marker is centred on the screen-pixel tap.
     * Full-bleed only — no locationOnScreen adjustment.
     */
    fun markerMargins(screenX: Int, screenY: Int): Pair<Int, Int> {
        val r = MARKER_SIZE_PX / 2
        return screenX - r to screenY - r
    }
}
