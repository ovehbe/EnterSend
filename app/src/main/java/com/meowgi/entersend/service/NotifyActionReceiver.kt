package com.meowgi.entersend.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.ui.MainActivity
import java.time.LocalDate

/**
 * Handles actions from the "Teach Enter for <app>?" notification.
 * The package is always read from [EXTRA_PACKAGE]; each package uses its own PendingIntent request code.
 */
class NotifyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val app = context.applicationContext as EnterSendApp
        val service = EnterSendService.instance
        // Cancel this package's teach notification (id is deterministic).
        context.getSystemService(android.app.NotificationManager::class.java)
            .cancel(NewAppNotifier.notificationId(pkg))

        when (intent.action) {
            ACTION_OFF -> {
                if (service?.store != null) {
                    service.store!!.setOff(pkg)
                } else {
                    app.io.execute {
                        app.db.rules().ensure(pkg)
                        app.db.rules().setOff(pkg)
                    }
                }
            }
            ACTION_TEACH -> {
                if (service?.teach != null) {
                    service.teach!!.startTeaching(pkg)
                    context.packageManager.getLaunchIntentForPackage(pkg)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let { context.startActivity(it) }
                } else {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(EXTRA_PACKAGE, pkg),
                    )
                }
            }
        }
    }

    companion object {
        const val ACTION_TEACH = "com.meowgi.entersend.action.TEACH"
        const val ACTION_OFF = "com.meowgi.entersend.action.OFF"
        const val EXTRA_PACKAGE = "package"
    }
}
