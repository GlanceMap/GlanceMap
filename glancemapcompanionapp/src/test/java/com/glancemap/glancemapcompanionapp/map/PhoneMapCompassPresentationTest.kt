package com.glancemap.glancemapcompanionapp.map

import android.hardware.SensorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapCompassPresentationTest {
    @Test
    fun northUpKeepsTheMapAndNorthIndicatorFixedWhileTheMarkerFollowsHeading() {
        listOf(0f, 90f, 180f, 270f).forEach { heading ->
            val presentation = phoneMapCompassPresentation(PhoneMapOrientation.NORTH_UP, heading)

            assertEquals(0f, presentation.mapBearingDegrees, 0.001f)
            assertEquals(heading, requireNotNull(presentation.markerScreenRotationDegrees), 0.001f)
            assertEquals(0f, presentation.northIndicatorScreenRotationDegrees, 0.001f)
        }
    }

    @Test
    fun headingUpRotatesTheMapKeepsMarkerScreenUpAndShowsNorthRelativeToScreen() {
        val expectedNorthRotation = mapOf(0f to 0f, 90f to 270f, 180f to 180f, 270f to 90f)

        expectedNorthRotation.forEach { (heading, northRotation) ->
            val presentation = phoneMapCompassPresentation(PhoneMapOrientation.HEADING_UP, heading)

            assertEquals(heading, presentation.mapBearingDegrees, 0.001f)
            assertEquals(0f, requireNotNull(presentation.markerScreenRotationDegrees), 0.001f)
            assertEquals(northRotation, presentation.northIndicatorScreenRotationDegrees, 0.001f)
        }
    }

    @Test
    fun manualBearingDetachesFollowAndRemainsAuthoritativeUntilRecenter() {
        val manual = PhoneMapMode(orientation = PhoneMapOrientation.HEADING_UP).detachAfterManualRotation(90f)
        val presentation = phoneMapCompassPresentation(manual, 10f)

        assertEquals(90f, presentation.mapBearingDegrees, 0.001f)
        assertEquals(280f, requireNotNull(presentation.markerScreenRotationDegrees), 0.001f)
        assertEquals(270f, presentation.northIndicatorScreenRotationDegrees, 0.001f)

        val recentered = manual.recenterOnLocation()
        assertEquals(PhoneMapFollowMode.FOLLOW_LOCATION, recentered.follow)
        assertNull(recentered.manualBearingDegrees)
        assertEquals(10f, phoneMapCompassPresentation(recentered, 10f).mapBearingDegrees, 0.001f)
    }

    @Test
    fun manualBearingNormalizesAcrossZero() {
        val presentation = phoneMapCompassPresentation(PhoneMapMode().detachAfterManualRotation(359f), 1f)

        assertEquals(2f, requireNotNull(presentation.markerScreenRotationDegrees), 0.001f)
        assertEquals(1f, presentation.northIndicatorScreenRotationDegrees, 0.001f)
    }

    @Test
    fun headingWrapUsesTheShortestSmoothingPath() {
        assertEquals(1f, shortestPhoneHeadingDelta(targetDegrees = 0f, currentDegrees = 359f), 0.001f)
        assertEquals(359.24f, smoothPhoneHeading(currentDegrees = 359f, targetDegrees = 0f), 0.001f)
        assertEquals(-90f, mapsforgeRotationDegreesFor(90f), 0.001f)
        assertEquals(90f, mapsforgeRotationDegreesFor(270f), 0.001f)
    }

    @Test
    fun unavailableOrInvalidSensorSamplesNeverProduceRenderableHeading() {
        val unavailable =
            phoneCompassStateForSample(
                pipeline = PhoneCompassPipeline.NONE,
                headingDegrees = 90f,
                accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
            )
        val invalid =
            phoneCompassStateForSample(
                pipeline = PhoneCompassPipeline.ROTATION_VECTOR,
                headingDegrees = Float.NaN,
                accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
            )

        assertFalse(unavailable.sensorAvailable)
        assertFalse(unavailable.isRenderable)
        assertNull(unavailable.headingDegrees)
        assertTrue(invalid.sensorAvailable)
        assertFalse(invalid.isRenderable)
        assertNull(invalid.headingDegrees)
        assertEquals(
            1f,
            requireNotNull(
                phoneCompassStateForSample(
                    pipeline = PhoneCompassPipeline.ROTATION_VECTOR,
                    headingDegrees = 361f,
                    accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                ).headingDegrees,
            ),
            0.001f,
        )
    }

    @Test
    fun sensorPipelineUsesWearCompatibleFallbackPriority() {
        assertEquals(
            PhoneCompassPipeline.ROTATION_VECTOR,
            resolvePhoneCompassPipeline(
                hasRotationVector = true,
                hasHeadingSensor = true,
                hasMagAccelerometerFallback = true,
            ),
        )
        assertEquals(
            PhoneCompassPipeline.HEADING_SENSOR,
            resolvePhoneCompassPipeline(
                hasRotationVector = false,
                hasHeadingSensor = true,
                hasMagAccelerometerFallback = true,
            ),
        )
        assertEquals(
            PhoneCompassPipeline.MAG_ACCEL_FALLBACK,
            resolvePhoneCompassPipeline(
                hasRotationVector = false,
                hasHeadingSensor = false,
                hasMagAccelerometerFallback = true,
            ),
        )
    }
}
