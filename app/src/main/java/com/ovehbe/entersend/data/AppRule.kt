package com.ovehbe.entersend.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ovehbe.entersend.core.AppLists

enum class Mode { AUTO, LEARNED, OFF, PASS_THROUGH }

/**
 * Everything EnterSend knows about one app. A missing row means "never seen", which is the same
 * as [AppRule] with only [packageName] set.
 */
@Entity(tableName = "app_rules")
data class AppRule(
    @PrimaryKey val packageName: String,
    /** User override of [Mode]; null means the default from [effectiveMode]. */
    val mode: String? = null,
    val teachDeclined: Boolean = false,
    /** Epoch day the teach card was last shown, so it appears at most once per day. */
    val lastAskedDay: Long? = null,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    /** App versionCode when the rule was taught. */
    val taughtVersionCode: Long? = null,
    /** View id of the text field the rule was taught in. */
    val fieldViewId: String? = null,
    val sendViewId: String? = null,
    val sendText: String? = null,
    val sendDesc: String? = null,
    /** "ParentClass>Class" of the send node. */
    val sendClassPath: String? = null,
    val tapX: Int? = null,
    val tapY: Int? = null,
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
) {
    val hasNodeRule: Boolean
        get() = sendViewId != null || sendText != null || sendDesc != null || sendClassPath != null

    val hasTapSpot: Boolean
        get() = tapX != null && tapY != null && cropPath != null && cropLeft != null &&
            cropTop != null && cropRight != null && cropBottom != null &&
            orientation != null && windowWidth != null && windowHeight != null

    val isTaught: Boolean get() = hasNodeRule || hasTapSpot

    val effectiveMode: Mode
        get() = mode?.let { m -> Mode.entries.firstOrNull { it.name == m } } ?: when {
            packageName in AppLists.NATIVE_ENTER -> Mode.PASS_THROUGH
            isTaught -> Mode.LEARNED
            else -> Mode.AUTO
        }
}
