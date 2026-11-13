package com.meowgi.entersend

import com.meowgi.entersend.core.CropMatch
import com.meowgi.entersend.data.EnterDiagLog
import com.meowgi.entersend.ui.DiagnosticsActivity
import com.meowgi.entersend.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EnterDiagLogTest {
    @Before fun setUp() {
        EnterDiagLog.resetForTests(context())
        MainActivity.prefs(context()).edit().putBoolean(MainActivity.KEY_SETUP_DONE, true).apply()
    }

    @After fun tearDown() = EnterDiagLog.resetForTests(context())

    @Test fun ringBufferKeepsLast300() {
        val log = EnterDiagLog.get(context())
        repeat(EnterDiagLog.MAX + 25) { i -> log.record("=== ENTER #$i") }
        val snap = log.snapshot()
        assertEquals(EnterDiagLog.MAX, snap.size)
        assertTrue(snap.first().contains("#25"))
        assertTrue(snap.last().contains("#${EnterDiagLog.MAX + 24}"))
    }

    @Test fun enterPressRecordsDetailedBlock() {
        EnterDiagLog.resetForTests(context())
        val device = FakeDevice()
        device.field = chatField("hello")
        device.root = window(sendButton())
        val db = memoryDb()
        try {
            val s = service(device, db)
            // Inject diag via re-attach would need EnterHandler — drive through service after attach
            // already wires EnterDiagLog in production attach; unit attach also uses it now.
            assertEquals(listOf(true, true), s.press(1000))
            val text = EnterDiagLog.get(context()).formatText()
            assertTrue(text, text.contains("=== ENTER"))
            assertTrue(text, text.contains("pkg=com.whatsapp"))
            assertTrue(text, text.contains("outcome=performAction-CLICK") || text.contains("strategy=AUTO"))
            assertTrue(text, text.contains("field "))
            assertTrue(text, text.contains("consumed=true"))
        } finally {
            db.close()
        }
    }

    @Test fun settingsHasShareAndClearEnterLog() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNotNull(a.findViewById(R.id.share_enter_log))
        assertNotNull(a.findViewById(R.id.clear_enter_log))
    }

    @Test fun diagnosticsShowsEnterLogSection() {
        EnterDiagLog.get(context()).record("=== ENTER sample pkg=com.x outcome=test")
        val a = Robolectric.buildActivity(DiagnosticsActivity::class.java).setup().get()
        assertTrue(a.findViewById<android.widget.TextView>(R.id.enter_diag_log).text.contains("com.x"))
        assertNotNull(a.findViewById(R.id.enter_log_share))
        assertNotNull(a.findViewById(R.id.enter_log_clear))
    }

    @Test fun cropMatchScoreAgreesWithMatches() {
        val a = solid(0xFF2196F3.toInt(), 48, 48)
        val b = solid(0xFF2196F3.toInt(), 48, 48)
        val c = solid(0xFF4CAF50.toInt(), 48, 48)
        assertTrue(CropMatch.score(a, b).pass)
        assertTrue(CropMatch.matches(a, b))
        assertFalse(CropMatch.score(a, c).pass)
        assertFalse(CropMatch.matches(a, c))
        assertTrue(CropMatch.score(a, b).line().contains("PASS"))
    }
}
