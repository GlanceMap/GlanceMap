package com.glancemap.glancemapwearos.presentation.features.recording.sensors

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingSensorPermissionsTest {
    @Test
    fun olderWatchesRequestTheLegacyHeartRatePermission() {
        assertEquals("android.permission.BODY_SENSORS", recordingHeartRatePermission(28))
        assertEquals("android.permission.BODY_SENSORS", recordingHeartRatePermission(35))
    }

    @Test
    fun api36RequestsThePermissionThatCanRegisterHeartRate() {
        assertEquals("android.permission.health.READ_HEART_RATE", recordingHeartRatePermission(36))
    }

    @Test
    fun newerWatchesKeepUsingTheGranularHeartRatePermission() {
        assertEquals("android.permission.health.READ_HEART_RATE", recordingHeartRatePermission(37))
    }
}
