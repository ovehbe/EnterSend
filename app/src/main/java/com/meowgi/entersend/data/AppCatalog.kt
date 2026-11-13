package com.meowgi.entersend.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.meowgi.entersend.core.SystemAppPolicy
import com.meowgi.entersend.ui.AppRow
import com.meowgi.entersend.ui.AppRows
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Room-backed Apps catalog with a hard 0.3.3 listing fallback when the cache path
 * yields nothing visible. Diagnostics are published for the Apps page + Diagnostics log.
 */
class AppCatalog(
    private val context: Context,
    private val dao: AppCatalogDao,
    private val io: Executor,
    private val policy: SystemAppPolicy = SystemAppPolicy(context),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val pm = context.packageManager

    @Volatile
    var memory: List<AppRow> = emptyList()
        private set

    private var receiver: BroadcastReceiver? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val scanGeneration = AtomicInteger(0)

    @Volatile
    var lastLoadMs: Long = -1L
        private set

    @Volatile
    var lastLoadSource: String = ""
        private set

    @Volatile
    var lastDiagnostics: AppsListDiagnostics = AppsListDiagnostics()
        private set

    @Volatile
    private var lastError: Throwable? = null

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        for (l in listeners) {
            try {
                l()
            } catch (_: RuntimeException) {
            }
        }
    }

    fun registerPackageReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                io.execute {
                    onPackageIntent(intent)
                    notifyChanged()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.applicationContext.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.applicationContext.registerReceiver(r, filter)
        }
        receiver = r
    }

    fun unregisterPackageReceiver() {
        receiver?.let {
            try {
                context.applicationContext.unregisterReceiver(it)
            } catch (_: RuntimeException) {
            }
        }
        receiver = null
    }

    fun loadCached(rules: List<AppRule>): List<AppRow> {
        val t0 = SystemClock.elapsedRealtime()
        val cached = dao.all()
        val rows = AppRows.fromCache(cached, rules, policy)
        memory = rows
        lastLoadMs = SystemClock.elapsedRealtime() - t0
        lastLoadSource = "cache"
        Log.d(TAG, "apps_load source=cache ms=$lastLoadMs count=${rows.size}")
        return rows
    }

    fun needsRescan(): Boolean {
        if (dao.count() == 0) return true
        if (memory.isEmpty()) return true
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val last = prefs.getInt(KEY_SEQ, 0)
                val changed = pm.getChangedPackages(last)
                if (changed != null) {
                    prefs.edit().putInt(KEY_SEQ, changed.sequenceNumber).apply()
                    return changed.packageNames.isNotEmpty()
                }
            } catch (_: RuntimeException) {
            }
        }
        val cachedNames = if (memory.isNotEmpty()) {
            memory.mapTo(HashSet()) { it.pkg }
        } else {
            dao.packageNames().toHashSet()
        }
        return packageNamesDiffer(cachedNames)
    }

    fun fullScan(rules: List<AppRule>): List<AppRow> {
        scanGeneration.incrementAndGet()
        val t0 = SystemClock.elapsedRealtime()
        lastError = null
        val scanned = try {
            AppRows.scanPackages(context, policy)
        } catch (e: RuntimeException) {
            lastError = e
            Log.e(TAG, "apps_load scan failed", e)
            emptyList()
        }
        if (scanned.isNotEmpty()) {
            try {
                dao.replaceAll(scanned)
                bumpSequence()
            } catch (e: RuntimeException) {
                lastError = e
                Log.e(TAG, "apps_load persist failed", e)
            }
            val rows = AppRows.fromCache(scanned, rules, policy)
            memory = rows
            lastLoadMs = SystemClock.elapsedRealtime() - t0
            lastLoadSource = "scan"
            Log.d(TAG, "apps_load source=scan ms=$lastLoadMs count=${rows.size}")
            publishDiagnostics(rows, source = "scan")
            return ensureVisible(rows, rules, t0)
        }
        return liveFallback(rules, t0)
    }

    fun refresh(rules: List<AppRule>): List<AppRow> {
        clearSequence()
        lastError = null
        return fullScan(rules)
    }

    fun open(rules: List<AppRule>, forceScan: Boolean = false): List<AppRow> {
        try {
            if (!forceScan && memory.isNotEmpty() && dao.count() > 0 && !needsRescan()) {
                val rows = rebindRules(rules, source = "cache")
                publishDiagnostics(rows, source = "cache")
                return ensureVisible(rows, rules, SystemClock.elapsedRealtime())
            }
            if (!forceScan) {
                val cached = loadCached(rules)
                if (cached.isNotEmpty() && dao.count() > 0 && !needsRescan()) {
                    publishDiagnostics(cached, source = "cache")
                    return ensureVisible(cached, rules, SystemClock.elapsedRealtime())
                }
            }
            return fullScan(rules)
        } catch (e: RuntimeException) {
            lastError = e
            Log.e(TAG, "apps_load open failed", e)
            return liveFallback(rules, SystemClock.elapsedRealtime())
        }
    }

    fun liveFallback(rules: List<AppRule>, t0: Long = SystemClock.elapsedRealtime()): List<AppRow> {
        val scanned = try {
            AppRows.scanPackages(context, policy)
        } catch (e: RuntimeException) {
            lastError = e
            Log.e(TAG, "apps_load fallback failed", e)
            emptyList()
        }
        if (scanned.isNotEmpty()) {
            try {
                dao.replaceAll(scanned)
                bumpSequence()
            } catch (e: RuntimeException) {
                lastError = e
                Log.e(TAG, "apps_load fallback persist failed", e)
            }
            val rows = AppRows.fromCache(scanned, rules, policy)
            memory = rows
            lastLoadMs = SystemClock.elapsedRealtime() - t0
            lastLoadSource = "fallback"
            Log.d(TAG, "apps_load source=fallback ms=$lastLoadMs count=${rows.size}")
            publishDiagnostics(rows, source = "fallback")
            return ensureVisible(rows, rules, t0)
        }
        return load033Fallback(rules, t0)
    }

    /**
     * Hard fallback: exact 0.3.3 listing code. Used when cache/scan paths produce nothing
     * visible (empty PM result, Room failure, or everything incorrectly hidden).
     */
    fun load033Fallback(rules: List<AppRule>, t0: Long = SystemClock.elapsedRealtime()): List<AppRow> {
        val rows = try {
            AppRows.loadExact033(context, rules, policy)
        } catch (e: RuntimeException) {
            lastError = e
            Log.e(TAG, "apps_load 033 fallback failed", e)
            memory
        }
        if (rows.isNotEmpty()) {
            memory = rows
            try {
                dao.replaceAll(
                    rows.map {
                        CachedApp(it.pkg, it.label, policy.hasSystemFlag(it.pkg), it.versionCode)
                    },
                )
                bumpSequence()
            } catch (e: RuntimeException) {
                // Listing still wins even if Room persist fails.
                lastError = lastError ?: e
                Log.e(TAG, "apps_load 033 persist failed", e)
            }
        }
        lastLoadMs = SystemClock.elapsedRealtime() - t0
        lastLoadSource = "033"
        Log.d(TAG, "apps_load source=033 ms=$lastLoadMs count=${rows.size}")
        publishDiagnostics(rows, source = "033")
        return rows
    }

    /**
     * If the catalog rows would show nothing under default filters (no system, no needs-only),
     * fall back to the 0.3.3 listing so the Apps page is never stuck empty when PM sees apps.
     */
    private fun ensureVisible(rows: List<AppRow>, rules: List<AppRule>, t0: Long): List<AppRow> {
        val visible = AppRows.filter(rows, "", needsTeachingOnly = false, showSystemApps = false)
        if (visible.isNotEmpty()) return rows
        val launcher = AppRows.launchablePackages(context).filter { it != context.packageName }
        if (launcher.isEmpty() && rules.isEmpty()) {
            publishDiagnostics(rows, source = lastLoadSource)
            return rows
        }
        Log.w(TAG, "apps_load empty-after-filter; falling back to 0.3.3 listing")
        return load033Fallback(rules, t0)
    }

    fun ensureSeeded(rules: List<AppRule>): List<AppRow> {
        if (dao.count() > 0 && memory.isNotEmpty()) {
            return memory
        }
        if (dao.count() > 0) {
            val cached = loadCached(rules)
            return ensureVisible(cached, rules, SystemClock.elapsedRealtime())
        }
        return fullScan(rules)
    }

    /** Recompute diagnostics for the current filter state (UI calls this after filter). */
    fun updateShownCount(shown: Int) {
        val d = lastDiagnostics
        lastDiagnostics = d.copy(shown = shown)
        Log.d(TAG, "apps_diag ${lastDiagnostics.line()}")
    }

    fun publishDiagnostics(rows: List<AppRow>, source: String, shown: Int = -1) {
        val launcherPkgs = try {
            AppRows.launchablePackages(context).filter { it != context.packageName }
        } catch (e: RuntimeException) {
            lastError = lastError ?: e
            emptyList()
        }
        val scanCount = try {
            AppRows.installedApplicationCount(context)
        } catch (e: RuntimeException) {
            lastError = lastError ?: e
            0
        }
        val cachedCount = try {
            dao.count()
        } catch (e: RuntimeException) {
            lastError = lastError ?: e
            0
        }
        val systemCount = try {
            AppRows.flagSystemCount(context, rows.map { it.pkg }.ifEmpty { launcherPkgs })
        } catch (e: RuntimeException) {
            lastError = lastError ?: e
            rows.count { it.isSystemApp }
        }
        val shownCount = if (shown >= 0) {
            shown
        } else {
            AppRows.filter(rows, "", false, false).size
        }
        val diag = AppsListDiagnostics(
            scan = scanCount,
            launcher = launcherPkgs.size,
            system = systemCount,
            cached = cachedCount,
            shown = shownCount,
            err = AppsListDiagnostics.errOf(lastError),
            source = source,
        )
        lastDiagnostics = diag
        Log.d(TAG, "apps_diag ${diag.line()} source=$source")
        try {
            PromptDecisionLog.get(context).record(
                pkg = AppsListDiagnostics.LOG_PKG,
                source = AppsListDiagnostics.LOG_SOURCE,
                decision = diag.line() + " src=$source",
            )
        } catch (_: RuntimeException) {
        }
    }

    private fun rebindRules(rules: List<AppRule>, source: String): List<AppRow> {
        val t0 = SystemClock.elapsedRealtime()
        val cached = memory.map {
            CachedApp(it.pkg, it.label, it.isSystemApp, it.versionCode)
        }
        val rows = AppRows.fromCache(cached, rules, policy)
        memory = rows
        lastLoadMs = SystemClock.elapsedRealtime() - t0
        lastLoadSource = source
        Log.d(TAG, "apps_load source=$source ms=$lastLoadMs count=${rows.size}")
        return rows
    }

    fun onPackageIntent(intent: Intent) {
        val action = intent.action ?: return
        val pkg = intent.data?.schemeSpecificPart
            ?: intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
            ?: return
        if (action == Intent.ACTION_PACKAGE_REMOVED &&
            intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        ) {
            return
        }
        when (action) {
            Intent.ACTION_PACKAGE_REMOVED -> removePackage(pkg)
            Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED -> upsertPackage(pkg, action)
        }
    }

    fun removePackage(pkg: String) {
        dao.delete(pkg)
        memory = memory.filter { it.pkg != pkg }
        Log.d(TAG, "apps_pkg removed=$pkg")
        bumpSequence()
    }

    fun upsertPackage(pkg: String, action: String = Intent.ACTION_PACKAGE_ADDED) {
        val entry = AppRows.scanOne(context, pkg, policy) ?: return
        dao.upsert(entry)
        val existingRule = memory.firstOrNull { it.pkg == pkg }?.rule ?: AppRule(pkg)
        memory = memory.filter { it.pkg != pkg } +
            AppRows.rowFromCached(entry, existingRule, policy)
        Log.d(TAG, "apps_pkg upsert=$pkg action=$action")
        bumpSequence()
    }

    private fun packageNamesDiffer(cached: Set<String> = dao.packageNames().toHashSet()): Boolean {
        val live = AppRows.launchablePackages(context)
            .filter { it != context.packageName }
            .toHashSet()
        return cached != live
    }

    private fun bumpSequence() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val changed = pm.getChangedPackages(prefs.getInt(KEY_SEQ, 0))
            if (changed != null) {
                prefs.edit().putInt(KEY_SEQ, changed.sequenceNumber).apply()
            }
        } catch (_: RuntimeException) {
        }
    }

    private fun clearSequence() {
        prefs.edit().putInt(KEY_SEQ, 0).apply()
    }

    companion object {
        const val TAG = "EnterSendApps"
        private const val PREFS = "entersend_apps_catalog"
        private const val KEY_SEQ = "pkg_seq"
    }
}
