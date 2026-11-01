package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCompassFallbackMemoryTest {
    @Test
    fun onlyAUsableSensorSampleDuringFusedFallbackIsRemembered() {
        val memory = PhoneCompassFallbackMemory()
        assertFalse(memory.preferSensor)

        memory.recordSample(PhoneCompassProviderMode.GOOGLE_FUSED, PhoneCompassPipeline.ROTATION_VECTOR, false, 10L)
        memory.recordSample(PhoneCompassProviderMode.GOOGLE_FUSED, PhoneCompassPipeline.ROTATION_VECTOR, true, null)
        memory.recordSample(PhoneCompassProviderMode.GOOGLE_FUSED, PhoneCompassPipeline.ROTATION_VECTOR, true, 0L)
        memory.recordSample(PhoneCompassProviderMode.GOOGLE_FUSED, PhoneCompassPipeline.GOOGLE_FUSED, true, 10L)
        memory.recordSample(PhoneCompassProviderMode.SENSOR_MANAGER, PhoneCompassPipeline.ROTATION_VECTOR, true, 10L)
        assertFalse(memory.preferSensor)

        memory.recordSample(PhoneCompassProviderMode.GOOGLE_FUSED, PhoneCompassPipeline.ROTATION_VECTOR, true, 10L)
        assertTrue(memory.preferSensor)
    }

    @Test
    fun resumedSensorRejectsSamplesFromBeforeRegistrationOrTheFuture() {
        assertFalse(phoneCompassSampleIsFreshForRestart(99L, 100L, 110L))
        assertTrue(phoneCompassSampleIsFreshForRestart(100L, 100L, 110L))
        assertFalse(phoneCompassSampleIsFreshForRestart(111L, 100L, 110L))
    }

    @Test
    fun oldFusedCallbacksAreIgnoredAfterSwitchingToTheSensor() {
        assertTrue(phoneCompassFusedCallbackIsCurrent(true, PhoneCompassPipeline.GOOGLE_FUSED, 4L, 4L))
        assertFalse(phoneCompassFusedCallbackIsCurrent(true, PhoneCompassPipeline.ROTATION_VECTOR, 4L, 4L))
        assertFalse(phoneCompassFusedCallbackIsCurrent(true, PhoneCompassPipeline.GOOGLE_FUSED, 5L, 4L))
        assertFalse(phoneCompassFusedCallbackIsCurrent(false, PhoneCompassPipeline.GOOGLE_FUSED, 4L, 4L))
    }

    @Test
    fun clearingTheMemoryAllowsThePreferredProviderToBeRetried() {
        val memory = PhoneCompassFallbackMemory()
        memory.recordSample(PhoneCompassProviderMode.GOOGLE_FUSED, PhoneCompassPipeline.ROTATION_VECTOR, true, 10L)
        assertTrue(memory.preferSensor)

        memory.clear()

        assertFalse(memory.preferSensor)
    }
}
