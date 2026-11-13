package com.meowgi.entersend.service

import com.meowgi.entersend.core.ChatFieldGuard
import com.meowgi.entersend.core.Field
import com.meowgi.entersend.core.SystemAppPolicy
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.AutoFocusPrefs
import com.meowgi.entersend.data.EnterDiagLog
import com.meowgi.entersend.data.Mode

/**
 * When a taught/learned app appears, optionally focus its message field once so the hardware
 * keyboard can type immediately (0.3.12). Never re-steals focus for the same appearance.
 */
class AutoFocusController(
    private val device: Device,
    private val store: RuleStore,
    private val policy: SystemAppPolicy,
    private val enabledFor: (String) -> Boolean,
    private val diag: EnterDiagLog? = null,
    private val resolveMode: (AppRule) -> Mode = policy::resolveMode,
) {
    /** Appearance key we already attempted (success or skip). */
    private var attemptedKey: String? = null

    /**
     * Consider focusing [pkg]'s message field for this [appearanceKey]
     * (e.g. package + window id). Safe to call often; at most one attempt per key.
     */
    fun onAppearance(pkg: String, appearanceKey: String) {
        if (appearanceKey == attemptedKey) return

        if (!enabledFor(pkg)) {
            log(pkg, appearanceKey, "skip", "disabled")
            return
        }
        if (policy.isSystemApp(pkg) || policy.defaultsOff(pkg)) {
            log(pkg, appearanceKey, "skip", "system-or-defaults-off")
            return
        }

        val state = store.get(pkg)
        val rule = state?.rule ?: AppRule(pkg)
        if (!rule.isTaught) {
            log(pkg, appearanceKey, "skip", "not-set-up")
            return
        }
        val mode = resolveMode(rule)
        if (mode == Mode.OFF || mode == Mode.PASS_THROUGH) {
            log(pkg, appearanceKey, "skip", "mode=$mode")
            return
        }

        val focused = device.focusedField()
        if (focused != null && focused.packageName == pkg) {
            // Editable already has focus — mark appearance handled, do not steal later.
            attemptedKey = appearanceKey
            log(pkg, appearanceKey, "skip", "already-focused viewId=${focused.viewId}")
            return
        }
        if (focused != null && focused.packageName != pkg) {
            // Another app's field (unusual) — still try our target below.
        }

        val target = device.findMessageField(pkg, rule.fieldViewId)
            ?: run {
                attemptedKey = appearanceKey
                log(pkg, appearanceKey, "skip", "field-not-found viewId=${rule.fieldViewId}")
                return
            }
        // Taught fieldViewId match always counts; otherwise require chat-like heuristics.
        if (rule.fieldViewId == null || target.viewId != rule.fieldViewId) {
            if (!ChatFieldGuard.isChatLike(target, rule)) {
                attemptedKey = appearanceKey
                log(pkg, appearanceKey, "skip", "field-not-chat-like")
                return
            }
        }

        attemptedKey = appearanceKey
        val ok = device.focusForTyping(target)
        log(
            pkg,
            appearanceKey,
            if (ok) "focused" else "focus-failed",
            "viewId=${target.viewId} taught=${rule.fieldViewId}",
        )
    }

    /** Tests / package-leave: allow a fresh attempt on the next appearance. */
    fun reset() {
        attemptedKey = null
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
