package com.meowgi.entersend.ui

import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Evidence for 0.3.19: card-tap loadOne was queued on [EnterSendApp.io], the same
 * single-thread executor that runs the full AppRows.load from onResume.reload().
 * A slow job ahead of loadOne delays the sheet even though loadOne itself is cheap.
 */
@RunWith(RobolectricTestRunner::class)
class LastUsedTapLatencyCauseTest {
    private val app get() = context() as EnterSendApp

    @Test fun sharedIoQueuesLoadOneBehindSlowFullScanStyleWork() {
        val started = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val loadOneDone = CountDownLatch(1)
        val waitedMs = AtomicLong(0)

        app.io.execute {
            started.countDown()
            // Simulates AppRows.load (~seconds of PM work) occupying app.io.
            releaseSlow.await(2, TimeUnit.SECONDS)
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))

        val t0 = System.nanoTime()
        app.io.execute {
            waitedMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0))
            loadOneDone.countDown()
        }

        try {
            // Hold the slow job for 200ms — if loadOne shared the queue, it waits ~200ms+.
            Thread.sleep(200)
        } finally {
            releaseSlow.countDown()
        }
        assertTrue(loadOneDone.await(3, TimeUnit.SECONDS))
        assertTrue(
            "loadOne-style work waited ${waitedMs.get()}ms behind slow app.io job " +
                "(proves shared single-thread queue is the card-tap delay)",
            waitedMs.get() >= 150,
        )
    }

    @Test fun dedicatedQuickExecutorIsNotBlockedBySlowAppIo() {
        val releaseSlow = CountDownLatch(1)
        val quickDone = CountDownLatch(1)
        val waitedMs = AtomicLong(0)

        app.io.execute {
            releaseSlow.await(2, TimeUnit.SECONDS)
        }

        try {
            val t0 = System.nanoTime()
            app.quick.execute {
                waitedMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0))
                quickDone.countDown()
            }
            assertTrue(quickDone.await(2, TimeUnit.SECONDS))
            assertTrue(
                "quick path waited only ${waitedMs.get()}ms while app.io was blocked",
                waitedMs.get() < 100,
            )
        } finally {
            releaseSlow.countDown()
            app.io.submit {}.get(2, TimeUnit.SECONDS)
        }
    }
}
