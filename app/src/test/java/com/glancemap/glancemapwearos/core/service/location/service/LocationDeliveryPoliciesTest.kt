package com.glancemap.glancemapwearos.core.service.location.service

import com.glancemap.glancemapwearos.core.service.location.engine.RequestSpec
import com.glancemap.glancemapwearos.core.service.location.model.GpsSignalSnapshot
import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.core.service.location.policy.LocationRuntimeMode
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import com.glancemap.glancemapwearos.core.service.location.policy.NavigationRuntimeDemandReason
import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationDeliveryPoliciesTest {
    @Test
    fun freshRecordingStreamSuppressesRedundantBurst() {
        assertTrue(
            shouldSuppressImmediateBurstForFreshStream(
                runtimeReason = NavigationRuntimeDemandReason.RECORDING,
                runtimeMode = LocationRuntimeMode.INTERACTIVE,
                intervalMs = 3_000L,
                signal = freshSignal(),
                nowElapsedMs = 100_000L,
            ),
        )
    }

    @Test
    fun freshKnownWatchGpsAccuracyFloorSuppressesRedundantBurst() {
        assertTrue(
            shouldSuppressImmediateBurstForFreshStream(
                runtimeReason = NavigationRuntimeDemandReason.RECORDING,
                runtimeMode = LocationRuntimeMode.INTERACTIVE,
                intervalMs = 3_000L,
                signal =
                    GpsSignalSnapshot(
                        lastFixElapsedRealtimeMs = 98_000L,
                        lastFixAccuracyM = 125f,
                        lastFixFresh = true,
                        watchGpsOnlyActive = true,
                    ),
                nowElapsedMs = 100_000L,
            ),
        )
    }

    @Test
    fun fusedAccuracyFloorValueDoesNotSuppressRedundantBurst() {
        assertFalse(
            shouldSuppressImmediateBurstForFreshStream(
                runtimeReason = NavigationRuntimeDemandReason.RECORDING,
                runtimeMode = LocationRuntimeMode.INTERACTIVE,
                intervalMs = 3_000L,
                signal =
                    GpsSignalSnapshot(
                        lastFixElapsedRealtimeMs = 98_000L,
                        lastFixAccuracyM = 125f,
                        lastFixFresh = true,
                    ),
                nowElapsedMs = 100_000L,
            ),
        )
    }

    @Test
    fun staleOrOrdinaryMapStreamDoesNotSuppressBurst() {
        assertFalse(
            shouldSuppressImmediateBurstForFreshStream(
                runtimeReason = NavigationRuntimeDemandReason.NAVIGATE_VISIBLE,
                runtimeMode = LocationRuntimeMode.INTERACTIVE,
                intervalMs = 3_000L,
                signal = freshSignal(),
                nowElapsedMs = 100_000L,
            ),
        )
        assertFalse(
            shouldSuppressImmediateBurstForFreshStream(
                runtimeReason = NavigationRuntimeDemandReason.RECORDING,
                runtimeMode = LocationRuntimeMode.INTERACTIVE,
                intervalMs = 3_000L,
                signal = freshSignal(fixElapsedMs = 90_000L),
                nowElapsedMs = 100_000L,
            ),
        )
    }

    @Test
    fun ordinaryNavigateRequestsOneShotOnlyWhenFixIsMissingOrStale() {
        assertTrue(
            shouldRequestStaleNavigateOneShot(
                runtimeReason = NavigationRuntimeDemandReason.NAVIGATE_VISIBLE,
                sourceMode = LocationSourceMode.AUTO_FUSED,
                signal = GpsSignalSnapshot(),
                nowElapsedMs = 100_000L,
                freshnessMaxAgeMs = 6_000L,
            ),
        )
        assertTrue(
            shouldRequestStaleNavigateOneShot(
                runtimeReason = NavigationRuntimeDemandReason.NAVIGATE_VISIBLE,
                sourceMode = LocationSourceMode.AUTO_FUSED,
                signal = freshSignal(fixElapsedMs = 90_000L),
                nowElapsedMs = 100_000L,
                freshnessMaxAgeMs = 6_000L,
            ),
        )
        assertFalse(
            shouldRequestStaleNavigateOneShot(
                runtimeReason = NavigationRuntimeDemandReason.NAVIGATE_VISIBLE,
                sourceMode = LocationSourceMode.AUTO_FUSED,
                signal = freshSignal(),
                nowElapsedMs = 100_000L,
                freshnessMaxAgeMs = 6_000L,
            ),
        )
    }

    @Test
    fun recordingGuidanceAndNonFusedSourcesNeverRequestNavigateOneShot() {
        assertFalse(
            shouldRequestStaleNavigateOneShot(
                runtimeReason = NavigationRuntimeDemandReason.RECORDING,
                sourceMode = LocationSourceMode.AUTO_FUSED,
                signal = GpsSignalSnapshot(),
                nowElapsedMs = 100_000L,
                freshnessMaxAgeMs = 6_000L,
            ),
        )
        assertFalse(
            shouldRequestStaleNavigateOneShot(
                runtimeReason = NavigationRuntimeDemandReason.GUIDANCE_VISIBLE,
                sourceMode = LocationSourceMode.AUTO_FUSED,
                signal = GpsSignalSnapshot(),
                nowElapsedMs = 100_000L,
                freshnessMaxAgeMs = 6_000L,
            ),
        )
        assertFalse(
            shouldRequestStaleNavigateOneShot(
                runtimeReason = NavigationRuntimeDemandReason.NAVIGATE_VISIBLE,
                sourceMode = LocationSourceMode.WATCH_GPS,
                signal = GpsSignalSnapshot(),
                nowElapsedMs = 100_000L,
                freshnessMaxAgeMs = 6_000L,
            ),
        )
    }

    @Test
    fun navigateOneShotIsCancelledOutsideInteractiveOrdinaryNavigate() {
        assertNull(
            navigateOneShotCancellationReason(
                trackingEnabled = true,
                screenState = LocationScreenState.INTERACTIVE,
                runtimeReason = NavigationRuntimeDemandReason.NAVIGATE_VISIBLE,
            ),
        )
        assertEquals(
            "screen_non_interactive",
            navigateOneShotCancellationReason(
                trackingEnabled = true,
                screenState = LocationScreenState.SCREEN_OFF,
                runtimeReason = NavigationRuntimeDemandReason.NAVIGATE_VISIBLE,
            ),
        )
        assertEquals(
            "navigate_not_visible",
            navigateOneShotCancellationReason(
                trackingEnabled = true,
                screenState = LocationScreenState.INTERACTIVE,
                runtimeReason = NavigationRuntimeDemandReason.RECORDING,
            ),
        )
        assertEquals(
            "tracking_disabled",
            navigateOneShotCancellationReason(
                trackingEnabled = false,
                screenState = LocationScreenState.INTERACTIVE,
                runtimeReason = NavigationRuntimeDemandReason.NAVIGATE_VISIBLE,
            ),
        )
    }

    @Test
    fun screenOffRecordingBatchesOnlyFusedDelivery() {
        val state = requestState(runtimeReason = NavigationRuntimeDemandReason.RECORDING)
        assertEquals(15_000L, resolveMaxUpdateDelayMs(state, requestSpec(intervalMs = 3_000L)))
        assertEquals(
            0L,
            resolveMaxUpdateDelayMs(
                state,
                requestSpec(intervalMs = 3_000L, sourceMode = LocationSourceMode.WATCH_GPS),
            ),
        )
    }

    @Test
    fun screenOffTurnByTurnBatchingRequiresOptIn() {
        val request = requestSpec(intervalMs = 3_000L)
        assertEquals(
            0L,
            resolveMaxUpdateDelayMs(
                requestState(runtimeReason = NavigationRuntimeDemandReason.GUIDANCE_AMBIENT),
                request,
            ),
        )
        assertEquals(
            6_000L,
            resolveMaxUpdateDelayMs(
                requestState(
                    runtimeReason = NavigationRuntimeDemandReason.GUIDANCE_AMBIENT,
                    tbtBatching = true,
                ),
                request,
            ),
        )
    }

    @Test
    fun autoPauseUsesMovementThresholdAndNeverBatches() {
        val request = requestSpec(intervalMs = 5_000L)
        val adjusted = request.forRuntimeReason(NavigationRuntimeDemandReason.RECORDING_AUTO_PAUSED)
        assertEquals(5f, adjusted.minDistanceMeters)
        assertEquals(
            0L,
            resolveMaxUpdateDelayMs(
                requestState(runtimeReason = NavigationRuntimeDemandReason.RECORDING_AUTO_PAUSED),
                adjusted,
            ),
        )
    }

    private fun freshSignal(fixElapsedMs: Long = 98_000L) =
        GpsSignalSnapshot(
            lastFixElapsedRealtimeMs = fixElapsedMs,
            lastFixAccuracyM = 12f,
            lastFixFresh = true,
        )

    private fun requestState(
        runtimeReason: String,
        tbtBatching: Boolean = false,
    ) = RequestUpdateState(
        bound = true,
        tracking = true,
        keepOpen = true,
        watchOnlyRequested = false,
        watchOnlyEffective = false,
        screenState = LocationScreenState.SCREEN_OFF,
        backgroundGps = true,
        runtimeReason = runtimeReason,
        passiveLocationExperiment = false,
        userIntervalMs = 3_000L,
        ambientIntervalMs = 60_000L,
        turnByTurnScreenOffBatchingEnabled = tbtBatching,
    )

    private fun requestSpec(
        intervalMs: Long,
        sourceMode: LocationSourceMode = LocationSourceMode.AUTO_FUSED,
    ) = RequestSpec(
        priority = Priority.PRIORITY_HIGH_ACCURACY,
        intervalMs = intervalMs,
        minDistanceMeters = 1f,
        mode = LocationRuntimeMode.INTERACTIVE,
        sourceMode = sourceMode,
    )
}
