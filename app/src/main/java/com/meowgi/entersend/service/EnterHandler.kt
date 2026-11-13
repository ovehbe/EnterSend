package com.meowgi.entersend.service

import android.os.Handler
import android.view.KeyEvent
import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.ChatFieldGuard
import com.meowgi.entersend.core.CropMatch
import com.meowgi.entersend.core.Field
import com.meowgi.entersend.core.Pixels
import com.meowgi.entersend.core.SendFinder
import com.meowgi.entersend.core.TapSpot
import com.meowgi.entersend.core.insertNewline
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.data.EnterDiagLog
import com.meowgi.entersend.data.Mode

/**
 * Decides what a fresh Enter / Shift+Enter press does. Returns true when the key is consumed.
 * Order: learned node → auto-detect → coordinate tap → pass through. One attempt per press.
 *
 * 0.3.10: optional [diag] records a detailed ring-buffer trace.
 * 0.3.11: crop-gated tap candidates (edge / absolute / proportional) + screenshot retry.
 */
class EnterHandler(
    private val device: Device,
    private val store: RuleStore,
    private val main: Handler,
    private val offerTeach: (AppRule) -> Unit,
    private val debug: Boolean = false,
    private val strings: (Int) -> String = { "" },
    private val resolveMode: (AppRule) -> Mode = { it.effectiveMode },
    /** Diagnostics: focused-input package, rule label, whether Enter was consumed. */
    private val onDecision: (pkg: String, ruleLabel: String, consumed: Boolean) -> Unit = { _, _, _ -> },
    private val diag: EnterDiagLog? = null,
) {
    private var inFlight: Any? = null

    fun onEnter(e: KeyEvent): Boolean {
        if (e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return false
        val t0 = System.currentTimeMillis()
        val lines = mutableListOf<String>()
        fun flush(outcome: String, reason: String, pkg: String?, ruleLabel: String, consumed: Boolean): Boolean {
            lines += "outcome=$outcome"
            lines += "reason=$reason"
            lines += "consumed=$consumed"
            diag?.record(lines.joinToString("\n"))
            return decide(pkg, ruleLabel, consumed)
        }

        // Package comes ONLY from the focused input (FOCUS_INPUT) — never prompt foreground.
        val field = device.focusedField()
        if (field == null) {
            lines += header(t0, e, null)
            lines += "field=(none)"
            return flush("passthrough", "no-field", null, "no-field", false)
        }
        val pkg = field.packageName
        val ime = device.fieldImeDiag(field)
        lines += header(t0, e, field)
        lines += fieldLine(field, ime)
        lines += "send_nodes_near=" + summarizeNearbySend(field)

        val state = store.getForEnter(pkg)
        if (state == null) {
            lines += "rule=loading"
            return flush("passthrough", "rule-loading", pkg, "loading", false)
        }
        val rule = state.rule
        val ruleLabel = rule.mode ?: resolveMode(rule).name
        val mode = resolveMode(rule)
        lines += ruleLine(rule, mode, ruleLabel)

        if (mode == Mode.OFF || mode == Mode.PASS_THROUGH) {
            return flush("passthrough", "mode-$mode", pkg, ruleLabel, false)
        }
        if (!ChatFieldGuard.isChatLike(field, rule)) {
            return flush("passthrough", "not-chat-like", pkg, ruleLabel, false)
        }

        if (e.isShiftPressed) {
            val (text, cursor) = insertNewline(field.text, field.selStart, field.selEnd)
            device.setText(field, text, cursor)
            return flush("setText-newline", "shift-enter", pkg, ruleLabel, true)
        }
        if (field.text.isBlank()) {
            return flush("swallow", "blank-field", pkg, ruleLabel, true)
        }
        if (inFlight != null) {
            return flush("swallow", "in-flight", pkg, ruleLabel, true)
        }

        val root = device.windowRoot(field)
        if (root == null) {
            return flush("passthrough", "no-window-root", pkg, ruleLabel, false)
        }

        if (rule.hasNodeRule) {
            lines.addAll(SendFinder.diagnoseLearned(root, field, rule))
        } else {
            lines += "strategy=learned (skipped: no node rule)"
        }
        lines.addAll(SendFinder.diagnoseAuto(root, field))

        val learned = if (rule.hasNodeRule) SendFinder.learned(root, field, rule) else null
        val node = learned ?: SendFinder.auto(root, field)
        if (node != null) {
            val via = if (learned != null) {
                when {
                    rule.sendViewId != null -> "learned-view-id"
                    rule.sendDesc != null || rule.sendText != null -> "learned-desc-text"
                    rule.sendClassPath != null -> "learned-classPath"
                    else -> "learned"
                }
            } else {
                "AUTO"
            }
            lines += "chosen=$via node id=${node.viewId} desc=${node.desc} class=${node.className} " +
                "bounds=${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}"
            if (!device.click(node)) {
                return flush("performAction-CLICK-failed", "click-returned-false", pkg, ruleLabel, false)
            }
            if (debug) device.notice(strings(com.meowgi.entersend.R.string.debug_send_button))
            verify(field, viaTap = false)
            return flush("performAction-CLICK", via, pkg, ruleLabel, true)
        }
        lines += "chosen=none (learned+auto miss)"

        if (rule.hasTapSpot) {
            if (rule.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                lines += "strategy=tap-spot FAIL consecutiveFailures=${rule.consecutiveFailures}"
                offerTeach(rule)
                return flush("passthrough", "tap-spot-max-failures", pkg, ruleLabel, false)
            }
            return tapSpot(field, rule, state.crop, lines, pkg, ruleLabel)
        }

        lines += "strategy=tap-spot (skipped: no tap spot)"
        offerTeach(rule)
        return flush("passthrough", "offer-teach-no-target", pkg, ruleLabel, false)
    }

    private fun decide(pkg: String?, ruleLabel: String, consumed: Boolean): Boolean {
        onDecision(pkg ?: "(null)", ruleLabel, consumed)
        return consumed
    }

    private fun tapSpot(
        field: Field,
        rule: AppRule,
        crop: Pixels?,
        lines: MutableList<String>,
        pkg: String,
        ruleLabel: String,
    ): Boolean {
        fun finish(outcome: String, reason: String, consumed: Boolean): Boolean {
            lines += "outcome=$outcome"
            lines += "reason=$reason"
            lines += "consumed=$consumed"
            diag?.record(lines.joinToString("\n"))
            return decide(pkg, ruleLabel, consumed)
        }

        val ws = device.windowState(field)
        if (ws == null) {
            lines += "strategy=tap-spot FAIL no-window-state"
            return finish("passthrough", "tap-spot-no-window", false)
        }
        val cands = TapSpot.candidates(rule, field, ws, ws.density, ws.keyboardShown)
        lines += "strategy=tap-spot candidates=${cands.joinToString { "${it.name}=(${it.point.first},${it.point.second})" }} " +
            "anchors dxDp=${rule.tapDxDp} dyDp=${rule.tapDyDp} fx=${rule.tapFx} fy=${rule.tapFy} " +
            "abs=${rule.tapX},${rule.tapY} taughtCrop=${rule.cropLeft},${rule.cropTop},${rule.cropRight},${rule.cropBottom}"
        if (cands.isEmpty()) {
            lines += "  (no on-screen candidates)"
            store.markNeedsRecheck(rule.packageName)
            offerTeach(rule)
            return finish("passthrough", "tap-spot-no-candidates", false)
        }
        if (crop == null || rule.cropLeft == null) {
            lines += "  cropMissing FAIL"
            offerTeach(rule)
            return finish("passthrough", "tap-spot-crop-missing", false)
        }
        val token = Any()
        inFlight = token
        // One full-screen capture, then crop-match each candidate (avoids per-cand rate limits).
        val fullRect = Box(0, 0, ws.width, ws.height)
        fun onFull(full: Pixels?, retried: Boolean) {
            if (inFlight !== token) return
            val extra = mutableListOf<String>()
            if (full == null) {
                if (!retried) {
                    extra += "  screenshot=null → retry once after ${SCREENSHOT_RETRY_MS}ms"
                    lines += extra
                    main.postDelayed({
                        if (inFlight !== token) return@postDelayed
                        device.capture(fullRect) { again -> onFull(again, retried = true) }
                    }, SCREENSHOT_RETRY_MS)
                    return
                }
                extra += "  screenshot=null after retry (no imeEnter — avoid false newline)"
                inFlight = null
                store.markNeedsRecheck(rule.packageName)
                offerTeach(rule)
                extra += "outcome=swallow"
                extra += "reason=crop-screenshot-null"
                extra += "consumed=true"
                diag?.record((lines + extra).joinToString("\n"))
                return
            }
            var won: TapSpot.Candidate? = null
            for ((i, cand) in cands.withIndex()) {
                val n = i + 1
                val cropRect = TapSpot.cropBox(cand.point.first, cand.point.second, ws)
                val live = full.crop(cropRect)
                if (live == null) {
                    extra += "  cand#$n name=${cand.name} tap=(${cand.point.first},${cand.point.second}) " +
                        "cropBox=${cropRect.left},${cropRect.top},${cropRect.right},${cropRect.bottom} cropExtract=FAIL"
                    continue
                }
                val scored = CropMatch.score(crop, live)
                extra += "  cand#$n name=${cand.name} tap=(${cand.point.first},${cand.point.second}) " +
                    "cropBox=${cropRect.left},${cropRect.top},${cropRect.right},${cropRect.bottom} ${scored.line()}"
                if (scored.pass) {
                    won = cand
                    break
                }
            }
            if (won != null) {
                val point = won.point
                android.util.Log.d(
                    "EnterSend",
                    "gesture screen=(${point.first},${point.second}) cand=${won.name} saved=(${rule.tapX},${rule.tapY})",
                )
                device.tap(point.first, point.second)
                if (debug) device.notice(strings(com.meowgi.entersend.R.string.debug_send_tap))
                verify(field, viaTap = true, token = token)
                extra += "chosen_candidate=${won.name}"
                extra += "outcome=dispatchGesture-tap"
                extra += "reason=tap-spot-crop-pass"
                extra += "consumed=true"
            } else {
                inFlight = null
                store.markNeedsRecheck(rule.packageName)
                device.imeEnter(field)
                offerTeach(rule)
                extra += "outcome=imeEnter"
                extra += "reason=crop-mismatch-fallback"
                extra += "consumed=true"
            }
            diag?.record((lines + extra).joinToString("\n"))
        }
        device.capture(fullRect) { live -> onFull(live, retried = false) }
        onDecision(pkg, ruleLabel, true)
        return true
    }

    private fun verify(field: Field, viaTap: Boolean, token: Any = Any()) {
        inFlight = token
        main.postDelayed({
            if (inFlight !== token) return@postDelayed
            val text = device.readText(field)
            when {
                text == null -> inFlight = null
                text.isBlank() -> {
                    inFlight = null
                    store.recordResult(field.packageName, true)
                    device.vibrate()
                    device.focus(field)
                }
                else -> {
                    inFlight = null
                    store.recordResult(field.packageName, false)
                    device.notice(strings(com.meowgi.entersend.R.string.send_failed))
                    val failures = store.get(field.packageName)?.rule?.consecutiveFailures ?: 0
                    if (viaTap && failures >= MAX_CONSECUTIVE_FAILURES) {
                        offerTeach(store.get(field.packageName)?.rule ?: AppRule(field.packageName))
                    }
                }
            }
        }, VERIFY_MS)
    }

    private fun header(t0: Long, e: KeyEvent, field: Field?): String {
        val pkg = field?.packageName ?: "(null)"
        return "=== ENTER ${EnterDiagLog.stamp(t0)} pkg=$pkg " +
            "shift=${e.isShiftPressed} ctrl=${e.isCtrlPressed} alt=${e.isAltPressed} " +
            "meta=0x${Integer.toHexString(e.metaState)} action=${e.action} " +
            "keyCode=${e.keyCode} repeat=${e.repeatCount} deviceId=${e.deviceId}"
    }

    private fun fieldLine(field: Field, ime: FieldImeDiag): String {
        val lines = field.text.count { it == '\n' } + if (field.text.isEmpty()) 0 else 1
        val b = field.bounds
        return "field class=${ime.className ?: field.className} id=${field.viewId} " +
            "hint=${field.hint} inputType=0x${Integer.toHexString(field.inputType)} " +
            "multiLine=${ime.multiLine} textLen=${field.text.length} lineCount=$lines " +
            "sel=${field.selStart}-${field.selEnd} " +
            "bounds=${b.left},${b.top},${b.right},${b.bottom} " +
            "imeActionId=${ime.imeActionId} imeActionLabel=${ime.imeActionLabel} " +
            "hasImeEnter=${ime.hasImeEnterAction} actions=${ime.actions.joinToString("|")} " +
            "extras=${ime.extrasHint}"
    }

    private fun ruleLine(rule: AppRule, mode: Mode, ruleLabel: String): String =
        "rule label=$ruleLabel resolvedMode=$mode storedMode=${rule.mode} " +
            "hasNode=${rule.hasNodeRule} hasTap=${rule.hasTapSpot} " +
            "sendViewId=${rule.sendViewId} sendDesc=${rule.sendDesc} sendText=${rule.sendText} " +
            "sendClassPath=${rule.sendClassPath} " +
            "tapDxDp=${rule.tapDxDp} tapDyDp=${rule.tapDyDp} tapX=${rule.tapX} tapY=${rule.tapY} " +
            "consecFail=${rule.consecutiveFailures} needsRecheck=${rule.needsRecheck}"

    private fun summarizeNearbySend(field: Field): String {
        val root = device.windowRoot(field) ?: return "(no-root)"
        val maxGap = kotlin.math.max(field.bounds.height * 2, 200).toDouble()
        val hits = root.flatten()
            .filter { it.visible && it.enabled && !it.editable && SendFinder.looksLikeSend(it) }
            .filter { it.bounds.distanceTo(field.bounds) <= maxGap }
            .take(5)
        if (hits.isEmpty()) return "(none)"
        return hits.joinToString("; ") { n ->
            "id=${n.viewId} desc=${n.desc} text=${n.text} " +
                "bounds=${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom}"
        }
    }

    companion object {
        const val VERIFY_MS = 500L
        const val MAX_CONSECUTIVE_FAILURES = 3
        /** AccessibilityService.takeScreenshot is ~1/s; one short retry before giving up. */
        const val SCREENSHOT_RETRY_MS = 350L
    }
}
