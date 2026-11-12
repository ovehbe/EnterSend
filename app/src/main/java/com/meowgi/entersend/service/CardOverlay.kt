package com.meowgi.entersend.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.meowgi.entersend.R
import com.meowgi.entersend.core.PopupEdge
import com.meowgi.entersend.core.ScreenCoords

/**
 * Draws cards / the teach capture overlay as TYPE_ACCESSIBILITY_OVERLAY windows.
 *
 * Capture is full-bleed so raw screen pixels == view layout space. Marker margins use those
 * raw coordinates directly (centred); never subtract getLocationOnScreen.
 * Instruction / Done bars sit at the top by default (bottom only when [PopupEdge.BOTTOM]).
 */
class CardOverlay(private val service: AccessibilityService) : Overlay {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var tapListener: ((Int, Int) -> Unit)? = null

    /** Last screen-pixel tap delivered to [tapListener] — for tests / debug logs. */
    var lastRawTap: Pair<Int, Int>? = null
        private set

    /** Last marker centre in screen pixels (same numbers used for margins + radius). */
    var lastMarkerScreen: Pair<Int, Int>? = null
        private set

    /** Last marker FrameLayout margins after placement — for tests. */
    var lastMarkerMargins: Pair<Int, Int>? = null
        private set

    /** Last debug readout shown on the Save card. */
    var lastDebugLine: String? = null
        private set

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

        val edge = when (card) {
            is Card.Ask -> card.edge
            is Card.TeachingBar -> card.edge
            is Card.Teaching -> card.edge
            is Card.Saved -> card.edge
            is Card.Confirm -> card.edge
            is Card.Capture -> error("use showCapture")
        }

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
            gravity = if (edge == PopupEdge.BOTTOM) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            if (edge == PopupEdge.TOP) {
                y = statusBarInset()
            }
        }
        add(v, lp)
    }

    private fun showCapture(card: Card.Capture, onChoice: (Choice) -> Unit) {
        val ctx = ContextThemeWrapper(service, R.style.Theme_EnterSend_Overlay)
        val root = FrameLayout(ctx)
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val (sx, sy) = ScreenCoords.fromTouch(e)
                lastRawTap = sx to sy
                android.util.Log.d(TAG, "tap raw=($sx,$sy) viewLocal=(${e.x.toInt()},${e.y.toInt()})")
                tapListener?.invoke(sx, sy)
            }
            true
        }
        root.isFocusableInTouchMode = false

        if (card.markerX != null && card.markerY != null) {
            val (marginX, marginY) = ScreenCoords.markerMargins(card.markerX, card.markerY)
            val marker = View(ctx).apply {
                setBackgroundColor(0x88FF9800.toInt())
                layoutParams = FrameLayout.LayoutParams(
                    ScreenCoords.MARKER_SIZE_PX,
                    ScreenCoords.MARKER_SIZE_PX,
                ).apply {
                    leftMargin = marginX
                    topMargin = marginY
                    // Explicit gravity so margins are from the screen top-left of the full-bleed root.
                    gravity = Gravity.TOP or Gravity.START
                }
            }
            root.addView(marker)
            lastMarkerMargins = marginX to marginY
            lastMarkerScreen = card.markerX to card.markerY

            val bar = LayoutInflater.from(ctx).inflate(R.layout.overlay_card, root, false)
            bar.findViewById<TextView>(R.id.card_title).setText(R.string.teach_marker_title)
            val keys = bar.findViewById<TextView>(R.id.card_keys)
            val status = card.status ?: ctx.getString(R.string.teach_capture_keys)
            // Debug readout filled after attach (needs real locationOnScreen / metrics).
            keys.text = status
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
            val barGravity = if (card.barEdge == PopupEdge.BOTTOM) Gravity.BOTTOM else Gravity.TOP
            root.addView(
                bar,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    barGravity,
                ),
            )
            root.post {
                val debug = buildDebugLine(root, card.markerX, card.markerY)
                lastDebugLine = debug
                keys.text = "$status\n$debug"
                keys.textSize = 10f
                android.util.Log.d(TAG, debug)
            }
        } else if (card.status != null) {
            val hint = TextView(ctx).apply {
                text = card.status
                setBackgroundColor(0xCC000000.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(32, 24, 32, 24)
            }
            val hintGravity = if (card.barEdge == PopupEdge.BOTTOM) Gravity.BOTTOM else Gravity.TOP
            root.addView(
                hint,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    hintGravity,
                ),
            )
        }

        add(root, fullBleedLp())
    }

    /** Compact line for the next device test — remove once coords are confirmed. */
    private fun buildDebugLine(root: View, markerX: Int, markerY: Int): String {
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val raw = lastRawTap
        val dm = displaySize()
        val insets = systemBarInsets()
        val density = service.resources.displayMetrics.density
        return "raw=${raw?.first},${raw?.second} mk=$markerX,$markerY loc=${loc[0]},${loc[1]} " +
            "disp=${dm.first}x${dm.second} insets=${insets[0]},${insets[1]},${insets[2]},${insets[3]} dens=$density"
    }

    private fun displaySize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val bounds = wm.maximumWindowMetrics.bounds
                return bounds.width() to bounds.height()
            } catch (_: RuntimeException) {
            }
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun systemBarInsets(): IntArray {
        val out = intArrayOf(0, 0, 0, 0) // top, right, bottom, left
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val insets = wm.maximumWindowMetrics.windowInsets
                    .getInsets(WindowInsets.Type.systemBars())
                out[0] = insets.top
                out[1] = insets.right
                out[2] = insets.bottom
                out[3] = insets.left
                return out
            } catch (_: RuntimeException) {
            }
        }
        out[0] = statusBarInset()
        return out
    }

    private fun fullBleedLp() = baseLp(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
        flags = flags or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun baseLp(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    )

    private fun statusBarInset(): Int = try {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) service.resources.getDimensionPixelSize(id) else 0
    } catch (_: RuntimeException) {
        0
    }

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
        lastRawTap = null
        lastMarkerScreen = null
        lastMarkerMargins = null
        lastDebugLine = null
    }

    companion object {
        private const val TAG = "EnterSend"
    }
}
