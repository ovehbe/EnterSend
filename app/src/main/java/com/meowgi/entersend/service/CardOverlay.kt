package com.meowgi.entersend.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.meowgi.entersend.R

/** Draws cards as accessibility overlays that never take focus, so typing keeps going to the app. */
class CardOverlay(private val service: AccessibilityService) : Overlay {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null

    override fun show(card: Card, onChoice: (Choice) -> Unit) {
        hide()
        val ctx = ContextThemeWrapper(service, R.style.Theme_EnterSend_Overlay)
        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_card, null)
        val title = v.findViewById<TextView>(R.id.card_title)
        val keys = v.findViewById<TextView>(R.id.card_keys)
        val image = v.findViewById<ImageView>(R.id.card_image)
        val yes = v.findViewById<Button>(R.id.card_yes)
        val no = v.findViewById<Button>(R.id.card_no)
        val close = v.findViewById<Button>(R.id.card_close)
        yes.setOnClickListener { onChoice(Choice.YES) }
        no.setOnClickListener { onChoice(Choice.NO) }
        close.setOnClickListener { onChoice(Choice.CLOSE) }

        when (card) {
            is Card.Ask -> {
                title.setText(if (card.reteach) R.string.card_reteach_title else R.string.card_ask_title)
                keys.setText(R.string.card_ask_keys)
                close.setText(R.string.later)
            }
            is Card.Teaching -> {
                title.setText(R.string.card_teaching_title)
                keys.text = card.status ?: ctx.getString(R.string.card_teaching_keys)
                yes.visibility = View.GONE
                no.visibility = View.GONE
                close.setText(R.string.cancel)
            }
            is Card.Confirm -> {
                title.setText(R.string.card_confirm_title)
                keys.setText(R.string.card_confirm_keys)
                close.setText(R.string.cancel)
                card.crop?.let {
                    image.setImageBitmap(Bitmap.createBitmap(it.argb, it.width, it.height, Bitmap.Config.ARGB_8888))
                    image.visibility = View.VISIBLE
                }
                if (card.crop == null && card.label != null) {
                    title.text = ctx.getString(R.string.card_confirm_title_label, card.label)
                }
            }
        }

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // The teaching bar sits at the top so it never covers (or gets screenshotted over) the send button.
            gravity = if (card is Card.Teaching) Gravity.TOP else Gravity.BOTTOM
        }
        try {
            wm.addView(v, lp)
            view = v
        } catch (_: RuntimeException) {
            // BadTokenException etc. — leave teaching state so the user can Esc-cancel; don't kill the service.
            view = null
        }
    }

    override fun hide() {
        try {
            view?.let { wm.removeView(it) }
        } catch (_: RuntimeException) {
        }
        view = null
    }
}
