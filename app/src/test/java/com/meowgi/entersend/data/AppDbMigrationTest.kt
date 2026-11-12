package com.meowgi.entersend.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.meowgi.entersend.context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class AppDbMigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDb::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After fun tearDown() {
        context().deleteDatabase(name)
    }

    private fun insertV1Data() = helper.createDatabase(name, 1).use { db ->
        db.execSQL(
            """INSERT INTO app_rules (packageName, mode, teachDeclined, lastAskedDay, successCount,
                failureCount, taughtVersionCode, fieldViewId, sendViewId, sendText, sendDesc,
                sendClassPath, tapX, tapY, cropPath, cropLeft, cropTop, cropRight, cropBottom,
                orientation, windowWidth, windowHeight)
               VALUES ('com.whatsapp', NULL, 0, 20400, 41, 2, 251010, 'com.whatsapp:id/entry',
                'com.whatsapp:id/send', NULL, 'Gönder', 'android.widget.FrameLayout>android.widget.ImageButton',
                650, 640, '/data/user/0/com.meowgi.entersend/files/crops/com.whatsapp-1.png',
                626, 616, 674, 664, 1, 720, 720)""",
        )
        db.execSQL(
            "INSERT INTO app_rules (packageName, mode, teachDeclined, successCount, failureCount) " +
                "VALUES ('org.telegram.messenger', 'OFF', 1, 0, 0)",
        )
    }

    private fun <T> offMain(block: () -> T): T = try {
        Executors.newSingleThreadExecutor().submit(Callable(block)).get()
    } catch (e: ExecutionException) {
        throw e.cause!!
    }

    @Test fun everyVersionStepHasAMigration() {
        for (from in 1 until AppDb.VERSION) {
            assertTrue(
                "missing migration $from -> ${from + 1}",
                AppDb.MIGRATIONS.any { it.startVersion == from && it.endVersion == from + 1 },
            )
        }
    }

    @Test fun taughtV1RuleSurvivesUpgradeToV2() {
        insertV1Data()
        helper.runMigrationsAndValidate(name, 2, true, AppDb.MIGRATION_1_2).close()

        val db = AppDb.build(context(), name)
        try {
            val (wa, tg) = offMain { db.rules().get("com.whatsapp")!! to db.rules().get("org.telegram.messenger")!! }
            assertEquals("Gönder", wa.sendDesc)
            assertEquals(41, wa.successCount)
            assertEquals(650, wa.tapX)
            assertEquals(640, wa.tapY)
            assertEquals("/data/user/0/com.meowgi.entersend/files/crops/com.whatsapp-1.png", wa.cropPath)
            assertTrue(wa.hasNodeRule)
            assertTrue(wa.hasTapSpot) // legacy absolute anchors still count
            assertEquals(Mode.LEARNED, wa.effectiveMode)
            assertEquals(0, wa.consecutiveFailures)
            assertFalse(wa.needsRecheck)
            assertNull(wa.tapDxDp)
            assertNull(wa.tapFx)
            assertNull(wa.keyboardShown)
            assertEquals(Mode.OFF, tg.effectiveMode)
            assertTrue(tg.teachDeclined)
        } finally {
            db.close()
        }
    }

    @Test fun unknownVersionFailsInsteadOfWipingData() {
        insertV1Data()
        context().openOrCreateDatabase(name, 0, null).use { it.version = AppDb.VERSION + 1 }

        val db = AppDb.build(context(), name)
        try {
            offMain { db.rules().all() }
            fail("opening a database without a migration path must fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message, expected.message!!.contains("migration", ignoreCase = true))
        } finally {
            db.close()
        }
        context().openOrCreateDatabase(name, 0, null).use { raw ->
            raw.rawQuery("SELECT COUNT(*) FROM app_rules", null).use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
            }
        }
    }
}
