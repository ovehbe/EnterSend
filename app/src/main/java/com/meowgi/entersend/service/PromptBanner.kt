package com.meowgi.entersend.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.meowgi.entersend.R

/** Test seam for the accessibility overlay fallback when notifications can't post. */
interface PromptBanner {
    fun show(pkg: String, title: String, body: String, onOpen: () -> Unit)
    fun hide()
    /** Packages currently showing a banner (for tests). */
    fun showing(): Set<String>
}

/**
 * Small top banner via [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY].
 * Auto-dismisses after [AUTO_DISMISS_MS]; tap opens the full prompt.
 */
class A11yPromptBanner(
    private val service: AccessibilityService,
    private val main: Handler = Handler(Looper.getMainLooper()),
) : PromptBanner {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: android.view.View? = null
    private var pkg: String? = null
    private val dismiss = Runnable { hide() }

    override fun show(pkg: String, title: String, body: String, onOpen: () -> Unit) {
        hide()
        this.pkg = pkg
        val v = LayoutInflater.from(service).inflate(R.layout.overlay_prompt_banner, null)
        v.findViewById<TextView>(R.id.banner_title).text = title
        v.findViewById<TextView>(R.id.banner_body).text = body
        val open = {
            hide()
            onOpen()
        }
        v.setOnClickListener { open() }
        v.findViewById<Button>(R.id.banner_open).setOnClickListener { open() }
        v.findViewById<Button>(R.id.banner_dismiss).setOnClickListener { hide() }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        wm.addView(v, lp)
        view = v
        main.removeCallbacks(dismiss)
        main.postDelayed(dismiss, AUTO_DISMISS_MS)
    }

    override fun hide() {
        main.removeCallbacks(dismiss)
        view?.let {
            try {
                wm.removeView(it)
            } catch (_: RuntimeException) {
            }
        }
        view = null
        pkg = null
    }

    override fun showing(): Set<String> = pkg?.let { setOf(it) } ?: emptySet()

    companion object {
        const val AUTO_DISMISS_MS = 10_000L
    }
}

/** In-memory banner for unit tests. */
class FakePromptBanner : PromptBanner {
    data class Shown(val pkg: String, val title: String, val body: String)
    var last: Shown? = null
        private set
    var openCount = 0
        private set
    private var onOpen: (() -> Unit)? = null

    override fun show(pkg: String, title: String, body: String, onOpen: () -> Unit) {
        last = Shown(pkg, title, body)
        this.onOpen = onOpen
    }

    override fun hide() {
        last = null
        onOpen = null
    }

    override fun showing(): Set<String> = last?.pkg?.let { setOf(it) } ?: emptySet()

    fun tap() {
        openCount++
        onOpen?.invoke()
    }
}
