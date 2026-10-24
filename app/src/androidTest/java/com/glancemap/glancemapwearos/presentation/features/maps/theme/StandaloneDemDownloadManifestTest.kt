package com.glancemap.glancemapwearos.presentation.features.maps.theme

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.presentation.features.recording.sensors.recordingHeartRatePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class StandaloneDemDownloadManifestTest {
    @Suppress("DEPRECATION")
    @Test
    fun demTransferHasAPrivateDataSyncForegroundService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service =
            context.packageManager.getServiceInfo(
                ComponentName(context, StandaloneDemDownloadForegroundService::class.java),
                0,
            )

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.foregroundServiceType)
        assertFalse(service.exported)
    }

    @Suppress("DEPRECATION")
    @Test
    fun installedAppDeclaresTheHeartRatePermissionForThisWatch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val declared =
            context.packageManager
                .getPackageInfo(
                    context.packageName,
                    PackageManager.GET_PERMISSIONS,
                ).requestedPermissions
                .orEmpty()

        assertTrue(recordingHeartRatePermission(Build.VERSION.SDK_INT) in declared)
    }
}
