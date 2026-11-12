package com.meowgi.entersend.service

import android.view.accessibility.AccessibilityWindowInfo

/**
 * Foreground package for **new-app prompts only**.
 *
 * Never used by Enter handling — Enter always uses the focused input field's package.
 *
 * Prefers the TYPE_APPLICATION window that is active/focused. Ignores IME, accessibility
 * overlays, SystemUI, and our own package without clearing the previous app.
 */
object ForegroundPackage {
    data class WindowHint(
        val packageName: String,
        val type: Int,
        val layer: Int,
        val active: Boolean = false,
        val focused: Boolean = false,
    )

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

        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        // Prefer the active / focused application window (not merely highest layer).
        val preferred = apps.firstOrNull { it.active || it.focused }
            ?: apps.maxByOrNull { it.layer }
        usable(preferred?.packageName)?.let { return Resolved(it, "windows") }

        usable(rootPackage)?.let { return Resolved(it, "root") }
        usable(eventPackage)?.let { return Resolved(it, "event") }
        return null
    }

    fun isNoiseType(type: Int): Boolean =
        type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
            type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
            type == AccessibilityWindowInfo.TYPE_SYSTEM
}
