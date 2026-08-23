package com.glancemap.glancemapwearos.presentation.features.navigate

import android.hardware.SensorManager
import com.glancemap.glancemapwearos.domain.sensors.CompassMagneticQuality
import com.glancemap.glancemapwearos.domain.sensors.CompassNorthBasis
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingReason
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import com.glancemap.glancemapwearos.domain.sensors.HeadingSource
import com.glancemap.glancemapwearos.domain.sensors.initialCompassRenderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigateDebugOverlayStateTest {
    @Test
    fun compassPopupShowsInterferenceIntegrityAndHeldAnchor() {
        val label = compassIntegrityDebugOverlayLabel(interferenceState(), renderedHeadingDeg = 85f)

        assertTrue(label.contains("I:DEGRADED(magnetic_interference)"))
        assertTrue(label.contains("F:138.6µT M:interference Q:yes"))
        assertTrue(label.contains("D:held/frozen anchor B:google_automatic"))
    }

    @Test
    fun compassPopupDistinguishesTrustedAndDegradedLiveDisplays() {
        val trusted =
            initialCompassRenderState(CompassProviderType.GOOGLE_FUSED).copy(
                headingDeg = 121f,
                headingSource = HeadingSource.FUSED_ORIENTATION,
                accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                headingSampleElapsedRealtimeMs = 1_000L,
                headingRenderable = true,
                headingTrusted = true,
                trackingState = CompassTrackingState.TRACKING,
                trackingReason = CompassTrackingReason.STABLE,
            )

        assertEquals("live trusted", compassDisplayedHeadingDebugState(trusted, 121f))
        assertEquals("degraded/live", compassDisplayedHeadingDebugState(interferenceState(), 121f))
    }

    private fun interferenceState() =
        initialCompassRenderState(CompassProviderType.GOOGLE_FUSED).copy(
            headingDeg = 121f,
            headingSource = HeadingSource.FUSED_ORIENTATION,
            accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
            headingSampleElapsedRealtimeMs = 1_000L,
            headingRenderable = true,
            trackingState = CompassTrackingState.DEGRADED,
            trackingReason = CompassTrackingReason.MAGNETIC_INTERFERENCE,
            magneticQuality = CompassMagneticQuality.INTERFERENCE,
            magneticInterference = true,
            magneticFieldUt = 138.6f,
            quarantineActive = true,
            northBasis = CompassNorthBasis.GOOGLE_AUTOMATIC,
        )
}
