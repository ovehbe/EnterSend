package com.meowgi.entersend.ui

import com.meowgi.entersend.core.AppLists
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRowsTest {
    private fun row(
        rule: AppRule,
        label: String = rule.packageName,
        version: Long? = 10,
        isSystemApp: Boolean = false,
    ): AppRow {
        val mode = rule.mode?.let { name -> Mode.entries.first { it.name == name } }
            ?: when {
                rule.packageName in AppLists.NATIVE_ENTER -> Mode.PASS_THROUGH
                rule.isTaught -> Mode.LEARNED
                else -> Mode.AUTO
            }
        return AppRow(rule.packageName, label, null, rule, version, mode, isSystemApp)
    }

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

    @Test fun searchMatchesWordStartsAndPackageSegmentsCaseInsensitive() {
        val rows = listOf(
            row(AppRule("com.facebook.orca"), "Facebook Messenger"),
            row(AppRule("com.whatsapp"), "WhatsApp"),
            row(AppRule("org.telegram.messenger"), "Telegram"),
        )
        assertTrue(AppRows.matchesQuery("Facebook Messenger", "com.facebook.orca", "mes"))
        assertTrue(AppRows.matchesQuery("Facebook Messenger", "com.facebook.orca", "MES"))
        assertTrue(AppRows.matchesQuery("WhatsApp", "com.whatsapp", "what"))
        assertTrue(AppRows.matchesQuery("Telegram", "org.telegram.messenger", "tele"))
        assertTrue(AppRows.matchesQuery("Telegram", "org.telegram.messenger", "messenger"))
        assertFalse(AppRows.matchesQuery("WhatsApp", "com.whatsapp", "zzzz"))
        // "mes" is a word-start of Messenger; also a substring of package …messenger — both OK.
        assertTrue(AppRows.filter(rows, "mes", false).any { it.label == "Facebook Messenger" })
        assertEquals(listOf("Facebook Messenger"), AppRows.filter(rows, "orca", false).map { it.label })
        assertEquals(listOf("Telegram"), AppRows.filter(rows, "telegram", false).map { it.label })
    }

    @Test fun systemAppsHiddenUnlessShowSystemChecked() {
        val rows = listOf(
            row(AppRule("com.whatsapp"), "WhatsApp", isSystemApp = false),
            row(AppRule("com.android.systemui", mode = Mode.OFF.name), "System UI", isSystemApp = true),
        )
        assertEquals(listOf("WhatsApp"), AppRows.filter(rows, "", false, showSystemApps = false).map { it.label })
        assertEquals(
            listOf("System UI", "WhatsApp"),
            AppRows.filter(rows, "", false, showSystemApps = true).map { it.label },
        )
    }
}
