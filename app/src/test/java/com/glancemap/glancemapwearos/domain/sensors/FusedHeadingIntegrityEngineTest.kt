package com.glancemap.glancemapwearos.domain.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FusedHeadingIntegrityEngineTest {
    @Test
    fun normalMagneticWarmupIsNotReportedAsInterference() {
        val replay = Replay(integrityEngine())

        replay.magnetic(42f)
        val snapshot = replay.absolute(headingDeg = 32f)

        assertEquals(CompassTrackingState.ACQUIRING, snapshot.state)
        assertEquals(CompassTrackingReason.RECOVERING, snapshot.reason)
        assertFalse(snapshot.magneticQuality == CompassMagneticQuality.INTERFERENCE)
    }

    @Test
    fun stableAbsoluteAndRelativeEvidenceCompletesAcquisition() {
        val replay = Replay(integrityEngine())

        val snapshot = replay.acquireStableHeading(headingDeg = 32f)

        assertEquals(CompassTrackingState.TRACKING, snapshot.state)
        assertEquals(CompassTrackingReason.STABLE, snapshot.reason)
        assertTrue(snapshot.renderable)
        assertTrue(snapshot.trusted)
        assertEquals(32f, requireNotNull(snapshot.renderHeadingDeg), ANGLE_TOLERANCE_DEG)
        assertTrue(snapshot.relativeWitnessAvailable)
        assertTrue(snapshot.relativeWitnessSupportsHighRate)
        assertFalse(snapshot.relativeWitnessSuppressed)
    }

    @Test
    fun acquiringDoesNotDisplaceSeedHeadingForAnUnverifiedFirstSample() {
        val engine = integrityEngine()
        engine.reset(
            seedHeadingDeg = 100f,
            atElapsedMs = 1_000L,
            clearSensorEvidence = true,
        )
        val replay = Replay(engine, nowElapsedMs = 1_000L)

        replay.magnetic(42f)
        replay.advance(200L)
        replay.magnetic(42f)
        val snapshot = replay.absolute(headingDeg = 2f)

        assertEquals(CompassTrackingState.ACQUIRING, snapshot.state)
        assertEquals(100f, requireNotNull(snapshot.renderHeadingDeg), ANGLE_TOLERANCE_DEG)
        assertFalse(snapshot.trusted)
    }

    @Test
    fun persistentWitnessDisagreementIsSuppressedAndCannotSteerTheMap() {
        val replay = Replay(integrityEngine())
        replay.acquireStableHeading(headingDeg = 10f)

        var latest: FusedHeadingIntegritySnapshot? = null
        var previousHeading = 10f
        repeat(12) {
            replay.advance(20L)
            replay.magnetic(42f)
            replay.relative(headingDeg = 0f)
            latest = replay.absolute(headingDeg = 100f, liveErrorDeg = 25f, conservativeErrorDeg = 180f)
            val currentHeading = requireNotNull(latest.renderHeadingDeg)
            assertTrue(
                abs(shortestAngleDiffDeg(currentHeading, previousHeading)) <=
                    MAX_UNVERIFIED_20_MS_CORRECTION_DEG + ANGLE_TOLERANCE_DEG,
            )
            previousHeading = currentHeading
        }

        val snapshot = requireNotNull(latest)
        assertEquals(CompassTrackingState.TRACKING, snapshot.state)
        assertTrue(snapshot.relativeWitnessSuppressed)
        assertFalse(snapshot.relativeWitnessAvailable)
        assertFalse(snapshot.relativeWitnessSupportsHighRate)
        assertTrue(requireNotNull(snapshot.renderHeadingDeg) in 10f..60f)
    }

    @Test
    fun agreeingWitnessAllowsResponsiveButBoundedFusedCorrection() {
        val replay = Replay(integrityEngine())
        replay.acquireStableHeading(headingDeg = 10f)

        replay.advance(20L)
        replay.magnetic(42f)
        replay.relative(headingDeg = 100f)
        val snapshot = replay.absolute(headingDeg = 100f)

        assertEquals(CompassTrackingState.TRACKING, snapshot.state)
        assertTrue(snapshot.relativeWitnessSupportsHighRate)
        assertEquals(
            MAX_VERIFIED_20_MS_CORRECTION_DEG,
            requireNotNull(snapshot.renderHeadingDeg) - 10f,
            ANGLE_TOLERANCE_DEG,
        )
    }

    @Test
    fun unavailableWitnessUsesBoundedFusedCorrectionWithoutBlockingTracking() {
        val replay = Replay(integrityEngine())
        replay.acquireStableHeading(headingDeg = 10f)

        replay.advance(20L)
        replay.witnessUnavailable(horizontalProjection = 0.1f)
        val snapshot = replay.absolute(headingDeg = 100f)

        assertEquals(CompassTrackingState.TRACKING, snapshot.state)
        assertFalse(snapshot.relativeWitnessAvailable)
        assertFalse(snapshot.relativeWitnessSupportsHighRate)
        assertEquals(
            10f + MAX_UNVERIFIED_20_MS_CORRECTION_DEG,
            requireNotNull(snapshot.renderHeadingDeg),
            ANGLE_TOLERANCE_DEG,
        )
    }

    @Test
    fun coherentFastTurnWithoutWitnessUsesResponsiveBoundedCorrection() {
        val replay = Replay(integrityEngine())
        replay.acquireStableHeading(headingDeg = 0f)

        replay.advance(20L)
        replay.witnessUnavailable(horizontalProjection = 0.1f)
        val first = replay.absolute(headingDeg = 8f)
        replay.advance(20L)
        replay.witnessUnavailable(horizontalProjection = 0.1f)
        val second = replay.absolute(headingDeg = 16f)

        assertEquals(
            MAX_UNVERIFIED_20_MS_CORRECTION_DEG,
            requireNotNull(first.renderHeadingDeg),
            ANGLE_TOLERANCE_DEG,
        )
        assertEquals(
            MAX_UNVERIFIED_20_MS_CORRECTION_DEG + MAX_UNVERIFIED_FAST_TURN_20_MS_CORRECTION_DEG,
            requireNotNull(second.renderHeadingDeg),
            ANGLE_TOLERANCE_DEG,
        )
    }

    @Test
    fun magneticInterferenceStillDegradesButNeverUsesTheRelativeHeadingAsMapHeading() {
        val replay = Replay(integrityEngine())
        replay.acquireStableHeading(headingDeg = 40f)

        replay.magnetic(2_000f)
        replay.advance(20L)
        replay.relative(headingDeg = 20f)
        val snapshot = replay.absolute(headingDeg = 60f)

        assertEquals(CompassTrackingState.DEGRADED, snapshot.state)
        assertEquals(CompassTrackingReason.MAGNETIC_INTERFERENCE, snapshot.reason)
        assertEquals(
            40f + MAX_VERIFIED_20_MS_CORRECTION_DEG,
            requireNotNull(snapshot.renderHeadingDeg),
            ANGLE_TOLERANCE_DEG,
        )
    }

    @Test
    fun genuineFullTurnTracksGoogleFusedWhenWitnessAgrees() {
        val replay = Replay(integrityEngine())
        var latest = replay.acquireStableHeading(headingDeg = 0f)

        val headings = (12..348 step 12).map(Int::toFloat) + 0f
        headings.forEach { headingDeg ->
            replay.advance(20L)
            replay.magnetic(42f)
            replay.relative(headingDeg)
            latest = replay.absolute(headingDeg)
            assertEquals(CompassTrackingState.TRACKING, latest.state)
            assertTrue(latest.relativeWitnessSupportsHighRate)
            assertEquals(headingDeg, requireNotNull(latest.renderHeadingDeg), ANGLE_TOLERANCE_DEG)
        }
    }

    @Test
    fun unavailableRelativeSensorStillAllowsStableAbsoluteAcquisition() {
        val engine = FusedHeadingIntegrityEngine(relativeSensorAvailable = false, magnetometerAvailable = false)
        val replay = Replay(engine, relativeSensorAvailable = false, magnetometerAvailable = false)

        val snapshot = replay.acquireStableHeading(headingDeg = 75f)

        assertEquals(CompassTrackingState.TRACKING, snapshot.state)
        assertEquals(CompassMagneticQuality.UNAVAILABLE, snapshot.magneticQuality)
        assertEquals(75f, requireNotNull(snapshot.renderHeadingDeg), ANGLE_TOLERANCE_DEG)
    }

    private fun integrityEngine(): FusedHeadingIntegrityEngine =
        FusedHeadingIntegrityEngine(
            relativeSensorAvailable = true,
            magnetometerAvailable = true,
        )

    private class Replay(
        private val engine: FusedHeadingIntegrityEngine,
        private val relativeSensorAvailable: Boolean = true,
        private val magnetometerAvailable: Boolean = true,
        var nowElapsedMs: Long = 1_000L,
    ) {
        fun advance(durationMs: Long) {
            nowElapsedMs += durationMs
        }

        fun relative(headingDeg: Float): FusedHeadingIntegritySnapshot =
            engine.onRelativeHeading(
                headingDeg = headingDeg,
                horizontalProjection = 0.9f,
                atElapsedMs = nowElapsedMs,
            )

        fun witnessUnavailable(horizontalProjection: Float) = engine.onRelativeWitnessUnavailable(horizontalProjection)

        fun magnetic(strengthUt: Float): FusedHeadingIntegritySnapshot =
            engine.onMagneticField(
                strengthUt = strengthUt,
                atElapsedMs = nowElapsedMs,
            )

        fun absolute(
            headingDeg: Float,
            liveErrorDeg: Float = 8f,
            conservativeErrorDeg: Float = 30f,
        ): FusedHeadingIntegritySnapshot =
            engine.onAbsoluteHeading(
                FusedAbsoluteHeadingSample(
                    headingDeg = headingDeg,
                    liveErrorDeg = liveErrorDeg,
                    conservativeErrorDeg = conservativeErrorDeg,
                    atElapsedMs = nowElapsedMs,
                ),
            )

        fun acquireStableHeading(headingDeg: Float): FusedHeadingIntegritySnapshot {
            if (magnetometerAvailable) {
                magnetic(42f)
                advance(200L)
                magnetic(42f)
            }
            var latest: FusedHeadingIntegritySnapshot? = null
            repeat(9) { index ->
                if (index > 0) advance(50L)
                if (magnetometerAvailable) magnetic(42f)
                if (relativeSensorAvailable) relative(0f)
                latest = absolute(headingDeg)
            }
            return requireNotNull(latest)
        }
    }

    private companion object {
        const val ANGLE_TOLERANCE_DEG = 0.01f
        const val MAX_VERIFIED_20_MS_CORRECTION_DEG = 14.4f
        const val MAX_UNVERIFIED_20_MS_CORRECTION_DEG = 3.6f
        const val MAX_UNVERIFIED_FAST_TURN_20_MS_CORRECTION_DEG = 7.2f
    }
}
