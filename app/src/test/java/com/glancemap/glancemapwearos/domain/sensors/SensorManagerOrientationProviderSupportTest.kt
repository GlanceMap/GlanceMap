package com.glancemap.glancemapwearos.domain.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorManagerOrientationProviderSupportTest {
    @Test
    fun initialHeadingHasNoTimestampAndIsStale() {
        val freshness = SensorHeadingSampleFreshness()

        assertNull(freshness.sampleAtElapsedRealtimeMs)
        assertTrue(freshness.stale)
    }

    @Test
    fun publishedHeadingCarriesItsElapsedRealtimeAndIsFresh() {
        val freshness =
            SensorHeadingSampleFreshness.afterPublish(
                sampleAtElapsedRealtimeMs = 12_345L,
            )

        assertEquals(12_345L, freshness.sampleAtElapsedRealtimeMs)
        assertFalse(freshness.stale)
    }

    @Test
    fun lifecycleStopRetainsTimestampButMarksHeadingStale() {
        val stoppedFreshness =
            SensorHeadingSampleFreshness
                .afterPublish(sampleAtElapsedRealtimeMs = 12_345L)
                .markStale()

        assertEquals(12_345L, stoppedFreshness.sampleAtElapsedRealtimeMs)
        assertTrue(stoppedFreshness.stale)
    }

    @Test
    fun shutdownSensorThreadIsNeverReusedForFallbackRegistration() {
        assertFalse(
            shouldReuseSensorCallbackHandler(
                callbackThreadAlive = true,
                callbackThreadStopping = true,
            ),
        )
        assertTrue(
            shouldReuseSensorCallbackHandler(
                callbackThreadAlive = true,
                callbackThreadStopping = false,
            ),
        )
    }

    @Test
    fun rapidStopStartRejectsBothTheOldTimeoutAndStoppingHandler() {
        assertFalse(
            isCurrentFusedReadyTimeout(
                timeoutIsCurrent = false,
                started = true,
                usingFallback = false,
                awaitingFusedReady = true,
                timeoutRequestGeneration = 10L,
                activeRequestGeneration = 12L,
            ),
        )
        assertFalse(
            shouldReuseSensorCallbackHandler(
                callbackThreadAlive = true,
                callbackThreadStopping = true,
            ),
        )
    }
}
