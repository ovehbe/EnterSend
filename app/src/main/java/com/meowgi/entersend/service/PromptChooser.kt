package com.meowgi.entersend.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.R
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.Mode
import com.meowgi.entersend.data.PromptPrefs

/** Test seam for the accessibility overlay chooser. */
interface PromptChooser {
    fun show(pkg: String)
    fun hide()
    fun isShowing(): Boolean
    fun onKey(event: KeyEvent): Boolean
    /** Packages currently showing (for tests). */
    fun showing(): Set<String>
}

/**
 * Compact top chooser via [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY].
 * Stays over the current app; Back/Esc dismisses. Teach starts in-place via [TeachFlow].
 */
class A11yPromptChooser(
    private val service: AccessibilityService,
    private val storeProvider: () -> RuleStore?,
    private val teachProvider: () -> TeachFlow?,
    private val main: Handler = Handler(Looper.getMainLooper()),
) : PromptChooser {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: android.view.View? = null
    private var pkg: String? = null

    override fun show(pkg: String) {
        hide()
        this.pkg = pkg
        val v = LayoutInflater.from(service).inflate(R.layout.overlay_prompt_chooser, null)
        val label = appLabel(pkg)
        v.findViewById<TextView>(R.id.chooser_title).text =
            service.getString(R.string.teach_notify_title, label)
        v.findViewById<Button>(R.id.chooser_teach).setOnClickListener { teach(pkg) }
        v.findViewById<Button>(R.id.chooser_later).setOnClickListener { snooze(pkg) }
        v.findViewById<Button>(R.id.chooser_off).setOnClickListener { off(pkg) }
        v.findViewById<Button>(R.id.chooser_dont_ask).setOnClickListener { dontAsk(pkg) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        wm.addView(v, lp)
        view = v
        v.findViewById<Button>(R.id.chooser_teach).requestFocus()
        service.getSystemService(android.app.NotificationManager::class.java)
            .cancel(NewAppNotifier.TAG, NewAppNotifier.notificationId(pkg))
    }

    override fun hide() {
        view?.let {
            try {
                wm.removeView(it)
            } catch (_: RuntimeException) {
            }
        }
        view = null
        pkg = null
    }

    override fun isShowing(): Boolean = view != null

    override fun showing(): Set<String> = pkg?.let { setOf(it) } ?: emptySet()

    override fun onKey(event: KeyEvent): Boolean {
        if (!isShowing() || event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                hide()
                true
            }
            else -> false
        }
    }

    private fun teach(pkg: String) {
        hide()
        teachProvider()?.startTeaching(pkg)
    }

    private fun snooze(pkg: String) {
        PromptPrefs.snooze(service, pkg, System.currentTimeMillis() + PromptPrefs.SNOOZE_MS)
        hide()
    }

    private fun off(pkg: String) {
        val store = storeProvider()
        if (store != null) {
            store.setOff(pkg)
        } else {
            val app = service.application as EnterSendApp
            app.io.execute {
                app.db.rules().ensure(pkg)
                app.db.rules().setOff(pkg)
            }
        }
        hide()
    }

    private fun dontAsk(pkg: String) {
        val store = storeProvider()
        if (store != null) {
            store.decline(pkg)
        } else {
            val app = service.application as EnterSendApp
            app.io.execute {
                app.db.rules().ensure(pkg)
                app.db.rules().setDeclined(pkg)
            }
        }
        hide()
    }

    private fun appLabel(pkg: String): String = try {
        val pm = service.packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (_: Exception) {
        pkg
    }
}

/** In-memory chooser for unit tests. */
class FakePromptChooser : PromptChooser {
    var lastPkg: String? = null
        private set
    var teachCount = 0
        private set
    var laterCount = 0
        private set
    var offCount = 0
        private set
    var dontAskCount = 0
        private set
    var actions: ((String, String) -> Unit)? = null

    override fun show(pkg: String) {
        lastPkg = pkg
    }

    override fun hide() {
        lastPkg = null
    }

    override fun isShowing(): Boolean = lastPkg != null

    override fun showing(): Set<String> = lastPkg?.let { setOf(it) } ?: emptySet()

    override fun onKey(event: KeyEvent): Boolean {
        if (!isShowing() || event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            hide()
            return true
        }
        return false
    }

    fun tapTeach() {
        teachCount++
        lastPkg?.let { actions?.invoke("teach", it) }
        hide()
    }

    fun tapLater() {
        laterCount++
        lastPkg?.let { actions?.invoke("later", it) }
        hide()
    }

    fun tapOff() {
        offCount++
        lastPkg?.let { actions?.invoke("off", it) }
        hide()
    }

    fun tapDontAsk() {
        dontAskCount++
        lastPkg?.let { actions?.invoke("dont-ask", it) }
        hide()
    }
}

/** Opens the overlay chooser when the service is live; otherwise MainActivity Apps sheet. */
object PromptOpen {
    fun open(context: Context, pkg: String) {
        val service = EnterSendService.instance
        if (service != null) {
            val show = Runnable { service.showPromptChooser(pkg) }
            if (Looper.myLooper() == Looper.getMainLooper()) show.run()
            else mainHandler.post(show)
            return
        }
        context.startActivity(
            android.content.Intent(context, com.meowgi.entersend.ui.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(NotifyActionReceiver.EXTRA_PACKAGE, pkg)
                .putExtra(NotifyActionReceiver.EXTRA_SHOW_ACTIONS, true),
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
}
