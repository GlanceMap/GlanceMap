package com.glancemap.glancemapwearos.presentation.features.navigate

import android.hardware.SensorManager
import com.glancemap.glancemapwearos.domain.sensors.CompassHeadingProvenance
import com.glancemap.glancemapwearos.domain.sensors.CompassMagneticQuality
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.domain.sensors.CompassRelativeMotionSample
import com.glancemap.glancemapwearos.domain.sensors.CompassRenderState
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingReason
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import com.glancemap.glancemapwearos.domain.sensors.HeadingSource
import com.glancemap.glancemapwearos.domain.sensors.initialCompassRenderState
import com.glancemap.glancemapwearos.domain.sensors.normalize360Deg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigateMagneticMotionFallbackTest {
    @Test
    fun magneticWarningWithAllowedAbsoluteMotionNeverStartsBackup() {
        val replay = Replay()
        repeat(30) { index ->
            replay.now += 100L
            val heading = normalize360Deg(100f + index * 10f)
            val state =
                replay.disturbedState().copy(
                    headingSampleHeldOutput = true,
                    quarantineActive = true,
                    trackingReason =
                        if (index % 2 == 0) {
                            CompassTrackingReason.MAGNETIC_INTERFERENCE
                        } else {
                            CompassTrackingReason.ABSOLUTE_RELATIVE_DISAGREEMENT
                        },
                )
            val target = replay.resolve(state, NavigationRotationTarget(heading))
            assertEquals(heading, target.headingDeg, 0f)
            assertFalse(target.relativeMotion)
            assertFalse(replay.policy.coneSuppressed)
        }
    }

    @Test
    fun quarantinedJumpUsesBackupEvenWhenNavigationReceivesTheHeldAngle() {
        val replay = Replay()

        fun heldState(relativeDeg: Float): CompassRenderState =
            replay.disturbedState().copy(
                headingSampleHeldOutput = true,
                quarantineActive = true,
                headingJumpHeld = true,
                trackingReason = CompassTrackingReason.MAGNETIC_INTERFERENCE,
                relativeMotionSample = replay.motion(relativeDeg),
            )

        replay.resolve(heldState(0f), NavigationRotationTarget(100f))
        replay.now += 100L
        val turned = replay.resolve(heldState(20f), NavigationRotationTarget(100f))
        assertEquals(120f, turned.headingDeg, 0f)
        assertTrue(turned.relativeMotion)
        replay.now += MAGNETIC_CONE_HIDE_DELAY_MS
        replay.resolve(heldState(20f), NavigationRotationTarget(100f))
        assertTrue(replay.policy.coneSuppressed)
    }

    @Test
    fun ordinaryWeakConfidenceKeepsTheConeAndAbsoluteAuthority() {
        val replay = Replay()
        repeat(30) {
            replay.now += 100L
            val state =
                replay.state().copy(headingErrorDeg = 25f, conservativeHeadingErrorDeg = 180f, headingTrusted = false)
            val target = replay.resolve(state, NavigationRotationTarget(105f))
            assertEquals(105f, target.headingDeg, 0f)
            assertFalse(target.relativeMotion)
            assertFalse(replay.policy.coneSuppressed)
        }
    }

    @Test
    fun briefMagneticSpikeDoesNotBlinkTheConeDuringRecovery() {
        val replay = Replay()
        replay.disturb()
        replay.now += 100L
        repeat(15) {
            replay.resolve(replay.state(), NavigationRotationTarget(100f))
            assertFalse(replay.policy.coneSuppressed)
            replay.now += 100L
        }
    }

    @Test
    fun briefSevereSpikeProtectsTheAnchorWithoutBlinkingTheCone() {
        val replay = Replay()
        replay.now += 100L
        val severe = replay.disturbedState().copy(severeMagneticInterference = true)
        val held = replay.resolve(severe, NavigationRotationTarget(180f))
        assertTrue(held.relativeMotion)
        assertEquals(100f, held.headingDeg, 0f)
        assertFalse(replay.policy.coneSuppressed)
        repeat(15) {
            replay.now += 100L
            replay.resolve(replay.state(), NavigationRotationTarget(100f))
            assertFalse(replay.policy.coneSuppressed)
        }
    }

    @Test
    fun sustainedInterferenceHidesTheConeWithoutHidingTheLocationMarker() {
        val replay = Replay()
        replay.disturb()
        replay.now += MAGNETIC_CONE_HIDE_DELAY_MS - 1L
        replay.disturb()
        assertFalse(replay.policy.coneSuppressed)
        replay.now += 1L
        replay.disturb()
        assertTrue(replay.policy.coneSuppressed)
        assertTrue(
            markerRenderStateForMode(NavMode.COMPASS_FOLLOW, replay.displayed, -replay.displayed, 0f, true).isVisible,
        )
    }

    @Test
    fun stationaryAbsoluteFlipIsIgnoredWhileRealRelativeTurnsStillMove() {
        val replay = Replay()
        val badAbsolute =
            replay.state().copy(
                headingDeg = 280f,
                magneticInterference = true,
                magneticQuality = CompassMagneticQuality.INTERFERENCE,
                headingSampleHeldOutput = true,
                quarantineActive = true,
                unresolvedIndependentDisagreement = true,
            )
        assertEquals(100f, replay.resolve(badAbsolute).headingDeg, 0f)
        replay.now += 100L
        assertEquals(100f, replay.resolve(badAbsolute.copy(relativeMotionSample = replay.motion(0f))).headingDeg, 0f)
        replay.now += 100L
        val turned = replay.resolve(badAbsolute.copy(relativeMotionSample = replay.motion(30f)))
        assertEquals(130f, turned.headingDeg, 0f)
        assertTrue(turned.relativeMotion)
        assertFalse(badAbsolute.headingTrusted)
    }

    @Test
    fun fullTurnsAndNorthWrapWorkInBothDirections() {
        for (direction in listOf(-1f, 1f)) {
            val replay = Replay()
            replay.disturb()
            repeat(36) { index ->
                replay.now += 50L
                val heading = direction * (index + 1) * 10f
                val target = replay.disturb(relativeDeg = normalize360Deg(heading))
                assertEquals(normalize360Deg(100f + heading), target.headingDeg, 0.001f)
                assertTrue(target.relativeMotion)
            }
        }
    }

    @Test
    fun duplicateAndOutOfOrderMotionCannotAddAnotherTurn() {
        val replay = Replay()
        replay.disturb()
        replay.now += 100L
        val state = replay.disturbedState(30f)
        assertEquals(130f, replay.resolve(state).headingDeg, 0f)
        assertEquals(130f, replay.resolve(state).headingDeg, 0f)
        val reordered =
            requireNotNull(state.relativeMotionSample).copy(headingDeg = 200f, atElapsedMs = replay.now - 1L)
        assertEquals(130f, replay.resolve(state.copy(relativeMotionSample = reordered)).headingDeg, 0f)
    }

    @Test
    fun staleFutureAndTiltedSamplesFreezeAndResumeWithoutReplayingTheGap() {
        for (kind in listOf("stale", "future", "tilted", "missing", "nonfinite")) {
            val replay = Replay()
            replay.disturb()
            replay.now += 100L
            replay.disturb(20f)
            replay.now += 100L
            val valid = replay.motion(180f)
            val invalid =
                when (kind) {
                    "stale" -> valid.copy(atElapsedMs = replay.now - MAGNETIC_MOTION_SAMPLE_FRESHNESS_MS - 1L)
                    "future" -> valid.copy(atElapsedMs = replay.now + 1L)
                    "tilted" -> valid.copy(horizontalProjection = 0.1f)
                    "nonfinite" -> valid.copy(headingDeg = Float.NaN)
                    else -> null
                }
            val frozen = replay.resolve(replay.disturbedState().copy(relativeMotionSample = invalid))
            assertEquals(120f, frozen.headingDeg, 0f)
            assertFalse(frozen.relativeMotion)
            replay.now += 100L
            assertEquals(120f, replay.disturb(180f).headingDeg, 0f)
            replay.now += 100L
            assertEquals(130f, replay.disturb(190f).headingDeg, 0f)
        }
    }

    @Test
    fun implausibleMotionJumpIsRebasedInsteadOfApplied() {
        val replay = Replay()
        replay.disturb()
        replay.now += 20L
        assertEquals(100f, replay.disturb(180f).headingDeg, 0f)
        replay.now += 20L
        assertEquals(100f, replay.disturb(0f).headingDeg, 0f)
        replay.now += 100L
        assertEquals(110f, replay.disturb(10f).headingDeg, 0f)
    }

    @Test
    fun registrationAndDisplayFrameChangesNeverBecomePhysicalTurns() {
        val replay = Replay()
        replay.disturb()
        replay.now += 100L
        val nextProvenance = PROVENANCE.copy(generation = 2L)
        var state =
            replay.disturbedState().copy(
                headingProvenance = nextProvenance,
                relativeMotionSample = replay.motion(200f).copy(provenance = nextProvenance),
            )
        assertEquals(100f, replay.resolve(state).headingDeg, 0f)
        replay.now += 100L
        state = state.copy(relativeMotionSample = replay.motion(210f).copy(provenance = nextProvenance))
        assertEquals(110f, replay.resolve(state).headingDeg, 0f)
        replay.now += 100L
        state =
            state.copy(
                relativeMotionSample = replay.motion(300f).copy(provenance = nextProvenance, displayRotation = 1),
            )
        assertEquals(110f, replay.resolve(state).headingDeg, 0f)
        replay.now += 100L
        assertEquals(110f, replay.resolve(state.copy(relativeMotionSample = replay.motion(0f))).headingDeg, 0f)
    }

    @Test
    fun wakeOnlyUsesTurnsMeasuredInItsNewInteractiveSession() {
        val replay = Replay()
        replay.disturb()
        replay.now += 100L
        replay.disturb(20f)
        val old = replay.disturbedState(180f)
        replay.now += 100L
        replay.policy.beginSession(replay.now)
        assertEquals(120f, replay.resolve(old).headingDeg, 0f)
        assertEquals(120f, replay.disturb(180f).headingDeg, 0f)
        replay.now += 100L
        assertEquals(130f, replay.disturb(190f).headingDeg, 0f)
    }

    @Test
    fun driftBudgetExpiresAndCannotBeRenewedByWaking() {
        val replay = Replay()
        replay.disturb()
        replay.now += MAGNETIC_MOTION_MAX_DURATION_MS
        assertFalse(replay.disturb(20f).relativeMotion)
        assertTrue(replay.policy.coneSuppressed)
        replay.policy.beginSession(replay.now)
        replay.now += 100L
        assertFalse(replay.disturb(30f).relativeMotion)
        assertEquals(100f, replay.displayed, 0f)
    }

    @Test
    fun expiredBriefHoldCannotHideTheConeOnAnOrdinaryWake() {
        val replay = Replay()
        replay.disturb()
        replay.now += 100L
        val acquiring =
            replay.state().copy(
                trackingState = CompassTrackingState.ACQUIRING,
                trackingReason = CompassTrackingReason.ABSOLUTE_WINDOW_UNSTABLE,
                headingSampleHeldOutput = true,
            )
        replay.resolve(acquiring)
        replay.now += MAGNETIC_MOTION_MAX_DURATION_MS
        replay.policy.beginSession(replay.now)
        val frozen = replay.resolve(acquiring.copy(headingSampleElapsedRealtimeMs = replay.now))
        assertFalse(frozen.relativeMotion)
        assertFalse(replay.policy.coneSuppressed)

        replay.disturb()
        replay.now += MAGNETIC_CONE_HIDE_DELAY_MS
        assertFalse(replay.disturb(30f).relativeMotion)
        assertTrue(replay.policy.coneSuppressed)
    }

    @Test
    fun wakeReconnectsToItsAcceptedFreshHeadingWithoutAnotherRecoveryDelay() {
        val replay = Replay()
        replay.hideCone()
        val cached = replay.state()
        replay.now += 100L
        replay.policy.beginSession(replay.now)
        replay.resolve(cached, NavigationRotationTarget(100f))
        assertTrue(replay.policy.coneSuppressed)

        replay.now += 100L
        val target = replay.resolve(replay.state(), NavigationRotationTarget(100f))
        assertFalse(target.relativeMotion)
        assertEquals(MAGNETIC_MOTION_RECOVERY_MAX_STEP_DEG, requireNotNull(target.maxVisualStepDeg), 0f)
        assertFalse(replay.policy.coneSuppressed)
    }

    @Test
    fun wakeReconnectionRetainsTheVisualCapAndWaitsForConvergence() {
        val replay = Replay()
        replay.hideCone()
        replay.now += 5_000L
        replay.policy.beginSession(replay.now)
        val allowed = replay.state().copy(headingDeg = 200f)
        val absolute = NavigationRotationTarget(200f, maxVisualStepDeg = 2f)
        val target = replay.resolve(allowed, absolute, applyTarget = false)
        assertFalse(target.relativeMotion)
        assertEquals(2f, requireNotNull(target.maxVisualStepDeg), 0f)
        assertTrue(replay.policy.coneSuppressed)

        replay.displayed = 196f
        replay.resolve(allowed, absolute)
        assertFalse(replay.policy.coneSuppressed)
    }

    @Test
    fun aNewHoldDuringWakeReconnectionRequiresTheStableRecoveryWindowAgain() {
        val replay = Replay()
        replay.hideCone()
        replay.now += 5_000L
        replay.policy.beginSession(replay.now)
        replay.resolve(replay.state(), NavigationRotationTarget(200f), applyTarget = false)

        replay.now += 100L
        replay.disturb()
        replay.now += 100L
        val waiting = replay.resolve(replay.state(), NavigationRotationTarget(100f))
        assertTrue(waiting.relativeMotion)
        assertTrue(replay.policy.coneSuppressed)

        replay.now += MAGNETIC_MOTION_RECOVERY_HOLD_MS
        val recovered = replay.resolve(replay.state(), NavigationRotationTarget(100f))
        assertFalse(recovered.relativeMotion)
        assertFalse(replay.policy.coneSuppressed)
    }

    @Test
    fun wakeStillRequiresItsGateAndAReleasedJumpBeforeRestoringCone() {
        val replay = Replay()
        replay.hideCone()
        replay.now += 5_000L
        replay.policy.beginSession(replay.now)
        replay.resolve(replay.state())
        assertTrue(replay.policy.coneSuppressed)
        repeat(2) {
            replay.now += MAGNETIC_MOTION_RECOVERY_HOLD_MS
            val held = replay.state().copy(headingJumpHeld = true)
            val target = replay.resolve(held, NavigationRotationTarget(280f))
            assertTrue(target.relativeMotion)
            assertEquals(100f, target.headingDeg, 0f)
            assertTrue(replay.policy.coneSuppressed)
        }
    }

    @Test
    fun historicalDisagreementCannotKeepAReleasedAbsoluteHeadingInBackup() {
        val replay = Replay()
        replay.hideCone()
        val allowed = replay.state().copy(unresolvedIndependentDisagreement = true, headingTrusted = false)
        replay.resolve(allowed, NavigationRotationTarget(100f))
        replay.now += MAGNETIC_MOTION_RECOVERY_HOLD_MS
        val target =
            replay.resolve(
                allowed.copy(headingSampleElapsedRealtimeMs = replay.now),
                NavigationRotationTarget(100f),
            )
        assertFalse(target.relativeMotion)
        assertFalse(replay.policy.coneSuppressed)
        assertFalse(allowed.headingTrusted)
    }

    @Test
    fun unresolvedRecoveryCannotInventAColdRelativeAnchor() {
        val replay = Replay(seedAnchor = false)
        replay.hideCone()
        replay.resolve(replay.state().copy(unresolvedIndependentDisagreement = true), NavigationRotationTarget(100f))
        replay.now += MAGNETIC_MOTION_RECOVERY_HOLD_MS
        replay.resolve(replay.state().copy(unresolvedIndependentDisagreement = true), NavigationRotationTarget(100f))
        assertFalse(replay.policy.coneSuppressed)

        replay.now += 100L
        replay.disturb()
        replay.now += 100L
        val target = replay.disturb(30f)
        assertFalse(target.relativeMotion)
        assertEquals(100f, target.headingDeg, 0f)
    }

    @Test
    fun recoveryWaitsForHealthyTrackingThenConvergesBeforeRestoringCone() {
        val replay = Replay()
        replay.hideCone()
        var target = replay.resolve(replay.state(), NavigationRotationTarget(200f))
        assertTrue(target.relativeMotion)
        replay.now += MAGNETIC_MOTION_RECOVERY_HOLD_MS - 1L
        target = replay.resolve(replay.state(), NavigationRotationTarget(200f))
        assertTrue(target.relativeMotion)
        replay.now += 1L
        target =
            replay.resolve(replay.state(), NavigationRotationTarget(200f, maxVisualStepDeg = 2f), applyTarget = false)
        assertEquals(200f, target.headingDeg, 0f)
        assertEquals(2f, requireNotNull(target.maxVisualStepDeg), 0f)
        assertFalse(target.relativeMotion)
        assertTrue(replay.policy.coneSuppressed)
        replay.displayed = 196f
        replay.resolve(replay.state(), NavigationRotationTarget(200f))
        assertFalse(replay.policy.coneSuppressed)
    }

    @Test
    fun recoveryRelapseRestartsTheStableWindowWithoutConeFlicker() {
        val replay = Replay()
        replay.hideCone()
        replay.resolve(replay.state(), NavigationRotationTarget(100f))
        replay.now += MAGNETIC_MOTION_RECOVERY_HOLD_MS - 1L
        replay.disturb()
        replay.now += 100L
        replay.resolve(replay.state(), NavigationRotationTarget(100f))
        assertTrue(replay.policy.coneSuppressed)
        replay.now += MAGNETIC_MOTION_RECOVERY_HOLD_MS
        replay.resolve(replay.state(), NavigationRotationTarget(100f))
        assertFalse(replay.policy.coneSuppressed)
    }

    @Test
    fun coldDisturbedStartCannotInventANorthAnchor() {
        val replay = Replay(seedAnchor = false)
        replay.disturb()
        replay.now += 100L
        assertFalse(replay.disturb(30f).relativeMotion)
        assertEquals(100f, replay.displayed, 0f)
        replay.now += MAGNETIC_CONE_HIDE_DELAY_MS
        replay.disturb(60f)
        assertTrue(replay.policy.coneSuppressed)
    }

    @Test
    fun healthyReplacementProviderClearsTheEpisode() {
        val replay = Replay()
        replay.hideCone()
        val fallback = replay.state().copy(providerType = CompassProviderType.SENSOR_MANAGER)
        val target = replay.resolve(fallback, NavigationRotationTarget(180f))
        assertEquals(180f, target.headingDeg, 0f)
        assertFalse(target.relativeMotion)
        assertFalse(replay.policy.coneSuppressed)
    }

    private class Replay(
        seedAnchor: Boolean = true,
    ) {
        val policy = NavigateMagneticMotionFallback()
        var now = 1_000L
        var displayed = 100f

        init {
            policy.beginSession(now)
            if (seedAnchor) resolve(state(), NavigationRotationTarget(displayed))
        }

        fun motion(
            headingDeg: Float,
        ): CompassRelativeMotionSample = CompassRelativeMotionSample(headingDeg, 0.9f, now, PROVENANCE)

        fun state(): CompassRenderState =
            initialCompassRenderState(CompassProviderType.GOOGLE_FUSED).copy(
                headingDeg = 100f,
                accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
                headingSource = HeadingSource.FUSED_ORIENTATION,
                headingRenderable = true,
                headingSampleElapsedRealtimeMs = now,
                headingProvenance = PROVENANCE,
                trackingState = CompassTrackingState.TRACKING,
                trackingReason = CompassTrackingReason.STABLE,
                magneticQuality = CompassMagneticQuality.GOOD,
                relativeMotionSample = motion(0f),
            )

        fun disturbedState(relativeDeg: Float = 0f): CompassRenderState =
            state().copy(
                magneticInterference = true,
                magneticQuality = CompassMagneticQuality.INTERFERENCE,
                trackingState = CompassTrackingState.DEGRADED,
                relativeMotionSample = motion(relativeDeg),
            )

        fun disturb(relativeDeg: Float = 0f): NavigationRotationTarget = resolve(disturbedState(relativeDeg))

        fun hideCone() {
            disturb()
            now += MAGNETIC_CONE_HIDE_DELAY_MS
            disturb()
            assertTrue(policy.coneSuppressed)
        }

        fun resolve(
            state: CompassRenderState,
            absolute: NavigationRotationTarget? = null,
            applyTarget: Boolean = true,
        ): NavigationRotationTarget {
            val target = requireNotNull(policy.resolve(state, absolute, displayed, now))
            if (applyTarget) displayed = target.headingDeg
            return target
        }
    }

    private companion object {
        val PROVENANCE = CompassHeadingProvenance(CompassProviderType.GOOGLE_FUSED, 1L)
    }
}
