package com.glancemap.glancemapwearos.domain.sensors

import android.hardware.SensorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@Suppress("LargeClass") // Keeps the compass publication and threshold regressions together.
class CompassManagerMathTest {
    @Test
    fun resolveHeadingPipelineAutoPrefersRotationVector() {
        val pipeline =
            resolveHeadingPipeline(
                mode = CompassHeadingSourceMode.AUTO,
                headingSensorAvailable = true,
                rotationVectorAvailable = true,
                magAccelFallbackAvailable = true,
            )
        assertEquals(HeadingPipeline.ROTATION_VECTOR, pipeline)
    }

    @Test
    fun resolveHeadingPipelineExplicitHeadingDoesNotSilentlyFallback() {
        val pipeline =
            resolveHeadingPipeline(
                mode = CompassHeadingSourceMode.TYPE_HEADING,
                headingSensorAvailable = false,
                rotationVectorAvailable = true,
                magAccelFallbackAvailable = true,
            )
        assertEquals(HeadingPipeline.NONE, pipeline)
    }

    @Test
    fun resolveHeadingPipelineExplicitRotationVectorDoesNotSilentlyFallback() {
        val pipeline =
            resolveHeadingPipeline(
                mode = CompassHeadingSourceMode.ROTATION_VECTOR,
                headingSensorAvailable = true,
                rotationVectorAvailable = false,
                magAccelFallbackAvailable = true,
            )
        assertEquals(HeadingPipeline.NONE, pipeline)
    }

    @Test
    fun resolveHeadingPipelineExplicitMagnetometerDoesNotSilentlyFallback() {
        val pipeline =
            resolveHeadingPipeline(
                mode = CompassHeadingSourceMode.MAGNETOMETER,
                headingSensorAvailable = true,
                rotationVectorAvailable = true,
                magAccelFallbackAvailable = false,
            )
        assertEquals(HeadingPipeline.NONE, pipeline)
    }

    @Test
    fun shortestAngleDiffDegPreservesDirectionAt180() {
        assertEquals(180f, shortestAngleDiffDeg(target = 180f, current = 0f), 0.0001f)
        assertEquals(-180f, shortestAngleDiffDeg(target = 0f, current = 180f), 0.0001f)
    }

    @Test
    fun shortestAngleDiffDegWrapsAcrossZero() {
        assertEquals(20f, shortestAngleDiffDeg(target = 10f, current = 350f), 0.0001f)
        assertEquals(-20f, shortestAngleDiffDeg(target = 350f, current = 10f), 0.0001f)
    }

    @Test
    fun inferHeadingAccuracyCapsFastTurnAtMediumWhenNoiseIsLow() {
        val inferred =
            inferHeadingAccuracy(
                noiseDeg = 0.8f,
                turnRateDegPerSec = 80f,
            )
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM, inferred)
    }

    @Test
    fun inferHeadingAccuracyKeepsFastTurnLowWhenNoiseIsHigh() {
        val inferred =
            inferHeadingAccuracy(
                noiseDeg = 7.2f,
                turnRateDegPerSec = 80f,
            )
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_LOW, inferred)
    }

    @Test
    fun inferHeadingAccuracyAllowsModeratelyFastTurnWhenNoiseIsLow() {
        val inferred =
            inferHeadingAccuracy(
                noiseDeg = 0.8f,
                turnRateDegPerSec = 65f,
            )
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_HIGH, inferred)
    }

    @Test
    fun inferHeadingAccuracyUsesRelaxedNoiseThresholds() {
        assertEquals(
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
            inferHeadingAccuracy(noiseDeg = 2.9f, turnRateDegPerSec = 0f),
        )
        assertEquals(
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
            inferHeadingAccuracy(noiseDeg = 5.3f, turnRateDegPerSec = 0f),
        )
        assertEquals(
            SensorManager.SENSOR_STATUS_ACCURACY_LOW,
            inferHeadingAccuracy(noiseDeg = 8.7f, turnRateDegPerSec = 0f),
        )
    }

    @Test
    fun combineCompassAccuracyUpgradesRotVecWhenSensorUnavailable() {
        val combined =
            combineCompassAccuracy(
                sensorAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                inferredAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                usingRotationVector = true,
            )
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_LOW, combined)
    }

    @Test
    fun combineCompassAccuracyUsesWeakerSignalWhenBothPresent() {
        val combined =
            combineCompassAccuracy(
                sensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                inferredAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
                usingRotationVector = true,
            )
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM, combined)
    }

    @Test
    fun combineCompassAccuracyCapsAtLowWhenMagneticInterferenceDetected() {
        val combined =
            combineCompassAccuracy(
                sensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                inferredAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                usingRotationVector = true,
                hasMagneticInterference = true,
            )
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_LOW, combined)
    }

    @Test
    fun combineCompassAccuracyKeepsLowWhenInferenceDropsButSensorStillGood() {
        val combined =
            combineCompassAccuracy(
                sensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                inferredAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                usingRotationVector = true,
            )
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_LOW, combined)
    }

    @Test
    fun combineCompassAccuracyStaysUnreliableWhenBothSignalsAreUnreliable() {
        val combined =
            combineCompassAccuracy(
                sensorAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                inferredAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                usingRotationVector = true,
            )
        assertEquals(SensorManager.SENSOR_STATUS_UNRELIABLE, combined)
    }

    @Test
    fun headingWithNorthReferenceAppliesDeclinationInTrueMode() {
        val heading =
            headingWithNorthReference(
                azimuthDeg = 350f,
                declinationDeg = 12f,
                northReferenceMode = NorthReferenceMode.TRUE,
            )
        assertEquals(2f, heading, 0.0001f)
    }

    @Test
    fun headingWithNorthReferenceSkipsDeclinationInMagneticMode() {
        val heading =
            headingWithNorthReference(
                azimuthDeg = 350f,
                declinationDeg = 12f,
                northReferenceMode = NorthReferenceMode.MAGNETIC,
            )
        assertEquals(350f, heading, 0.0001f)
    }

    @Test
    fun remapHeadingForNorthReferenceSwitchAddsDeclinationWhenSwitchingToTrue() {
        val heading =
            remapHeadingForNorthReferenceSwitch(
                currentHeadingDeg = 350f,
                fromMode = NorthReferenceMode.MAGNETIC,
                toMode = NorthReferenceMode.TRUE,
                declinationDeg = 12f,
            )
        assertEquals(2f, heading, 0.0001f)
    }

    @Test
    fun remapHeadingForNorthReferenceSwitchSubtractsDeclinationWhenSwitchingToMagnetic() {
        val heading =
            remapHeadingForNorthReferenceSwitch(
                currentHeadingDeg = 2f,
                fromMode = NorthReferenceMode.TRUE,
                toMode = NorthReferenceMode.MAGNETIC,
                declinationDeg = 12f,
            )
        assertEquals(350f, heading, 0.0001f)
    }

    @Test
    fun deadbandConvergenceAlphaNudgesOnlyWhenStableAndInsideDeadband() {
        val enabled =
            deadbandConvergenceAlpha(
                diffDeg = 0.5f,
                minDeltaDeg = 0.9f,
                isFastTurn = false,
                isModerateTurn = false,
                isNoisy = false,
            )
        val disabledByTurn =
            deadbandConvergenceAlpha(
                diffDeg = 0.5f,
                minDeltaDeg = 0.9f,
                isFastTurn = true,
                isModerateTurn = false,
                isNoisy = false,
            )
        assertEquals(0.08f, enabled, 0.0001f)
        assertEquals(0f, disabledByTurn, 0.0001f)
    }

    @Test
    fun headingAccuracyFromUncertaintyMapsExpectedBands() {
        assertEquals(
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
            headingAccuracyFromUncertainty(6f),
        )
        assertEquals(
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
            headingAccuracyFromUncertainty(12f),
        )
        assertEquals(
            SensorManager.SENSOR_STATUS_ACCURACY_LOW,
            headingAccuracyFromUncertainty(22f),
        )
        assertEquals(
            SensorManager.SENSOR_STATUS_UNRELIABLE,
            headingAccuracyFromUncertainty(45f),
        )
    }

    @Test
    fun rotationVectorPoorUncertaintyRemainsCappedAcrossAccuracyCallbacks() {
        var rotVecAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        var magAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        var inferredAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_LOW
        var rotVecUncertaintyDeg = 45f

        fun publish(hasMagneticInterference: Boolean = false): Int =
            computeCompassAccuracyPublication(
                pipeline = HeadingPipeline.ROTATION_VECTOR,
                headingAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                headingUncertaintyDeg = Float.NaN,
                magAccuracy = magAccuracy,
                rotVecAccuracy = rotVecAccuracy,
                rotVecHeadingUncertaintyDeg = rotVecUncertaintyDeg,
                inferredAccuracy = inferredAccuracy,
                usingRotationVector = true,
                usingHeadingSensor = false,
                hasMagneticInterference = hasMagneticInterference,
            )

        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_LOW, publish())

        rotVecAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_LOW, publish())

        magAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        inferredAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_LOW, publish())

        rotVecUncertaintyDeg = 6f
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_HIGH, publish())
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_LOW, publish(hasMagneticInterference = true))
    }

    @Test
    fun typeHeadingPoorUncertaintyRecoversOnlyAfterUncertaintyImproves() {
        var headingAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        var headingUncertaintyDeg = 45f

        fun publish(): Int =
            computeCompassAccuracyPublication(
                pipeline = HeadingPipeline.HEADING_SENSOR,
                headingAccuracy = headingAccuracy,
                headingUncertaintyDeg = headingUncertaintyDeg,
                magAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                rotVecAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                rotVecHeadingUncertaintyDeg = Float.NaN,
                inferredAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                usingRotationVector = false,
                usingHeadingSensor = true,
                hasMagneticInterference = false,
            )

        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_LOW, publish())

        headingAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_LOW, publish())

        headingUncertaintyDeg = 6f
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_HIGH, publish())
    }

    @Test
    fun missingUncertaintyKeepsSensorAccuracyFallback() {
        assertEquals(
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
            computeCompassAccuracyPublication(
                pipeline = HeadingPipeline.ROTATION_VECTOR,
                headingAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                headingUncertaintyDeg = Float.NaN,
                magAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                rotVecAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                rotVecHeadingUncertaintyDeg = Float.NaN,
                inferredAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                usingRotationVector = true,
                usingHeadingSensor = false,
                hasMagneticInterference = false,
            ),
        )
        assertEquals(
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
            computeCompassAccuracyPublication(
                pipeline = HeadingPipeline.HEADING_SENSOR,
                headingAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                headingUncertaintyDeg = Float.NaN,
                magAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                rotVecAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                rotVecHeadingUncertaintyDeg = Float.NaN,
                inferredAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                usingRotationVector = false,
                usingHeadingSensor = true,
                hasMagneticInterference = false,
            ),
        )
        assertEquals(
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
            computeCompassAccuracyPublication(
                pipeline = HeadingPipeline.HEADING_SENSOR,
                headingAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                headingUncertaintyDeg = -1f,
                magAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                rotVecAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                rotVecHeadingUncertaintyDeg = Float.NaN,
                inferredAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                usingRotationVector = false,
                usingHeadingSensor = true,
                hasMagneticInterference = false,
            ),
        )
    }

    @Test
    fun resolveStartupTransientActionFirstSampleAwaitsConfirmation() {
        val decision =
            resolveStartupTransientAction(
                rawDeg = 48f,
                candidateHeadingDeg = null,
                remainingSamplesToIgnore = 2,
                withinStartupWindow = true,
                usingRotationVector = true,
                hasInit = false,
            )
        require(decision != null)
        assertEquals(StartupTransientAction.IGNORE_AWAIT_CONFIRMATION, decision.action)
        assertEquals(48f, decision.nextCandidateHeadingDeg!!, 0.0001f)
        assertEquals(1, decision.nextRemainingSamplesToIgnore)
        assertNull(decision.acceptedHeadingDeg)
    }

    @Test
    fun resolveStartupTransientActionAcceptsConfirmedSample() {
        val decision =
            resolveStartupTransientAction(
                rawDeg = 60f,
                candidateHeadingDeg = 48f,
                remainingSamplesToIgnore = 1,
                withinStartupWindow = true,
                usingRotationVector = true,
                hasInit = false,
            )
        require(decision != null)
        assertEquals(StartupTransientAction.ACCEPT_CONFIRMED, decision.action)
        assertNull(decision.nextCandidateHeadingDeg)
        assertEquals(0, decision.nextRemainingSamplesToIgnore)
        assertEquals(60f, decision.acceptedHeadingDeg!!, 0.0001f)
    }

    @Test
    fun resolveStartupTransientActionReplacesCandidateWhenStillUnconfirmed() {
        val decision =
            resolveStartupTransientAction(
                rawDeg = 200f,
                candidateHeadingDeg = 48f,
                remainingSamplesToIgnore = 2,
                withinStartupWindow = true,
                usingRotationVector = true,
                hasInit = false,
            )
        require(decision != null)
        assertEquals(StartupTransientAction.IGNORE_REPLACE_CANDIDATE, decision.action)
        assertEquals(200f, decision.nextCandidateHeadingDeg!!, 0.0001f)
        assertEquals(1, decision.nextRemainingSamplesToIgnore)
        assertNull(decision.acceptedHeadingDeg)
    }

    @Test
    fun resolveStartupTransientActionKeepsFilteringInconsistentSampleAfterBudget() {
        val decision =
            resolveStartupTransientAction(
                rawDeg = 200f,
                candidateHeadingDeg = 48f,
                remainingSamplesToIgnore = 1,
                withinStartupWindow = true,
                usingRotationVector = true,
                hasInit = false,
            )
        require(decision != null)
        assertEquals(StartupTransientAction.IGNORE_REPLACE_CANDIDATE, decision.action)
        assertEquals(200f, decision.nextCandidateHeadingDeg!!, 0.0001f)
        assertEquals(1, decision.nextRemainingSamplesToIgnore)
        assertNull(decision.acceptedHeadingDeg)
    }

    @Test
    fun resolveStartupTransientTraceDoesNotForceWildlyInconsistentThirdSample() {
        val first =
            requireNotNull(
                resolveStartupTransientAction(
                    rawDeg = 4.3f,
                    candidateHeadingDeg = null,
                    remainingSamplesToIgnore = 2,
                    withinStartupWindow = true,
                    usingRotationVector = true,
                    hasInit = false,
                ),
            )
        val second =
            requireNotNull(
                resolveStartupTransientAction(
                    rawDeg = 290f,
                    candidateHeadingDeg = first.nextCandidateHeadingDeg,
                    remainingSamplesToIgnore = first.nextRemainingSamplesToIgnore,
                    withinStartupWindow = true,
                    usingRotationVector = true,
                    hasInit = false,
                ),
            )
        val third =
            requireNotNull(
                resolveStartupTransientAction(
                    rawDeg = 200f,
                    candidateHeadingDeg = second.nextCandidateHeadingDeg,
                    remainingSamplesToIgnore = second.nextRemainingSamplesToIgnore,
                    withinStartupWindow = true,
                    usingRotationVector = true,
                    hasInit = false,
                ),
            )
        val coherentFourth =
            requireNotNull(
                resolveStartupTransientAction(
                    rawDeg = 204f,
                    candidateHeadingDeg = third.nextCandidateHeadingDeg,
                    remainingSamplesToIgnore = third.nextRemainingSamplesToIgnore,
                    withinStartupWindow = true,
                    usingRotationVector = true,
                    hasInit = false,
                ),
            )

        assertEquals(StartupTransientAction.IGNORE_AWAIT_CONFIRMATION, first.action)
        assertEquals(StartupTransientAction.IGNORE_REPLACE_CANDIDATE, second.action)
        assertEquals(StartupTransientAction.IGNORE_REPLACE_CANDIDATE, third.action)
        assertNull(third.acceptedHeadingDeg)
        assertEquals(StartupTransientAction.ACCEPT_CONFIRMED, coherentFourth.action)
        assertEquals(204f, coherentFourth.acceptedHeadingDeg!!, 0.0001f)
    }

    @Test
    fun shouldMaskStartupHeadingPublishOnlyWhenLargeJumpDuringWarmup() {
        val masked =
            shouldMaskStartupHeadingPublish(
                candidateHeadingDeg = 42f,
                displayedHeadingDeg = 20f,
                remainingPublishesToMask = 2,
                withinMaskWindow = true,
            )
        val notMaskedSmallDelta =
            shouldMaskStartupHeadingPublish(
                candidateHeadingDeg = 24f,
                displayedHeadingDeg = 20f,
                remainingPublishesToMask = 2,
                withinMaskWindow = true,
            )
        assertEquals(true, masked)
        assertEquals(false, notMaskedSmallDelta)
    }

    @Test
    fun shouldMaskStartupHeadingPublishStopsOutsideWindowOrBudget() {
        val noBudget =
            shouldMaskStartupHeadingPublish(
                candidateHeadingDeg = 42f,
                displayedHeadingDeg = 20f,
                remainingPublishesToMask = 0,
                withinMaskWindow = true,
            )
        val outOfWindow =
            shouldMaskStartupHeadingPublish(
                candidateHeadingDeg = 42f,
                displayedHeadingDeg = 20f,
                remainingPublishesToMask = 2,
                withinMaskWindow = false,
            )
        assertEquals(false, noBudget)
        assertEquals(false, outOfWindow)
    }

    @Test
    fun resolveHeadingSmoothingMinDeltaShrinksForTurns() {
        assertEquals(
            0.4f,
            resolveHeadingSmoothingMinDelta(
                isFastTurn = false,
                isModerateTurn = false,
                isNoisy = false,
            ),
            0.0001f,
        )
        assertEquals(
            0.6f,
            resolveHeadingSmoothingMinDelta(
                isFastTurn = false,
                isModerateTurn = true,
                isNoisy = false,
            ),
            0.0001f,
        )
        assertEquals(
            0.35f,
            resolveHeadingSmoothingMinDelta(
                isFastTurn = true,
                isModerateTurn = false,
                isNoisy = false,
            ),
            0.0001f,
        )
    }

    @Test
    fun resolveHeadingSmoothingAlphaGetsMoreResponsiveAsTurnRateRises() {
        assertEquals(
            0.19f,
            resolveHeadingSmoothingAlpha(
                diffDeg = 10f,
                isFastTurn = false,
                isModerateTurn = false,
                isNoisy = false,
            ),
            0.0001f,
        )
        assertEquals(
            0.28f,
            resolveHeadingSmoothingAlpha(
                diffDeg = 10f,
                isFastTurn = false,
                isModerateTurn = true,
                isNoisy = false,
            ),
            0.0001f,
        )
        assertEquals(
            0.54f,
            resolveHeadingSmoothingAlpha(
                diffDeg = 10f,
                isFastTurn = true,
                isModerateTurn = false,
                isNoisy = false,
            ),
            0.0001f,
        )
    }

    @Test
    fun stepMagneticInterferenceStartupGraceResetsBaseline() {
        val state =
            MagneticInterferenceState(
                strengthUt = 62f,
                emaUt = 61f,
                holdUntilElapsedMs = 50_000L,
                detected = true,
            )
        val duringGrace =
            stepMagneticInterferenceState(
                state = state,
                strengthUt = 68f,
                nowElapsedMs = 10_000L,
                startupGraceUntilElapsedMs = 11_000L,
            )
        assertEquals(false, duringGrace.state.strengthUt.isFinite())
        assertEquals(false, duringGrace.state.emaUt.isFinite())
        assertEquals(0L, duringGrace.state.holdUntilElapsedMs)
        assertEquals(false, duringGrace.state.detected)

        // First sample after grace must not trigger a synthetic spike against grace-era values.
        val firstAfterGrace =
            stepMagneticInterferenceState(
                state = duringGrace.state,
                strengthUt = 34f,
                nowElapsedMs = 11_100L,
                startupGraceUntilElapsedMs = 11_000L,
            )
        assertEquals(false, firstAfterGrace.state.detected)
        assertEquals(0f, firstAfterGrace.deltaUt, 0.0001f)
    }

    @Test
    fun stepMagneticInterferenceMaintainsHoldAndClearsAfterExpiry() {
        val initial =
            MagneticInterferenceState(
                strengthUt = 40f,
                emaUt = 40f,
                holdUntilElapsedMs = 0L,
                detected = false,
            )
        val spike =
            stepMagneticInterferenceState(
                state = initial,
                strengthUt = 70f,
                nowElapsedMs = 1_000L,
                startupGraceUntilElapsedMs = 0L,
            )
        assertEquals(true, spike.state.detected)
        assertEquals("spike", spike.reason)

        val held =
            stepMagneticInterferenceState(
                state = spike.state,
                strengthUt = 71f,
                nowElapsedMs = 2_000L,
                startupGraceUntilElapsedMs = 0L,
            )
        assertEquals(true, held.state.detected)

        val afterHold =
            stepMagneticInterferenceState(
                state = held.state,
                strengthUt = 71f,
                nowElapsedMs = 4_200L,
                startupGraceUntilElapsedMs = 0L,
            )
        assertEquals(false, afterHold.state.detected)
        assertEquals("hold_expired", afterHold.reason)
    }
}
