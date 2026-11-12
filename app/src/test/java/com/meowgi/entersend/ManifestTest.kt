package com.meowgi.entersend

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import com.meowgi.entersend.service.EnterSendService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ManifestTest {
    private val pm = context().packageManager
    private val info = pm.getPackageInfo(context().packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES)

    @Test fun noInternetAndOnlyExpectedPermissions() {
        val requested = info.requestedPermissions.orEmpty().toSet()
        assertFalse(android.Manifest.permission.INTERNET in requested)
        // AndroidX may inject a package-private DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION.
        val unexpected = requested.filterNot {
            it in ALLOWED || it.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        }
        assertEquals(emptyList<String>(), unexpected)
    }

    @Test fun backupIsDisabled() {
        assertEquals(0, info.applicationInfo!!.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test fun applicationIdAndServiceUseMeowgiPackage() {
        assertEquals(EXPECTED_PACKAGE, context().packageName)
        assertEquals(EXPECTED_PACKAGE, javaClass.`package`!!.name)
        assertTrue(EnterSendService::class.java.name.startsWith("$EXPECTED_PACKAGE."))

        val service = info.services!!.single { it.name == EnterSendService::class.java.name }
        assertEquals(EXPECTED_PACKAGE, service.packageName)
        assertEquals(
            ComponentName(EXPECTED_PACKAGE, EnterSendService::class.java.name).flattenToString(),
            ComponentName(service.packageName, service.name).flattenToString(),
        )
        assertEquals(
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            service.permission,
        )
        assertTrue(service.flags and ServiceInfo.FLAG_ISOLATED_PROCESS == 0)
    }

    @Test fun mergedManifestContainsNoOvehbe() {
        // Robolectric's packaged manifest for the debug unit-test APK.
        val manifest = File("build/intermediates/packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml")
            .takeIf { it.exists() }
            ?: File("build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml")
        assertTrue("merged manifest missing at ${manifest.path}", manifest.exists())
        val text = manifest.readText()
        val legacy = listOf("com", "ovehbe").joinToString(".")
        assertFalse("merged manifest still mentions $legacy:\n$text", legacy in text)
        assertTrue(EXPECTED_PACKAGE in text)
        assertTrue(EnterSendService::class.java.name in text)
        assertTrue("com.meowgi.entersend.ui.MainActivity" in text)
    }

    companion object {
        const val EXPECTED_PACKAGE = "com.meowgi.entersend"

        /** Install-time permissions only; accessibility is granted by the user in system settings. */
        val ALLOWED = setOf(
            android.Manifest.permission.VIBRATE,
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.PACKAGE_USAGE_STATS,
        )
    }
}
