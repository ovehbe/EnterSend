package com.meowgi.entersend.service

import com.meowgi.entersend.core.Pixels

sealed interface Card {
    /** Legacy ask card (still used as a fallback UI in tests). */
    data class Ask(val reteach: Boolean) : Card

    /** Step-2 instruction bar (no full-screen capture yet). */
    data class TeachingBar(val message: String) : Card

    /**
     * Full-screen capture overlay. When [markerX] is null the overlay is fully transparent and
     * waiting for a tap; otherwise a marker is drawn and Done/Retry/Cancel are offered.
     */
    data class Capture(val markerX: Int?, val markerY: Int?, val status: String? = null) : Card

    /** Brief "Saved" confirmation after a node rule is learned. */
    data class Saved(val label: String?) : Card

    /** Kept for older tests that still show a confirm crop card. */
    class Confirm(val crop: Pixels?, val label: String?) : Card

    /** @deprecated use TeachingBar */
    data class Teaching(val status: String? = null) : Card
}

enum class Choice { YES, NO, CLOSE, RETRY }

/** Non-focusable cards / capture overlay. [CardOverlay] in production, a fake in tests. */
interface Overlay {
    fun show(card: Card, onChoice: (Choice) -> Unit)
    /** Receives screen coordinates for taps on a transparent [Card.Capture]. */
    fun setTapListener(listener: ((x: Int, y: Int) -> Unit)?)
    fun hide()
}
