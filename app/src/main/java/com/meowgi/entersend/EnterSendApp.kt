package com.meowgi.entersend

import android.app.Application
import android.content.pm.PackageManager
import com.google.android.material.color.DynamicColors
import com.meowgi.entersend.data.AppCatalog
import com.meowgi.entersend.data.AppDb
import com.meowgi.entersend.data.CropFiles
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EnterSendApp : Application() {
    val db: AppDb by lazy { AppDb.build(this) }

    /** Single background thread for every database and file access. */
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    val catalog: AppCatalog by lazy { AppCatalog(this, db.apps(), io) }

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        catalog.registerPackageReceiver()
        io.execute {
            removeUninstalled()
            // After v2→v3 migration cached_apps is empty — always seed (or re-seed) so Apps
            // is warm. ensureSeeded also recovers when a prior empty scan left the table blank.
            catalog.ensureSeeded(db.rules().all())
        }
        ServiceWatch.schedule(this)
    }

    override fun onTerminate() {
        catalog.unregisterPackageReceiver()
        super.onTerminate()
    }

    /** Deletes rules and crops of apps that are no longer installed, plus orphaned crop files. */
    internal fun removeUninstalled() {
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
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
