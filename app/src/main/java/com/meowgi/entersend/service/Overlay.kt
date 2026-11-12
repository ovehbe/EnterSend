package com.meowgi.entersend.service

import com.meowgi.entersend.core.Pixels

sealed interface Card {
    /** "Didn't find send. Teach this app?" (or the re-teach variant). */
    data class Ask(val reteach: Boolean) : Card

    /** Slim bar: "Send a message now" with Cancel. [status] is an error/hint under the title. */
    data class Teaching(val status: String? = null) : Card

    /** "Is this the send button?" with the cropped button image. */
    class Confirm(val crop: Pixels?, val label: String?) : Card
}

enum class Choice { YES, NO, CLOSE }

/** Non-focusable cards drawn over other apps. [CardOverlay] in production, a fake in tests. */
interface Overlay {
    fun show(card: Card, onChoice: (Choice) -> Unit)
    fun hide()
}
