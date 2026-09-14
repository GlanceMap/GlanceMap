package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.core.service.location.model.GpsEnvironmentWarning
import com.glancemap.glancemapwearos.data.repository.PoiType
import com.glancemap.glancemapwearos.presentation.features.poi.PoiNavigateTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class LocationMarkerTrustTest {
    @Test
    fun noAcceptedAnchorMeansNoPosition() {
        assertEquals(
            LocationMarkerTrustState.NO_POSITION,
            resolveLocationMarkerTrustState(
                retainedLocationAnchor = null,
                nowElapsedRealtimeMs = 10_000L,
                currentSourceEpoch = 1L,
                requiresFreshLiveFixAfterSourceChange = false,
                freshnessMaxAgeMs = 15_000L,
            ),
        )
    }

    @Test
    fun freshAcceptedFixStaysCurrentEvenWhenAccuracyIsPoor() {
        assertEquals(
            LocationMarkerTrustState.CURRENT,
            resolveLocationMarkerTrustState(
                retainedLocationAnchor = anchor(fixElapsedRealtimeMs = 8_000L, accuracyM = 250f),
                nowElapsedRealtimeMs = 10_000L,
                currentSourceEpoch = 2L,
                requiresFreshLiveFixAfterSourceChange = false,
                freshnessMaxAgeMs = 15_000L,
            ),
        )
    }

    @Test
    fun acceptedAnchorBecomesHistoricalWhenItIsStale() {
        assertEquals(
            LocationMarkerTrustState.HISTORICAL,
            resolveLocationMarkerTrustState(
                retainedLocationAnchor = anchor(fixElapsedRealtimeMs = 1_000L),
                nowElapsedRealtimeMs = 10_000L,
                currentSourceEpoch = 2L,
                requiresFreshLiveFixAfterSourceChange = false,
                freshnessMaxAgeMs = 5_000L,
            ),
        )
    }

    @Test
    fun sourceHandoffMakesRetainedMarkerHistoricalUntilNewFix() {
        assertEquals(
            LocationMarkerTrustState.HISTORICAL,
            resolveLocationMarkerTrustState(
                retainedLocationAnchor = anchor(fixElapsedRealtimeMs = 9_000L, sourceEpoch = 1L),
                nowElapsedRealtimeMs = 10_000L,
                currentSourceEpoch = 2L,
                requiresFreshLiveFixAfterSourceChange = true,
                freshnessMaxAgeMs = 5_000L,
            ),
        )
    }

    @Test
    fun acceptedFixFromNewSourceRestoresCurrentTrust() {
        assertEquals(
            LocationMarkerTrustState.CURRENT,
            resolveLocationMarkerTrustState(
                retainedLocationAnchor = anchor(fixElapsedRealtimeMs = 9_000L, sourceEpoch = 2L),
                nowElapsedRealtimeMs = 10_000L,
                currentSourceEpoch = 2L,
                requiresFreshLiveFixAfterSourceChange = false,
                freshnessMaxAgeMs = 5_000L,
            ),
        )
    }

    @Test
    fun cachedAnchorRemainsVisibleButHistoricalEvenWhenTimestampLooksFresh() {
        assertEquals(
            LocationMarkerTrustState.HISTORICAL,
            resolveLocationMarkerTrustState(
                retainedLocationAnchor = anchor(isAcceptedFix = false, fixElapsedRealtimeMs = 9_000L),
                nowElapsedRealtimeMs = 10_000L,
                currentSourceEpoch = 2L,
                requiresFreshLiveFixAfterSourceChange = false,
                freshnessMaxAgeMs = 15_000L,
            ),
        )
    }

    @Test
    fun indicatorCadenceBoundaryControlsMarkerFreshness() {
        val anchor = anchor(fixElapsedRealtimeMs = 1_000L)
        assertEquals(
            LocationMarkerTrustState.CURRENT,
            resolveLocationMarkerTrustState(
                retainedLocationAnchor = anchor,
                nowElapsedRealtimeMs = 16_000L,
                currentSourceEpoch = 2L,
                requiresFreshLiveFixAfterSourceChange = false,
                freshnessMaxAgeMs = 15_000L,
            ),
        )
        assertEquals(
            LocationMarkerTrustState.HISTORICAL,
            resolveLocationMarkerTrustState(
                retainedLocationAnchor = anchor,
                nowElapsedRealtimeMs = 16_001L,
                currentSourceEpoch = 2L,
                requiresFreshLiveFixAfterSourceChange = false,
                freshnessMaxAgeMs = 15_000L,
            ),
        )
    }

    @Test
    fun transientProviderUnavailableDoesNotAgeOutFreshAcceptedFix() {
        assertEquals(
            LocationMarkerTrustState.CURRENT,
            resolveLocationMarkerTrustState(
                retainedLocationAnchor = anchor(fixElapsedRealtimeMs = 9_000L),
                nowElapsedRealtimeMs = 10_000L,
                currentSourceEpoch = 2L,
                requiresFreshLiveFixAfterSourceChange = false,
                freshnessMaxAgeMs = 15_000L,
            ),
        )
    }

    @Test
    fun locationSettingsRestrictionMakesFreshAcceptedFixHistorical() {
        assertEquals(
            LocationMarkerTrustState.HISTORICAL,
            resolveLocationMarkerTrustState(
                retainedLocationAnchor = anchor(fixElapsedRealtimeMs = 9_000L),
                nowElapsedRealtimeMs = 10_000L,
                currentSourceEpoch = 2L,
                requiresFreshLiveFixAfterSourceChange = false,
                freshnessMaxAgeMs = 15_000L,
                hasHardEnvironmentRestriction =
                    hasHardLocationEnvironmentRestriction(
                        GpsEnvironmentWarning.LOCATION_SETTINGS_UNSATISFIED,
                    ),
            ),
        )
    }

    @Test
    fun rejectedSampleDoesNotRefreshAcceptedAnchorTimestamp() {
        val accepted = anchor(fixElapsedRealtimeMs = 1_000L)
        val rejectedSampleTimestamp = 20_000L
        assertEquals(1_000L, accepted.fixElapsedRealtimeMs)
        assertEquals(
            LocationMarkerTrustState.HISTORICAL,
            resolveLocationMarkerTrustState(
                retainedLocationAnchor = accepted,
                nowElapsedRealtimeMs = rejectedSampleTimestamp,
                currentSourceEpoch = 2L,
                requiresFreshLiveFixAfterSourceChange = false,
                freshnessMaxAgeMs = 15_000L,
            ),
        )
    }

    @Test
    fun renderedMovementChangesOnlyRetainedPositionNotFixProvenance() {
        val accepted = anchor(fixElapsedRealtimeMs = 9_000L)
        val rendered = accepted.copy(latLong = LatLong(48.1, 11.1))
        assertEquals(accepted.fixElapsedRealtimeMs, rendered.fixElapsedRealtimeMs)
        assertEquals(accepted.sourceEpoch, rendered.sourceEpoch)
        assertEquals(accepted.sourceModeName, rendered.sourceModeName)
    }

    @Test
    fun startupFallbackWaitsOnlyBeforeAnyAcceptedPositionOrUserIntent() {
        val pending =
            shouldWaitForOnlineStartupMapFallback(
                OnlineStartupMapFallbackInput(
                    offlineMode = false,
                    shouldTrackLocation = true,
                    locationMarkerLatLong = null,
                    retainedLocationAnchor = null,
                    lastKnownLocation = null,
                    startupMapFallbackState = StartupMapFallbackState.WAITING,
                    navigateTarget = null,
                    pendingPoiFocusTarget = null,
                ),
            )
        assertTrue(pending)
        assertFalse(
            shouldWaitForOnlineStartupMapFallback(
                OnlineStartupMapFallbackInput(
                    offlineMode = false,
                    shouldTrackLocation = true,
                    locationMarkerLatLong = null,
                    retainedLocationAnchor = anchor(),
                    lastKnownLocation = null,
                    startupMapFallbackState = StartupMapFallbackState.WAITING,
                    navigateTarget = null,
                    pendingPoiFocusTarget = null,
                ),
            ),
        )
        assertFalse(
            shouldWaitForOnlineStartupMapFallback(
                OnlineStartupMapFallbackInput(
                    offlineMode = false,
                    shouldTrackLocation = true,
                    locationMarkerLatLong = null,
                    retainedLocationAnchor = null,
                    lastKnownLocation = null,
                    startupMapFallbackState = StartupMapFallbackState.CANCELLED,
                    navigateTarget = null,
                    pendingPoiFocusTarget = null,
                ),
            ),
        )
    }

    @Test
    fun startupFallbackStateDoesNotRearmAfterCancellationOrCompletion() {
        val input =
            OnlineStartupMapFallbackInput(
                offlineMode = false,
                shouldTrackLocation = true,
                locationMarkerLatLong = null,
                retainedLocationAnchor = null,
                lastKnownLocation = null,
                startupMapFallbackState = StartupMapFallbackState.READY,
                navigateTarget = null,
                pendingPoiFocusTarget = null,
            )
        assertFalse(
            shouldWaitForOnlineStartupMapFallback(
                input.copy(startupMapFallbackState = StartupMapFallbackState.CANCELLED),
            ),
        )
        assertFalse(
            shouldWaitForOnlineStartupMapFallback(
                input.copy(startupMapFallbackState = StartupMapFallbackState.COMPLETED),
            ),
        )
        assertTrue(
            shouldWaitForOnlineStartupMapFallback(
                input.copy(startupMapFallbackState = StartupMapFallbackState.WAITING),
            ),
        )
    }

    @Test
    fun readyFallbackDoesNotActivateWhenADisplayedAnchorIsRestored() {
        val input =
            OnlineStartupMapFallbackInput(
                offlineMode = false,
                shouldTrackLocation = true,
                locationMarkerLatLong = null,
                retainedLocationAnchor = anchor(),
                lastKnownLocation = null,
                startupMapFallbackState = StartupMapFallbackState.READY,
                navigateTarget = null,
                pendingPoiFocusTarget = null,
            )

        assertFalse(shouldStartOnlineStartupMapCentering(input))
    }

    @Test
    fun onlyCurrentTrustStateUsesBlueNavigationMarker() {
        assertEquals(
            "blue",
            navigationMarkerBitmapForTrustState(
                trustState = LocationMarkerTrustState.CURRENT,
                currentBitmap = "blue",
                historicalBitmap = "grey",
            ),
        )
        assertEquals(
            "grey",
            navigationMarkerBitmapForTrustState(
                trustState = LocationMarkerTrustState.HISTORICAL,
                currentBitmap = "blue",
                historicalBitmap = "grey",
            ),
        )
        assertEquals(
            "grey",
            navigationMarkerBitmapForTrustState(
                trustState = LocationMarkerTrustState.NO_POSITION,
                currentBitmap = "blue",
                historicalBitmap = "grey",
            ),
        )
    }

    @Test
    fun displayedAnchorPreservesAcceptedFixProvenanceWhileMoving() {
        val accepted =
            anchor(
                fixElapsedRealtimeMs = 1_000L,
                sourceEpoch = 7L,
            ).copy(sourceModeName = "WATCH_GPS")
        val rendered = accepted.copy(latLong = LatLong(48.2, 11.2))
        assertEquals(accepted.fixElapsedRealtimeMs, rendered.fixElapsedRealtimeMs)
        assertEquals(accepted.accuracyM, rendered.accuracyM)
        assertEquals(accepted.sourceEpoch, rendered.sourceEpoch)
        assertEquals(accepted.sourceModeName, rendered.sourceModeName)
        assertTrue(rendered.isAcceptedFix)
    }

    @Test
    fun offlineAndExplicitPoiFocusDoNotEnterOnlineFallback() {
        val poiTarget = PoiNavigateTarget(48.0, 11.0, "test", PoiType.GENERIC)
        assertFalse(
            shouldWaitForOnlineStartupMapFallback(
                OnlineStartupMapFallbackInput(
                    offlineMode = true,
                    shouldTrackLocation = true,
                    locationMarkerLatLong = null,
                    retainedLocationAnchor = null,
                    lastKnownLocation = null,
                    startupMapFallbackState = StartupMapFallbackState.WAITING,
                    navigateTarget = null,
                    pendingPoiFocusTarget = null,
                ),
            ),
        )
        assertFalse(
            shouldWaitForOnlineStartupMapFallback(
                OnlineStartupMapFallbackInput(
                    offlineMode = false,
                    shouldTrackLocation = true,
                    locationMarkerLatLong = null,
                    retainedLocationAnchor = null,
                    lastKnownLocation = null,
                    startupMapFallbackState = StartupMapFallbackState.WAITING,
                    navigateTarget = poiTarget,
                    pendingPoiFocusTarget = null,
                ),
            ),
        )
    }

    private fun anchor(
        fixElapsedRealtimeMs: Long = 9_000L,
        accuracyM: Float = 5f,
        sourceEpoch: Long = 2L,
        isAcceptedFix: Boolean = true,
    ): RetainedLocationAnchor =
        RetainedLocationAnchor(
            latLong = LatLong(48.0, 11.0),
            fixElapsedRealtimeMs = fixElapsedRealtimeMs,
            accuracyM = accuracyM,
            sourceEpoch = sourceEpoch,
            isAcceptedFix = isAcceptedFix,
        )
}
