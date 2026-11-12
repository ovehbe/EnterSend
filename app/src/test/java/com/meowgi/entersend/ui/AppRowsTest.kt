package com.meowgi.entersend.ui

import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRowsTest {
    private fun row(rule: AppRule, label: String = rule.packageName, version: Long? = 10) =
        AppRow(rule.packageName, label, null, rule, version)

    private val taught = AppRule("com.whatsapp", sendViewId = "com.whatsapp:id/send", taughtVersionCode = 10)

    @Test fun healthFromCounters() {
        assertEquals(Health.NONE, row(AppRule("a")).health)
        assertEquals(Health.GOOD, row(AppRule("a", successCount = 8, failureCount = 2)).health)
        assertEquals(Health.MIXED, row(AppRule("a", successCount = 5, failureCount = 5)).health)
        assertEquals(Health.BAD, row(AppRule("a", successCount = 1, failureCount = 4)).health)
    }

    @Test fun recheckOnlyWhenTaughtVersionDiffers() {
        assertFalse(row(taught, version = 10).needsRecheck)
        assertTrue(row(taught, version = 11).needsRecheck)
        assertTrue(row(taught, version = 11).needsTeaching)
        assertFalse(row(AppRule("x"), version = 11).needsRecheck)
    }

    @Test fun needsTeachingAfterAMissButNotWhenOffOrDeclinedToAct() {
        assertTrue(row(AppRule("com.x", lastAskedDay = 1)).needsTeaching)
        assertFalse(row(AppRule("com.x")).needsTeaching)
        assertFalse(row(AppRule("com.x", lastAskedDay = 1, mode = Mode.OFF.name)).needsTeaching)
        assertFalse(row(AppRule("com.discord", lastAskedDay = 1)).needsTeaching) // pass-through by default
        assertFalse(row(AppRule("com.android.chrome", lastAskedDay = 1, mode = Mode.AUTO.name)).needsTeaching)
        assertFalse(row(taught).needsTeaching)
    }

    @Test fun filterBySearchAndNeedsTeachingWithSeenAppsFirst() {
        val rows = listOf(
            row(AppRule("org.telegram.messenger"), "Telegram"),
            row(AppRule("com.whatsapp", lastAskedDay = 1), "WhatsApp"),
            row(AppRule("com.android.calculator"), "Calculator"),
        )
        assertEquals(listOf("WhatsApp", "Calculator", "Telegram"), AppRows.filter(rows, "", false).map { it.label })
        assertEquals(listOf("Telegram"), AppRows.filter(rows, " tele", false).map { it.label })
        assertEquals(listOf("Telegram"), AppRows.filter(rows, "org.telegram", false).map { it.label })
        assertEquals(listOf("WhatsApp"), AppRows.filter(rows, "", true).map { it.label })
    }
}
