package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.core.service.location.model.GpsEnvironmentWarning
import com.glancemap.glancemapwearos.core.service.location.model.GpsSignalSnapshot
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import com.glancemap.glancemapwearos.data.repository.PoiType
import com.glancemap.glancemapwearos.presentation.features.navigate.effects.WakeAnchorSeed
import com.glancemap.glancemapwearos.presentation.features.navigate.effects.shouldReleaseWakeReacquireHold
import com.glancemap.glancemapwearos.presentation.features.poi.PoiNavigateTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.mapsforge.core.model.LatLong

class StartupMapPreviewTest {
    private val center = LatLong(48.0, 11.0)
    private val startup =
        OnlineStartupMapFallbackInput(
            offlineMode = false,
            shouldTrackLocation = true,
            locationMarkerLatLong = null,
            retainedLocationAnchor = null,
            lastKnownLocation = null,
            startupMapFallbackState = StartupMapFallbackState.WAITING,
            navigateTarget = null,
            pendingPoiFocusTarget = null,
        )
    private val fix =
        WakeAnchorSeed(
            latLong = center,
            fixElapsedMs = 7_200L,
            accuracyM = 19.6f,
            speedMps = 0f,
            bearingDeg = null,
        )
    private val signal =
        GpsSignalSnapshot(
            lastFixElapsedRealtimeMs = fix.fixElapsedMs,
            lastFixFresh = true,
            lastFixFreshMaxAgeMs = 6_000L,
            activeSourceModeValue = LocationSourceMode.AUTO_FUSED.telemetryValue,
        )

    @Test
    fun recentPreSessionFixPreviewsMapWithoutReleasingLiveMarkerHold() {
        assertEquals(center, preview())
        assertFalse(
            shouldReleaseWakeReacquireHold(
                holdMarkerUntilFreshFix = true,
                fixFromCurrentTrackingSession = false,
                wakeSnapEligible = true,
                wakeReleaseEligible = true,
                holdTimedOut = false,
            ),
        )
        assertNull(startup.retainedLocationAnchor)
        assertNull(startup.lastKnownLocation)
    }

    @Test
    fun serviceAcceptedFixCanPreviewWhileMarkerWaitsForBetterAccuracy() {
        assertEquals(center, preview(candidate = fix.copy(accuracyM = 37.8f)))
        assertFalse(
            shouldReleaseWakeReacquireHold(
                holdMarkerUntilFreshFix = true,
                fixFromCurrentTrackingSession = true,
                wakeSnapEligible = false,
                wakeReleaseEligible = true,
                holdTimedOut = false,
            ),
        )
    }

    @Test
    fun existingPositionKeepsPriorityOverStartupPreview() {
        val anchor =
            RetainedLocationAnchor(
                latLong = LatLong(49.0, 12.0),
                fixElapsedRealtimeMs = 5_000L,
                accuracyM = 20f,
                sourceEpoch = 1L,
            )
        assertNull(preview(input = startup.copy(locationMarkerLatLong = anchor.latLong)))
        assertNull(preview(input = startup.copy(retainedLocationAnchor = anchor)))
        assertNull(preview(input = startup.copy(lastKnownLocation = anchor.latLong)))
    }

    @Test
    fun userPanAndPoiFocusKeepPriority() {
        assertNull(preview(input = startup.copy(shouldFollowPosition = false)))
        val target = PoiNavigateTarget(lat = 49.0, lon = 12.0, label = "test", type = PoiType.GENERIC)
        assertNull(preview(input = startup.copy(navigateTarget = target)))
        assertNull(preview(input = startup.copy(pendingPoiFocusTarget = target)))
    }

    @Test
    fun offlineAndStoppedTrackingKeepExistingCentering() {
        assertNull(preview(input = startup.copy(offlineMode = true)))
        assertNull(preview(input = startup.copy(shouldTrackLocation = false)))
    }

    @Test
    fun previewDoesNotRearmAfterCancellationOrFallbackCentering() {
        for (state in StartupMapFallbackState.entries.filter { it != StartupMapFallbackState.WAITING }) {
            assertNull(preview(input = startup.copy(startupMapFallbackState = state)))
        }
    }

    @Test
    fun sourceHandoffAndDifferentSourceCannotPreview() {
        assertNull(preview(gps = signal.copy(requiresFreshLiveFixAfterSourceChange = true)))
        assertNull(preview(mode = LocationSourceMode.WATCH_GPS))
        assertNull(preview(mode = null))
        assertNull(preview(gps = signal.copy(activeSourceModeValue = null)))
    }

    @Test
    fun freshWatchSourceCanPreviewOnceSourceHandoffFinishes() {
        assertEquals(
            center,
            preview(
                mode = LocationSourceMode.WATCH_GPS,
                gps = signal.copy(activeSourceModeValue = LocationSourceMode.WATCH_GPS.telemetryValue),
            ),
        )
    }

    @Test
    fun hardLocationRestrictionCannotPreview() {
        assertNull(preview(gps = signal.copy(environmentWarning = GpsEnvironmentWarning.LOCATION_SETTINGS_UNSATISFIED)))
    }

    @Test
    fun actualFixAgeIsRecheckedEvenIfSignalStillSaysFresh() {
        assertEquals(center, preview(candidate = fix.copy(fixElapsedMs = 4_000L)))
        assertNull(preview(candidate = fix.copy(fixElapsedMs = 3_999L)))
        assertNull(preview(gps = signal.copy(lastFixFresh = false)))
        assertNull(preview(gps = signal.copy(lastFixFreshMaxAgeMs = 0L)))
    }

    @Test
    fun missingAndFutureTimestampsCannotPreview() {
        assertNull(preview(candidate = null))
        assertNull(preview(candidate = fix.copy(fixElapsedMs = 0L)))
        assertNull(preview(candidate = fix.copy(fixElapsedMs = 10_001L)))
    }

    @Test
    fun invalidAccuracyCannotPreview() {
        assertNull(preview(candidate = fix.copy(accuracyM = Float.NaN)))
        assertNull(preview(candidate = fix.copy(accuracyM = Float.POSITIVE_INFINITY)))
        assertNull(preview(candidate = fix.copy(accuracyM = -1f)))
    }

    private fun preview(
        input: OnlineStartupMapFallbackInput = startup,
        candidate: WakeAnchorSeed? = fix,
        mode: LocationSourceMode? = LocationSourceMode.AUTO_FUSED,
        gps: GpsSignalSnapshot = signal,
    ): LatLong? =
        resolveOnlineStartupMapPreview(
            startupInput = input,
            fix = candidate,
            sourceMode = mode,
            gpsSignal = gps,
            nowElapsedMs = 10_000L,
        )
}
