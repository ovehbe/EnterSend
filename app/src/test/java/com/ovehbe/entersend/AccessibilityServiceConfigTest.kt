package com.ovehbe.entersend

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.res.XmlResourceParser
import android.util.AttributeSet
import android.util.Xml
import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/** Parses the real compiled accessibility-service XML shipped in the APK. */
@RunWith(RobolectricTestRunner::class)
class AccessibilityServiceConfigTest {
    private val ns = "http://schemas.android.com/apk/res/android"

    private val attrs: AttributeSet = run {
        val parser: XmlResourceParser = context().resources.getXml(R.xml.accessibility_service)
        while (parser.next() != XmlPullParser.START_TAG) {
            // skip to the accessibility-service element
        }
        assertEquals("accessibility-service", parser.name)
        Xml.asAttributeSet(parser)
    }

    private fun bool(name: String) = attrs.getAttributeBooleanValue(ns, name, false)
    private fun int(name: String) = attrs.getAttributeIntValue(ns, name, 0)

    @Test fun requestsKeyFilteringAndWindowContent() {
        val flags = int("accessibilityFlags")
        assertTrue(flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0)
        assertTrue(flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0)
        assertTrue(flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
        assertTrue(bool("canRequestFilterKeyEvents"))
        assertTrue(bool("canRetrieveWindowContent"))
        assertTrue(bool("canTakeScreenshot"))
        assertTrue(bool("canPerformGestures"))
        assertTrue(bool("isAccessibilityTool"))
    }

    @Test fun listensForWindowClicksAndTextAcrossAllPackages() {
        val types = int("accessibilityEventTypes")
        assertTrue(types and AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED != 0)
        assertTrue(types and AccessibilityEvent.TYPE_VIEW_CLICKED != 0)
        assertTrue(types and AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED != 0)
        // No packageNames → every app. Attribute absent ⇒ empty/default.
        val packages = attrs.getAttributeValue(ns, "packageNames")
        assertTrue("packageNames must be unset so all apps are watched, was $packages", packages.isNullOrEmpty())
        assertNotEquals(0, attrs.getAttributeResourceValue(ns, "description", 0))
    }
}
