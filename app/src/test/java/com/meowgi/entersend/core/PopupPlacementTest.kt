package com.meowgi.entersend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupPlacementTest {
    private val screen = 720
    private val status = 48
    private val popupH = 120

    @Test fun defaultTopWhenFieldIsInBottomHalf() {
        val field = Box(10, 600, 600, 680) // bottom half on 720
        val r = PopupPlacement.place(screen, status, screen, popupH, field, null)
        assertEquals(PopupEdge.TOP, r.edge)
        assertEquals(status, r.topPx)
        val rect = PopupPlacement.rect(r.edge, status, screen, popupH)
        assertFalse(overlaps(rect, field))
    }

    @Test fun bottomWhenFieldIsInTopHalf() {
        val field = Box(10, 80, 600, 160) // top half
        val keyboardTop = 500
        val r = PopupPlacement.place(screen, status, keyboardTop, popupH, field, null)
        assertEquals(PopupEdge.BOTTOM, r.edge)
        val rect = PopupPlacement.rect(r.edge, status, keyboardTop, popupH)
        assertEquals(keyboardTop - popupH, rect.top)
        assertFalse(overlaps(rect, field))
    }

    @Test fun neverOverlapsSavedTapSpot_flipsAwayFromCollision() {
        // Prefer top (field in bottom half), but tap spot sits under the top bar → flip to bottom.
        // Field stays clear of the bottom popup band so BOTTOM is a valid escape.
        val field = Box(10, 400, 600, 480)
        val tap = 100 to (status + 40) // inside the top popup band
        val r = PopupPlacement.place(screen, status, screen, popupH, field, tap)
        assertEquals(PopupEdge.BOTTOM, r.edge)
        val rect = PopupPlacement.rect(r.edge, status, screen, popupH)
        assertFalse(rect.contains(tap.first, tap.second))
        assertFalse(overlaps(rect, field))
    }

    @Test fun topBarStaysBelowStatusBar() {
        val r = PopupPlacement.place(screen, status, screen, popupH, Box(10, 500, 100, 560), null)
        assertTrue(r.topPx >= status)
        assertEquals(PopupEdge.TOP, r.edge)
    }

    private fun overlaps(a: Box, b: Box) =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
}
