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
    const val MAX_MEAN_DIFF = 12.0
    const val MAX_CELL_DIFF = 48

    /** Detailed score for Enter diagnostics; [matches] stays the production gate. */
    data class Score(
        val meanDiff: Double,
        val worstCell: Int,
        val sizeOk: Boolean,
        val dims: String,
        val pass: Boolean,
    ) {
        fun line(): String =
            "cropMatch mean=${"%.2f".format(meanDiff)} threshMean=$MAX_MEAN_DIFF " +
                "worst=$worstCell threshWorst=$MAX_CELL_DIFF sizeOk=$sizeOk dims=$dims " +
                if (pass) "PASS" else "FAIL"
    }

    fun score(saved: Pixels, live: Pixels): Score {
        val dims = "${saved.width}x${saved.height}->${live.width}x${live.height}"
        if (saved.width != live.width || saved.height != live.height) {
            return Score(Double.POSITIVE_INFINITY, Int.MAX_VALUE, sizeOk = false, dims = dims, pass = false)
        }
        if (saved.width < GRID || saved.height < GRID) {
            return Score(Double.POSITIVE_INFINITY, Int.MAX_VALUE, sizeOk = false, dims = dims, pass = false)
        }
        val a = cells(saved)
        val b = cells(live)
        var sum = 0L
        var worst = 0
        for (i in a.indices) {
            val d = abs(a[i] - b[i])
            sum += d
            worst = max(worst, d)
        }
        val mean = sum.toDouble() / a.size
        val pass = mean <= MAX_MEAN_DIFF && worst <= MAX_CELL_DIFF
        return Score(mean, worst, sizeOk = true, dims = dims, pass = pass)
    }

    fun matches(saved: Pixels, live: Pixels): Boolean = score(saved, live).pass

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
