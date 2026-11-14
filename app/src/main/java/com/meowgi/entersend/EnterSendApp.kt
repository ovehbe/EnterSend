package com.meowgi.entersend

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.material.color.DynamicColors
import com.meowgi.entersend.data.AppDb
import com.meowgi.entersend.data.CropFiles
import com.meowgi.entersend.diag.AnrWatchdog
import com.meowgi.entersend.diag.CrashHandler
import com.meowgi.entersend.diag.DiagLog
import com.meowgi.entersend.diag.ExitReasons
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EnterSendApp : Application() {
    val db: AppDb by lazy { AppDb.build(this) }

    /** Single background thread for every database and file access. */
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        DiagLog.init(this)
        DiagLog.i("app", "onCreate")
        // Skip the 4s main-thread watchdog under Robolectric — it stalls the JVM test suite.
        if (!isRobolectric()) {
            AnrWatchdog.startDefault()
        } else {
            DiagLog.i("anr", "watchdog skipped (robolectric)")
        }
        io.execute {
            try {
                ExitReasons.logAtStartup(this)
            } catch (_: Exception) {
            }
            removeUninstalled()
        }
        DynamicColors.applyToActivitiesIfAvailable(this)
        ServiceWatch.schedule(this)
    }

    private fun isRobolectric(): Boolean =
        Build.FINGERPRINT.contains("robolectric", ignoreCase = true) ||
            Build.PRODUCT.contains("robolectric", ignoreCase = true)

    /** Deletes rules and crops of apps that are no longer installed, plus orphaned crop files. */
    internal fun removeUninstalled() {
        val t0 = System.currentTimeMillis()
        val dao = db.rules()
        val kept = HashSet<String>()
        for (rule in dao.all()) {
            if (isInstalled(rule.packageName)) {
                rule.cropPath?.let(kept::add)
            } else {
                rule.cropPath?.let { File(it).delete() }
                dao.delete(rule.packageName)
            }
        }
        CropFiles.dir(filesDir).listFiles()?.filter { it.absolutePath !in kept }?.forEach { it.delete() }
        DiagLog.i("db", "removeUninstalled ${System.currentTimeMillis() - t0}ms")
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
