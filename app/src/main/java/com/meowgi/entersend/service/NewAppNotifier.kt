package com.meowgi.entersend.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.meowgi.entersend.R
import com.meowgi.entersend.core.AppLists
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.Mode

/**
 * One "Teach Enter for <app>?" notification per package per day for apps with no rule and no
 * previous answer. Never for pass-through / Off / declined / blocked packages.
 */
class NewAppNotifier(
    private val context: Context,
    private val store: RuleStore,
    private val today: () -> Long,
) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    fun onAppSeen(pkg: String, rule: AppRule) {
        if (pkg == context.packageName) return
        if (pkg in AppLists.NEVER || pkg in AppLists.NATIVE_ENTER) return
        if (rule.teachDeclined || rule.isTaught) return
        if (rule.effectiveMode == Mode.OFF || rule.effectiveMode == Mode.PASS_THROUGH) return
        if (rule.lastAskedDay == today()) return
        // Password fields are handled by not calling this from a password focus; package-level gate only.

        store.markAsked(pkg, today())
        post(pkg)
    }

    fun post(pkg: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return // degrade quietly
        }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_teach), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val label = appLabel(pkg)
        val teach = action(pkg, NotifyActionReceiver.ACTION_TEACH, requestCode(pkg, 1))
        val off = action(pkg, NotifyActionReceiver.ACTION_OFF, requestCode(pkg, 2))
        val content = action(pkg, NotifyActionReceiver.ACTION_TEACH, requestCode(pkg, 0))
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.teach_notify_title, label))
            .setContentText(context.getString(R.string.teach_notify_text))
            .setContentIntent(content)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.action_teach), teach).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.action_off_for_app), off).build())
            .build()
        try {
            nm.notify(notificationId(pkg), notification)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(pkg: String) = nm.cancel(notificationId(pkg))

    private fun action(pkg: String, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, NotifyActionReceiver::class.java)
            .setAction(action)
            .putExtra(NotifyActionReceiver.EXTRA_PACKAGE, pkg)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun appLabel(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    companion object {
        const val CHANNEL = "teach_prompt"
        fun notificationId(pkg: String) = 1000 + (pkg.hashCode() and 0x0FFF)
        fun requestCode(pkg: String, slot: Int) = (pkg.hashCode() xor (slot * 31)) and 0x7FFFFFFF
    }
}
