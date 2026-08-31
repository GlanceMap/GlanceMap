package com.glancemap.glancemapcompanionapp.map

import android.hardware.SensorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCompassSettingsTest {
    @Test
    fun defaultsMatchTheWatchProviderAndAutomaticSource() {
        val settings = PhoneCompassSettings()

        assertEquals(PhoneCompassProviderMode.GOOGLE_FUSED, settings.providerMode)
        assertEquals(PhoneCompassSettingsMode.AUTOMATIC, settings.settingsMode)
        assertEquals(PhoneCompassHeadingSourceMode.AUTO, settings.headingSourceMode)
        assertFalse(settings.calibrationAlertsEnabled)
        assertTrue(settings.accuracyDisplayEnabled)
    }

    @Test
    fun automaticModeClearsAnAdvancedHeadingSource() {
        val normalized =
            PhoneCompassSettings(
                settingsMode = PhoneCompassSettingsMode.AUTOMATIC,
                headingSourceMode = PhoneCompassHeadingSourceMode.MAGNETOMETER,
            ).normalized()

        assertEquals(PhoneCompassHeadingSourceMode.AUTO, normalized.headingSourceMode)
    }

    @Test
    fun advancedSourceResolutionHonorsAvailability() {
        assertEquals(
            PhoneCompassPipeline.ROTATION_VECTOR,
            resolvePhoneCompassPipeline(
                mode = PhoneCompassHeadingSourceMode.ROTATION_VECTOR,
                hasRotationVector = true,
                hasHeadingSensor = true,
                hasMagAccelerometerFallback = true,
            ),
        )
        assertEquals(
            PhoneCompassPipeline.NONE,
            resolvePhoneCompassPipeline(
                mode = PhoneCompassHeadingSourceMode.TYPE_HEADING,
                hasRotationVector = true,
                hasHeadingSensor = false,
                hasMagAccelerometerFallback = true,
            ),
        )
    }

    @Test
    fun lowAccuracySamplesRecommendCalibration() {
        val state =
            phoneCompassStateForSample(
                pipeline = PhoneCompassPipeline.ROTATION_VECTOR,
                headingDegrees = 90f,
                accuracy = SensorManager.SENSOR_STATUS_ACCURACY_LOW,
                hasSample = true,
                calibrationRecommended = true,
            )

        assertTrue(state.calibrationRecommended)
        assertEquals(90f, state.headingDegrees)
    }

    @Test
    fun rotationVectorSampleIsRenderableWhenDeviceOmitsAccuracyCallback() {
        assertEquals(
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
            phoneCompassEffectiveAccuracy(
                pipeline = PhoneCompassPipeline.ROTATION_VECTOR,
                accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                hasSample = true,
            ),
        )
        assertTrue(
            phoneCompassStateForSample(
                pipeline = PhoneCompassPipeline.ROTATION_VECTOR,
                headingDegrees = 90f,
                accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                hasSample = true,
            ).isRenderable,
        )
    }

    @Test
    fun unavailablePipelineRemainsUnreliableWithoutARealSample() {
        assertEquals(
            SensorManager.SENSOR_STATUS_UNRELIABLE,
            phoneCompassEffectiveAccuracy(
                pipeline = PhoneCompassPipeline.NONE,
                accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                hasSample = true,
            ),
        )
    }
}
