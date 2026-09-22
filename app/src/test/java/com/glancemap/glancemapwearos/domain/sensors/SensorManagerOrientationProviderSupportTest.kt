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

    @Test
    fun freshAccelAndMagnetometerPairIsAcceptedFromSourceTimestamps() {
        val validity =
            validateSensorMagAccelPair(
                state = pairState(accelerometerAtMs = 9_900L, magnetometerAtMs = 10_000L),
                nowElapsedRealtimeMs = 10_000L,
                registrationGeneration = 7L,
            )

        assertTrue(validity.accepted)
        assertEquals(SensorMagAccelPairReason.ACCEPTED, validity.reason)
        assertEquals(100L, validity.pairAgeMs)
        assertEquals(100L, validity.pairSkewMs)
    }

    @Test
    fun freshAccelerometerCannotPairWithStaleMagnetometer() {
        val validity =
            validateSensorMagAccelPair(
                state =
                    pairState(
                        accelerometerAtMs = 10_000L,
                        magnetometerAtMs = 10_000L - SENSOR_MAG_ACCEL_COMPONENT_STALE_MS,
                    ),
                nowElapsedRealtimeMs = 10_000L,
                registrationGeneration = 7L,
            )

        assertFalse(validity.accepted)
        assertEquals(SensorMagAccelPairReason.MAGNETOMETER_STALE, validity.reason)
    }

    @Test
    fun freshMagnetometerCannotPairWithStaleAccelerometer() {
        val validity =
            validateSensorMagAccelPair(
                state =
                    pairState(
                        accelerometerAtMs = 10_000L - SENSOR_MAG_ACCEL_COMPONENT_STALE_MS,
                        magnetometerAtMs = 10_000L,
                    ),
                nowElapsedRealtimeMs = 10_000L,
                registrationGeneration = 7L,
            )

        assertFalse(validity.accepted)
        assertEquals(SensorMagAccelPairReason.ACCELEROMETER_STALE, validity.reason)
    }

    @Test
    fun currentComponentsWithExcessiveSourceTimeSkewAreRejected() {
        val validity =
            validateSensorMagAccelPair(
                state =
                    pairState(
                        accelerometerAtMs = 9_500L,
                        magnetometerAtMs = 10_000L,
                    ),
                nowElapsedRealtimeMs = 10_000L,
                registrationGeneration = 7L,
            )

        assertFalse(validity.accepted)
        assertEquals(SensorMagAccelPairReason.EXCESSIVE_SKEW, validity.reason)
    }

    @Test
    fun componentSilenceMakesTheFallbackPairStale() {
        val validity =
            validateSensorMagAccelPair(
                state =
                    pairState(
                        accelerometerAtMs = 12_000L,
                        magnetometerAtMs = 10_500L,
                    ),
                nowElapsedRealtimeMs = 12_000L,
                registrationGeneration = 7L,
            )

        assertFalse(validity.accepted)
        assertEquals(SensorMagAccelPairReason.MAGNETOMETER_STALE, validity.reason)
    }

    @Test
    fun stationaryFreshEventsRemainAValidPair() {
        val validity =
            validateSensorMagAccelPair(
                state = pairState(accelerometerAtMs = 20_000L, magnetometerAtMs = 20_000L),
                nowElapsedRealtimeMs = 20_000L,
                registrationGeneration = 7L,
            )

        assertTrue(validity.accepted)
    }

    @Test
    fun reregistrationClearsOldFallbackComponentsBeforeNewSamplesArrive() {
        val previousGeneration = pairState(accelerometerAtMs = 30_000L, magnetometerAtMs = 30_000L)
        val afterReregistration =
            updateSensorMagAccelPairState(
                state = SensorMagAccelPairState(),
                component = SensorMagAccelComponent.ACCELEROMETER,
                sourceTimestampElapsedRealtimeMs = 31_000L,
                registrationGeneration = 8L,
            )

        assertTrue(
            validateSensorMagAccelPair(
                state = previousGeneration,
                nowElapsedRealtimeMs = 30_000L,
                registrationGeneration = 7L,
            ).accepted,
        )
        assertEquals(
            SensorMagAccelPairReason.MAGNETOMETER_MISSING,
            validateSensorMagAccelPair(
                state = afterReregistration,
                nowElapsedRealtimeMs = 31_000L,
                registrationGeneration = 8L,
            ).reason,
        )
    }

    @Test
    fun oldGenerationAccelerometerCannotPairWithNewGenerationMagnetometer() {
        val validity =
            validateSensorMagAccelPair(
                state =
                    pairState(
                        accelerometerAtMs = 40_000L,
                        magnetometerAtMs = 40_000L,
                        accelerometerGeneration = 7L,
                        magnetometerGeneration = 8L,
                    ),
                nowElapsedRealtimeMs = 40_000L,
                registrationGeneration = 8L,
            )

        assertFalse(validity.accepted)
        assertEquals(SensorMagAccelPairReason.ACCELEROMETER_GENERATION_MISMATCH, validity.reason)
    }

    @Test
    fun oldGenerationMagnetometerCannotPairWithNewGenerationAccelerometer() {
        val validity =
            validateSensorMagAccelPair(
                state =
                    pairState(
                        accelerometerAtMs = 40_000L,
                        magnetometerAtMs = 40_000L,
                        accelerometerGeneration = 8L,
                        magnetometerGeneration = 7L,
                    ),
                nowElapsedRealtimeMs = 40_000L,
                registrationGeneration = 8L,
            )

        assertFalse(validity.accepted)
        assertEquals(SensorMagAccelPairReason.MAGNETOMETER_GENERATION_MISMATCH, validity.reason)
    }

    @Test
    fun failedRequiredFallbackRegistrationsAreNotOperational() {
        val accelerometerFailed =
            CompassSensorRegistrationResult(
                accelerometerRegistered = false,
                magnetometerRegistered = true,
            )
        val magnetometerFailed =
            CompassSensorRegistrationResult(
                accelerometerRegistered = true,
                magnetometerRegistered = false,
            )

        assertFalse(accelerometerFailed.isOperational(HeadingPipeline.MAG_ACCEL_FALLBACK))
        assertFalse(magnetometerFailed.isOperational(HeadingPipeline.MAG_ACCEL_FALLBACK))
        assertTrue(
            buildCompassSensorRegistrationTrace(
                registrationGeneration = 9L,
                pipeline = HeadingPipeline.MAG_ACCEL_FALLBACK,
                result = magnetometerFailed,
            ).contains("operational=false"),
        )
    }

    @Test
    fun partialFallbackRegistrationCannotClaimOperationalAndSuccessfulReregistrationRecovers() {
        val partial =
            CompassSensorRegistrationResult(
                accelerometerRegistered = true,
                magnetometerRegistered = false,
            )
        val recovered =
            CompassSensorRegistrationResult(
                accelerometerRegistered = true,
                magnetometerRegistered = true,
            )

        assertFalse(partial.isOperational(HeadingPipeline.MAG_ACCEL_FALLBACK))
        assertTrue(recovered.isOperational(HeadingPipeline.MAG_ACCEL_FALLBACK))
    }

    @Test
    fun optionalMagnetometerRegistrationDoesNotDisableHeadingSensorPipeline() {
        val result =
            CompassSensorRegistrationResult(
                headingSensorRegistered = true,
                magnetometerRegistered = false,
            )

        assertTrue(result.isOperational(HeadingPipeline.HEADING_SENSOR))
    }

    @Test
    fun deepTracePairEvidenceIncludesTheRejectionReasonAndSourceTiming() {
        val validity =
            validateSensorMagAccelPair(
                state =
                    pairState(
                        accelerometerAtMs = 50_000L,
                        magnetometerAtMs = 50_000L - SENSOR_MAG_ACCEL_COMPONENT_STALE_MS,
                    ),
                nowElapsedRealtimeMs = 50_000L,
                registrationGeneration = 7L,
            )

        val trace = buildSensorMagAccelPairTrace(registrationGeneration = 7L, validity = validity)

        assertTrue(trace.contains("reason=magnetometer_stale"))
        assertTrue(trace.contains("accelAtMs=50000"))
        assertTrue(trace.contains("magAtMs=48500"))
    }

    private fun pairState(
        accelerometerAtMs: Long,
        magnetometerAtMs: Long,
        accelerometerGeneration: Long = 7L,
        magnetometerGeneration: Long = 7L,
    ): SensorMagAccelPairState =
        SensorMagAccelPairState(
            accelerometer =
                SensorMagAccelComponentSample(
                    sourceTimestampElapsedRealtimeMs = accelerometerAtMs,
                    registrationGeneration = accelerometerGeneration,
                ),
            magnetometer =
                SensorMagAccelComponentSample(
                    sourceTimestampElapsedRealtimeMs = magnetometerAtMs,
                    registrationGeneration = magnetometerGeneration,
                ),
        )
}
