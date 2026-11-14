package com.meowgi.entersend.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.data.PromptPrefs
import com.meowgi.entersend.ui.MainActivity

/**
 * Handles actions from the "Teach Enter for <app>?" notification.
 * Content tap / OPEN shows the accessibility overlay chooser when the service is live;
 * otherwise opens MainActivity's Apps action sheet for that package.
 */
class NotifyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val app = context.applicationContext as EnterSendApp
        val service = EnterSendService.instance
        context.getSystemService(android.app.NotificationManager::class.java)
            .cancel(NewAppNotifier.TAG, NewAppNotifier.notificationId(pkg))

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
            ACTION_LATER -> {
                PromptPrefs.snooze(
                    context,
                    pkg,
                    System.currentTimeMillis() + PromptPrefs.SNOOZE_MS,
                )
            }
            ACTION_DONT_ASK -> {
                // Same as the popup "Don't ask again" button (RuleStore.decline / setDeclined).
                if (service?.store != null) {
                    service.store!!.decline(pkg)
                } else {
                    app.io.execute {
                        app.db.rules().ensure(pkg)
                        app.db.rules().setDeclined(pkg)
                    }
                }
            }
            ACTION_TEACH -> {
                if (service?.teach != null) {
                    service.teach!!.startTeaching(pkg)
                } else {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(EXTRA_PACKAGE, pkg)
                            .putExtra(EXTRA_SHOW_ACTIONS, true),
                    )
                }
            }
            ACTION_OPEN -> PromptOpen.open(context, pkg)
        }
    }

    companion object {
        const val ACTION_TEACH = "com.meowgi.entersend.action.TEACH"
        const val ACTION_OFF = "com.meowgi.entersend.action.OFF"
        const val ACTION_LATER = "com.meowgi.entersend.action.LATER"
        /** Notification button: same decision as the popup Don't-ask-again choice. */
        const val ACTION_DONT_ASK = "com.meowgi.entersend.action.DONT_ASK"
        const val ACTION_OPEN = "com.meowgi.entersend.action.OPEN"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_SHOW_ACTIONS = "show_actions"
    }
}
