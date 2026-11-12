package com.ovehbe.entersend

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.ovehbe.entersend.service.EnterSendService
import java.util.concurrent.TimeUnit

/** Tells the user when the accessibility service is off or was killed. */
object ServiceWatch {
    private const val CHANNEL = "service_state"
    const val NOTIFICATION_ID = 1
    private const val JOB_ID = 1

    fun isRunning() = EnterSendService.instance != null

    fun accessibilitySettings(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens EnterSend's own accessibility page, or the accessibility list if that page doesn't exist. */
    fun openAccessibilitySettings(activity: Activity) {
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(activity, EnterSendService::class.java).flattenToString())
        try {
            activity.startActivity(details)
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(accessibilitySettings())
        }
    }

    /** Shows a persistent "EnterSend is off" notification while the service isn't running. */
    fun updateNotice(context: Context, running: Boolean = isRunning()) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (running) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val fix = PendingIntent.getActivity(context, 0, accessibilitySettings(), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.service_off_title))
            .setContentText(context.getString(R.string.service_off_text))
            .setOngoing(true)
            .setContentIntent(fix)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.turn_on), fix).build())
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notification permission denied; the in-app banner still shows.
        }
    }

    /** Periodic check so a killed service is noticed even if the app is never opened. */
    fun schedule(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, CheckJob::class.java))
                .setPeriodic(TimeUnit.MINUTES.toMillis(15))
                .build(),
        )
    }

    class CheckJob : JobService() {
        override fun onStartJob(params: JobParameters?): Boolean {
            updateNotice(this)
            return false
        }

        override fun onStopJob(params: JobParameters?) = false
    }
}
