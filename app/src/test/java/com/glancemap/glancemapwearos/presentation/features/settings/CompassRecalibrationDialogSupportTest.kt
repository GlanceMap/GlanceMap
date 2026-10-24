package com.glancemap.glancemapwearos.presentation.features.settings

import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.presentation.features.navigate.CompassMarkerQuality
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassRecalibrationDialogSupportTest {
    @Test
    fun customCompassResetSucceedsWhenQualityImproves() {
        assertTrue(
            didCompleteCompassRecalibration(
                providerType = CompassProviderType.SENSOR_MANAGER,
                headingSourceResolved = true,
                qualityImproved = true,
                resultQuality = CompassMarkerQuality.LOW,
            ),
        )
    }

    @Test
    fun customCompassResetAlsoSucceedsWhenQualityIsUsableWithoutImprovement() {
        assertTrue(
            didCompleteCompassRecalibration(
                providerType = CompassProviderType.SENSOR_MANAGER,
                headingSourceResolved = true,
                qualityImproved = false,
                resultQuality = CompassMarkerQuality.MEDIUM,
            ),
        )
    }

    @Test
    fun customCompassResetFailsWithoutResolvedHeadingSource() {
        assertFalse(
            didCompleteCompassRecalibration(
                providerType = CompassProviderType.SENSOR_MANAGER,
                headingSourceResolved = false,
                qualityImproved = true,
                resultQuality = CompassMarkerQuality.GOOD,
            ),
        )
    }

    @Test
    fun googleFusedNeverUsesCustomResetSuccessPath() {
        assertFalse(
            didCompleteCompassRecalibration(
                providerType = CompassProviderType.GOOGLE_FUSED,
                headingSourceResolved = true,
                qualityImproved = true,
                resultQuality = CompassMarkerQuality.GOOD,
            ),
        )
    }
}
