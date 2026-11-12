package com.meowgi.entersend.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.meowgi.entersend.core.AppLists

enum class Mode { AUTO, LEARNED, OFF, PASS_THROUGH }

/** How Settings labels a taught / default rule. */
enum class DisplayMode { AUTO, BUTTON, TAP_SPOT, OFF, PASS_THROUGH, NEVER }

/**
 * Everything EnterSend knows about one app. A missing row means "never seen", which is the same
 * as [AppRule] with only [packageName] set.
 *
 * Schema v2 adds field-relative tap anchors (dp), window-fraction fallback, keyboardShown,
 * consecutiveFailures and needsRecheck.
 */
@Entity(tableName = "app_rules")
data class AppRule(
    @PrimaryKey val packageName: String,
    /** User override of [Mode]; null means the default from [effectiveMode]. */
    val mode: String? = null,
    val teachDeclined: Boolean = false,
    /** Epoch day the teach card/notification was last shown, so it appears at most once per day. */
    val lastAskedDay: Long? = null,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    /** Consecutive failed tap-spot sends; after 3, stop tapping and offer re-teach. */
    val consecutiveFailures: Int = 0,
    /** Crop/anchor no longer matches — Settings shows "needs re-check". */
    val needsRecheck: Boolean = false,
    /** App versionCode when the rule was taught. */
    val taughtVersionCode: Long? = null,
    /** View id of the text field the rule was taught in. */
    val fieldViewId: String? = null,
    val sendViewId: String? = null,
    val sendText: String? = null,
    val sendDesc: String? = null,
    /** "ParentClass>Class" of the send node. */
    val sendClassPath: String? = null,
    /** Legacy absolute tap (v1); kept so upgraded rules still work until re-taught. */
    val tapX: Int? = null,
    val tapY: Int? = null,
    /** Primary anchor: dp from the focused field's right edge / vertical center. */
    val tapDxDp: Float? = null,
    val tapDyDp: Float? = null,
    /** Fallback anchor: fractions of the app window, used only when no text box is focused. */
    val tapFx: Float? = null,
    val tapFy: Float? = null,
    /** Absolute path of the PNG crop of the send button, in app-private storage. */
    val cropPath: String? = null,
    val cropLeft: Int? = null,
    val cropTop: Int? = null,
    val cropRight: Int? = null,
    val cropBottom: Int? = null,
    /** Configuration.orientation and app window size when the tap spot was taught. */
    val orientation: Int? = null,
    val windowWidth: Int? = null,
    val windowHeight: Int? = null,
    /** Whether the IME was visible when the tap spot was taught (fallback match). */
    val keyboardShown: Boolean? = null,
) {
    val hasNodeRule: Boolean
        get() = sendViewId != null || sendText != null || sendDesc != null || sendClassPath != null

    val hasTapSpot: Boolean
        get() = cropPath != null && cropLeft != null && cropTop != null && cropRight != null &&
            cropBottom != null && (
            (tapDxDp != null && tapDyDp != null) ||
                (tapFx != null && tapFy != null && orientation != null && windowWidth != null) ||
                (tapX != null && tapY != null && orientation != null && windowWidth != null && windowHeight != null)
            )

    val isTaught: Boolean get() = hasNodeRule || hasTapSpot

    val effectiveMode: Mode
        get() = mode?.let { m -> Mode.entries.firstOrNull { it.name == m } } ?: when {
            packageName in AppLists.NATIVE_ENTER -> Mode.PASS_THROUGH
            isTaught -> Mode.LEARNED
            else -> Mode.AUTO
        }

    val displayMode: DisplayMode
        get() = when {
            packageName in AppLists.NEVER -> DisplayMode.NEVER
            effectiveMode == Mode.OFF -> DisplayMode.OFF
            effectiveMode == Mode.PASS_THROUGH -> DisplayMode.PASS_THROUGH
            hasNodeRule -> DisplayMode.BUTTON
            hasTapSpot -> DisplayMode.TAP_SPOT
            else -> DisplayMode.AUTO
        }
}
