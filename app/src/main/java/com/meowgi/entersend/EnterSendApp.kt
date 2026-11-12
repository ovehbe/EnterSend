package com.meowgi.entersend

import android.app.Application
import android.content.pm.PackageManager
import com.meowgi.entersend.data.AppDb
import com.meowgi.entersend.data.CropFiles
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EnterSendApp : Application() {
    val db: AppDb by lazy { AppDb.build(this) }

    /** Single background thread for every database and file access. */
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        io.execute { removeUninstalled() }
        ServiceWatch.schedule(this)
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
