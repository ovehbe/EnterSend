package com.ovehbe.entersend.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CropMatchTest {
    private val bg = 0xFFFFFFFF.toInt()
    private val ink = 0xFF128C7E.toInt()

    /** A 48x48 crop with a filled shape drawn by [inside]. */
    private fun icon(inside: (Int, Int) -> Boolean) =
        Pixels(48, 48, IntArray(48 * 48) { i -> if (inside(i % 48, i / 48)) ink else bg })

    private val arrow = icon { x, y -> x in 10..38 && y in 24 - (x - 10) / 2..24 + (x - 10) / 2 }
    private val mic = icon { x, y -> x in 18..30 && y in 6..34 || y in 38..42 && x in 14..34 }

    @Test fun identicalCropsMatch() = assertTrue(CropMatch.matches(arrow, arrow))

    @Test fun compressionNoiseStillMatches() {
        val r = Random(1)
        val noisy = Pixels(48, 48, arrow.argb.map { c ->
            val d = r.nextInt(-4, 5)
            val ch = { s: Int -> (((c shr s) and 0xFF) + d).coerceIn(0, 255) shl s }
            (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }.toIntArray())
        assertTrue(CropMatch.matches(arrow, noisy))
    }

    @Test fun differentIconDoesNotMatch() = assertFalse(CropMatch.matches(arrow, mic))

    @Test fun buttonGoneDoesNotMatch() = assertFalse(CropMatch.matches(arrow, icon { _, _ -> false }))

    @Test fun differentSizeDoesNotMatch() =
        assertFalse(CropMatch.matches(arrow, Pixels(40, 48, IntArray(40 * 48) { bg })))
}
