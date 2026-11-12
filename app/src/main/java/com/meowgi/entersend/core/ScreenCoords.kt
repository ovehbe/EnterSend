package com.meowgi.entersend.core

import android.view.MotionEvent
import android.view.View

/**
 * One screen-pixel coordinate space end to end: overlay tap → saved rule → dispatchGesture.
 *
 * Accessibility overlays that are laid out inside system insets report [MotionEvent.getX]/Y
 * relative to the window, which is shifted by the status bar / cutout. [MotionEvent.getRawX]
 * is always screen space — the same space as [android.view.accessibility.AccessibilityNodeInfo.getBoundsInScreen]
 * and [android.accessibilityservice.GestureDescription]. Always use raw for capture.
 */
object ScreenCoords {
    fun fromTouch(e: MotionEvent): Pair<Int, Int> = e.rawX.toInt() to e.rawY.toInt()

    /**
     * Converts a screen-pixel point into layout margins for a child of [host], whose origin
     * may sit below the status bar when the overlay window is not full-bleed.
     */
    fun toViewLocal(screenX: Int, screenY: Int, host: View): Pair<Int, Int> {
        val loc = IntArray(2)
        host.getLocationOnScreen(loc)
        return screenX - loc[0] to screenY - loc[1]
    }
}
