package com.meowgi.entersend.service

import android.view.accessibility.AccessibilityWindowInfo

/**
 * Resolves the real foreground **app** package for teach prompts.
 *
 * [AccessibilityEvent.getPackageName] is often the IME, SystemUI, an overlay, or our own
 * banner/prompt — not the chat app underneath. Prefer application windows, then the active
 * root, and only then the event package when it is not noise.
 */
object ForegroundPackage {
    data class WindowHint(val packageName: String, val type: Int, val layer: Int)

    data class Resolved(val packageName: String, val source: String)

    fun resolve(
        ownPackage: String,
        eventPackage: String?,
        rootPackage: String?,
        windows: List<WindowHint>,
        isNoise: (String) -> Boolean,
    ): Resolved? {
        fun usable(pkg: String?) =
            pkg?.takeIf { it.isNotBlank() && it != ownPackage && !isNoise(it) }

        val fromWindows = windows
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { hint -> usable(hint.packageName)?.let { it to hint.layer } }
            .sortedByDescending { it.second }
            .map { it.first }
            .firstOrNull()
        if (fromWindows != null) return Resolved(fromWindows, "windows")

        usable(rootPackage)?.let { return Resolved(it, "root") }
        usable(eventPackage)?.let { return Resolved(it, "event") }
        return null
    }
}
