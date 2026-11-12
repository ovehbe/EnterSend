package com.meowgi.entersend.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.R
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.Mode
import com.meowgi.entersend.data.PromptPrefs
import com.meowgi.entersend.service.EnterSendService
import com.meowgi.entersend.service.NewAppNotifier
import com.meowgi.entersend.service.NotifyActionReceiver

/**
 * Compact, keyboard-navigable chooser shown when the user taps a new-app notification/banner.
 * Fits the Q25; does not steal focus from chat until this activity is open.
 */
class PromptActivity : AppCompatActivity() {
    private val app get() = application as EnterSendApp
    private lateinit var pkg: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra(NotifyActionReceiver.EXTRA_PACKAGE) ?: run {
            finish()
            return
        }
        setContentView(R.layout.activity_prompt)
        setFinishOnTouchOutside(true)

        val label = appLabel(pkg)
        findViewById<TextView>(R.id.prompt_title).text = getString(R.string.teach_notify_title, label)
        findViewById<View>(R.id.prompt_teach).setOnClickListener { teach() }
        findViewById<View>(R.id.prompt_later).setOnClickListener { snooze() }
        findViewById<View>(R.id.prompt_passthrough).setOnClickListener { setMode(Mode.PASS_THROUGH) }
        findViewById<View>(R.id.prompt_off).setOnClickListener { off() }
        findViewById<View>(R.id.prompt_dont_ask).setOnClickListener { dontAsk() }

        // Cancel any live notification / banner for this package.
        EnterSendService.instance?.let { svc ->
            // Access via public cancel on a fresh notifier is awkward; cancel by id.
            getSystemService(android.app.NotificationManager::class.java)
                .cancel(NewAppNotifier.TAG, NewAppNotifier.notificationId(pkg))
        } ?: getSystemService(android.app.NotificationManager::class.java)
            .cancel(NewAppNotifier.TAG, NewAppNotifier.notificationId(pkg))
    }

    private fun teach() {
        val service = EnterSendService.instance
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (service?.teach != null && launch != null) {
            service.teach!!.startTeaching(pkg)
            startActivity(launch)
        } else {
            Toast.makeText(this, R.string.turn_on_first, Toast.LENGTH_LONG).show()
            startActivity(
                IntentMain(pkg),
            )
        }
        finish()
    }

    private fun IntentMain(pkg: String) =
        android.content.Intent(this, MainActivity::class.java)
            .putExtra(NotifyActionReceiver.EXTRA_PACKAGE, pkg)

    private fun snooze() {
        PromptPrefs.snooze(this, pkg, System.currentTimeMillis() + PromptPrefs.SNOOZE_MS)
        Toast.makeText(this, R.string.prompt_snoozed, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun setMode(mode: Mode) {
        app.io.execute {
            val dao = app.db.rules()
            dao.ensure(pkg)
            val updated = (dao.get(pkg) ?: AppRule(pkg)).copy(mode = mode.name)
            dao.upsert(updated)
            runOnUiThread {
                EnterSendService.instance?.store?.putLocal(updated)
                finish()
            }
        }
    }

    private fun off() {
        val service = EnterSendService.instance
        if (service?.store != null) {
            service.store!!.setOff(pkg)
            finish()
        } else {
            app.io.execute {
                app.db.rules().ensure(pkg)
                app.db.rules().setOff(pkg)
                runOnUiThread { finish() }
            }
        }
    }

    private fun dontAsk() {
        val service = EnterSendService.instance
        if (service?.store != null) {
            service.store!!.decline(pkg)
            finish()
        } else {
            app.io.execute {
                app.db.rules().ensure(pkg)
                app.db.rules().setDeclined(pkg)
                runOnUiThread { finish() }
            }
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }
}
