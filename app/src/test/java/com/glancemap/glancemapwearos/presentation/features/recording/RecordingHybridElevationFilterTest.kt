package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingHybridElevationFilterTest {
    @Test
    fun onlySmartModeUsesTheHybridElevationFilter() {
        assertTrue(SettingsRepository.RECORDING_ELEVATION_SOURCE_AUTO.usesHybridRecordingElevation())
        assertFalse(SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM.usesHybridRecordingElevation())
        assertFalse(SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS.usesHybridRecordingElevation())
    }

    @Test
    fun pressureChangeAddsResponsiveClimbWhileDemAnchorsAbsoluteAltitude() {
        val filter = RecordingHybridElevationFilter()
        val first = filter.update(100.0, "DEM", 1_000.0, 0L, enabled = true, startsNewSegment = false)
        val second = filter.update(100.0, "DEM", 998.0, 10_000L, enabled = true, startsNewSegment = false)

        assertEquals(100.0, first.elevationMeters ?: Double.NaN, 0.01)
        assertFalse(first.pressureUsed)
        assertTrue(second.pressureUsed)
        assertEquals(RECORDING_ELEVATION_SOURCE_HYBRID, second.elevationSource)
        assertTrue((second.elevationMeters ?: 0.0) > 110.0)
        assertTrue(second.absoluteAnchorCorrectionMeters < 0.0)
    }

    @Test
    fun absoluteAnchorLimitsSlowPressureDrift() {
        val filter = RecordingHybridElevationFilter()
        filter.update(100.0, "DEM", 1_000.0, 0L, enabled = true, startsNewSegment = false)
        var result: RecordingHybridElevationResult? = null
        repeat(60) { index ->
            result =
                filter.update(
                    absoluteElevationMeters = 100.0,
                    absoluteElevationSource = "DEM",
                    pressureHpa = 1_000.0 - (index + 1) * 0.02,
                    timeMillis = (index + 1) * 10_000L,
                    enabled = true,
                    startsNewSegment = false,
                )
        }

        assertTrue((result?.elevationMeters ?: Double.MAX_VALUE) < 106.0)
    }

    @Test
    fun newSegmentResetsPressureContinuity() {
        val filter = RecordingHybridElevationFilter()
        filter.update(100.0, "DEM", 1_000.0, 0L, enabled = true, startsNewSegment = false)

        val reset =
            filter.update(
                absoluteElevationMeters = 220.0,
                absoluteElevationSource = "DEM",
                pressureHpa = 980.0,
                timeMillis = 120_000L,
                enabled = true,
                startsNewSegment = true,
            )

        assertEquals(220.0, reset.elevationMeters ?: Double.NaN, 0.01)
        assertFalse(reset.pressureUsed)
    }

    @Test
    fun unavailablePressureKeepsAbsoluteElevationAndSource() {
        val result =
            RecordingHybridElevationFilter()
                .update(125.0, "DEM", null, 10_000L, enabled = true, startsNewSegment = false)

        assertEquals(125.0, result.elevationMeters ?: Double.NaN, 0.01)
        assertEquals("DEM", result.elevationSource)
        assertFalse(result.pressureUsed)
    }
}
