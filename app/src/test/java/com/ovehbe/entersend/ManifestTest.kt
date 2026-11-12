package com.ovehbe.entersend

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManifestTest {
    private val info = context().packageManager
        .getPackageInfo(context().packageName, PackageManager.GET_PERMISSIONS)

    @Test fun noInternetAndOnlyExpectedPermissions() {
        val requested = info.requestedPermissions.orEmpty().toSet()
        assertFalse(android.Manifest.permission.INTERNET in requested)
        assertEquals(emptySet<String>(), requested - ALLOWED)
    }

    @Test fun backupIsDisabled() {
        assertEquals(0, info.applicationInfo!!.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    companion object {
        /** Install-time permissions only; accessibility is granted by the user in system settings. */
        val ALLOWED = setOf(
            android.Manifest.permission.VIBRATE,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
