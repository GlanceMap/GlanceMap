package com.glancemap.glancemapwearos.presentation.features.navigate

import android.hardware.SensorManager
import com.glancemap.glancemapwearos.domain.sensors.CompassHeadingProvenance
import com.glancemap.glancemapwearos.domain.sensors.CompassMagneticQuality
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.domain.sensors.CompassRelativeMotionSample
import com.glancemap.glancemapwearos.domain.sensors.CompassRenderState
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingReason
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import com.glancemap.glancemapwearos.domain.sensors.FusedAbsoluteHeadingSample
import com.glancemap.glancemapwearos.domain.sensors.FusedHeadingIntegrityConfig
import com.glancemap.glancemapwearos.domain.sensors.FusedHeadingIntegrityEngine
import com.glancemap.glancemapwearos.domain.sensors.FusedHeadingIntegritySnapshot
import com.glancemap.glancemapwearos.domain.sensors.HeadingSource
import com.glancemap.glancemapwearos.domain.sensors.initialCompassRenderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FusedHeadingWakeIntegrationTest {
    @Test
    fun interferenceWithCorroboratedAbsoluteTurnsKeepsNormalNavigationAndCone() {
        val replay = WakeReplay(relativeSensorAvailable = true)
        replay.reset(seedHeadingDeg = 100f)
        replay.warmMagneticField()
        replay.acquireStableHeading(100f)
        replay.relative(0f)
        val healthy = replay.absolute(100f)
        val policy = NavigateMagneticMotionFallback()
        val gate = NavigateRotationSettleGate()
        policy.beginSession(replay.nowElapsedMs)
        policy.resolve(
            healthy.toRenderState(replay.nowElapsedMs),
            gate.resolveFromIntegrity(healthy, replay.nowElapsedMs),
            100f,
            replay.nowElapsedMs,
        )
        var displayed = 100f
        repeat(20) { index ->
            replay.advance(100L)
            replay.magnetic(600f)
            replay.relative((index + 1) * 5f)
            val snapshot = replay.absolute(100f + (index + 1) * 5f)
            val absolute = requireNotNull(gate.resolveFromIntegrity(snapshot, replay.nowElapsedMs))
            val applied =
                requireNotNull(
                    policy.resolve(
                        snapshot.toRenderState(replay.nowElapsedMs),
                        absolute,
                        displayed,
                        replay.nowElapsedMs,
                    ),
                )
            assertEquals(CompassMagneticQuality.INTERFERENCE, snapshot.magneticQuality)
            assertFalse(snapshot.headingJumpHeld)
            assertTrue(requireNotNull(snapshot.renderHeadingDeg) > displayed)
            assertEquals(absolute, applied)
            assertFalse(applied.relativeMotion)
            assertFalse(policy.coneSuppressed)
            displayed = applied.headingDeg
        }
    }

    @Test
    fun magneticWakeUsesOnlyAnchoredRelativeTurnsWhileAbsoluteFlipStaysQuarantined() {
        val replay = WakeReplay(relativeSensorAvailable = true)
        replay.reset(seedHeadingDeg = 100f)
        replay.warmMagneticField()
        replay.acquireStableHeading(100f)
        replay.relative(0f)
        val healthy = replay.absolute(100f)
        assertEquals(CompassTrackingState.TRACKING, healthy.state)
        val policy = NavigateMagneticMotionFallback()
        policy.beginSession(replay.nowElapsedMs)
        policy.resolve(
            healthy.toRenderState(replay.nowElapsedMs),
            NavigationRotationTarget(100f),
            100f,
            replay.nowElapsedMs,
        )
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(replay.nowElapsedMs, heldHeadingDeg = 100f)

        replay.advance(20L)
        replay.magnetic(600f)
        replay.relative(0f)
        var snapshot = replay.absolute(280f)
        assertTrue(snapshot.heldOutput)
        assertTrue(snapshot.headingJumpHeld)
        assertNull(gate.resolveFromIntegrity(snapshot, replay.nowElapsedMs))
        val stationary = policy.resolve(snapshot.toRenderState(replay.nowElapsedMs), null, 100f, replay.nowElapsedMs)
        assertEquals(100f, requireNotNull(stationary).headingDeg, ANGLE_TOLERANCE_DEG)

        replay.advance(100L)
        replay.relative(30f)
        snapshot = replay.absolute(280f)
        val turned = policy.resolve(snapshot.toRenderState(replay.nowElapsedMs), null, 100f, replay.nowElapsedMs)
        assertEquals(130f, requireNotNull(turned).headingDeg, ANGLE_TOLERANCE_DEG)
        assertTrue(turned.relativeMotion)
        assertEquals(100f, requireNotNull(snapshot.renderHeadingDeg), ANGLE_TOLERANCE_DEG)
        assertFalse(snapshot.trusted)
        assertTrue(snapshot.quarantineActive)
        assertNull(gate.resolveFromIntegrity(snapshot, replay.nowElapsedMs))
    }

    @Test
    fun seededAcquisitionRemainsHeldPastWakeTimeoutUntilStableAndReleasesItsRecoveredTarget() {
        val replay = WakeReplay(relativeSensorAvailable = false)
        replay.reset(seedHeadingDeg = 100f)
        replay.warmMagneticField()

        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(nowElapsedMs = 1_000L, heldHeadingDeg = 100f)

        var latest = replay.absolute(headingDeg = 160f)
        assertTrue(latest.heldOutput)
        assertEquals(100f, latest.renderHeadingDeg ?: -1f, ANGLE_TOLERANCE_DEG)
        assertNull(gate.resolveFromIntegrity(latest, replay.nowElapsedMs))

        listOf(240f, 160f, 240f, 160f, 240f, 160f).forEach { headingDeg ->
            replay.advance(100L)
            replay.magnetic(42f)
            latest = replay.absolute(headingDeg)
            assertTrue(latest.heldOutput)
            assertEquals(100f, latest.renderHeadingDeg ?: -1f, ANGLE_TOLERANCE_DEG)
            assertNull(gate.resolveFromIntegrity(latest, replay.nowElapsedMs))
        }

        repeat(20) {
            if (latest.state == CompassTrackingState.TRACKING) return@repeat
            replay.advance(100L)
            replay.magnetic(42f)
            latest = replay.absolute(240f)
        }

        assertEquals(CompassTrackingState.TRACKING, latest.state)
        assertEquals(CompassTrackingReason.STABLE, latest.reason)
        assertFalse(latest.heldOutput)
        val target = gate.resolveFromIntegrity(latest, replay.nowElapsedMs)

        assertEquals(latest.renderHeadingDeg ?: -1f, target?.headingDeg ?: -2f, ANGLE_TOLERANCE_DEG)
    }

    @Test
    fun unseededAcquisitionPublishesFirstSampleImmediatelyThenHoldsChangingSamplesPastTimeout() {
        val replay = WakeReplay(relativeSensorAvailable = false)
        replay.reset(seedHeadingDeg = null)
        replay.warmMagneticField()

        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(nowElapsedMs = 1_000L, heldHeadingDeg = 0f)

        var latest = replay.absolute(headingDeg = 30f)
        assertFalse(latest.heldOutput)
        assertEquals(30f, latest.renderHeadingDeg ?: -1f, ANGLE_TOLERANCE_DEG)
        assertNull(gate.resolveFromIntegrity(latest, replay.nowElapsedMs))

        listOf(120f, 240f, 120f, 240f, 120f).forEach { headingDeg ->
            replay.advance(100L)
            replay.magnetic(42f)
            latest = replay.absolute(headingDeg)
            assertTrue(latest.heldOutput)
            assertEquals(30f, latest.renderHeadingDeg ?: -1f, ANGLE_TOLERANCE_DEG)
            assertNull(gate.resolveFromIntegrity(latest, replay.nowElapsedMs))
        }

        repeat(20) {
            if (latest.state == CompassTrackingState.TRACKING) return@repeat
            replay.advance(100L)
            replay.magnetic(42f)
            latest = replay.absolute(240f)
        }

        assertEquals(CompassTrackingState.TRACKING, latest.state)
        assertEquals(CompassTrackingReason.STABLE, latest.reason)
        assertFalse(latest.heldOutput)
        val target = gate.resolveFromIntegrity(latest, replay.nowElapsedMs)

        assertEquals(latest.renderHeadingDeg ?: -1f, target?.headingDeg ?: -2f, ANGLE_TOLERANCE_DEG)
    }

    @Test
    fun seededAcquisitionMovesAndReleasesDuringAContinuousTurnWithoutRelativeWitness() {
        val replay = WakeReplay(relativeSensorAvailable = false)
        replay.reset(seedHeadingDeg = 100f)
        replay.warmMagneticField()

        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(nowElapsedMs = replay.nowElapsedMs, heldHeadingDeg = 100f)

        var latest = replay.absolute(headingDeg = 100f)
        var target: NavigationRotationTarget? = gate.resolveFromIntegrity(latest, replay.nowElapsedMs)
        repeat(60) { index ->
            replay.advance(20L)
            replay.magnetic(42f)
            latest = replay.absolute(headingDeg = 100f + index * 1.2f, liveErrorDeg = 25f)
            target = gate.resolveFromIntegrity(latest, replay.nowElapsedMs) ?: target
        }

        assertFalse(latest.heldOutput)
        assertTrue((latest.renderHeadingDeg ?: 100f) > 100f)
        assertTrue(
            "A coherent absolute turn without a relative witness must eventually release wake",
            target != null,
        )
        assertEquals(10f, target?.maxVisualStepDeg ?: -1f, 0f)
    }

    @Test
    fun lowPowerAcquisitionReleasesDuringPlausibleMotionWithoutRelativeEvidence() {
        val replay = WakeReplay(relativeSensorAvailable = false)
        replay.reset(seedHeadingDeg = 100f)
        replay.warmMagneticField()
        val wakeStartedAtMs = replay.nowElapsedMs
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(nowElapsedMs = wakeStartedAtMs, heldHeadingDeg = 100f)

        var latest = replay.absolute(100f, liveErrorDeg = 25f, conservativeErrorDeg = 180f)
        var firstTarget: NavigationRotationTarget? = null
        var firstReleaseAtMs: Long? = null
        repeat(40) { index ->
            replay.advance(200L)
            replay.magnetic(42f)
            val headingDeg = (100f + (index + 1) * 24f) % 360f
            latest = replay.absolute(headingDeg, liveErrorDeg = 25f, conservativeErrorDeg = 180f)
            val target = gate.resolveFromIntegrity(latest, replay.nowElapsedMs)
            if (firstTarget == null && target != null) {
                firstTarget = target
                firstReleaseAtMs = replay.nowElapsedMs
            }
        }

        assertTrue("A plausible 120 deg/s turn at 5 Hz must release wake", firstTarget != null)
        val releaseAgeMs = requireNotNull(firstReleaseAtMs) - wakeStartedAtMs
        assertTrue(releaseAgeMs in WAKE_TIMEOUT_MS..(WAKE_TIMEOUT_MS + 200L))
        assertEquals(10f, firstTarget?.maxVisualStepDeg ?: -1f, 0f)
        assertFalse(latest.heldOutput)
        assertFalse(latest.trusted)
        assertEquals(CompassTrackingState.ACQUIRING, latest.state)
        assertEquals(340f, requireNotNull(latest.renderHeadingDeg), ANGLE_TOLERANCE_DEG)
    }

    @Test
    fun unseededAcquisitionMovesDuringAContinuousTurnWhenTheRelativeWitnessIsUnavailable() {
        val replay = WakeReplay(relativeSensorAvailable = true)
        replay.reset(seedHeadingDeg = null)
        replay.warmMagneticField()

        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(nowElapsedMs = replay.nowElapsedMs, heldHeadingDeg = 0f)

        var latest = replay.absolute(headingDeg = 100f)
        var target: NavigationRotationTarget? = gate.resolveFromIntegrity(latest, replay.nowElapsedMs)
        repeat(60) { index ->
            replay.advance(20L)
            replay.magnetic(42f)
            replay.witnessUnavailable(horizontalProjection = 0.1f)
            latest = replay.absolute(headingDeg = 100f + index * 1.2f, liveErrorDeg = 25f)
            target = gate.resolveFromIntegrity(latest, replay.nowElapsedMs) ?: target
        }

        assertFalse(latest.heldOutput)
        assertTrue((latest.renderHeadingDeg ?: 100f) > 100f)
        assertTrue(target != null)
    }

    @Test
    fun corroboratedMovementDuringWakeReleasesTheMovedTargetWithoutWaitingForTimeout() {
        val replay = WakeReplay(relativeSensorAvailable = true)
        replay.reset(seedHeadingDeg = 100f)
        replay.warmMagneticField()

        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(nowElapsedMs = 1_000L, heldHeadingDeg = 100f)

        replay.relative(headingDeg = 0f)
        var latest = replay.absolute(headingDeg = 100f)
        assertTrue(latest.heldOutput)
        assertNull(gate.resolveFromIntegrity(latest, replay.nowElapsedMs))

        (120..260 step 20).forEach { headingDeg ->
            replay.advance(50L)
            replay.magnetic(42f)
            replay.relative(headingDeg = headingDeg - 100f)
            latest = replay.absolute(headingDeg = headingDeg.toFloat())
        }

        assertEquals(CompassTrackingState.TRACKING, latest.state)
        assertEquals(CompassTrackingReason.STABLE, latest.reason)
        assertFalse(latest.heldOutput)
        assertTrue(
            "Expected the recovered heading to move away from the wake anchor: ${latest.renderHeadingDeg}",
            (latest.renderHeadingDeg ?: 0f) > 100f,
        )
        val target = gate.resolveFromIntegrity(latest, replay.nowElapsedMs)

        assertEquals(latest.renderHeadingDeg ?: -1f, target?.headingDeg ?: -2f, ANGLE_TOLERANCE_DEG)
    }

    @Test
    fun magneticFeedLossAllowsBoundedDegradedWakeReleaseWithoutTrustingUnavailableEvidence() {
        val replay = WakeReplay(relativeSensorAvailable = false)
        replay.reset(seedHeadingDeg = 100f)
        replay.warmMagneticField()
        val stable = replay.acquireStableHeading(headingDeg = 100f)

        assertEquals(CompassTrackingState.TRACKING, stable.state)
        assertEquals(CompassMagneticQuality.GOOD, stable.magneticQuality)

        val gate = NavigateRotationSettleGate()
        val wakeStartedAtElapsedMs = replay.nowElapsedMs
        gate.beginWakeSession(
            nowElapsedMs = wakeStartedAtElapsedMs,
            heldHeadingDeg = requireNotNull(stable.renderHeadingDeg),
        )

        replay.advance(20L)
        replay.magnetic(2_000f)
        var latest = replay.absolute(headingDeg = 100f)
        assertEquals(CompassMagneticQuality.INTERFERENCE, latest.magneticQuality)
        assertNull(gate.resolveFromIntegrity(latest, replay.nowElapsedMs))

        var target: NavigationRotationTarget? = null
        var steps = 0
        while (steps < 45 && target == null) {
            replay.advance(100L)
            latest = replay.absolute(headingDeg = 100f)
            val candidate = gate.resolveFromIntegrity(latest, replay.nowElapsedMs)
            if (latest.state == CompassTrackingState.TRACKING &&
                latest.magneticQuality == CompassMagneticQuality.UNAVAILABLE &&
                candidate != null
            ) {
                target = candidate
            }
            steps += 1
        }

        assertEquals(CompassTrackingState.TRACKING, latest.state)
        assertEquals(CompassTrackingReason.STABLE, latest.reason)
        assertEquals(CompassMagneticQuality.UNAVAILABLE, latest.magneticQuality)
        assertFalse(latest.trusted)
        assertTrue(replay.nowElapsedMs - wakeStartedAtElapsedMs >= WAKE_TIMEOUT_MS)
        assertTrue(
            "Expected bounded release after magnetic evidence became unavailable: " +
                "state=${latest.state} reason=${latest.reason} held=${latest.heldOutput} " +
                "render=${latest.renderHeadingDeg} quality=${latest.magneticQuality} target=$target",
            target != null,
        )
        assertEquals(100f, target?.headingDeg ?: -1f, ANGLE_TOLERANCE_DEG)
        assertEquals(10f, target?.maxVisualStepDeg ?: -1f, 0f)
    }

    @Test
    fun magneticRecoveryObligationArmsEvenWhenTheFirstInterferenceOutputIsHeld() {
        val replay =
            WakeReplay(
                relativeSensorAvailable = false,
                integrityConfig =
                    FusedHeadingIntegrityConfig(
                        acquisitionMinimumSamples = 2,
                        acquisitionWindowMs = 100L,
                        magneticSampleStaleMs = 100L,
                        magneticSampleUnavailableMs = 200L,
                    ),
            )
        replay.reset(seedHeadingDeg = 100f)
        replay.warmMagneticField()
        val stable = replay.acquireStableHeading(headingDeg = 100f)
        assertEquals(CompassTrackingState.TRACKING, stable.state)

        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(replay.nowElapsedMs, 100f)
        replay.advance(20L)
        replay.magnetic(2_000f)
        var latest = replay.absolute(100f, liveErrorDeg = 25f)
        assertTrue(latest.heldOutput)
        assertNull(gate.resolveFromIntegrity(latest, replay.nowElapsedMs))

        replay.advance(250L)
        latest = replay.absolute(100f, liveErrorDeg = 25f)
        assertEquals(CompassMagneticQuality.UNAVAILABLE, latest.magneticQuality)
        assertNull(gate.resolveFromIntegrity(latest, replay.nowElapsedMs))

        replay.advance(WAKE_TIMEOUT_MS)
        latest = replay.absolute(100f, liveErrorDeg = 25f)
        val target = gate.resolveFromIntegrity(latest, replay.nowElapsedMs)
        assertEquals(CompassMagneticQuality.UNAVAILABLE, latest.magneticQuality)
        assertTrue(
            "Expected bounded release after unavailable magnetic evidence: " +
                "state=${latest.state} reason=${latest.reason} held=${latest.heldOutput} " +
                "render=${latest.renderHeadingDeg} target=$target",
            target != null,
        )
        assertEquals(100f, target?.headingDeg ?: -1f, ANGLE_TOLERANCE_DEG)
    }

    @Test
    fun jumpHoldSurvivesInterferenceAfterWakeHasAlreadyReleased() {
        val replay = WakeReplay(relativeSensorAvailable = true)
        replay.reset(seedHeadingDeg = 100f)
        replay.warmMagneticField()
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(nowElapsedMs = replay.nowElapsedMs, heldHeadingDeg = 100f)
        var target: NavigationRotationTarget? = null

        repeat(500) {
            replay.advance(20L)
            replay.magnetic(42f)
            replay.relative(0f)
            val snapshot = replay.absolute(100f)
            target = gate.resolveFromIntegrity(snapshot, replay.nowElapsedMs) ?: target
        }
        assertEquals(100f, requireNotNull(target).headingDeg, ANGLE_TOLERANCE_DEG)

        replay.advance(20L)
        replay.relative(0f)
        val held = replay.absolute(280f, liveErrorDeg = 25f, conservativeErrorDeg = 180f)
        assertTrue(held.heldOutput)
        repeat(75) {
            replay.advance(20L)
            replay.magnetic(2_000f)
            replay.relative(0f)
            val snapshot = replay.absolute(280f, liveErrorDeg = 25f, conservativeErrorDeg = 180f)
            val applied = gate.resolveFromIntegrity(snapshot, replay.nowElapsedMs)

            assertEquals(100f, requireNotNull(applied).headingDeg, ANGLE_TOLERANCE_DEG)
            assertFalse(snapshot.trusted)
        }

        var latest = held
        repeat(100) {
            replay.advance(20L)
            replay.magnetic(42f)
            replay.relative(0f)
            latest = replay.absolute(100f)
            val applied = gate.resolveFromIntegrity(latest, replay.nowElapsedMs)
            assertEquals(100f, requireNotNull(applied).headingDeg, ANGLE_TOLERANCE_DEG)
        }
        assertEquals(CompassTrackingState.TRACKING, latest.state)
        assertFalse(latest.heldOutput)
    }

    private fun NavigateRotationSettleGate.resolveFromIntegrity(
        snapshot: FusedHeadingIntegritySnapshot,
        nowElapsedMs: Long,
    ): NavigationRotationTarget? {
        val heading = snapshot.renderHeadingDeg ?: return null
        return resolve(
            renderState = snapshot.toRenderState(nowElapsedMs),
            compassHeadingDeg = heading,
            headingSampleElapsedRealtimeMs = nowElapsedMs,
            nowElapsedMs = nowElapsedMs,
        )
    }

    private fun FusedHeadingIntegritySnapshot.toRenderState(
        sampleAtElapsedMs: Long,
    ): CompassRenderState =
        initialCompassRenderState(providerType = CompassProviderType.GOOGLE_FUSED).copy(
            headingDeg = requireNotNull(renderHeadingDeg),
            accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
            headingSampleElapsedRealtimeMs = sampleAtElapsedMs,
            headingSampleSequenceId = sampleAtElapsedMs,
            headingSampleHeldOutput = heldOutput,
            headingProvenance = CompassHeadingProvenance(CompassProviderType.GOOGLE_FUSED, 1L),
            headingSource = HeadingSource.FUSED_ORIENTATION,
            headingRenderable = renderable,
            headingTrusted = trusted,
            trackingState = state,
            trackingReason = reason,
            magneticQuality = magneticQuality,
            magneticFieldUt = magneticFieldUt,
            quarantineActive = quarantineActive,
            headingJumpHeld = headingJumpHeld,
            unresolvedIndependentDisagreement = unresolvedIndependentDisagreement,
            relativeHeadingDeg = relativeHeadingDeg,
            relativeMotionSample =
                relativeHeadingDeg?.let { heading ->
                    CompassRelativeMotionSample(
                        headingDeg = heading,
                        horizontalProjection = relativeHorizontalProjection ?: 0f,
                        atElapsedMs = sampleAtElapsedMs,
                        provenance = CompassHeadingProvenance(CompassProviderType.GOOGLE_FUSED, 1L),
                    )
                },
        )

    private class WakeReplay(
        relativeSensorAvailable: Boolean,
        integrityConfig: FusedHeadingIntegrityConfig = FusedHeadingIntegrityConfig(),
    ) {
        private val engine =
            FusedHeadingIntegrityEngine(
                relativeSensorAvailable = relativeSensorAvailable,
                magnetometerAvailable = true,
                config = integrityConfig,
            )
        var nowElapsedMs = 1_000L
            private set

        fun reset(seedHeadingDeg: Float?) {
            engine.reset(
                seedHeadingDeg = seedHeadingDeg,
                atElapsedMs = nowElapsedMs,
                clearSensorEvidence = true,
            )
        }

        fun advance(durationMs: Long) {
            nowElapsedMs += durationMs
        }

        fun warmMagneticField() {
            magnetic(42f)
            advance(200L)
            magnetic(42f)
        }

        fun magnetic(strengthUt: Float) {
            engine.onMagneticField(strengthUt = strengthUt, atElapsedMs = nowElapsedMs)
        }

        fun relative(headingDeg: Float) {
            engine.onRelativeHeading(
                headingDeg = headingDeg,
                horizontalProjection = 0.9f,
                atElapsedMs = nowElapsedMs,
            )
        }

        fun witnessUnavailable(horizontalProjection: Float) {
            engine.onRelativeWitnessUnavailable(horizontalProjection)
        }

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
                    sourceSampleId = nowElapsedMs,
                ),
            )

        fun acquireStableHeading(headingDeg: Float): FusedHeadingIntegritySnapshot {
            var latest = absolute(headingDeg)
            repeat(20) {
                if (latest.state == CompassTrackingState.TRACKING &&
                    latest.magneticQuality == CompassMagneticQuality.GOOD
                ) {
                    return latest
                }
                advance(100L)
                magnetic(42f)
                latest = absolute(headingDeg)
            }
            return latest
        }
    }

    private companion object {
        const val ANGLE_TOLERANCE_DEG = 0.01f
        const val WAKE_TIMEOUT_MS = 700L
    }
}
