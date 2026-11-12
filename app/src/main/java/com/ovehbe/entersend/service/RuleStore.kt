package com.ovehbe.entersend.service

import com.ovehbe.entersend.core.Pixels
import com.ovehbe.entersend.data.AppRule
import com.ovehbe.entersend.data.Crops
import com.ovehbe.entersend.data.RuleDao
import java.util.concurrent.Executor

/**
 * In-memory copy of the rules for recently used apps. Key handling reads only from memory;
 * loading and every write run on [io], results are applied on [main].
 */
class RuleStore(
    private val dao: RuleDao,
    private val io: Executor,
    private val main: Executor,
    private val crops: Crops,
) {
    class AppState(val rule: AppRule, val crop: Pixels?)

    private val loaded = object : LinkedHashMap<String, AppState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AppState>) = size > 16
    }
    private val loading = HashSet<String>()
    private var foreground: String? = null

    /** The app's state, or null while it is still loading. */
    fun get(pkg: String): AppState? = loaded[pkg] ?: run { refresh(pkg); loaded[pkg] }

    fun onForeground(pkg: String) {
        if (pkg == foreground) return
        foreground = pkg
        refresh(pkg)
    }

    fun refresh(pkg: String) {
        if (!loading.add(pkg)) return
        io.execute {
            val rule = dao.get(pkg) ?: AppRule(pkg)
            val crop = rule.cropPath?.let(crops::load)
            main.execute {
                loading.remove(pkg)
                loaded[pkg] = AppState(rule, crop)
            }
        }
    }

    fun recordResult(pkg: String, success: Boolean) {
        update(pkg) { if (success) it.copy(successCount = it.successCount + 1) else it.copy(failureCount = it.failureCount + 1) }
        io.execute {
            dao.ensure(pkg)
            if (success) dao.addSuccess(pkg) else dao.addFailure(pkg)
        }
    }

    fun markAsked(pkg: String, day: Long) {
        update(pkg) { it.copy(lastAskedDay = day) }
        io.execute {
            dao.ensure(pkg)
            dao.setLastAsked(pkg, day)
        }
    }

    fun decline(pkg: String) {
        update(pkg) { it.copy(teachDeclined = true) }
        io.execute {
            dao.ensure(pkg)
            dao.setDeclined(pkg)
        }
    }

    /** Saves a taught rule; [crop] replaces the old crop file. New file first, then DB, then delete old. */
    fun saveTaught(rule: AppRule, crop: Pixels?) {
        val pkg = rule.packageName
        io.execute {
            val previous = dao.get(pkg)?.cropPath
            val saved = rule.copy(cropPath = crop?.let { crops.save(pkg, it) } ?: rule.cropPath)
            dao.upsert(saved)
            if (previous != null && previous != saved.cropPath) crops.delete(previous)
            main.execute { loaded[pkg] = AppState(saved, crop ?: saved.cropPath?.let(crops::load)) }
        }
    }

    private fun update(pkg: String, change: (AppRule) -> AppRule) {
        loaded[pkg]?.let { loaded[pkg] = AppState(change(it.rule), it.crop) }
    }
}
