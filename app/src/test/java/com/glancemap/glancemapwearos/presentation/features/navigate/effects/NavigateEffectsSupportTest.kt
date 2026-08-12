package com.glancemap.glancemapwearos.presentation.features.navigate

import android.hardware.SensorManager
import com.glancemap.glancemapwearos.domain.sensors.CompassMagneticQuality
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import com.glancemap.glancemapwearos.domain.sensors.HeadingSource
import com.glancemap.glancemapwearos.domain.sensors.initialCompassRenderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigateEffectsSupportTest {
    @Test
    fun compassFollowMapStaysFrozenWithoutActiveHeadingSource() {
        assertFalse(
            shouldDriveCompassFollowMap(
                initialCompassRenderState(providerType = CompassProviderType.SENSOR_MANAGER),
            ),
        )
    }

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

    @Test
    fun rotationSettleGateHoldsWhileCompassIsDegraded() {
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(nowElapsedMs = 900L, heldHeadingDeg = 120f)
        val degradedState =
            stableCompassRenderState()
                .copy(
                    trackingState = CompassTrackingState.DEGRADED,
                    magneticInterference = true,
                    magneticQuality = CompassMagneticQuality.INTERFERENCE,
                )

        assertNull(
            gate.resolve(
                nowElapsedMs = 1_000L,
                renderState = degradedState,
                compassHeadingDeg = 120f,
                headingSampleElapsedRealtimeMs = 1_000L,
                relativeHeadingDeg = null,
                gpsFixFresh = false,
                gpsFixSpeedMps = 0f,
                gpsFixBearingDeg = null,
            ),
        )
    }

    @Test
    fun rotationSettleGateWaitsForStableCompassWindowBeforeBlending() {
        val gate = NavigateRotationSettleGate()
        val stableState = stableCompassRenderState()
        gate.beginWakeSession(nowElapsedMs = 900L, heldHeadingDeg = 120f)

        assertNull(gate.resolve(1_000L, stableState, 120f, 1_000L, null, false, 0f, null))
        assertNull(gate.resolve(1_300L, stableState, 120f, 1_300L, null, false, 0f, null))
        assertNull(gate.resolve(1_599L, stableState, 120f, 1_599L, null, false, 0f, null))

        val target = gate.resolve(1_600L, stableState, 120f, 1_600L, null, false, 0f, null)
        assertEquals(120f, target?.headingDeg ?: Float.NaN, 0f)
        assertEquals(NavigationRotationTargetSource.COMPASS, target?.source)
    }

    @Test
    fun rotationSettleGateUsesFreshMovingGpsBearingBeforeCompassStabilizes() {
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(nowElapsedMs = 900L, heldHeadingDeg = 10f)
        val degradedState =
            stableCompassRenderState()
                .copy(
                    trackingState = CompassTrackingState.DEGRADED,
                )

        val target =
            gate.resolve(
                nowElapsedMs = 1_000L,
                renderState = degradedState,
                compassHeadingDeg = 10f,
                headingSampleElapsedRealtimeMs = 1_000L,
                relativeHeadingDeg = null,
                gpsFixFresh = true,
                gpsFixSpeedMps = 1.3f,
                gpsFixBearingDeg = 275f,
            )

        assertEquals(275f, target?.headingDeg ?: Float.NaN, 0f)
        assertEquals(NavigationRotationTargetSource.GPS_BEARING, target?.source)

        val retainedTarget =
            gate.resolve(1_001L, degradedState, 10f, 1_001L, null, false, 0f, null)
        assertEquals(275f, retainedTarget?.headingDeg ?: Float.NaN, 0f)
        assertEquals(NavigationRotationTargetSource.GPS_BEARING, retainedTarget?.source)
    }

    @Test
    fun rotationSettleGateRejectsCachedPreWakeSample() {
        val gate = NavigateRotationSettleGate()
        val stableState = stableCompassRenderState()
        gate.beginWakeSession(nowElapsedMs = 1_000L, heldHeadingDeg = 120f)

        assertNull(
            gate.resolve(
                1_001L,
                stableState,
                120f,
                1_000L,
                null,
                false,
                0f,
                null,
            ),
        )
    }

    @Test
    fun rotationSettleGateRejectsLargeStationaryCompassChangeWithoutRelativeTurn() {
        val gate = NavigateRotationSettleGate()
        val stableState = stableGoogleFusedCompassRenderState()
        gate.beginWakeSession(nowElapsedMs = 900L, heldHeadingDeg = 120f)

        gate.resolve(1_000L, stableState, 120f, 1_000L, 0f, false, 0f, null)
        gate.resolve(1_300L, stableState, 120f, 1_300L, 0f, false, 0f, null)
        assertEquals(
            120f,
            gate.resolve(1_600L, stableState, 120f, 1_600L, 0f, false, 0f, null)?.headingDeg
                ?: Float.NaN,
            0f,
        )

        assertNull(
            gate.resolve(1_601L, stableState, 210f, 1_601L, 0f, false, 0f, null),
        )
        assertEquals(
            210f,
            gate.resolve(1_602L, stableState, 210f, 1_602L, 90f, false, 0f, null)?.headingDeg
                ?: Float.NaN,
            0f,
        )
    }

    private fun stableCompassRenderState() =
        initialCompassRenderState(providerType = CompassProviderType.SENSOR_MANAGER).copy(
            headingSource = HeadingSource.ROTATION_VECTOR,
            accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
            trackingState = CompassTrackingState.TRACKING,
            magneticQuality = CompassMagneticQuality.GOOD,
        )

    private fun stableGoogleFusedCompassRenderState() =
        initialCompassRenderState(providerType = CompassProviderType.GOOGLE_FUSED).copy(
            headingSource = HeadingSource.FUSED_ORIENTATION,
            accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
            headingSampleElapsedRealtimeMs = 1_000L,
            headingSampleStale = false,
            headingRenderable = true,
            trackingState = CompassTrackingState.TRACKING,
            magneticQuality = CompassMagneticQuality.GOOD,
        )
}
