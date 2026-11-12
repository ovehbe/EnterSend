package com.meowgi.entersend

import android.content.pm.PackageInfo
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.CropFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CleanupTest {
    @Test fun uninstalledAppsLoseTheirRulesAndCrops() {
        val app = context() as EnterSendApp
        app.io.submit {}.get() // let the startup cleanup finish first
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "com.whatsapp" })

        val dir = CropFiles.dir(app.filesDir)
        val kept = File(dir, "com.whatsapp-1.png").apply { writeText("x") }
        val gone = File(dir, "org.telegram.messenger-1.png").apply { writeText("x") }
        val orphan = File(dir, "stale.png").apply { writeText("x") }
        val dao = app.db.rules()
        app.io.submit {
            dao.upsert(AppRule("com.whatsapp", cropPath = kept.absolutePath))
            dao.upsert(AppRule("org.telegram.messenger", cropPath = gone.absolutePath))
            app.removeUninstalled()
        }.get()

        assertEquals("com.whatsapp", app.io.submit<String?> { dao.get("com.whatsapp")?.packageName }.get())
        assertNull(app.io.submit<AppRule?> { dao.get("org.telegram.messenger") }.get())
        assertTrue(kept.exists())
        assertFalse(gone.exists())
        assertFalse(orphan.exists())
        app.db.close()
    }
}
