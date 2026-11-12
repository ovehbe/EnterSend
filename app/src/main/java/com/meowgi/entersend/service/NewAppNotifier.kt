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
import com.meowgi.entersend.data.PromptPrefs
import com.meowgi.entersend.ui.PromptActivity

/**
 * Prompts for launcher-visible non-system apps that are still **undecided**.
 *
 * Root causes of missed prompts (fixed here):
 * 1. Once-per-day [AppRule.lastAskedDay] throttle burned the slot after one show.
 * 2. IMPORTANCE_DEFAULT often failed to heads-up on Android 14.
 * 3. Missing POST_NOTIFICATIONS / blocked channel failed silently with no fallback.
 * 4. Same-package WINDOW_STATE storms would either spam or, with day-throttle, never retry.
 *
 * Policy: prompt on every **new foreground visit** while undecided. Session dedupe blocks
 * intra-app activity spam; leaving the package clears the session so the next open asks again.
 * Snooze / don't-ask / Off / taught / pass-through / global-off / system apps stop prompts.
 */
class NewAppNotifier(
    private val context: Context,
    private val store: RuleStore,
    private val today: () -> Long,
    private val policy: SystemAppPolicy = SystemAppPolicy(context),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val banner: PromptBanner? = null,
) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    /** Package we already prompted during the current foreground stay. */
    private var promptedThisVisit: String? = null

    /** Last outcome for tests. */
    var lastResult: Result? = null
        private set

    enum class Result { NOTIFIED, BANNER, SKIPPED }

    /** Call when the foreground package changes away from [pkg] so the next open can prompt. */
    fun onPackageLeft(pkg: String) {
        if (promptedThisVisit == pkg) promptedThisVisit = null
        banner?.hide()
    }

    /**
     * @return true if a notification or banner was shown.
     */
    fun onAppSeen(pkg: String, rule: AppRule): Boolean {
        if (!shouldPrompt(pkg, rule)) {
            lastResult = Result.SKIPPED
            return false
        }
        if (pkg == promptedThisVisit) {
            lastResult = Result.SKIPPED
            return false
        }

        ensureChannel()
        val label = appLabel(pkg)
        val posted = canPostNotifications() && postNotification(pkg, label)
        if (posted) {
            promptedThisVisit = pkg
            // Bookkeeping only — does NOT suppress future opens.
            store.markAsked(pkg, today())
            lastResult = Result.NOTIFIED
            return true
        }

        // Fallback: accessibility overlay banner when notifications are denied/blocked.
        val b = banner
        if (b != null) {
            b.show(
                pkg,
                context.getString(R.string.teach_notify_title, label),
                context.getString(R.string.teach_notify_text),
            ) { openPrompt(pkg) }
            promptedThisVisit = pkg
            store.markAsked(pkg, today())
            lastResult = Result.BANNER
            return true
        }
        lastResult = Result.SKIPPED
        return false
    }

    fun shouldPrompt(pkg: String, rule: AppRule): Boolean {
        if (pkg == context.packageName) return false
        if (!PromptPrefs.promptsEnabled(context)) return false
        if (pkg in AppLists.NEVER || pkg in AppLists.NATIVE_ENTER) return false
        if (!policy.mayPrompt(pkg)) return false
        if (rule.teachDeclined || rule.isTaught) return false
        val mode = policy.resolveMode(rule)
        if (mode == Mode.OFF || mode == Mode.PASS_THROUGH) return false
        if (PromptPrefs.isSnoozed(context, pkg, clock())) return false
        return true
    }

    fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!nm.areNotificationsEnabled()) return false
        val ch = nm.getNotificationChannel(CHANNEL)
        if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) return false
        return true
    }

    /** @return true if delivered to the system. */
    fun postNotification(pkg: String, label: String = appLabel(pkg)): Boolean {
        if (!canPostNotifications()) return false
        ensureChannel()
        val open = promptPending(pkg, requestCode(pkg, 0))
        val teach = action(pkg, NotifyActionReceiver.ACTION_TEACH, requestCode(pkg, 1))
        val later = action(pkg, NotifyActionReceiver.ACTION_LATER, requestCode(pkg, 2))
        val off = action(pkg, NotifyActionReceiver.ACTION_OFF, requestCode(pkg, 3))
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.teach_notify_title, label))
            .setContentText(context.getString(R.string.teach_notify_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.action_teach), teach).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.action_remind_later), later).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.action_off_for_app), off).build())
            .build()
        return try {
            // Tag + id: one live notification per package.
            nm.notify(TAG, notificationId(pkg), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** Kept for older call sites / tests. */
    fun post(pkg: String): Boolean = postNotification(pkg)

    fun cancel(pkg: String) {
        nm.cancel(TAG, notificationId(pkg))
        banner?.hide()
    }

    fun openPrompt(pkg: String) {
        context.startActivity(
            Intent(context, PromptActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(NotifyActionReceiver.EXTRA_PACKAGE, pkg),
        )
    }

    private fun ensureChannel() {
        val ch = NotificationChannel(
            CHANNEL,
            context.getString(R.string.channel_teach),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_teach_desc)
            enableVibration(true)
            setShowBadge(true)
        }
        nm.createNotificationChannel(ch)
    }

    private fun promptPending(pkg: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, PromptActivity::class.java)
            .putExtra(NotifyActionReceiver.EXTRA_PACKAGE, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

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
        /** New high-importance channel (old DEFAULT channel can't be upgraded in-place). */
        const val CHANNEL = "teach_prompt_heads"
        const val TAG = "teach"
        fun notificationId(pkg: String) = 1000 + (pkg.hashCode() and 0x0FFF)
        fun requestCode(pkg: String, slot: Int) = (pkg.hashCode() xor (slot * 31)) and 0x7FFFFFFF
    }
}
