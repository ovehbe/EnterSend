package com.meowgi.entersend.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Compares the saved send-button crop with a live crop of the same screen rect.
 * Both are averaged into a [GRID]x[GRID] grid of RGB cells, which tolerates compression noise and
 * 1-2 px shifts but not a different icon (e.g. send arrow turned into a mic).
 */
object CropMatch {
    private const val GRID = 12
    private const val MAX_MEAN_DIFF = 12.0
    private const val MAX_CELL_DIFF = 48

    fun matches(saved: Pixels, live: Pixels): Boolean {
        if (saved.width != live.width || saved.height != live.height) return false
        if (saved.width < GRID || saved.height < GRID) return false
        val a = cells(saved)
        val b = cells(live)
        var sum = 0L
        var worst = 0
        for (i in a.indices) {
            val d = abs(a[i] - b[i])
            sum += d
            worst = max(worst, d)
        }
        return sum.toDouble() / a.size <= MAX_MEAN_DIFF && worst <= MAX_CELL_DIFF
    }

    private fun cells(p: Pixels): IntArray {
        val out = IntArray(GRID * GRID * 3)
        for (gy in 0 until GRID) for (gx in 0 until GRID) {
            val x0 = gx * p.width / GRID
            val x1 = (gx + 1) * p.width / GRID
            val y0 = gy * p.height / GRID
            val y1 = (gy + 1) * p.height / GRID
            var r = 0L
            var g = 0L
            var b = 0L
            for (y in y0 until y1) for (x in x0 until x1) {
                val c = p.argb[y * p.width + x]
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
            val n = ((x1 - x0) * (y1 - y0)).coerceAtLeast(1)
            val i = (gy * GRID + gx) * 3
            out[i] = (r / n).toInt()
            out[i + 1] = (g / n).toInt()
            out[i + 2] = (b / n).toInt()
        }
        return out
    }
}
