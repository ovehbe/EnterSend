package com.meowgi.entersend.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent as ViewKeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.meowgi.entersend.R

/** Draws cards / the teach capture overlay as TYPE_ACCESSIBILITY_OVERLAY windows. */
class CardOverlay(private val service: AccessibilityService) : Overlay {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var tapListener: ((Int, Int) -> Unit)? = null

    override fun setTapListener(listener: ((x: Int, y: Int) -> Unit)?) {
        tapListener = listener
    }

    override fun show(card: Card, onChoice: (Choice) -> Unit) {
        hide()
        when (card) {
            is Card.Capture -> showCapture(card, onChoice)
            else -> showCard(card, onChoice)
        }
    }

    private fun showCard(card: Card, onChoice: (Choice) -> Unit) {
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
            is Card.TeachingBar -> {
                title.text = card.message
                keys.setText(R.string.card_teaching_keys)
                yes.visibility = View.GONE
                no.visibility = View.GONE
                close.setText(R.string.cancel)
            }
            is Card.Teaching -> {
                title.setText(R.string.card_teaching_title)
                keys.text = card.status ?: ctx.getString(R.string.card_teaching_keys)
                yes.visibility = View.GONE
                no.visibility = View.GONE
                close.setText(R.string.cancel)
            }
            is Card.Saved -> {
                title.setText(R.string.teach_saved)
                keys.text = card.label ?: ""
                yes.visibility = View.GONE
                no.visibility = View.GONE
                close.visibility = View.GONE
            }
            is Card.Confirm -> {
                title.setText(R.string.card_confirm_title)
                keys.setText(R.string.card_confirm_keys)
                close.setText(R.string.cancel)
                card.crop?.let {
                    image.setImageBitmap(Bitmap.createBitmap(it.argb, it.width, it.height, Bitmap.Config.ARGB_8888))
                    image.visibility = View.VISIBLE
                }
            }
            is Card.Capture -> error("use showCapture")
        }

        val lp = baseLp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        add(v, lp)
    }

    private fun showCapture(card: Card.Capture, onChoice: (Choice) -> Unit) {
        val ctx = ContextThemeWrapper(service, R.style.Theme_EnterSend_Overlay)
        val root = FrameLayout(ctx)
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                tapListener?.invoke(e.rawX.toInt(), e.rawY.toInt())
            }
            true // consume so the app does not send
        }
        // Esc/Back via key listener if the overlay ever takes focus; service onKeyEvent is primary.
        root.isFocusableInTouchMode = false

        if (card.markerX != null && card.markerY != null) {
            val marker = View(ctx).apply {
                setBackgroundColor(0x88FF9800.toInt())
                layoutParams = FrameLayout.LayoutParams(48, 48).apply {
                    leftMargin = card.markerX - 24
                    topMargin = card.markerY - 24
                }
            }
            root.addView(marker)
            val bar = LayoutInflater.from(ctx).inflate(R.layout.overlay_card, root, false)
            bar.findViewById<TextView>(R.id.card_title).setText(R.string.teach_marker_title)
            bar.findViewById<TextView>(R.id.card_keys).text =
                card.status ?: ctx.getString(R.string.teach_capture_keys)
            bar.findViewById<Button>(R.id.card_yes).apply {
                setText(R.string.done)
                setOnClickListener { onChoice(Choice.YES) }
            }
            bar.findViewById<Button>(R.id.card_no).apply {
                setText(R.string.retry)
                setOnClickListener { onChoice(Choice.RETRY) }
            }
            bar.findViewById<Button>(R.id.card_close).apply {
                setText(R.string.cancel)
                setOnClickListener { onChoice(Choice.CLOSE) }
            }
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
            root.addView(bar, params)
        } else if (card.status != null) {
            val hint = TextView(ctx).apply {
                text = card.status
                setBackgroundColor(0xCC000000.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(32, 24, 32, 24)
            }
            root.addView(
                hint,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ),
            )
        }

        val lp = baseLp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.TOP
            // Consumes touches; not focusable so typing still reaches the app when not covered —
            // but this is full-screen, so touches are ours. Keys still go through the a11y filter.
        }
        add(root, lp)
    }

    private fun baseLp(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    )

    private fun add(v: View, lp: WindowManager.LayoutParams) {
        try {
            wm.addView(v, lp)
            view = v
        } catch (_: RuntimeException) {
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
