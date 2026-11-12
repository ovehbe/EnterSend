package com.ovehbe.entersend.service

import com.ovehbe.entersend.core.Pixels
import com.ovehbe.entersend.data.AppRule
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
    private val loadCrop: (String) -> Pixels?,
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
            val crop = rule.cropPath?.let(loadCrop)
            main.execute {
                loading.remove(pkg)
                loaded[pkg] = AppState(rule, crop)
            }
        }
    }

    fun recordResult(pkg: String, success: Boolean) {
        loaded[pkg]?.let { s ->
            val r = s.rule
            loaded[pkg] = AppState(
                if (success) r.copy(successCount = r.successCount + 1) else r.copy(failureCount = r.failureCount + 1),
                s.crop,
            )
        }
        io.execute {
            dao.ensure(pkg)
            if (success) dao.addSuccess(pkg) else dao.addFailure(pkg)
        }
    }
}
