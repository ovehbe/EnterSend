package com.meowgi.entersend

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import com.meowgi.entersend.service.EnterSendService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ServiceWatchTest {
    private val nm = shadowOf(context().getSystemService(NotificationManager::class.java))

    @Test fun offShowsPersistentNotificationWithOneTapFix() {
        ServiceWatch.updateNotice(context(), running = false)
        val n = nm.getNotification(ServiceWatch.NOTIFICATION_ID)
        assertNotNull(n)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull(n.contentIntent)
        assertEquals(1, n.actions.size)
    }

    @Test fun runningClearsTheNotification() {
        ServiceWatch.updateNotice(context(), running = false)
        ServiceWatch.updateNotice(context(), running = true)
        assertNull(nm.getNotification(ServiceWatch.NOTIFICATION_ID))
    }

    @Test fun serviceBeingTurnedOffPostsTheNotification() {
        val service = Robolectric.setupService(EnterSendService::class.java)
        service.onUnbind(Intent())
        assertNull(EnterSendService.instance)
        assertNotNull(nm.getNotification(ServiceWatch.NOTIFICATION_ID))
    }

    @Test fun periodicCheckIsScheduledOnAppStart() {
        val js = context().getSystemService(android.app.job.JobScheduler::class.java)
        assertEquals(1, js.allPendingJobs.size)
    }
}
