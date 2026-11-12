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
import com.meowgi.entersend.core.SystemAppPolicy
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.Mode

/**
 * One "Teach Enter for <app>?" notification per package per day for apps with no rule and no
 * previous answer. Never for pass-through / Off / declined / blocked / system packages.
 *
 * [markAsked] runs only after a successful [post], so a missing notification permission does not
 * burn the once-per-day slot.
 */
class NewAppNotifier(
    private val context: Context,
    private val store: RuleStore,
    private val today: () -> Long,
    private val policy: SystemAppPolicy = SystemAppPolicy(context),
) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    /** @return true if a notification was posted. */
    fun onAppSeen(pkg: String, rule: AppRule): Boolean {
        if (pkg == context.packageName) return false
        if (pkg in AppLists.NEVER || pkg in AppLists.NATIVE_ENTER) return false
        if (!policy.mayPrompt(pkg)) return false
        if (rule.teachDeclined || rule.isTaught) return false
        val mode = policy.resolveMode(rule)
        if (mode == Mode.OFF || mode == Mode.PASS_THROUGH) return false
        if (rule.lastAskedDay == today()) return false
        if (!canPost()) return false
        if (!post(pkg)) return false
        store.markAsked(pkg, today())
        return true
    }

    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return true
    }

    /** @return true if the notification was delivered to the system. */
    fun post(pkg: String): Boolean {
        if (!canPost()) return false
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
        return try {
            nm.notify(notificationId(pkg), notification)
            true
        } catch (_: SecurityException) {
            false
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
