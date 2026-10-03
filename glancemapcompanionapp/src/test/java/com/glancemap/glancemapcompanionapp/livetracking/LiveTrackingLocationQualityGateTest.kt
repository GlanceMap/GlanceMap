package com.glancemap.glancemapcompanionapp.livetracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTrackingLocationQualityGateTest {
    @Test
    fun acceptsNormalWalkingMovement() {
        val gate = gate()

        assertEquals(LiveTrackingLocationQualityResult.ACCEPT, evaluate(gate, fix(0.0, 0, speed = 1f)).result)
        val decision = evaluate(gate, fix(60.0, 60, speed = 1f))

        assertEquals(LiveTrackingLocationQualityResult.ACCEPT, decision.result)
        assertEquals("consistent_fix", decision.reason)
    }

    @Test
    fun acceptsStationaryNoisyFixes() {
        val gate = gate()

        evaluate(gate, fix(0.0, 0))
        val decision = evaluate(gate, fix(12.0, 60))

        assertEquals(LiveTrackingLocationQualityResult.ACCEPT, decision.result)
    }

    @Test
    fun acceptsNormalCyclingMovementWhenReportedSpeedAgrees() {
        val gate = gate()

        evaluate(gate, fix(0.0, 0, speed = 8.3f))
        val decision = evaluate(gate, fix(500.0, 60, speed = 8.3f))

        assertEquals(LiveTrackingLocationQualityResult.ACCEPT, decision.result)
    }

    @Test
    fun quarantinesAnIsolatedKilometreSpikeAndAcceptsTheReturn() {
        val gate = gate()

        evaluate(gate, fix(0.0, 0))
        val spike = evaluate(gate, fix(1_000.0, 60))
        val recovery = evaluate(gate, fix(20.0, 120))

        assertEquals(LiveTrackingLocationQualityResult.SUSPECT, spike.result)
        assertEquals("inconsistent_jump", spike.reason)
        assertEquals(LiveTrackingLocationQualityResult.ACCEPT, recovery.result)
        assertEquals("returned_to_previous_area", recovery.reason)
    }

    @Test
    fun holdsRepeatedBadLocationsUntilTheTrackReturns() {
        val gate = gate()

        evaluate(gate, fix(0.0, 0))
        assertEquals(LiveTrackingLocationQualityResult.SUSPECT, evaluate(gate, fix(1_000.0, 60)).result)
        val repeated = evaluate(gate, fix(1_005.0, 120))
        val recovery = evaluate(gate, fix(15.0, 180))

        assertEquals(LiveTrackingLocationQualityResult.SUSPECT, repeated.result)
        assertEquals("repeated_suspect_area", repeated.reason)
        assertEquals(LiveTrackingLocationQualityResult.ACCEPT, recovery.result)
    }

    @Test
    fun confirmsGenuineMovementWithAConsistentFollowUpFix() {
        val gate = gate()

        evaluate(gate, fix(0.0, 0))
        assertEquals(LiveTrackingLocationQualityResult.SUSPECT, evaluate(gate, fix(1_000.0, 60)).result)
        val confirmed = evaluate(gate, fix(1_100.0, 120))

        assertEquals(LiveTrackingLocationQualityResult.ACCEPT, confirmed.result)
        assertEquals("confirmed_suspect_movement", confirmed.reason)
    }

    @Test
    fun convergesOnARealRelocationWhenTheUserStopsInTheNewArea() {
        val gate = gate()

        evaluate(gate, fix(0.0, 0))
        assertEquals(LiveTrackingLocationQualityResult.SUSPECT, evaluate(gate, fix(1_000.0, 60)).result)
        assertEquals(LiveTrackingLocationQualityResult.SUSPECT, evaluate(gate, fix(1_005.0, 120)).result)
        val confirmed = evaluate(gate, fix(1_010.0, 180))

        assertEquals(LiveTrackingLocationQualityResult.ACCEPT, confirmed.result)
        assertEquals("confirmed_suspect_area", confirmed.reason)
    }

    @Test
    fun doesNotTrustADeceptivelySmallAccuracyForAnInconsistentSpike() {
        val gate = gate()

        evaluate(gate, fix(0.0, 0, accuracy = 3f))
        val decision = evaluate(gate, fix(1_000.0, 60, accuracy = 3f))

        assertEquals(LiveTrackingLocationQualityResult.SUSPECT, decision.result)
        assertTrue(decision.distanceFromPreviousMeters!! > 900.0)
    }

    @Test
    fun poorAccuracyCannotRaiseTheLargeJumpThreshold() {
        val gate = gate()

        evaluate(gate, fix(0.0, 0, accuracy = 20f))
        val decision = evaluate(gate, fix(800.0, 15, accuracy = 350f))

        assertEquals(LiveTrackingLocationQualityResult.SUSPECT, decision.result)
        assertEquals(400.0, decision.effectiveJumpThresholdMeters!!, 0.0)
        assertTrue(decision.poorAccuracy)
        assertEquals(LiveTrackingSpeedEvidence.UNAVAILABLE, decision.speedEvidence)
    }

    @Test
    fun poorAccuracyWithSmallSpatialMovementIsStillAccepted() {
        val gate = gate()

        evaluate(gate, fix(0.0, 0, accuracy = 20f))
        val decision = evaluate(gate, fix(20.0, 15, accuracy = 350f))

        assertEquals(LiveTrackingLocationQualityResult.ACCEPT, decision.result)
        assertTrue(decision.poorAccuracy)
    }

    @Test
    fun compatibleReportedSpeedCanConfirmLargeMovement() {
        val gate = gate()

        evaluate(gate, fix(0.0, 0, accuracy = 20f))
        val decision = evaluate(gate, fix(500.0, 15, accuracy = 350f, speed = 33.3f, speedAccuracy = 4f))

        assertEquals(LiveTrackingLocationQualityResult.ACCEPT, decision.result)
        assertEquals(LiveTrackingSpeedEvidence.CORROBORATED, decision.speedEvidence)
    }

    @Test
    fun incompatibleReportedSpeedMakesLargeMovementSuspect() {
        val gate = gate()

        evaluate(gate, fix(0.0, 0, accuracy = 20f))
        val decision = evaluate(gate, fix(1_000.0, 20, accuracy = 350f, speed = 4f, speedAccuracy = 2f))

        assertEquals(LiveTrackingLocationQualityResult.SUSPECT, decision.result)
        assertEquals(LiveTrackingSpeedEvidence.CONTRADICTED, decision.speedEvidence)
    }

    @Test
    fun rescueCooldownPreventsImmediateRepeatedRequests() {
        val cooldownMillis = 120_000L
        val lastRequest = 1_000_000_000L

        assertEquals(
            cooldownMillis,
            liveTrackingRescueCooldownRemainingMillis(lastRequest, lastRequest, cooldownMillis),
        )
        assertEquals(
            1_000L,
            liveTrackingRescueCooldownRemainingMillis(
                lastRequest,
                lastRequest + 119_000L * NANOS_PER_MILLISECOND,
                cooldownMillis,
            ),
        )
        assertFalse(
            liveTrackingRescueCooldownRemainingMillis(
                lastRequest,
                lastRequest + cooldownMillis * NANOS_PER_MILLISECOND,
                cooldownMillis,
            ) != null,
        )
    }

    @Test
    fun rejectsAStaleCachedFixUsingMonotonicAge() {
        val fix = fix(0.0, 0)
        val elapsedRealtimeNanos = checkNotNull(fix.elapsedRealtimeNanos)

        val age =
            liveTrackingLocationAgeMillis(
                fix = fix,
                nowElapsedRealtimeNanos = elapsedRealtimeNanos + 3 * 60 * NANOS_PER_SECOND,
                nowEpochMilliseconds = fix.epochMilliseconds,
            )

        assertEquals(3 * 60 * 1000L, age)
        assertEquals(
            LiveTrackingLocationQualityResult.REJECT,
            gate()
                .evaluate(
                    fix = fix,
                    nowElapsedRealtimeNanos = elapsedRealtimeNanos + 3 * 60 * NANOS_PER_SECOND,
                    nowEpochMilliseconds = fix.epochMilliseconds,
                ).result,
        )
    }

    @Test
    fun rejectsAStaleFirstCallbackUntilStartupHasAFreshFix() {
        val gate = gate()
        val fix = fix(0.0, 0)
        val elapsedRealtimeNanos = checkNotNull(fix.elapsedRealtimeNanos)

        val decision =
            gate.evaluate(
                fix = fix,
                nowElapsedRealtimeNanos = elapsedRealtimeNanos + 31 * NANOS_PER_SECOND,
                nowEpochMilliseconds = fix.epochMilliseconds,
            )

        assertEquals(LiveTrackingLocationQualityResult.REJECT, decision.result)
        assertEquals("stale_startup_callback", decision.reason)
        assertEquals(false, gate.hasFreshInitialFix)
    }

    @Test
    fun acceptsAFreshCallbackAsTheFirstFixAndEstablishesStartup() {
        val gate = gate()
        val fix = fix(0.0, 0)

        val decision = evaluateAtAge(gate, fix, 20_000L)

        assertEquals(LiveTrackingLocationQualityResult.ACCEPT, decision.result)
        assertEquals("first_fix", decision.reason)
        assertEquals(true, gate.hasFreshInitialFix)
    }

    @Test
    fun acceptsFreshCachedStartupFixAndUsesNormalLiveAgeAfterwards() {
        val gate = gate()
        val cachedFix = fix(0.0, 0)
        val normalFix = fix(0.0, 60)

        assertEquals(
            LiveTrackingLocationQualityResult.ACCEPT,
            evaluateAtAge(gate, cachedFix, 20_000L, "stale_startup_cache").result,
        )
        val decision = evaluateAtAge(gate, normalFix, 31_000L)
        val staleDecision = evaluateAtAge(gate, normalFix, 2 * 60 * 1000L + 1L)

        assertEquals(LiveTrackingLocationQualityResult.ACCEPT, decision.result)
        assertEquals("consistent_fix", decision.reason)
        assertEquals(LiveTrackingLocationQualityResult.REJECT, staleDecision.result)
        assertEquals("stale_fix", staleDecision.reason)
    }

    @Test
    fun serialStartupCandidatesCannotCompeteForTwoFirstFixes() {
        val gate = gate()
        val callbackFix = fix(0.0, 0)
        val cachedFix = fix(0.0, 1)

        val firstDecision = evaluateAtAge(gate, callbackFix, 20_000L)
        val secondDecision = evaluateAtAge(gate, cachedFix, 1_000L, "stale_startup_cache")

        assertEquals("first_fix", firstDecision.reason)
        assertEquals(LiveTrackingLocationQualityResult.ACCEPT, secondDecision.result)
        assertEquals("consistent_fix", secondDecision.reason)
    }

    @Test
    fun rejectsAFixWhenItsAgeCannotBeEstablished() {
        val fix =
            LiveTrackingLocationFix(
                latitude = 0.0,
                longitude = 0.0,
                epochMilliseconds = 0L,
                elapsedRealtimeNanos = null,
                accuracyMeters = 5f,
                speedMetersPerSecond = null,
            )

        assertEquals(
            LiveTrackingLocationQualityResult.REJECT,
            gate().evaluate(fix, nowElapsedRealtimeNanos = 1L, nowEpochMilliseconds = 1L).result,
        )
        assertEquals(
            false,
            isFreshLiveTrackingCachedLocation(
                fix = fix,
                nowElapsedRealtimeNanos = 1L,
                nowEpochMilliseconds = 1L,
            ),
        )
    }

    private fun evaluate(
        gate: LiveTrackingLocationQualityGate,
        fix: LiveTrackingLocationFix,
    ) = gate.evaluate(fix, fix.elapsedRealtimeNanos!!, fix.epochMilliseconds)

    private fun evaluateAtAge(
        gate: LiveTrackingLocationQualityGate,
        fix: LiveTrackingLocationFix,
        ageMillis: Long,
        startupStaleReason: String = "stale_startup_callback",
    ) = gate.evaluate(
        fix = fix,
        nowElapsedRealtimeNanos = fix.elapsedRealtimeNanos!! + ageMillis * NANOS_PER_MILLISECOND,
        nowEpochMilliseconds = fix.epochMilliseconds + ageMillis,
        startupStaleReason = startupStaleReason,
    )

    private fun gate() = LiveTrackingLocationQualityGate()

    private fun fix(
        longitudeMeters: Double,
        seconds: Long,
        accuracy: Float = 5f,
        speed: Float? = null,
        speedAccuracy: Float? = null,
    ) = LiveTrackingLocationFix(
        latitude = 0.0,
        longitude = longitudeMeters / METERS_PER_DEGREE_AT_EQUATOR,
        epochMilliseconds = BASE_EPOCH_MILLISECONDS + seconds * 1000L,
        elapsedRealtimeNanos = BASE_ELAPSED_REALTIME_NANOS + seconds * NANOS_PER_SECOND,
        accuracyMeters = accuracy,
        speedMetersPerSecond = speed,
        speedAccuracyMetersPerSecond = speedAccuracy,
    )

    private companion object {
        const val BASE_EPOCH_MILLISECONDS = 1_750_000_000_000L
        const val BASE_ELAPSED_REALTIME_NANOS = 1_000_000_000L
        const val METERS_PER_DEGREE_AT_EQUATOR = 111_195.0
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
