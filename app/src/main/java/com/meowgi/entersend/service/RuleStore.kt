package com.meowgi.entersend.service

import com.meowgi.entersend.core.Pixels
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.Crops
import com.meowgi.entersend.data.Mode
import com.meowgi.entersend.data.RuleDao
import java.util.concurrent.Executor

/**
 * In-memory copy of the rules for recently used apps. Key handling reads only from memory;
 * loading and every write run on [io], results are applied on [main].
 *
 * [refresh] uses a per-package generation so a Settings write that starts a second load is never
 * skipped while an older load is in flight, and a newer [putLocal] is never overwritten by a
 * stale DB read.
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
    private val generation = HashMap<String, Int>()
    private var foreground: String? = null
    private var onLoaded: ((String, AppState) -> Unit)? = null

    /** Called on [main] after a package finishes loading (or is replaced via [replaceAll]). */
    fun setOnLoaded(listener: ((String, AppState) -> Unit)?) {
        onLoaded = listener
    }

    /** The app's state, or null while it is still loading. */
    fun get(pkg: String): AppState? = loaded[pkg] ?: run { refresh(pkg); loaded[pkg] }

    fun onForeground(pkg: String) {
        if (pkg == foreground) return
        foreground = pkg
        refresh(pkg)
    }

    fun refresh(pkg: String) {
        val gen = (generation[pkg] ?: 0) + 1
        generation[pkg] = gen
        loading.add(pkg)
        io.execute {
            val rule = dao.get(pkg) ?: AppRule(pkg)
            val crop = rule.cropPath?.let(crops::load)
            main.execute {
                if (generation[pkg] != gen) return@execute
                loading.remove(pkg)
                val state = AppState(rule, crop)
                loaded[pkg] = state
                onLoaded?.invoke(pkg, state)
            }
        }
    }

    /**
     * Immediately applies a rule the UI just wrote to the database, so the accessibility
     * service sees the change without waiting for a restart or a racing [refresh].
     */
    fun putLocal(rule: AppRule, crop: Pixels? = null) {
        val pkg = rule.packageName
        generation[pkg] = (generation[pkg] ?: 0) + 1
        loading.remove(pkg)
        val existing = loaded[pkg]
        val resolvedCrop = when {
            crop != null -> crop
            rule.cropPath == null -> null
            rule.cropPath == existing?.rule?.cropPath -> existing.crop
            else -> null // refresh will load; keep prior crop until then if path unchanged handled above
        }
        val state = AppState(rule, resolvedCrop)
        loaded[pkg] = state
        if (resolvedCrop == null && rule.cropPath != null) {
            refresh(pkg)
        }
    }

    /** Drops the in-memory cache (used when switching profiles). */
    fun clearMemory() {
        loaded.clear()
        loading.clear()
        generation.clear()
        foreground = null
    }

    /**
     * Replaces the working set after a profile switch / import. [rules] are already in the DB;
     * crops must already be on disk at [AppRule.cropPath].
     */
    fun replaceAll(rules: List<AppRule>) {
        clearMemory()
        for (rule in rules) {
            val crop = rule.cropPath?.let(crops::load)
            val state = AppState(rule, crop)
            loaded[rule.packageName] = state
            generation[rule.packageName] = 1
            onLoaded?.invoke(rule.packageName, state)
        }
    }

    fun recordResult(pkg: String, success: Boolean) {
        update(pkg) {
            if (success) {
                it.copy(successCount = it.successCount + 1, consecutiveFailures = 0, needsRecheck = false)
            } else {
                it.copy(failureCount = it.failureCount + 1, consecutiveFailures = it.consecutiveFailures + 1)
            }
        }
        io.execute {
            dao.ensure(pkg)
            if (success) dao.addSuccess(pkg) else dao.addFailure(pkg)
        }
    }

    fun markNeedsRecheck(pkg: String) {
        update(pkg) { it.copy(needsRecheck = true) }
        io.execute {
            dao.ensure(pkg)
            dao.markNeedsRecheck(pkg)
        }
    }

    fun markAsked(pkg: String, day: Long) {
        putLocal((loaded[pkg]?.rule ?: AppRule(pkg)).copy(lastAskedDay = day))
        io.execute {
            dao.ensure(pkg)
            dao.setLastAsked(pkg, day)
        }
    }

    fun decline(pkg: String) {
        putLocal((loaded[pkg]?.rule ?: AppRule(pkg)).copy(teachDeclined = true))
        io.execute {
            dao.ensure(pkg)
            dao.setDeclined(pkg)
        }
    }

    /** Clears "don't ask again" so the next open can prompt. */
    fun clearDeclined(pkg: String) {
        putLocal((loaded[pkg]?.rule ?: AppRule(pkg)).copy(teachDeclined = false))
        io.execute {
            dao.ensure(pkg)
            dao.clearDeclined(pkg)
        }
    }

    fun setOff(pkg: String) {
        putLocal((loaded[pkg]?.rule ?: AppRule(pkg)).copy(mode = Mode.OFF.name, teachDeclined = true))
        io.execute {
            dao.ensure(pkg)
            dao.setOff(pkg)
        }
    }

    /** Saves a taught rule; [crop] replaces the old crop file. New file first, then DB, then delete old. */
    fun saveTaught(rule: AppRule, crop: Pixels?) {
        val pkg = rule.packageName
        generation[pkg] = (generation[pkg] ?: 0) + 1
        val gen = generation[pkg]!!
        io.execute {
            val previous = dao.get(pkg)?.cropPath
            val saved = rule.copy(
                cropPath = crop?.let { crops.save(pkg, it) } ?: rule.cropPath,
                consecutiveFailures = 0,
                needsRecheck = false,
                teachDeclined = false,
            )
            dao.upsert(saved)
            if (previous != null && previous != saved.cropPath) crops.delete(previous)
            main.execute {
                if (generation[pkg] != gen) return@execute
                loading.remove(pkg)
                loaded[pkg] = AppState(saved, crop ?: saved.cropPath?.let(crops::load))
            }
        }
    }

    private fun update(pkg: String, change: (AppRule) -> AppRule) {
        loaded[pkg]?.let { loaded[pkg] = AppState(change(it.rule), it.crop) }
    }
}
