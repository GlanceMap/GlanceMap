package com.glancemap.glancemapwearos.presentation.features.navigate.effects

import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigateCompassEffectsStateTest {
    @Test
    fun compassRunsOnlyWhenResumedInteractiveAndOnline() {
        assertTrue(
            shouldRunNavigateCompass(
                isResumed = true,
                screenState = LocationScreenState.INTERACTIVE,
                isOfflineMode = false,
            ),
        )
        assertFalse(
            shouldRunNavigateCompass(
                isResumed = true,
                screenState = LocationScreenState.AMBIENT,
                isOfflineMode = false,
            ),
        )
        assertFalse(
            shouldRunNavigateCompass(
                isResumed = true,
                screenState = LocationScreenState.SCREEN_OFF,
                isOfflineMode = false,
            ),
        )
        assertFalse(
            shouldRunNavigateCompass(
                isResumed = false,
                screenState = LocationScreenState.INTERACTIVE,
                isOfflineMode = false,
            ),
        )
        assertFalse(
            shouldRunNavigateCompass(
                isResumed = true,
                screenState = LocationScreenState.INTERACTIVE,
                isOfflineMode = true,
            ),
        )
    }

    @Test
    fun headingReferenceSensorsRunOnlyForAnInteractiveOnlineTest() {
        assertTrue(
            shouldRunHeadingReferenceSensors(
                headingReferenceTestActive = true,
                screenState = LocationScreenState.INTERACTIVE,
                isOfflineMode = false,
            ),
        )
        assertFalse(
            shouldRunHeadingReferenceSensors(
                headingReferenceTestActive = false,
                screenState = LocationScreenState.INTERACTIVE,
                isOfflineMode = false,
            ),
        )
        assertFalse(
            shouldRunHeadingReferenceSensors(
                headingReferenceTestActive = true,
                screenState = LocationScreenState.AMBIENT,
                isOfflineMode = false,
            ),
        )
        assertFalse(
            shouldRunHeadingReferenceSensors(
                headingReferenceTestActive = true,
                screenState = LocationScreenState.INTERACTIVE,
                isOfflineMode = true,
            ),
        )
    }

    @Test
    fun nonInteractiveAndOfflineStopsAreImmediate() {
        assertTrue(
            shouldStopNavigateCompassImmediately(
                screenState = LocationScreenState.SCREEN_OFF,
                isOfflineMode = false,
            ),
        )
        assertTrue(
            shouldStopNavigateCompassImmediately(
                screenState = LocationScreenState.AMBIENT,
                isOfflineMode = false,
            ),
        )
        assertTrue(
            shouldStopNavigateCompassImmediately(
                screenState = LocationScreenState.INTERACTIVE,
                isOfflineMode = true,
            ),
        )
        assertFalse(
            shouldStopNavigateCompassImmediately(
                screenState = LocationScreenState.INTERACTIVE,
                isOfflineMode = false,
            ),
        )
    }

    @Test
    fun resumeDefersStopWhileWaitingForInteractiveState() {
        assertTrue(
            shouldDeferNavigateCompassStopOnResume(
                screenState = LocationScreenState.SCREEN_OFF,
                isOfflineMode = false,
            ),
        )
        assertTrue(
            shouldDeferNavigateCompassStopOnResume(
                screenState = LocationScreenState.AMBIENT,
                isOfflineMode = false,
            ),
        )
        assertFalse(
            shouldDeferNavigateCompassStopOnResume(
                screenState = LocationScreenState.INTERACTIVE,
                isOfflineMode = false,
            ),
        )
        assertFalse(
            shouldDeferNavigateCompassStopOnResume(
                screenState = LocationScreenState.SCREEN_OFF,
                isOfflineMode = true,
            ),
        )
    }

    @Test
    fun wakeHeadingDeltaUsesShortestCircularDistance() {
        assertEquals(2f, shortestHeadingDeltaDeg(359f, 1f), 0.001f)
        assertEquals(2f, shortestHeadingDeltaDeg(1f, 359f), 0.001f)
        assertEquals(180f, shortestHeadingDeltaDeg(0f, 180f), 0.001f)
    }

    @Test
    fun stopReasonReflectsWhyCompassWasStopped() {
        org.junit.Assert.assertEquals(
            "screen_off",
            resolveNavigateCompassStopReason(
                isResumed = false,
                screenState = LocationScreenState.SCREEN_OFF,
                isOfflineMode = false,
            ),
        )
        org.junit.Assert.assertEquals(
            "ambient",
            resolveNavigateCompassStopReason(
                isResumed = false,
                screenState = LocationScreenState.AMBIENT,
                isOfflineMode = false,
            ),
        )
        org.junit.Assert.assertEquals(
            "offline_mode",
            resolveNavigateCompassStopReason(
                isResumed = true,
                screenState = LocationScreenState.INTERACTIVE,
                isOfflineMode = true,
            ),
        )
        org.junit.Assert.assertEquals(
            "lifecycle_pause",
            resolveNavigateCompassStopReason(
                isResumed = false,
                screenState = LocationScreenState.INTERACTIVE,
                isOfflineMode = false,
            ),
        )
    }

    @Test
    fun googleFusedStopsImmediatelyForScreenOffButKeepsAmbientGrace() {
        assertEquals(
            0L,
            resolveNavigateCompassImmediateStopDelayMs(
                compassProviderType = CompassProviderType.GOOGLE_FUSED,
                screenState = LocationScreenState.SCREEN_OFF,
                isOfflineMode = false,
                reason = "screen_off",
            ),
        )
        assertEquals(
            2_500L,
            resolveNavigateCompassImmediateStopDelayMs(
                compassProviderType = CompassProviderType.GOOGLE_FUSED,
                screenState = LocationScreenState.AMBIENT,
                isOfflineMode = false,
                reason = "ambient",
            ),
        )
    }

    @Test
    fun googleFusedKeepsManagerGraceWhenNavigateLeavesInteractiveScreen() {
        assertEquals(
            2_500L,
            resolveNavigateCompassImmediateStopDelayMs(
                compassProviderType = CompassProviderType.GOOGLE_FUSED,
                screenState = LocationScreenState.INTERACTIVE,
                isOfflineMode = false,
                reason = "effect_dispose",
            ),
        )
    }

    @Test
    fun screenOffDisposalAndOfflineModeStopImmediately() {
        assertEquals(
            0L,
            resolveNavigateCompassImmediateStopDelayMs(
                compassProviderType = CompassProviderType.GOOGLE_FUSED,
                screenState = LocationScreenState.SCREEN_OFF,
                isOfflineMode = false,
                reason = "effect_dispose",
            ),
        )
        assertEquals(
            0L,
            resolveNavigateCompassImmediateStopDelayMs(
                compassProviderType = CompassProviderType.GOOGLE_FUSED,
                screenState = LocationScreenState.INTERACTIVE,
                isOfflineMode = true,
                reason = "effect_dispose",
            ),
        )
    }

    @Test
    fun customSensorsAndOtherInteractiveStopsRemainImmediate() {
        assertEquals(
            0L,
            resolveNavigateCompassImmediateStopDelayMs(
                compassProviderType = CompassProviderType.SENSOR_MANAGER,
                screenState = LocationScreenState.INTERACTIVE,
                isOfflineMode = false,
                reason = "effect_dispose",
            ),
        )
        assertEquals(
            0L,
            resolveNavigateCompassImmediateStopDelayMs(
                compassProviderType = CompassProviderType.GOOGLE_FUSED,
                screenState = LocationScreenState.INTERACTIVE,
                isOfflineMode = false,
                reason = "lifecycle_pause",
            ),
        )
    }
}
