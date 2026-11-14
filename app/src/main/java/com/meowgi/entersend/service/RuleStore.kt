package com.meowgi.entersend.service

import com.meowgi.entersend.core.Pixels
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.Crops
import com.meowgi.entersend.data.Mode
import com.meowgi.entersend.data.RuleDao
import com.meowgi.entersend.diag.DiagLog
import java.util.concurrent.Executor

/**
 * In-memory copy of the rules. Key handling reads only from memory; loading and every write
 * run on [io], results are applied on [main].
 *
 * At service connect, [preloadAll] loads every DB row so Enter never has to guess Off /
 * don't-ask / taught state. [getForEnter] may only invent a default [AppRule] for packages
 * that are truly unknown (not in the DB).
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

    /** All stored rules stay resident — no LRU eviction (Enter must see Off/don't-ask). */
    private val loaded = LinkedHashMap<String, AppState>(64, 0.75f, true)
    private val loading = HashSet<String>()
    private val generation = HashMap<String, Int>()
    /** Packages that have (or had) a DB row — never invent a default for these while loading. */
    private val knownFromDb = HashSet<String>()
    @Volatile private var preloadDone = false
    private var foreground: String? = null
    private var onLoaded: ((String, AppState) -> Unit)? = null

    /** Called on [main] after a package finishes loading (or is replaced via [replaceAll]). */
    fun setOnLoaded(listener: ((String, AppState) -> Unit)?) {
        onLoaded = listener
    }

    /** The app's state, or null while it is still loading. */
    fun get(pkg: String): AppState? = loaded[pkg] ?: run { refresh(pkg); loaded[pkg] }

    /**
     * Enter path — package comes from the focused input only.
     *
     * - Cached rule → use it (respects Off / don't-ask / taught).
     * - After [preloadAll], unknown packages (no DB row) → default [AppRule] immediately.
     * - Known DB package still loading, or preload not finished → null (Enter passes through).
     */
    fun getForEnter(pkg: String): AppState? {
        loaded[pkg]?.let { return it }
        if (!preloadDone) {
            // Do not invent a default while Off/don't-ask rows may still be loading.
            return null
        }
        if (pkg in knownFromDb || pkg in loading) {
            // Stored decision exists (or is mid-load) — never optimistic-default over Off.
            if (pkg !in loading) refresh(pkg)
            return null
        }
        // Truly unknown app: safe default for Setup / undecided chat.
        val fresh = AppState(AppRule(pkg), null)
        loaded[pkg] = fresh
        return fresh
    }

    /**
     * Loads every DB rule (+ crops) into memory. Call from service connect so Enter never
     * races a pending Off / don't-ask row.
     */
    fun preloadAll() {
        io.execute {
            val t0 = System.currentTimeMillis()
            val rules = dao.all()
            val states = rules.map { rule ->
                AppState(rule, rule.cropPath?.let(crops::load))
            }
            DiagLog.i("db", "preloadAll rules=${rules.size} ${System.currentTimeMillis() - t0}ms")
            main.execute {
                for (state in states) {
                    val pkg = state.rule.packageName
                    knownFromDb.add(pkg)
                    // Don't clobber a newer putLocal that won the race.
                    val gen = generation[pkg] ?: 0
                    if (gen == 0 || loaded[pkg] == null) {
                        loaded[pkg] = state
                        generation[pkg] = gen.coerceAtLeast(1)
                    }
                }
                preloadDone = true
            }
        }
    }

    /** Warm the in-memory rule without treating this as prompt-foreground. */
    fun warm(pkg: String) {
        if (loaded.containsKey(pkg) || pkg in loading) return
        refresh(pkg)
    }

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
            val t0 = System.currentTimeMillis()
            val fromDb = dao.get(pkg)
            val rule = fromDb ?: AppRule(pkg)
            val crop = rule.cropPath?.let(crops::load)
            DiagLog.i("db", "refresh $pkg hit=${fromDb != null} ${System.currentTimeMillis() - t0}ms")
            main.execute {
                if (generation[pkg] != gen) return@execute
                loading.remove(pkg)
                if (fromDb != null) knownFromDb.add(pkg)
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
        knownFromDb.add(pkg)
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

    /** Drops the in-memory cache (used when switching profiles). Keeps preload flag. */
    fun clearMemory() {
        loaded.clear()
        loading.clear()
        generation.clear()
        knownFromDb.clear()
        foreground = null
        preloadDone = false
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
            knownFromDb.add(rule.packageName)
            loaded[rule.packageName] = state
            generation[rule.packageName] = 1
            onLoaded?.invoke(rule.packageName, state)
        }
        preloadDone = true
    }

    /** Test helper: drop one package from memory but remember it is a DB row. */
    internal fun dropFromMemoryForTests(pkg: String) {
        loaded.remove(pkg)
        loading.remove(pkg)
        knownFromDb.add(pkg)
        preloadDone = true
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
        knownFromDb.add(pkg)
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
