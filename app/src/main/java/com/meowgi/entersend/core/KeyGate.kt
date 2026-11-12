package com.meowgi.entersend.core

import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Turns the raw key stream into one decision per physical press.
 *
 * `decide` runs only for the first real ACTION_DOWN of a press. Key repeats, the ACTION_UP and
 * duplicate copies all get the same answer, so a press acts at most once.
 *
 * The Q25 delivers each physical press twice: the real event and a zero-duration copy with
 * deviceId -1 about 15 ms later. A virtual (deviceId -1) event mirrors the real press it overlaps
 * within [windowMs]; a virtual event with no real press nearby is passed through and never acted on.
 */
class KeyGate(private val windowMs: Long = 60) {
    private class Press(val downTime: Long, var lastTime: Long, val consumed: Boolean)

    private val presses = HashMap<Int, Press>()
    private val lastVirtual = HashMap<Int, Long>()

    fun filter(e: KeyEvent, decide: () -> Boolean): Boolean {
        val code = e.keyCode
        val press = presses[code]

        if (e.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) {
            lastVirtual[code] = e.eventTime
            return press != null && press.consumed &&
                e.eventTime >= press.downTime - windowMs && e.eventTime <= press.lastTime + windowMs
        }

        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            if (press != null && press.downTime == e.downTime) return press.consumed
            val virtualJustBefore = lastVirtual[code]?.let { e.eventTime - it in 0..windowMs } == true
            val consumed = if (virtualJustBefore) false else decide()
            presses[code] = Press(e.downTime, e.eventTime, consumed)
            return consumed
        }

        if (press == null || press.downTime != e.downTime) return false
        press.lastTime = e.eventTime
        return press.consumed
    }
}
