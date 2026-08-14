package com.glancemap.glancemapwearos.presentation.features.navigate

import android.hardware.SensorManager
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.domain.sensors.HeadingSource
import com.glancemap.glancemapwearos.domain.sensors.initialCompassRenderState
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
        )
        val state = readyGoogleFusedState()

        assertNull(
            gate.resolve(
                renderState = state,
                compassHeadingDeg = 180f,
                headingSampleElapsedRealtimeMs = 1_000L,
            ),
        )
        val target =
            gate.resolve(
                renderState = state,
                compassHeadingDeg = 180f,
                headingSampleElapsedRealtimeMs = 1_001L,
            )

        assertEquals(180f, target?.headingDeg ?: -1f, 0f)
    }

    @Test
    fun googleFusedWakeHeadingRemainsTheVisualAuthorityAfterARealTurn() {
        val gate = NavigateRotationSettleGate()
        gate.beginWakeSession(
            nowElapsedMs = 1_000L,
        )

        val target =
            gate.resolve(
                renderState = readyGoogleFusedState(),
                compassHeadingDeg = 90f,
                headingSampleElapsedRealtimeMs = 1_050L,
            )

        assertEquals(90f, target?.headingDeg ?: -1f, 0f)
    }

    @Test
    fun compassFollowMapStaysFrozenWithoutActiveHeadingSource() {
        assertFalse(
            shouldDriveCompassFollowMap(
                initialCompassRenderState(providerType = CompassProviderType.SENSOR_MANAGER),
            ),
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
