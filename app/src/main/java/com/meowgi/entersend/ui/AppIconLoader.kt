package com.meowgi.entersend.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.util.concurrent.Executor

/** Small process-wide icon cache — Apps list never loads every icon up front. */
object AppIconLoader {
    private val cache = LruCache<String, Drawable>(96)

    fun getCached(pkg: String): Drawable? = synchronized(cache) { cache.get(pkg) }

    fun load(context: Context, pkg: String): Drawable? {
        getCached(pkg)?.let { return it }
        val icon = try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        if (icon != null) {
            synchronized(cache) { cache.put(pkg, icon) }
        }
        return icon
    }

    fun loadAsync(context: Context, pkg: String, executor: Executor, onLoaded: (Drawable?) -> Unit) {
        getCached(pkg)?.let {
            onLoaded(it)
            return
        }
        executor.execute {
            val icon = load(context, pkg)
            onLoaded(icon)
        }
    }

    fun clear() {
        synchronized(cache) { cache.evictAll() }
    }
}
