package com.glancemap.glancemapwearos.core.service.location.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GnssFixQualityTest {
    private val quality =
        GnssFixQuality(satellitesUsedInFix = 28, usedCn0AvgDbHz = 31f, statusElapsedRealtimeMs = 10_000L)

    @Test
    fun statusTakenCloseToTheFixIsAttached() {
        assertEquals(quality, gnssFixQualityForFix(quality, fixElapsedRealtimeMs = 10_000L))
        assertEquals(quality, gnssFixQualityForFix(quality, fixElapsedRealtimeMs = 13_000L))
        assertEquals(quality, gnssFixQualityForFix(quality, fixElapsedRealtimeMs = 7_000L))
    }

    @Test
    fun staleOrMissingStatusIsNotAttachedToADelayedFix() {
        assertNull(gnssFixQualityForFix(quality, fixElapsedRealtimeMs = 13_001L))
        assertNull(gnssFixQualityForFix(quality, fixElapsedRealtimeMs = 6_999L))
        assertNull(gnssFixQualityForFix(null, fixElapsedRealtimeMs = 10_000L))
        assertNull(gnssFixQualityForFix(quality, fixElapsedRealtimeMs = 0L))
    }

    @Test
    fun statusWithoutUsedSatellitesDoesNotDescribeAFix() {
        assertNull(gnssFixQualityForFix(quality.copy(satellitesUsedInFix = 0), fixElapsedRealtimeMs = 10_000L))
    }
}
