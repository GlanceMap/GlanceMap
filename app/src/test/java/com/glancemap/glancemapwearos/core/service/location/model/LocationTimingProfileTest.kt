package com.glancemap.glancemapwearos.core.service.location.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationTimingProfileTest {
    @Test
    fun oneSecondIntervalTightensPredictionAndFreshnessWindows() {
        val profile = resolveLocationTimingProfile(1_000L)

        assertEquals(10_000L, profile.markerTrustFreshnessMaxAgeMs)
        assertEquals(1_500L, profile.markerPredictionFreshnessMaxAgeMs)
        assertEquals(3_000L, profile.indicatorStaleThresholdMs)
        assertEquals(3_000L, profile.uiImmediateSkipMaxAgeMs)
        assertEquals(3_000L, profile.strictFreshFixMaxAgeMs)
        assertEquals(4_000L, profile.selfHealFixGapMs)
        assertEquals(8_000L, profile.autoFusedNoFixFailoverGapMs)
    }

    @Test
    fun threeSecondIntervalMatchesExpectedWalkingProfile() {
        val profile = resolveLocationTimingProfile(3_000L)

        assertEquals(10_000L, profile.markerTrustFreshnessMaxAgeMs)
        assertEquals(4_500L, profile.markerPredictionFreshnessMaxAgeMs)
        assertEquals(4_500L, profile.indicatorStaleThresholdMs)
        assertEquals(6_000L, profile.strictFreshFixMaxAgeMs)
        assertEquals(9_000L, profile.selfHealFixGapMs)
        assertEquals(12_000L, profile.autoFusedNoFixFailoverGapMs)
    }

    @Test
    fun longIntervalsStillCapPredictionWindow() {
        val profile = resolveLocationTimingProfile(60_000L)

        assertEquals(30_000L, profile.markerTrustFreshnessMaxAgeMs)
        assertEquals(12_000L, profile.markerPredictionFreshnessMaxAgeMs)
        assertEquals(90_000L, profile.indicatorStaleThresholdMs)
        assertEquals(90_000L, profile.uiImmediateSkipMaxAgeMs)
        assertEquals(120_000L, profile.strictFreshFixMaxAgeMs)
        assertEquals(60_000L, profile.wakeAnchorMaxAgeMs)
    }

    @Test
    fun twoMinuteIntervalKeepsPredictionShortButRespectsExpectedFixCadence() {
        val profile = resolveLocationTimingProfile(120_000L)

        assertEquals(30_000L, profile.markerTrustFreshnessMaxAgeMs)
        assertEquals(12_000L, profile.markerPredictionFreshnessMaxAgeMs)
        assertEquals(180_000L, profile.indicatorStaleThresholdMs)
        assertEquals(180_000L, profile.uiImmediateSkipMaxAgeMs)
        assertEquals(240_000L, profile.strictFreshFixMaxAgeMs)
    }

    @Test
    fun markerTrustFreshnessUsesThreeTimesCadenceWithTenToThirtySecondBounds() {
        assertEquals(10_000L, resolveMarkerTrustFreshnessMaxAgeMs(1_000L))
        assertEquals(10_000L, resolveMarkerTrustFreshnessMaxAgeMs(3_000L))
        assertEquals(15_000L, resolveMarkerTrustFreshnessMaxAgeMs(5_000L))
        assertEquals(30_000L, resolveMarkerTrustFreshnessMaxAgeMs(60_000L))
    }
}
