package com.glancemap.glancemapwearos.presentation.features.navigate

import android.hardware.SensorManager
import com.glancemap.glancemapwearos.domain.sensors.CompassMagneticQuality
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.domain.sensors.CompassRenderState
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingReason
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import com.glancemap.glancemapwearos.domain.sensors.HeadingSource
import com.glancemap.glancemapwearos.domain.sensors.initialCompassRenderState
import com.glancemap.glancemapwearos.domain.sensors.shortestAngleDiffDeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigateEffectsSupportTest {
    @Test
    fun googleFusedWakeHeadingWaitsForANewSessionSample() {
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(
            nowElapsedMs = 1_000L,
            heldHeadingDeg = 0f,
        )
        val state = readyGoogleFusedState()

        assertNull(
            gate.resolve(
                renderState = state,
                compassHeadingDeg = 180f,
                headingSampleElapsedRealtimeMs = 1_000L,
                nowElapsedMs = 1_000L,
            ),
        )
        val target =
            gate.resolve(
                renderState = stableTrackingState(state),
                compassHeadingDeg = 180f,
                headingSampleElapsedRealtimeMs = 1_001L,
                nowElapsedMs = 1_100L,
            )

        assertEquals(180f, target?.headingDeg ?: -1f, 0f)
    }

    @Test
    fun googleFusedWakeHeadingRemainsTheVisualAuthorityAfterARealTurn() {
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(
            nowElapsedMs = 1_000L,
            heldHeadingDeg = 0f,
        )

        val target =
            gate.resolve(
                renderState = stableTrackingState(readyGoogleFusedState()),
                compassHeadingDeg = 90f,
                headingSampleElapsedRealtimeMs = 1_050L,
                nowElapsedMs = 1_100L,
            )

        assertEquals(90f, target?.headingDeg ?: -1f, 0f)
    }

    @Test
    fun trustedPreLoweringAnchorWaitsForHeadingPublishedAfterStableTracking() {
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(
            nowElapsedMs = 1_000L,
            heldHeadingDeg = 235.5f,
            requirePostStableHeading = true,
        )
        val stableState = stableTrackingState(readyGoogleFusedState())

        // The state transition can arrive with a freshly stamped but retained pre-restart
        // heading. Do not release a trusted visible anchor toward that value.
        assertNull(
            gate.resolve(
                renderState = stableState,
                compassHeadingDeg = 194.9f,
                headingSampleElapsedRealtimeMs = 1_500L,
                nowElapsedMs = 1_510L,
            ),
        )
        assertNull(
            gate.resolve(
                renderState = stableState,
                compassHeadingDeg = 194.9f,
                headingSampleElapsedRealtimeMs = 1_500L,
                nowElapsedMs = 1_525L,
            ),
        )

        val target =
            gate.resolve(
                renderState = stableState,
                compassHeadingDeg = 223f,
                headingSampleElapsedRealtimeMs = 1_530L,
                nowElapsedMs = 1_540L,
            )

        assertEquals(223f, target?.headingDeg ?: -1f, 0f)
        assertEquals(10f, target?.maxVisualStepDeg ?: -1f, 0f)
    }

    @Test
    fun trustedPreLoweringAnchorStillUsesTheBoundedSettleTimeout() {
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(
            nowElapsedMs = 1_000L,
            heldHeadingDeg = 235.5f,
            requirePostStableHeading = true,
        )
        val stableState = stableTrackingState(readyGoogleFusedState())

        assertNull(
            gate.resolve(
                renderState = stableState,
                compassHeadingDeg = 194.9f,
                headingSampleElapsedRealtimeMs = 1_500L,
                nowElapsedMs = 1_510L,
            ),
        )
        val target =
            gate.resolve(
                renderState = stableState,
                compassHeadingDeg = 194.9f,
                headingSampleElapsedRealtimeMs = 1_500L,
                nowElapsedMs = 1_700L,
            )

        assertEquals(194.9f, target?.headingDeg ?: -1f, 0.001f)
        assertEquals(10f, target?.maxVisualStepDeg ?: -1f, 0f)
    }

    @Test
    fun compassWakeFallsBackToAReadableHeadingAfterTheBoundedSettleTimeout() {
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(nowElapsedMs = 1_000L, heldHeadingDeg = 0f)

        val target =
            gate.resolve(
                renderState = readyGoogleFusedState(),
                compassHeadingDeg = 90f,
                headingSampleElapsedRealtimeMs = 1_050L,
                nowElapsedMs = 1_700L,
            )

        assertEquals(90f, target?.headingDeg ?: -1f, 0f)
        assertEquals(10f, target?.maxVisualStepDeg ?: -1f, 0f)
        assertTrue(target?.recordsWakeReleaseStep == true)
    }

    @Test
    fun coldCompassFollowWithGoodMagneticStateKeepsTheExistingImmediateStart() {
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(nowElapsedMs = 1_000L, heldHeadingDeg = 85f, coldStart = true)

        val target =
            gate.resolve(
                renderState = stableMagneticGoogleFusedState(),
                compassHeadingDeg = 121f,
                headingSampleElapsedRealtimeMs = 1_001L,
                nowElapsedMs = 1_010L,
                currentDisplayedHeadingDeg = 85f,
            )

        assertEquals(121f, target?.headingDeg ?: -1f, 0f)
        assertFalse(target?.recordsWakeReleaseStep == true)
    }

    @Test
    fun coldInterferenceDoesNotReplaceTheExistingVisibleCompassAnchor() {
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(nowElapsedMs = 1_000L, heldHeadingDeg = 85f, coldStart = true)
        val interference = interferenceGoogleFusedState()

        assertTrue(shouldHoldCompassFollowStartupForMagneticInterference(interference))
        assertNull(
            gate.resolve(
                renderState = interference,
                compassHeadingDeg = 121f,
                headingSampleElapsedRealtimeMs = 1_001L,
                nowElapsedMs = 1_010L,
                currentDisplayedHeadingDeg = 85f,
            ),
        )
        assertNull(
            gate.resolve(
                renderState = interference,
                compassHeadingDeg = 121f,
                headingSampleElapsedRealtimeMs = 1_100L,
                nowElapsedMs = 10_000L,
                currentDisplayedHeadingDeg = 85f,
            ),
        )
    }

    @Test
    fun coldInterferenceDoesNotEstablishTheFirstCompassOrientation() {
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(nowElapsedMs = 1_000L, heldHeadingDeg = 0f, coldStart = true)

        assertNull(
            gate.resolve(
                renderState = interferenceGoogleFusedState(),
                compassHeadingDeg = 121f,
                headingSampleElapsedRealtimeMs = 1_001L,
                nowElapsedMs = 1_010L,
                currentDisplayedHeadingDeg = 0f,
            ),
        )
    }

    @Test
    fun coldInterferenceReleasesOnlyAfterAStableMagneticHeading() {
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(nowElapsedMs = 1_000L, heldHeadingDeg = 85f, coldStart = true)

        assertNull(
            gate.resolve(
                renderState = interferenceGoogleFusedState(),
                compassHeadingDeg = 121f,
                headingSampleElapsedRealtimeMs = 1_001L,
                nowElapsedMs = 1_010L,
                currentDisplayedHeadingDeg = 85f,
            ),
        )
        assertNull(
            gate.resolve(
                renderState = stableTrackingState(readyGoogleFusedState()),
                compassHeadingDeg = 121f,
                headingSampleElapsedRealtimeMs = 1_500L,
                nowElapsedMs = 1_510L,
                currentDisplayedHeadingDeg = 85f,
            ),
        )
        val target =
            gate.resolve(
                renderState = stableMagneticGoogleFusedState(),
                compassHeadingDeg = 121f,
                headingSampleElapsedRealtimeMs = 2_001L,
                nowElapsedMs = 2_010L,
                currentDisplayedHeadingDeg = 85f,
            )

        assertEquals(121f, target?.headingDeg ?: -1f, 0f)
        assertEquals(10f, target?.maxVisualStepDeg ?: -1f, 0f)
    }

    @Test
    fun laterInterferenceKeepsDrivingAfterAStableCompassHeadingWasEstablished() {
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(nowElapsedMs = 1_000L, heldHeadingDeg = 85f, coldStart = true)

        assertEquals(
            90f,
            gate.resolve(
                renderState = stableMagneticGoogleFusedState(),
                compassHeadingDeg = 90f,
                headingSampleElapsedRealtimeMs = 1_001L,
                nowElapsedMs = 1_010L,
            )?.headingDeg ?: -1f,
            0f,
        )
        assertEquals(
            180f,
            gate.resolve(
                renderState = interferenceGoogleFusedState(),
                compassHeadingDeg = 180f,
                headingSampleElapsedRealtimeMs = 1_050L,
                nowElapsedMs = 1_060L,
            )?.headingDeg ?: -1f,
            0f,
        )
    }

    @Test
    fun restartedInterferenceGateRejectsAStaleRecoverySample() {
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(nowElapsedMs = 1_000L, heldHeadingDeg = 85f, coldStart = true)
        gate.resolve(
            renderState = interferenceGoogleFusedState(),
            compassHeadingDeg = 121f,
            headingSampleElapsedRealtimeMs = 1_001L,
            nowElapsedMs = 1_010L,
        )
        gate.endWakeSession(nowElapsedMs = 1_020L)
        gate.beginWakeSession(nowElapsedMs = 1_030L, heldHeadingDeg = 85f, coldStart = true)

        assertNull(
            gate.resolve(
                renderState = stableMagneticGoogleFusedState(),
                compassHeadingDeg = 121f,
                headingSampleElapsedRealtimeMs = 1_020L,
                nowElapsedMs = 1_031L,
            ),
        )
    }

    @Test
    fun wakeContinuityCaptureRetainsTheLastInteractiveHeadingAcrossHiddenProviderMovement() {
        val capture = NavigateWakeContinuityCapture()
        capture.recordInteractive(
            providerHeadingDeg = 300f,
            targetHeadingDeg = 302f,
            renderedHeadingDeg = 300f,
            pitchDeg = 0f,
            rollDeg = 0f,
            projection = 1f,
            atElapsedMs = 1_000L,
        )

        val screenOff = capture.screenOff(fallbackHeadingDeg = 300f, nowElapsedMs = 1_100L)
        // Provider samples can move to ~220° while the wrist is lowered, but they are not
        // interactive samples and must not replace the visible continuity reference.
        val wake = capture.wake(fallbackHeadingDeg = 220f)
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(
            nowElapsedMs = 1_000L,
            heldHeadingDeg = wake.wakeHeldHeadingDeg,
        )

        val target =
            gate.resolve(
                renderState = stableTrackingState(readyGoogleFusedState()),
                compassHeadingDeg = 300f,
                headingSampleElapsedRealtimeMs = 1_050L,
                nowElapsedMs = 1_100L,
            )

        assertEquals(300f, screenOff.lastInteractiveProviderHeadingDeg, 0f)
        assertEquals(302f, screenOff.lastInteractiveTargetHeadingDeg, 0f)
        assertEquals(300f, screenOff.lastInteractiveRenderedHeadingDeg, 0f)
        assertEquals(300f, wake.screenOffAnchorHeadingDeg, 0f)
        assertEquals(300f, wake.wakeHeldHeadingDeg, 0f)
        assertEquals(0f, wake.wakeHeadingDeltaDeg, 0f)
        assertEquals(300f, target?.headingDeg ?: -1f, 0f)
    }

    @Test
    fun stableHeadingKeepsTheCurrentScreenOffAnchor() {
        val capture = NavigateWakeContinuityCapture()
        capture.recordInteractive(342f, 342f, 342f, 0f, 0f, 1f, 0L)
        capture.recordInteractive(342f, 342f, 342f, 0f, 0f, 1f, 100L)
        capture.recordInteractive(342f, 342f, 342f, 0f, 0f, 1f, 200L)

        val screenOff = capture.screenOff(fallbackHeadingDeg = 342f, nowElapsedMs = 250L)

        assertEquals(342f, screenOff.screenOffAnchorHeadingDeg, 0f)
        assertFalse(screenOff.preLoweringDetected)
        assertEquals(0L, screenOff.anchorRewindMs)
    }

    @Test
    fun wristLoweringUsesTheLastStableVisibleHeadingAsScreenOffAnchor() {
        val capture = NavigateWakeContinuityCapture()
        capture.recordInteractive(342f, 342f, 342f, 0f, 0f, 1f, 0L)
        capture.recordInteractive(343f, 343f, 343f, 1f, 0f, 1f, 100L)
        capture.recordInteractive(342f, 342f, 342f, 1f, 0f, 1f, 200L)
        capture.recordInteractive(349f, 349f, 349f, 8f, 0f, 0.98f, 300L)
        capture.recordInteractive(358f, 358f, 358f, 18f, 0f, 0.93f, 400L)
        capture.recordInteractive(12f, 12f, 12f, 28f, 0f, 0.86f, 500L)

        val screenOff = capture.screenOff(fallbackHeadingDeg = 12f, nowElapsedMs = 520L)

        assertEquals(342f, screenOff.screenOffAnchorHeadingDeg, 0f)
        assertTrue(screenOff.preLoweringDetected)
        assertEquals("pre_lowering_stable_visible", screenOff.selectionReason)
        assertEquals(320L, screenOff.anchorRewindMs)
        assertTrue(capture.wake(fallbackHeadingDeg = 12f).preLoweringDetected)
    }

    @Test
    fun intentionalTurnWithoutTiltChangeDoesNotRewindTheAnchor() {
        val capture = NavigateWakeContinuityCapture()
        capture.recordInteractive(342f, 342f, 342f, 0f, 0f, 1f, 0L)
        capture.recordInteractive(342f, 342f, 342f, 0f, 0f, 1f, 100L)
        capture.recordInteractive(45f, 45f, 45f, 0f, 0f, 1f, 200L)
        capture.recordInteractive(90f, 90f, 90f, 0f, 0f, 1f, 300L)

        val screenOff = capture.screenOff(fallbackHeadingDeg = 90f, nowElapsedMs = 320L)

        assertEquals(90f, screenOff.screenOffAnchorHeadingDeg, 0f)
        assertFalse(screenOff.preLoweringDetected)
    }

    @Test
    fun smallHeadingMovementAcrossNorthDoesNotCreateAFalseAnchorRewind() {
        val capture = NavigateWakeContinuityCapture()
        capture.recordInteractive(355f, 355f, 355f, 0f, 0f, 1f, 0L)
        capture.recordInteractive(355f, 355f, 355f, 0f, 0f, 1f, 100L)
        capture.recordInteractive(355f, 355f, 355f, 0f, 0f, 1f, 200L)
        capture.recordInteractive(5f, 5f, 5f, 20f, 0f, 0.85f, 300L)

        val screenOff = capture.screenOff(fallbackHeadingDeg = 5f, nowElapsedMs = 320L)

        assertEquals(5f, screenOff.screenOffAnchorHeadingDeg, 0f)
        assertFalse(screenOff.preLoweringDetected)
        assertEquals(10f, shortestAngleDiffDeg(target = 5f, current = 355f), 0f)
    }

    @Test
    fun newInteractiveSessionCannotReuseThePreviousScreenOffHistory() {
        val capture = NavigateWakeContinuityCapture()
        capture.recordInteractive(300f, 300f, 300f, 0f, 0f, 1f, 0L)
        capture.recordInteractive(300f, 300f, 300f, 0f, 0f, 1f, 100L)
        capture.screenOff(fallbackHeadingDeg = 300f, nowElapsedMs = 120L)

        capture.beginInteractiveSession()
        capture.recordInteractive(20f, 20f, 20f, 0f, 0f, 1f, 130L)
        capture.recordInteractive(20f, 20f, 20f, 0f, 0f, 1f, 230L)
        val secondScreenOff = capture.screenOff(fallbackHeadingDeg = 20f, nowElapsedMs = 250L)

        assertEquals(20f, secondScreenOff.screenOffAnchorHeadingDeg, 0f)
        assertEquals(20f, capture.wake(fallbackHeadingDeg = 300f).wakeHeldHeadingDeg, 0f)
    }

    @Test
    fun wakeAnchorCrossesNorthUsingTheShortTenDegreePath() {
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(nowElapsedMs = 1_000L, heldHeadingDeg = 355f)

        val target =
            gate.resolve(
                renderState = stableTrackingState(readyGoogleFusedState()),
                compassHeadingDeg = 5f,
                headingSampleElapsedRealtimeMs = 1_050L,
                nowElapsedMs = 1_100L,
            )

        assertEquals(5f, target?.headingDeg ?: -1f, 0f)
        assertEquals(10f, shortestAngleDiffDeg(target = 5f, current = 355f), 0f)
        val firstStep =
            resolveHeadingAnimationDelta(
                diffDeg = shortestAngleDiffDeg(target = 5f, current = 355f),
                activeTurn = false,
                frameDeltaMs = 16.667f,
                maxStepDeg = target?.maxVisualStepDeg,
            )
        assertTrue(
            "Wake movement must take the short positive path and stay within the 10° cap.",
            firstStep > 0f && firstStep <= 10f,
        )
    }

    @Test
    fun rapidWakeRejectsThePreviousSessionSampleAndKeepsTheFrozenAnchor() {
        val capture = NavigateWakeContinuityCapture()
        val gate = NavigateRotationSettleGate()
        capture.screenOff(fallbackHeadingDeg = 300f, nowElapsedMs = 900L)

        gate.beginWakeSession(nowElapsedMs = 1_000L, heldHeadingDeg = capture.wake(220f).wakeHeldHeadingDeg)
        gate.endWakeSession(nowElapsedMs = 1_010L)
        val secondWake = capture.wake(fallbackHeadingDeg = 220f)
        gate.beginWakeSession(nowElapsedMs = 1_020L, heldHeadingDeg = secondWake.wakeHeldHeadingDeg)

        assertEquals(300f, secondWake.wakeHeldHeadingDeg, 0f)
        assertNull(
            gate.resolve(
                renderState = stableTrackingState(readyGoogleFusedState()),
                compassHeadingDeg = 220f,
                headingSampleElapsedRealtimeMs = 1_010L,
                nowElapsedMs = 1_021L,
            ),
        )
        assertEquals(
            300f,
            gate.resolve(
                renderState = stableTrackingState(readyGoogleFusedState()),
                compassHeadingDeg = 300f,
                headingSampleElapsedRealtimeMs = 1_021L,
                nowElapsedMs = 1_030L,
            )?.headingDeg ?: -1f,
            0f,
        )
    }

    @Test
    fun wakeAnchorIsUsedOnlyForCompassFollowNotManualPanning() {
        assertTrue(shouldUseWakeContinuityAnchor(NavMode.COMPASS_FOLLOW))
        assertFalse(shouldUseWakeContinuityAnchor(NavMode.NORTH_UP_FOLLOW))
        assertFalse(shouldUseWakeContinuityAnchor(NavMode.PANNING))
    }

    @Test
    fun compassFollowMapStaysFrozenWithoutActiveHeadingSource() {
        assertFalse(
            shouldDriveCompassFollowMap(
                initialCompassRenderState(providerType = CompassProviderType.SENSOR_MANAGER),
            ),
        )
    }

    @Test
    fun stableGoodCompassUsesResponsiveMapRotation() {
        val state =
            readyGoogleFusedState().copy(
                trackingState = CompassTrackingState.TRACKING,
                trackingReason = CompassTrackingReason.STABLE,
                magneticQuality = CompassMagneticQuality.GOOD,
            )

        assertTrue(shouldUseResponsiveCompassMapRotation(state))
        assertEquals(
            20f,
            resolveHeadingAnimationDelta(
                diffDeg = 120f,
                activeTurn = true,
                frameDeltaMs = 16.667f,
                responsiveRotation = true,
            ),
            0.01f,
        )
        assertEquals(
            10f,
            resolveHeadingAnimationDelta(
                diffDeg = 120f,
                activeTurn = true,
                frameDeltaMs = 16.667f,
                responsiveRotation = true,
                maxStepDeg = 10f,
            ),
            0.01f,
        )
    }

    @Test
    fun recoveringCompassKeepsConservativeRotationCap() {
        val state =
            readyGoogleFusedState().copy(
                trackingState = CompassTrackingState.ACQUIRING,
                trackingReason = CompassTrackingReason.RECOVERING,
                magneticQuality = CompassMagneticQuality.RECOVERING,
            )

        assertFalse(shouldUseResponsiveCompassMapRotation(state))
        assertEquals(
            10f,
            resolveHeadingAnimationDelta(
                diffDeg = 120f,
                activeTurn = true,
                frameDeltaMs = 16.667f,
            ),
            0.01f,
        )
    }

    private fun readyGoogleFusedState() =
        initialCompassRenderState(providerType = CompassProviderType.GOOGLE_FUSED).copy(
            headingSource = HeadingSource.FUSED_ORIENTATION,
            accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
            headingSampleElapsedRealtimeMs = 1_050L,
            headingSampleStale = false,
            headingRenderable = true,
        )

    private fun stableTrackingState(state: CompassRenderState) =
        state.copy(
            trackingState = CompassTrackingState.TRACKING,
            trackingReason = CompassTrackingReason.STABLE,
        )

    private fun stableMagneticGoogleFusedState() =
        stableTrackingState(readyGoogleFusedState()).copy(
            magneticQuality = CompassMagneticQuality.GOOD,
            magneticInterference = false,
        )

    private fun interferenceGoogleFusedState() =
        readyGoogleFusedState().copy(
            magneticQuality = CompassMagneticQuality.INTERFERENCE,
            magneticInterference = true,
            trackingState = CompassTrackingState.DEGRADED,
            trackingReason = CompassTrackingReason.MAGNETIC_INTERFERENCE,
        )

    @Test
    fun compassFollowMapStaysFrozenWhenAccuracyIsUnreliable() {
        val state =
            initialCompassRenderState(providerType = CompassProviderType.SENSOR_MANAGER).copy(
                headingSource = HeadingSource.HEADING_SENSOR,
                accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
            )

        assertFalse(shouldDriveCompassFollowMap(state))
    }

    @Test
    fun compassFollowMapWaitsForFreshGoogleFusedSample() {
        val state =
            initialCompassRenderState(providerType = CompassProviderType.GOOGLE_FUSED).copy(
                headingSource = HeadingSource.FUSED_ORIENTATION,
                accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
                headingSampleElapsedRealtimeMs = null,
                headingSampleStale = false,
            )

        assertFalse(shouldDriveCompassFollowMap(state))
    }

    @Test
    fun compassFollowMapWaitsWhenGoogleFusedSampleIsStale() {
        val state =
            initialCompassRenderState(providerType = CompassProviderType.GOOGLE_FUSED).copy(
                headingSource = HeadingSource.FUSED_ORIENTATION,
                accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
                headingSampleElapsedRealtimeMs = 1_000L,
                headingSampleStale = true,
            )

        assertFalse(shouldDriveCompassFollowMap(state))
    }

    @Test
    fun compassFollowMapDrivesWhenSensorManagerHeadingIsReady() {
        val state =
            initialCompassRenderState(providerType = CompassProviderType.SENSOR_MANAGER).copy(
                headingSource = HeadingSource.ROTATION_VECTOR,
                accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
            )

        assertTrue(shouldDriveCompassFollowMap(state))
    }

    @Test
    fun compassFollowMapDrivesWhenGoogleFusedSampleIsFresh() {
        val state =
            initialCompassRenderState(providerType = CompassProviderType.GOOGLE_FUSED).copy(
                headingSource = HeadingSource.FUSED_ORIENTATION,
                accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
                headingSampleElapsedRealtimeMs = 1_000L,
                headingSampleStale = false,
                headingRenderable = true,
            )

        assertTrue(shouldDriveCompassFollowMap(state))
    }

    @Test
    fun compassFollowMapWaitsWhileGoogleFusedUsesBootstrapSensorHeading() {
        val state =
            initialCompassRenderState(providerType = CompassProviderType.GOOGLE_FUSED).copy(
                headingSource = HeadingSource.ROTATION_VECTOR,
                accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
                headingSampleElapsedRealtimeMs = null,
                headingSampleStale = true,
            )

        assertFalse(shouldDriveCompassFollowMap(state))
    }

    @Test
    fun northUpMarkerWaitsWhileGoogleFusedUsesBootstrapSensorHeading() {
        val state =
            initialCompassRenderState(providerType = CompassProviderType.GOOGLE_FUSED).copy(
                headingSource = HeadingSource.ROTATION_VECTOR,
                accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
                headingSampleElapsedRealtimeMs = null,
                headingSampleStale = true,
            )

        assertFalse(shouldDriveMarkerHeading(state))
        assertFalse(shouldDriveHeadingForNavMode(NavMode.NORTH_UP_FOLLOW, state))
        assertFalse(shouldDriveHeadingForNavMode(NavMode.COMPASS_FOLLOW, state))
    }

    @Test
    fun northUpMarkerDrivesWhenGoogleFusedSampleIsFresh() {
        val state =
            initialCompassRenderState(providerType = CompassProviderType.GOOGLE_FUSED).copy(
                headingSource = HeadingSource.FUSED_ORIENTATION,
                accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
                headingSampleElapsedRealtimeMs = 1_000L,
                headingSampleStale = false,
                headingRenderable = true,
            )

        assertTrue(shouldDriveMarkerHeading(state))
        assertTrue(shouldDriveHeadingForNavMode(NavMode.NORTH_UP_FOLLOW, state))
    }

    @Test
    fun degradedGoogleHeadingKeepsMapAndMarkerMovingWhenRenderable() {
        val state =
            initialCompassRenderState(providerType = CompassProviderType.GOOGLE_FUSED).copy(
                headingSource = HeadingSource.FUSED_ORIENTATION,
                accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                headingSampleElapsedRealtimeMs = 1_000L,
                headingSampleStale = false,
                headingRenderable = true,
            )

        assertTrue(shouldDriveCompassFollowMap(state))
        assertTrue(shouldDriveMarkerHeading(state))
    }

    @Test
    fun northUpMarkerDrivesWhenSensorManagerHeadingIsReady() {
        val state =
            initialCompassRenderState(providerType = CompassProviderType.SENSOR_MANAGER).copy(
                headingSource = HeadingSource.ROTATION_VECTOR,
                accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
            )

        assertTrue(shouldDriveMarkerHeading(state))
        assertTrue(shouldDriveHeadingForNavMode(NavMode.NORTH_UP_FOLLOW, state))
    }

    @Test
    fun compassFollowMapCanSeedFromRecentGoogleFusedCachedHeading() {
        val state =
            initialCompassRenderState(providerType = CompassProviderType.GOOGLE_FUSED).copy(
                headingDeg = 182f,
                accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                headingSampleElapsedRealtimeMs = 10_000L,
                headingSampleStale = true,
                headingSource = HeadingSource.NONE,
            )

        assertTrue(
            shouldSeedCompassFollowMapWithCachedHeading(
                renderState = state,
                nowElapsedMs = 25_000L,
            ),
        )
    }

    @Test
    fun compassFollowMapDoesNotSeedFromOldGoogleFusedCachedHeading() {
        val state =
            initialCompassRenderState(providerType = CompassProviderType.GOOGLE_FUSED).copy(
                headingDeg = 182f,
                accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                headingSampleElapsedRealtimeMs = 10_000L,
                headingSampleStale = true,
                headingSource = HeadingSource.NONE,
            )

        assertFalse(
            shouldSeedCompassFollowMapWithCachedHeading(
                renderState = state,
                nowElapsedMs = 45_001L,
            ),
        )
    }

    @Test
    fun northUpMarkerCanSeedFromRecentGoogleFusedCachedHeading() {
        val state =
            initialCompassRenderState(providerType = CompassProviderType.GOOGLE_FUSED).copy(
                headingDeg = 182f,
                accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                headingSampleElapsedRealtimeMs = 10_000L,
                headingSampleStale = true,
                headingSource = HeadingSource.NONE,
            )

        assertTrue(
            shouldSeedNorthUpMarkerWithCachedHeading(
                renderState = state,
                nowElapsedMs = 25_000L,
            ),
        )
    }

    @Test
    fun initialRenderedHeadingUsesRecentGoogleFusedCachedHeading() {
        val state =
            initialCompassRenderState(providerType = CompassProviderType.GOOGLE_FUSED).copy(
                headingDeg = 182f,
                accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
                headingSampleElapsedRealtimeMs = 10_000L,
                headingSampleStale = true,
                headingSource = HeadingSource.NONE,
            )

        assertTrue(
            resolveNavigateInitialRenderedHeadingDeg(
                renderState = state,
                nowElapsedMs = 25_000L,
            ) > 180f,
        )
    }

    @Test
    fun compassFollowLimitsMapsforgeRotationToThirtyHz() {
        assertTrue(
            shouldThrottleMapsforgeRotation(
                navMode = NavMode.COMPASS_FOLLOW,
                nowElapsedMs = 1_032L,
                lastAppliedAtElapsedMs = 1_000L,
            ),
        )
        assertFalse(
            shouldThrottleMapsforgeRotation(
                navMode = NavMode.COMPASS_FOLLOW,
                nowElapsedMs = 1_033L,
                lastAppliedAtElapsedMs = 1_000L,
            ),
        )
    }

    @Test
    fun activeCompassTurnAllowsDisplayRateMapsforgeRotation() {
        assertTrue(
            shouldThrottleMapsforgeRotation(
                navMode = NavMode.COMPASS_FOLLOW,
                nowElapsedMs = 1_015L,
                lastAppliedAtElapsedMs = 1_000L,
                highFrequencyRotation = true,
            ),
        )
        assertFalse(
            shouldThrottleMapsforgeRotation(
                navMode = NavMode.COMPASS_FOLLOW,
                nowElapsedMs = 1_016L,
                lastAppliedAtElapsedMs = 1_000L,
                highFrequencyRotation = true,
            ),
        )
    }

    @Test
    fun northUpAndFirstRotationAreNotThrottled() {
        assertFalse(
            shouldThrottleMapsforgeRotation(
                navMode = NavMode.NORTH_UP_FOLLOW,
                nowElapsedMs = 1_001L,
                lastAppliedAtElapsedMs = 1_000L,
            ),
        )
        assertFalse(
            shouldThrottleMapsforgeRotation(
                navMode = NavMode.COMPASS_FOLLOW,
                nowElapsedMs = 1_001L,
                lastAppliedAtElapsedMs = Long.MIN_VALUE,
            ),
        )
    }

    @Test
    fun renderedCompassUiStatePublishesAtMapOverlayCadence() {
        assertTrue(
            shouldPublishRenderedCompassUiState(
                nowElapsedMs = 1_000L,
                lastPublishedAtElapsedMs = Long.MIN_VALUE,
            ),
        )
        assertFalse(
            shouldPublishRenderedCompassUiState(
                nowElapsedMs = 1_039L,
                lastPublishedAtElapsedMs = 1_000L,
            ),
        )
        assertTrue(
            shouldPublishRenderedCompassUiState(
                nowElapsedMs = 1_040L,
                lastPublishedAtElapsedMs = 1_000L,
            ),
        )
        assertTrue(
            shouldPublishRenderedCompassUiState(
                nowElapsedMs = 1_001L,
                lastPublishedAtElapsedMs = 1_000L,
                force = true,
            ),
        )
    }

    @Test
    fun activeTurnAnimationClosesHeadingErrorMoreAggressively() {
        val normalAlpha =
            resolveHeadingAnimationAlpha(
                diffDeg = 40f,
                activeTurn = false,
                frameDeltaMs = 16.667f,
            )
        val activeTurnAlpha =
            resolveHeadingAnimationAlpha(
                diffDeg = 40f,
                activeTurn = true,
                frameDeltaMs = 16.667f,
            )

        assertTrue(activeTurnAlpha > normalAlpha)
    }

    @Test
    fun headingAnimationResponseIsIndependentOfFrameRate() {
        fun renderOverOneHundredMilliseconds(frameDeltaMs: Float): Float {
            var renderedHeading = 0f
            repeat((100f / frameDeltaMs).toInt()) {
                renderedHeading +=
                    resolveHeadingAnimationDelta(
                        diffDeg = 10f - renderedHeading,
                        activeTurn = false,
                        frameDeltaMs = frameDeltaMs,
                    )
            }
            return renderedHeading
        }

        assertEquals(
            renderOverOneHundredMilliseconds(frameDeltaMs = 20f),
            renderOverOneHundredMilliseconds(frameDeltaMs = 10f),
            0.01f,
        )
    }

    @Test
    fun everyCompassVisualPathIsLimitedAcrossThrottledFramesAndNorth() {
        val firstAppliedAngle =
            resolveCompassVisualTargetAngle(
                currentAngleDeg = 0f,
                targetAngleDeg = 90f,
            )
        val secondAppliedAngle =
            resolveCompassVisualTargetAngle(
                currentAngleDeg = firstAppliedAngle,
                targetAngleDeg = 90f,
            )

        assertEquals(10f, firstAppliedAngle, 0f)
        assertEquals(20f, secondAppliedAngle, 0f)
        assertEquals(
            360f,
            resolveCompassVisualTargetAngle(
                currentAngleDeg = 350f,
                targetAngleDeg = 10f,
            ),
            0f,
        )
        assertEquals(
            10f,
            resolveCompassVisualTargetAngle(
                currentAngleDeg = 20f,
                targetAngleDeg = 350f,
            ),
            0f,
        )
    }

    @Test
    fun normalHeadingAnimationRejectsSingleFrameThirtyDegreeSweep() {
        assertEquals(
            10f,
            resolveHeadingAnimationDelta(
                diffDeg = 40f,
                activeTurn = true,
                frameDeltaMs = 16.667f,
            ),
            0.01f,
        )
        assertEquals(
            10f,
            resolveHeadingAnimationDelta(
                diffDeg = 40f,
                activeTurn = true,
                frameDeltaMs = 50f,
            ),
            0.01f,
        )
    }
}
