package com.meowgi.entersend.service

import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.Pixels
import com.meowgi.entersend.core.PopupEdge

sealed interface Card {
    /** Legacy ask card (still used as a fallback UI in tests). */
    data class Ask(val reteach: Boolean, val edge: PopupEdge = PopupEdge.TOP) : Card

    /** Step-2 instruction bar (no full-screen capture yet). Never covers the bottom by default. */
    data class TeachingBar(
        val message: String,
        val edge: PopupEdge = PopupEdge.TOP,
    ) : Card

    /**
     * Full-screen capture overlay. When [markerX] is null the overlay is fully transparent and
     * waiting for a tap; otherwise a marker is drawn and Done/Retry/Cancel are offered on [barEdge].
     * [markerX]/[markerY] are screen pixels.
     */
    data class Capture(
        val markerX: Int?,
        val markerY: Int?,
        val status: String? = null,
        val barEdge: PopupEdge = PopupEdge.TOP,
        val fieldBounds: Box? = null,
    ) : Card

    /** Brief "Saved" confirmation after a node rule is learned. */
    data class Saved(val label: String?, val edge: PopupEdge = PopupEdge.TOP) : Card

    /** Kept for older tests that still show a confirm crop card. */
    class Confirm(val crop: Pixels?, val label: String?, val edge: PopupEdge = PopupEdge.TOP) : Card

    /** @deprecated use TeachingBar */
    data class Teaching(val status: String? = null, val edge: PopupEdge = PopupEdge.TOP) : Card
}

enum class Choice { YES, NO, CLOSE, RETRY }

/** Non-focusable cards / capture overlay. [CardOverlay] in production, a fake in tests. */
interface Overlay {
    fun show(card: Card, onChoice: (Choice) -> Unit)
    /** Receives screen-pixel coordinates for taps on a transparent [Card.Capture]. */
    fun setTapListener(listener: ((x: Int, y: Int) -> Unit)?)
    fun hide()
}
