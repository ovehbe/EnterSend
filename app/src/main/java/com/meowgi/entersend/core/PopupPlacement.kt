package com.meowgi.entersend.core

/**
 * Where EnterSend cards / bars sit on screen.
 *
 * Default: top, just below the status bar (chat fields and send live at the bottom).
 * If the focused field is in the top half, flip to the bottom — above the keyboard when
 * present. Never overlap the focused field or a saved tap spot.
 */
enum class PopupEdge { TOP, BOTTOM }

object PopupPlacement {
    data class Result(val edge: PopupEdge, val topPx: Int, val bottomPx: Int)

    /**
     * [screenHeight] and all boxes/points are in the same screen-pixel space.
     * [statusBarBottom] is the y just below the status bar; [keyboardTop] is the y of the
     * IME top (or screenHeight when the keyboard is hidden). [popupHeight] is the card height.
     */
    fun place(
        screenHeight: Int,
        statusBarBottom: Int,
        keyboardTop: Int,
        popupHeight: Int,
        field: Box?,
        tapSpot: Pair<Int, Int>?,
    ): Result {
        require(screenHeight > 0)
        val preferBottom = field != null && field.centerY < screenHeight / 2
        val order = if (preferBottom) listOf(PopupEdge.BOTTOM, PopupEdge.TOP) else listOf(PopupEdge.TOP, PopupEdge.BOTTOM)
        for (edge in order) {
            val r = rect(edge, statusBarBottom, keyboardTop, popupHeight)
            if (field != null && overlaps(r, field)) continue
            if (tapSpot != null && r.contains(tapSpot.first, tapSpot.second)) continue
            return Result(edge, r.top, screenHeight - r.bottom)
        }
        // Both collide — still honour the preferred edge so the bar stays off the usual send zone.
        val edge = order.first()
        val r = rect(edge, statusBarBottom, keyboardTop, popupHeight)
        return Result(edge, r.top, screenHeight - r.bottom)
    }

    fun rect(edge: PopupEdge, statusBarBottom: Int, keyboardTop: Int, popupHeight: Int): Box =
        when (edge) {
            PopupEdge.TOP -> {
                val top = statusBarBottom.coerceAtLeast(0)
                Box(0, top, Int.MAX_VALUE / 4, top + popupHeight)
            }
            PopupEdge.BOTTOM -> {
                val bottom = keyboardTop.coerceAtLeast(popupHeight)
                Box(0, bottom - popupHeight, Int.MAX_VALUE / 4, bottom)
            }
        }

    private fun overlaps(a: Box, b: Box): Boolean =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
}
