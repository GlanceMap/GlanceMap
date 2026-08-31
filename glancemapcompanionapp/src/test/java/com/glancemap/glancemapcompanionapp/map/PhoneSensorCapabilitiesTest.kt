package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSensorCapabilitiesTest {
    @Test
    fun compassIsAvailableWhenEitherCompassFeatureOrMagnetometerExists() {
        assertTrue(
            PhoneSensorCapabilities(
                gpsAvailable = false,
                compassFeatureAvailable = true,
                headingSensorAvailable = false,
                rotationVectorAvailable = false,
                accelerometerAvailable = false,
                magnetometerAvailable = false,
                gyroscopeAvailable = false,
                barometerAvailable = false,
                stepDetectorAvailable = false,
                stepCounterAvailable = false,
            ).compassAvailable,
        )
        assertTrue(
            PhoneSensorCapabilities(
                gpsAvailable = false,
                compassFeatureAvailable = false,
                headingSensorAvailable = false,
                rotationVectorAvailable = false,
                accelerometerAvailable = false,
                magnetometerAvailable = true,
                gyroscopeAvailable = false,
                barometerAvailable = false,
                stepDetectorAvailable = false,
                stepCounterAvailable = false,
            ).compassAvailable,
        )
        assertFalse(
            PhoneSensorCapabilities(
                gpsAvailable = false,
                compassFeatureAvailable = false,
                headingSensorAvailable = false,
                rotationVectorAvailable = false,
                accelerometerAvailable = false,
                magnetometerAvailable = false,
                gyroscopeAvailable = false,
                barometerAvailable = false,
                stepDetectorAvailable = false,
                stepCounterAvailable = false,
            ).compassAvailable,
        )
    }
}
