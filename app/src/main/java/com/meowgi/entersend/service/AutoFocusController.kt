package com.meowgi.entersend.service

import com.meowgi.entersend.core.Field
import com.meowgi.entersend.core.SystemAppPolicy
import com.meowgi.entersend.data.AutoFocusPrefs
import com.meowgi.entersend.data.EnterDiagLog
import kotlin.math.hypot

/**
 * Per-app auto-focus (0.3.13). When enabled for [pkg], focuses the saved (or sole editable)
 * message field once per window appearance. Never re-steals focus.
 */
class AutoFocusController(
    private val device: Device,
    private val policy: SystemAppPolicy,
    private val isEnabled: (String) -> Boolean,
    private val loadTarget: (String) -> AutoFocusPrefs.Target?,
    private val saveTarget: (String, AutoFocusPrefs.Target) -> Unit,
    private val diag: EnterDiagLog? = null,
    private val requestTeach: ((String) -> Unit)? = null,
) {
    private var attemptedKey: String? = null

    fun onAppearance(pkg: String, appearanceKey: String) {
        if (appearanceKey == attemptedKey) return

        if (!isEnabled(pkg)) {
            log(pkg, appearanceKey, "skip", "disabled")
            return
        }
        if (policy.isSystemApp(pkg) || policy.defaultsOff(pkg)) {
            log(pkg, appearanceKey, "skip", "system-or-defaults-off")
            return
        }

        val focused = device.focusedField()
        if (focused != null && focused.packageName == pkg) {
            attemptedKey = appearanceKey
            log(pkg, appearanceKey, "skip", "already-focused viewId=${focused.viewId}")
            return
        }

        val saved = loadTarget(pkg)
        val editables = device.listEditableFields(pkg)
        val target = resolveTarget(pkg, saved, editables)
            ?: run {
                attemptedKey = appearanceKey
                log(pkg, appearanceKey, "skip", "field-not-found saved=${saved != null} n=${editables.size}")
                // Zero or many with no saved identity → ask user to teach (if callback set).
                if (saved == null && editables.size != 1) {
                    requestTeach?.invoke(pkg)
                }
                return
            }

        attemptedKey = appearanceKey
        focusOrTap(pkg, target, saved)
    }

    /**
     * When the user turns auto-focus on: save a sole editable if present, else start teach.
     * Returns true if a target was saved immediately.
     */
    fun enableWithDetect(pkg: String): Boolean {
        val editables = device.listEditableFields(pkg)
        if (editables.size == 1) {
            val f = editables[0]
            val ws = device.windowStateForPackage(pkg)
            val w = ws?.width ?: f.bounds.right.coerceAtLeast(1)
            val h = ws?.height ?: f.bounds.bottom.coerceAtLeast(1)
            saveTarget(
                pkg,
                AutoFocusPrefs.buildTarget(
                    f.viewId, f.className,
                    f.bounds.left, f.bounds.top, f.bounds.right, f.bounds.bottom,
                    w, h, tapFallback = false,
                ),
            )
            return true
        }
        requestTeach?.invoke(pkg)
        return false
    }

    fun reset() {
        attemptedKey = null
    }

    private fun resolveTarget(
        pkg: String,
        saved: AutoFocusPrefs.Target?,
        editables: List<Field>,
    ): Field? {
        if (saved != null) {
            matchSaved(saved, editables)?.let { return it }
        }
        // No saved identity (or stale): single-editable heuristic.
        if (editables.size == 1) return editables[0]
        return null
    }

    private fun matchSaved(saved: AutoFocusPrefs.Target, editables: List<Field>): Field? {
        if (!saved.viewId.isNullOrBlank()) {
            editables.firstOrNull { it.viewId == saved.viewId }?.let { return it }
        }
        val sameClass = if (!saved.className.isNullOrBlank()) {
            editables.filter { it.className == saved.className }
        } else {
            editables
        }
        if (sameClass.isEmpty()) return null
        val ws = device.windowStateForPackage(sameClass.first().packageName)
        val w = (ws?.width ?: 720).toFloat()
        val h = (ws?.height ?: 720).toFloat()
        val cx = saved.centerXFrac * w
        val cy = saved.centerYFrac * h
        return sameClass.minByOrNull { f ->
            hypot(
                (f.bounds.centerX - cx).toDouble(),
                (f.bounds.centerY - cy).toDouble(),
            )
        }
    }

    private fun focusOrTap(pkg: String, field: Field, saved: AutoFocusPrefs.Target?) {
        if (saved?.tapFallback == true) {
            tapCenter(pkg, field, saved)
            return
        }
        if (device.focus(field)) {
            log(pkg, attemptedKey ?: "", "focused", "via=ACTION_FOCUS viewId=${field.viewId}")
            return
        }
        if (device.clickField(field)) {
            log(pkg, attemptedKey ?: "", "focused", "via=ACTION_CLICK viewId=${field.viewId}")
            return
        }
        // Both failed → gesture tap + remember fallback mode.
        val ws = device.windowStateForPackage(pkg)
        val w = ws?.width ?: field.bounds.right.coerceAtLeast(1)
        val h = ws?.height ?: field.bounds.bottom.coerceAtLeast(1)
        val updated = AutoFocusPrefs.buildTarget(
            field.viewId, field.className,
            field.bounds.left, field.bounds.top, field.bounds.right, field.bounds.bottom,
            w, h, tapFallback = true,
        )
        saveTarget(pkg, updated)
        tapCenter(pkg, field, updated)
    }

    private fun tapCenter(pkg: String, field: Field, saved: AutoFocusPrefs.Target?) {
        val ws = device.windowStateForPackage(pkg)
        val x: Int
        val y: Int
        if (ws != null && saved != null) {
            // Prefer live node centre when we have the node; else relative saved centre.
            x = field.bounds.centerX
            y = field.bounds.centerY
        } else {
            x = field.bounds.centerX
            y = field.bounds.centerY
        }
        device.tap(x, y)
        log(pkg, attemptedKey ?: "", "tapped", "fallback tap=($x,$y) viewId=${field.viewId}")
    }

    private fun log(pkg: String, key: String, outcome: String, detail: String) {
        val block = buildString {
            append(EnterDiagLog.stamp())
            append('\n')
            append("auto-focus pkg=$pkg appearance=$key")
            append('\n')
            append("outcome=$outcome")
            append('\n')
            append("detail=$detail")
        }
        diag?.record(block)
        android.util.Log.d("EnterSend", "auto-focus pkg=$pkg $outcome $detail")
    }
}
