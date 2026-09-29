package com.glancemap.glancemapwearos.presentation.features.navigate

import android.hardware.SensorManager
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import com.glancemap.glancemapwearos.domain.sensors.HeadingSource
import com.glancemap.glancemapwearos.domain.sensors.initialCompassRenderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NavigationMarkerVisualsTest {
    @Test
    fun sensorManagerColorsEnabledUsesDisplayedGoodQuality() {
        assertEquals(
            CompassMarkerQuality.GOOD,
            resolveCompassConeQuality(
                selectedProviderType = CompassProviderType.SENSOR_MANAGER,
                renderProviderType = CompassProviderType.SENSOR_MANAGER,
                accuracyColorsEnabled = true,
                displayedSensorQuality = CompassMarkerQuality.GOOD,
                honestQuality = CompassMarkerQuality.UNRELIABLE,
            ),
        )
    }

    @Test
    fun sensorManagerColorsEnabledUsesDisplayedDegradedQuality() {
        assertEquals(
            CompassMarkerQuality.LOW,
            resolveCompassConeQuality(
                selectedProviderType = CompassProviderType.SENSOR_MANAGER,
                renderProviderType = CompassProviderType.SENSOR_MANAGER,
                accuracyColorsEnabled = true,
                displayedSensorQuality = CompassMarkerQuality.LOW,
                honestQuality = CompassMarkerQuality.GOOD,
            ),
        )
    }

    @Test
    fun sensorManagerColorsDisabledKeepsGreenConeDefault() {
        assertEquals(
            CompassMarkerQuality.GOOD,
            resolveCompassConeQuality(
                selectedProviderType = CompassProviderType.SENSOR_MANAGER,
                renderProviderType = CompassProviderType.SENSOR_MANAGER,
                accuracyColorsEnabled = false,
                displayedSensorQuality = CompassMarkerQuality.UNRELIABLE,
                honestQuality = CompassMarkerQuality.UNRELIABLE,
            ),
        )
    }

    @Test
    fun trustedFusedHeadingUsesHonestGoodQuality() {
        assertEquals(
            CompassMarkerQuality.GOOD,
            resolveCompassConeQuality(
                selectedProviderType = CompassProviderType.GOOGLE_FUSED,
                renderProviderType = CompassProviderType.GOOGLE_FUSED,
                accuracyColorsEnabled = false,
                displayedSensorQuality = CompassMarkerQuality.UNRELIABLE,
                honestQuality = CompassMarkerQuality.GOOD,
            ),
        )
    }

    @Test
    fun untrustedFusedHeadingCannotBeManufacturedAsGood() {
        assertNotEquals(
            CompassMarkerQuality.GOOD,
            resolveCompassConeQuality(
                selectedProviderType = CompassProviderType.GOOGLE_FUSED,
                renderProviderType = CompassProviderType.GOOGLE_FUSED,
                accuracyColorsEnabled = false,
                displayedSensorQuality = CompassMarkerQuality.GOOD,
                honestQuality = CompassMarkerQuality.MEDIUM,
            ),
        )
    }

    @Test
    fun selectedFusedProviderUsingSensorFallbackStaysNeutral() {
        val fallbackState =
            initialCompassRenderState(CompassProviderType.GOOGLE_FUSED).copy(
                headingSource = HeadingSource.ROTATION_VECTOR,
                headingSampleElapsedRealtimeMs = 1_000L,
                headingRenderable = true,
            )

        val honestQuality =
            compassQualityReadingFromRenderState(
                renderState = fallbackState,
                nowElapsedMs = 1_100L,
            ).quality

        assertEquals(null, honestQuality)
        assertEquals(
            CompassMarkerQuality.NEUTRAL,
            resolveCompassConeQuality(
                selectedProviderType = CompassProviderType.GOOGLE_FUSED,
                renderProviderType = fallbackState.providerType,
                accuracyColorsEnabled = false,
                displayedSensorQuality = CompassMarkerQuality.GOOD,
                honestQuality = honestQuality,
            ),
        )
    }

    @Test
    fun untrustedFreshFusedHeadingCannotRenderAsGood() {
        val reading =
            compassQualityReadingFromRenderState(
                renderState =
                    initialCompassRenderState(CompassProviderType.GOOGLE_FUSED).copy(
                        accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                        headingErrorDeg = 8f,
                        headingSampleElapsedRealtimeMs = 1_000L,
                        headingSource = HeadingSource.FUSED_ORIENTATION,
                        headingRenderable = true,
                        headingTrusted = false,
                        trackingState = CompassTrackingState.TRACKING,
                    ),
                nowElapsedMs = 1_100L,
            )

        assertNotEquals(CompassMarkerQuality.GOOD, reading.quality)
        assertEquals(CompassMarkerQuality.MEDIUM, reading.quality)
    }

    @Test
    fun missingFusedUncertaintyFallsBackToPublishedAccuracy() {
        val reading =
            compassQualityReadingFromRenderState(
                renderState =
                    initialCompassRenderState(CompassProviderType.GOOGLE_FUSED).copy(
                        accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                        headingSampleElapsedRealtimeMs = 1_000L,
                        headingSource = HeadingSource.FUSED_ORIENTATION,
                        headingRenderable = true,
                        headingTrusted = true,
                    ),
                nowElapsedMs = 1_100L,
            )

        assertEquals(CompassMarkerQuality.GOOD, reading.quality)
    }

    @Test
    fun staleFusedHeadingIsAlwaysUnreliable() {
        val reading =
            compassQualityReadingFromRenderState(
                renderState =
                    initialCompassRenderState(CompassProviderType.GOOGLE_FUSED).copy(
                        accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                        headingErrorDeg = 8f,
                        headingSampleElapsedRealtimeMs = 1_000L,
                        headingSampleStale = true,
                        headingSource = HeadingSource.FUSED_ORIENTATION,
                        headingRenderable = true,
                        headingTrusted = true,
                    ),
                nowElapsedMs = 2_000L,
            )

        assertEquals(CompassMarkerQuality.UNRELIABLE, reading.quality)
    }

    @Test
    fun staleSensorManagerHeadingCannotRenderAsGood() {
        val reading =
            compassQualityReadingFromRenderState(
                renderState =
                    initialCompassRenderState(CompassProviderType.SENSOR_MANAGER).copy(
                        accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                        headingSampleElapsedRealtimeMs = 1_000L,
                        headingSampleStale = true,
                        headingSource = HeadingSource.ROTATION_VECTOR,
                        headingRenderable = false,
                    ),
                nowElapsedMs = 2_000L,
            )

        assertEquals(CompassMarkerQuality.UNRELIABLE, reading.quality)
    }
}
