package com.meowgi.entersend.core

import com.meowgi.entersend.data.AppRule
import java.util.Locale
import kotlin.math.max

/** Finds the send button in a window snapshot. Returns the node to click, or null. */
object SendFinder {
    private val SEND_WORDS = setOf("send", "gönder", "gonder", "gönderin")
    private val EXCLUDE_WORDS = setOf(
        "voice", "audio", "record", "sticker", "gif", "money", "payment", "location", "file",
        "photo", "image", "camera", "emoji", "ses", "sesli", "kayıt", "kaydet", "para", "ödeme",
        "konum", "dosya", "fotoğraf", "çıkartma",
    )
    private val ID_EXCLUDE = listOf(
        "sender", "voice", "audio", "record", "sticker", "gif", "money", "pay", "location",
        "attach", "camera", "photo", "emoji",
    )

    /**
     * Node matching the taught identity. Prefer view id / desc / text; for classPath-only rules
     * resolve fresh near the focused field (multiline growth moves the button off the taught tap).
     */
    fun learned(root: NodeSnap, field: Field, rule: AppRule): NodeSnap? {
        val byAttrs = rule.sendViewId != null || rule.sendDesc != null || rule.sendText != null
        val maxGap = max(field.bounds.height * 2, 200).toDouble()
        val matches = root.flatten().filter { n ->
            if (!n.visible || !n.enabled || n.editable) return@filter false
            when {
                byAttrs ->
                    (rule.sendViewId == null || n.viewId == rule.sendViewId) &&
                        (rule.sendDesc == null || n.desc == rule.sendDesc) &&
                        (rule.sendText == null || n.text == rule.sendText)
                rule.sendClassPath != null && n.classPath == rule.sendClassPath -> {
                    val x = rule.tapX
                    val y = rule.tapY
                    // Still covering the taught tap is ideal; otherwise near-field classPath.
                    (x != null && y != null && n.bounds.contains(x, y)) ||
                        n.bounds.distanceTo(field.bounds) <= maxGap
                }
                else -> false
            }
        }
        return matches.mapNotNull(::clickTarget).minByOrNull { it.bounds.distanceTo(field.bounds) }
    }

    /** Clickable node near the field that is labelled "send" in English or Turkish. */
    fun auto(root: NodeSnap, field: Field): NodeSnap? {
        val maxGap = max(field.bounds.height * 2, 200).toDouble()
        return root.flatten()
            .filter { it.visible && it.enabled && !it.editable && looksLikeSend(it) }
            .mapNotNull(::clickTarget)
            .filter { it.bounds.distanceTo(field.bounds) <= maxGap }
            .minByOrNull { it.bounds.distanceTo(field.bounds) }
    }

    fun looksLikeSend(n: NodeSnap): Boolean {
        val id = n.viewId?.substringAfter(":id/")?.lowercase(Locale.ROOT)
        if (id != null && ID_EXCLUDE.any { it in id }) return false
        val label = listOfNotNull(n.desc, n.text).joinToString(" ").lowercase(Locale.ROOT)
        val words = label.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (words.any { it in EXCLUDE_WORDS }) return false
        if (words.any { it in SEND_WORDS }) return true
        if (label.isNotBlank()) return false
        return id != null && "send" in id
    }

    private fun clickTarget(n: NodeSnap): NodeSnap? {
        var cur: NodeSnap? = n
        repeat(3) {
            val c = cur ?: return null
            if (c.clickable && c.enabled) return c
            cur = c.parent
        }
        return null
    }

    /** Logging-only: every node considered for a learned match (does not change selection). */
    fun diagnoseLearned(root: NodeSnap, field: Field, rule: AppRule): List<String> {
        val byAttrs = rule.sendViewId != null || rule.sendDesc != null || rule.sendText != null
        val maxGap = max(field.bounds.height * 2, 200).toDouble()
        val strategy = when {
            byAttrs -> "learned-attrs(viewId=${rule.sendViewId} desc=${rule.sendDesc} text=${rule.sendText})"
            rule.sendClassPath != null -> "learned-classPath(${rule.sendClassPath} tap=${rule.tapX},${rule.tapY})"
            else -> "learned-none"
        }
        val out = mutableListOf("strategy=$strategy")
        var i = 0
        for (n in root.flatten()) {
            if (!n.visible || !n.enabled || n.editable) continue
            val attrOk = byAttrs &&
                (rule.sendViewId == null || n.viewId == rule.sendViewId) &&
                (rule.sendDesc == null || n.desc == rule.sendDesc) &&
                (rule.sendText == null || n.text == rule.sendText)
            val x = rule.tapX
            val y = rule.tapY
            val coversTap = x != null && y != null && n.bounds.contains(x, y)
            val dist = n.bounds.distanceTo(field.bounds)
            val near = dist <= maxGap
            val classOk = !byAttrs && rule.sendClassPath != null && n.classPath == rule.sendClassPath
            val match = when {
                byAttrs -> attrOk
                classOk -> coversTap || near
                else -> false
            }
            if (!match && !attrOk && !classOk) continue
            i++
            val target = clickTarget(n)
            out += "  cand#$i id=${n.viewId} desc=${n.desc} text=${n.text} class=${n.className} " +
                "path=${n.classPath} bounds=${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom} " +
                "clickable=${n.clickable} coversTap=$coversTap distField=${"%.1f".format(dist)} near=$near " +
                "clickTarget=${target?.let { "${it.viewId}@${it.bounds.left},${it.bounds.top}" } ?: "null"} " +
                if (match && target != null) "PASS" else "FAIL"
        }
        if (i == 0) out += "  (no candidates)"
        return out
    }

    /** Logging-only: auto send-label candidates. */
    fun diagnoseAuto(root: NodeSnap, field: Field): List<String> {
        val maxGap = max(field.bounds.height * 2, 200).toDouble()
        val out = mutableListOf("strategy=AUTO")
        var i = 0
        for (n in root.flatten()) {
            if (!n.visible || !n.enabled || n.editable) continue
            val looks = looksLikeSend(n)
            val dist = n.bounds.distanceTo(field.bounds)
            val near = dist <= maxGap
            val target = clickTarget(n)
            if (!looks && target == null) continue
            if (!looks) continue
            i++
            out += "  cand#$i id=${n.viewId} desc=${n.desc} text=${n.text} class=${n.className} " +
                "bounds=${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom} " +
                "looksLikeSend=$looks distField=${"%.1f".format(dist)} near=$near " +
                "clickTarget=${target?.let { "${it.viewId}@${it.bounds.left},${it.bounds.top}" } ?: "null"} " +
                if (looks && target != null && near) "PASS" else "FAIL"
        }
        if (i == 0) out += "  (no candidates)"
        return out
    }
}
