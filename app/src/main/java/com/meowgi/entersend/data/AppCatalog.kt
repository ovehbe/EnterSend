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
import java.util.concurrent.Executor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Room-backed Apps catalog: show cached rows instantly, rescan PackageManager only when
 * packages change (broadcast or [android.content.pm.PackageManager.getChangedPackages])
 * or on manual refresh. Icons stay out of the cache and load lazily in the list.
 *
 * Empty-cache / failed-scan rule: never stick on an empty list — fall back to a live
 * PackageManager scan and only advance the change-sequence after a non-empty write.
 */
class AppCatalog(
    private val context: Context,
    private val dao: AppCatalogDao,
    private val io: Executor,
    private val policy: SystemAppPolicy = SystemAppPolicy(context),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val pm = context.packageManager

    /** In-process snapshot (no icons). Replaced atomically after load/scan. */
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

    /** Instant path: Room → memory rows (icons null). */
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

    /**
     * Empty Room cache always needs a PackageManager scan — even if in-memory orphans
     * from rules made [memory] look warm, or [KEY_SEQ] was advanced after a failed scan.
     */
    fun needsRescan(): Boolean {
        if (dao.count() == 0) return true
        if (memory.isEmpty()) return true
        // Prefer package-change sequence (cheap). Only fall back to a launcher-name
        // diff when the sequence is too old / unavailable (e.g. some test shadows).
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

    /** Full PackageManager scan (labels/system/version only — no icons). */
    fun fullScan(rules: List<AppRule>): List<AppRow> {
        scanGeneration.incrementAndGet()
        val t0 = SystemClock.elapsedRealtime()
        val scanned = try {
            AppRows.scanPackages(context, policy)
        } catch (e: RuntimeException) {
            Log.e(TAG, "apps_load scan failed", e)
            emptyList()
        }
        if (scanned.isNotEmpty()) {
            dao.replaceAll(scanned)
            bumpSequence()
            val rows = AppRows.fromCache(scanned, rules, policy)
            memory = rows
            lastLoadMs = SystemClock.elapsedRealtime() - t0
            lastLoadSource = "scan"
            Log.d(TAG, "apps_load source=scan ms=$lastLoadMs count=${rows.size}")
            return rows
        }
        // Empty or failed scan: do not wipe a good cache / advance sequence.
        return liveFallback(rules, t0)
    }

    fun refresh(rules: List<AppRule>): List<AppRow> {
        // Manual refresh: drop sticky sequence so needsRescan cannot short-circuit recovery.
        clearSequence()
        return fullScan(rules)
    }

    /**
     * Open path: serve in-memory / Room cache when warm and unchanged; otherwise scan.
     * Empty cache or failed scan always falls back to a live PackageManager scan.
     */
    fun open(rules: List<AppRule>, forceScan: Boolean = false): List<AppRow> {
        try {
            if (!forceScan && memory.isNotEmpty() && dao.count() > 0 && !needsRescan()) {
                return rebindRules(rules, source = "cache")
            }
            if (!forceScan) {
                val cached = loadCached(rules)
                if (cached.isNotEmpty() && dao.count() > 0 && !needsRescan()) {
                    return cached
                }
            }
            return fullScan(rules)
        } catch (e: RuntimeException) {
            Log.e(TAG, "apps_load open failed", e)
            return liveFallback(rules, SystemClock.elapsedRealtime())
        }
    }

    /**
     * Last-resort live PackageManager scan used when Room is empty or a scan failed.
     * Never leaves the UI on a stuck empty list when launchable apps exist.
     */
    fun liveFallback(rules: List<AppRule>, t0: Long = SystemClock.elapsedRealtime()): List<AppRow> {
        val scanned = try {
            AppRows.scanPackages(context, policy)
        } catch (e: RuntimeException) {
            Log.e(TAG, "apps_load fallback failed", e)
            emptyList()
        }
        if (scanned.isNotEmpty()) {
            try {
                dao.replaceAll(scanned)
                bumpSequence()
            } catch (e: RuntimeException) {
                Log.e(TAG, "apps_load fallback persist failed", e)
            }
            val rows = AppRows.fromCache(scanned, rules, policy)
            memory = rows
            lastLoadMs = SystemClock.elapsedRealtime() - t0
            lastLoadSource = "fallback"
            Log.d(TAG, "apps_load source=fallback ms=$lastLoadMs count=${rows.size}")
            return rows
        }
        // Keep prior memory if we had anything; otherwise stay empty.
        lastLoadMs = SystemClock.elapsedRealtime() - t0
        lastLoadSource = "fallback"
        Log.d(TAG, "apps_load source=fallback ms=$lastLoadMs count=${memory.size}")
        return memory
    }

    /** Seed after Application start / upgrade: fill Room when the catalog table is empty. */
    fun ensureSeeded(rules: List<AppRule>): List<AppRow> {
        if (dao.count() > 0 && memory.isNotEmpty()) {
            return memory
        }
        if (dao.count() > 0) {
            return loadCached(rules)
        }
        return fullScan(rules)
    }

    /** Re-apply current rules onto cached labels without PackageManager or Room I/O. */
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
