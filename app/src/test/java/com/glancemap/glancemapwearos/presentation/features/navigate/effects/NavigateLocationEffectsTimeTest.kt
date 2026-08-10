package com.glancemap.glancemapwearos.presentation.features.navigate.effects

import com.glancemap.glancemapwearos.core.service.location.model.GpsEnvironmentWarning
import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.presentation.features.navigate.GpsFixIndicatorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class NavigateLocationEffectsTimeTest {
    @Test
    fun locationVisualUpdatesAreSkippedOnlyWhenScreenIsOff() {
        assertTrue(shouldRenderLocationVisualUpdate(LocationScreenState.INTERACTIVE))
        assertTrue(shouldRenderLocationVisualUpdate(LocationScreenState.AMBIENT))
        assertFalse(shouldRenderLocationVisualUpdate(LocationScreenState.SCREEN_OFF))
    }

    @Test
    fun markerPredictionLoopRunsOnlyForVisibleInteractiveTracking() {
        assertTrue(
            shouldRunMarkerPredictionLoop(
                shouldTrackLocation = true,
                screenState = LocationScreenState.INTERACTIVE,
                suppressLocationMarker = false,
            ),
        )
        assertFalse(
            shouldRunMarkerPredictionLoop(
                shouldTrackLocation = true,
                screenState = LocationScreenState.AMBIENT,
                suppressLocationMarker = false,
            ),
        )
        assertFalse(
            shouldRunMarkerPredictionLoop(
                shouldTrackLocation = true,
                screenState = LocationScreenState.SCREEN_OFF,
                suppressLocationMarker = false,
            ),
        )
        assertFalse(
            shouldRunMarkerPredictionLoop(
                shouldTrackLocation = false,
                screenState = LocationScreenState.INTERACTIVE,
                suppressLocationMarker = false,
            ),
        )
        assertFalse(
            shouldRunMarkerPredictionLoop(
                shouldTrackLocation = true,
                screenState = LocationScreenState.INTERACTIVE,
                suppressLocationMarker = true,
            ),
        )
    }

    @Test
    fun prefersElapsedRealtimeTimestampWhenAvailable() {
        val result =
            resolveLocationFixElapsedRealtimeMs(
                elapsedRealtimeNanos = 70_000L * 1_000_000L,
                utcTimeMs = 1_000_000L,
                receivedAtElapsedMs = 100_000L,
                nowWallClockMs = 2_000_000L,
            )

        assertEquals(70_000L, result)
    }

    @Test
    fun usesUtcTimeWhenElapsedRealtimeIsMissing() {
        val result =
            resolveLocationFixElapsedRealtimeMs(
                elapsedRealtimeNanos = 0L,
                utcTimeMs = 970_000L,
                receivedAtElapsedMs = 500_000L,
                nowWallClockMs = 1_000_000L,
            )

        assertEquals(470_000L, result)
    }

    @Test
    fun clampsFutureElapsedRealtimeToReceiveTime() {
        val result =
            resolveLocationFixElapsedRealtimeMs(
                elapsedRealtimeNanos = 900_000L * 1_000_000L,
                utcTimeMs = 0L,
                receivedAtElapsedMs = 500_000L,
                nowWallClockMs = 1_000_000L,
            )

        assertEquals(500_000L, result)
    }

    @Test
    fun returnsZeroWhenNoTimestampIsAvailable() {
        val result =
            resolveLocationFixElapsedRealtimeMs(
                elapsedRealtimeNanos = 0L,
                utcTimeMs = 0L,
                receivedAtElapsedMs = 500_000L,
                nowWallClockMs = 1_000_000L,
            )

        assertEquals(0L, result)
    }

    @Test
    fun indicatorStateKeepsGoodWhenProviderUnavailableButFixIsStillRecent() {
        val state =
            resolveGpsIndicatorState(
                isLocationAvailable = false,
                unavailableSinceElapsedMs = 96_000L,
                lastFixAtElapsedMs = 95_000L,
                accuracyM = 8f,
                nowElapsedMs = 100_000L,
                staleThresholdMs = 10_000L,
            )

        assertEquals(GpsFixIndicatorState.GOOD, state)
    }

    @Test
    fun indicatorStateKeepsPoorWhenProviderUnavailableButFixIsStillRecent() {
        val state =
            resolveGpsIndicatorState(
                isLocationAvailable = false,
                unavailableSinceElapsedMs = 96_000L,
                lastFixAtElapsedMs = 95_000L,
                accuracyM = 20f,
                nowElapsedMs = 100_000L,
                staleThresholdMs = 10_000L,
            )

        assertEquals(GpsFixIndicatorState.POOR, state)
    }

    @Test
    fun indicatorStateIsUnavailableWhenProviderUnavailableLongEnough() {
        val state =
            resolveGpsIndicatorState(
                isLocationAvailable = false,
                unavailableSinceElapsedMs = 70_000L,
                lastFixAtElapsedMs = 70_000L,
                accuracyM = 8f,
                nowElapsedMs = 100_000L,
                staleThresholdMs = 10_000L,
            )

        assertEquals(GpsFixIndicatorState.UNAVAILABLE, state)
    }

    @Test
    fun indicatorStateIsSearchingWhenUnavailableTimestampMissing() {
        val state =
            resolveGpsIndicatorState(
                isLocationAvailable = false,
                unavailableSinceElapsedMs = 0L,
                lastFixAtElapsedMs = 0L,
                accuracyM = Float.POSITIVE_INFINITY,
                nowElapsedMs = 100_000L,
                staleThresholdMs = 10_000L,
            )

        assertEquals(GpsFixIndicatorState.SEARCHING, state)
    }

    @Test
    fun indicatorStateIsSearchingWhenFixIsStale() {
        val state =
            resolveGpsIndicatorState(
                isLocationAvailable = true,
                unavailableSinceElapsedMs = 0L,
                lastFixAtElapsedMs = 70_000L,
                accuracyM = 9f,
                nowElapsedMs = 82_000L,
                staleThresholdMs = 10_000L,
            )

        assertEquals(GpsFixIndicatorState.SEARCHING, state)
    }

    @Test
    fun indicatorStateWaitsForFreshFixAfterSourceChange() {
        val state =
            resolveGpsIndicatorState(
                isLocationAvailable = true,
                unavailableSinceElapsedMs = 0L,
                lastFixAtElapsedMs = 99_000L,
                accuracyM = 8f,
                requiresFreshLiveFixAfterSourceChange = true,
                nowElapsedMs = 100_000L,
                staleThresholdMs = 10_000L,
            )

        assertEquals(GpsFixIndicatorState.SEARCHING, state)
    }

    @Test
    fun markerIsHeldOnlyDuringUnresolvedLiveSourceHandoff() {
        assertTrue(
            shouldHoldMarkerForLiveSourceHandoff(
                sourceEpoch = 2L,
                requiresFreshLiveFix = true,
            ),
        )
        assertFalse(
            shouldHoldMarkerForLiveSourceHandoff(
                sourceEpoch = 2L,
                requiresFreshLiveFix = false,
            ),
        )
        assertFalse(
            shouldHoldMarkerForLiveSourceHandoff(
                sourceEpoch = 1L,
                requiresFreshLiveFix = true,
            ),
        )
    }

    @Test
    fun indicatorEscalatesFromBlueToYellowToRedWhenFixStaysMissing() {
        assertEquals(
            GpsFixIndicatorState.SEARCHING,
            resolveGpsIndicatorEscalationState(
                rawState = GpsFixIndicatorState.SEARCHING,
                abnormalSinceElapsedMs = 10_000L,
                nowElapsedMs = 21_999L,
            ),
        )
        assertEquals(
            GpsFixIndicatorState.POOR,
            resolveGpsIndicatorEscalationState(
                rawState = GpsFixIndicatorState.SEARCHING,
                abnormalSinceElapsedMs = 10_000L,
                nowElapsedMs = 22_000L,
            ),
        )
        assertEquals(
            GpsFixIndicatorState.LOST,
            resolveGpsIndicatorEscalationState(
                rawState = GpsFixIndicatorState.SEARCHING,
                abnormalSinceElapsedMs = 10_000L,
                nowElapsedMs = 40_000L,
            ),
        )
    }

    @Test
    fun indicatorKeepsHardUnavailableAsCrossedGpsState() {
        assertEquals(
            GpsFixIndicatorState.UNAVAILABLE,
            resolveGpsIndicatorEscalationState(
                rawState = GpsFixIndicatorState.UNAVAILABLE,
                abnormalSinceElapsedMs = 10_000L,
                nowElapsedMs = 50_000L,
            ),
        )
    }

    @Test
    fun indicatorStateClassifiesAccuracyBands() {
        val good =
            resolveGpsIndicatorState(
                isLocationAvailable = true,
                unavailableSinceElapsedMs = 0L,
                lastFixAtElapsedMs = 95_000L,
                accuracyM = 10f,
                nowElapsedMs = 100_000L,
                staleThresholdMs = 10_000L,
            )
        val poor =
            resolveGpsIndicatorState(
                isLocationAvailable = true,
                unavailableSinceElapsedMs = 0L,
                lastFixAtElapsedMs = 95_000L,
                accuracyM = 25f,
                nowElapsedMs = 100_000L,
                staleThresholdMs = 10_000L,
            )
        val boundaryGood =
            resolveGpsIndicatorState(
                isLocationAvailable = true,
                unavailableSinceElapsedMs = 0L,
                lastFixAtElapsedMs = 95_000L,
                accuracyM = 12f,
                nowElapsedMs = 100_000L,
                staleThresholdMs = 10_000L,
            )

        assertEquals(GpsFixIndicatorState.GOOD, good)
        assertEquals(GpsFixIndicatorState.POOR, poor)
        assertEquals(GpsFixIndicatorState.GOOD, boundaryGood)
    }

    @Test
    fun indicatorStateRelaxesGoodThresholdForWatchGps() {
        val watchGpsGood =
            resolveGpsIndicatorState(
                isLocationAvailable = true,
                unavailableSinceElapsedMs = 0L,
                lastFixAtElapsedMs = 95_000L,
                accuracyM = 18f,
                watchGpsOnlyActive = true,
                nowElapsedMs = 100_000L,
                staleThresholdMs = 10_000L,
            )
        val nonWatchGpsPoor =
            resolveGpsIndicatorState(
                isLocationAvailable = true,
                unavailableSinceElapsedMs = 0L,
                lastFixAtElapsedMs = 95_000L,
                accuracyM = 18f,
                nowElapsedMs = 100_000L,
                staleThresholdMs = 10_000L,
            )

        assertEquals(GpsFixIndicatorState.GOOD, watchGpsGood)
        assertEquals(GpsFixIndicatorState.POOR, nonWatchGpsPoor)
    }

    @Test
    fun indicatorStateTreatsKnownWatchGpsAccuracyFloorAsGoodEnough() {
        val state =
            resolveGpsIndicatorState(
                isLocationAvailable = true,
                unavailableSinceElapsedMs = 0L,
                lastFixAtElapsedMs = 95_000L,
                accuracyM = 125f,
                watchGpsOnlyActive = true,
                nowElapsedMs = 100_000L,
                staleThresholdMs = 10_000L,
            )

        assertEquals(GpsFixIndicatorState.GOOD, state)
    }

    @Test
    fun displayStateKeepsGoodUnchanged() {
        val state =
            resolveGpsIndicatorDisplayState(
                rawState = GpsFixIndicatorState.GOOD,
            )

        assertEquals(GpsFixIndicatorState.GOOD, state)
    }

    @Test
    fun displayStateKeepsSearchingAsWarning() {
        val state =
            resolveGpsIndicatorDisplayState(
                rawState = GpsFixIndicatorState.SEARCHING,
            )

        assertEquals(GpsFixIndicatorState.SEARCHING, state)
    }

    @Test
    fun displayStateKeepsPoorUnchanged() {
        val state =
            resolveGpsIndicatorDisplayState(
                rawState = GpsFixIndicatorState.POOR,
            )

        assertEquals(GpsFixIndicatorState.POOR, state)
    }

    @Test
    fun displayStateKeepsUnavailableUnchanged() {
        val state =
            resolveGpsIndicatorDisplayState(
                rawState = GpsFixIndicatorState.UNAVAILABLE,
            )

        assertEquals(GpsFixIndicatorState.UNAVAILABLE, state)
    }

    @Test
    fun phoneDisconnectedWithoutWatchGpsDoesNotForceUnavailableWhenFixIsUsable() {
        val state =
            resolveGpsIndicatorStateForEnvironment(
                rawState = GpsFixIndicatorState.POOR,
                environmentWarning = GpsEnvironmentWarning.AUTO_PHONE_DISCONNECTED_NO_WATCH_GPS,
            )

        assertEquals(GpsFixIndicatorState.POOR, state)
    }

    @Test
    fun locationSettingsWarningStillForcesUnavailable() {
        val state =
            resolveGpsIndicatorStateForEnvironment(
                rawState = GpsFixIndicatorState.GOOD,
                environmentWarning = GpsEnvironmentWarning.LOCATION_SETTINGS_UNSATISFIED,
            )

        assertEquals(GpsFixIndicatorState.UNAVAILABLE, state)
    }

    @Test
    fun unpinnedIndicatorShowsForSearching() {
        val visible =
            shouldShowGpsIndicatorUnpinned(
                gpsIndicatorState = GpsFixIndicatorState.SEARCHING,
                watchGpsDegradedWarning = false,
            )

        assertTrue(visible)
    }

    @Test
    fun unpinnedIndicatorStaysHiddenForPlainPoorFix() {
        val visible =
            shouldShowGpsIndicatorUnpinned(
                gpsIndicatorState = GpsFixIndicatorState.POOR,
                watchGpsDegradedWarning = false,
            )

        assertFalse(visible)
    }

    @Test
    fun unpinnedIndicatorShowsForWatchGpsDegradedWarning() {
        val visible =
            shouldShowGpsIndicatorUnpinned(
                gpsIndicatorState = GpsFixIndicatorState.POOR,
                watchGpsDegradedWarning = true,
            )

        assertTrue(visible)
    }

    @Test
    fun markerPredictionFreshnessFollowsThreeSecondInterval() {
        assertEquals(4_500L, computeMarkerPredictionFreshnessMaxAgeMs(3_000L))
    }

    @Test
    fun markerPredictionFreshnessKeepsShortIntervalsConservative() {
        assertEquals(1_500L, computeMarkerPredictionFreshnessMaxAgeMs(1_000L))
    }

    @Test
    fun markerPredictionFreshnessCapsLongIntervals() {
        assertEquals(12_000L, computeMarkerPredictionFreshnessMaxAgeMs(60_000L))
    }

    @Test
    fun startupFreshFixMaxAgeFallsBackToStrictFreshnessProfile() {
        assertEquals(3_000L, resolveStartupFreshFixMaxAgeMs(1_000L, 0L))
    }

    @Test
    fun startupFreshFixMaxAgeHonorsLooserServiceFreshnessWhenPresent() {
        assertEquals(7_000L, resolveStartupFreshFixMaxAgeMs(3_000L, 7_000L))
    }

    @Test
    fun wakeReacquireThresholdsFollowTimingProfile() {
        assertEquals(1_500L, computeWakeReacquireSnapMaxAgeMs(1_000L))
        assertEquals(3_000L, computeWakeReacquireReleaseMaxAgeMs(1_000L))
        assertEquals(4_500L, computeWakeReacquireSnapMaxAgeMs(3_000L))
        assertEquals(6_000L, computeWakeReacquireReleaseMaxAgeMs(3_000L))
    }

    @Test
    fun wakeReacquireHoldReleasesImmediatelyForCurrentSessionSnapFix() {
        val release =
            shouldReleaseWakeReacquireHold(
                holdMarkerUntilFreshFix = true,
                fixFromCurrentTrackingSession = true,
                wakeSnapEligible = true,
                wakeReleaseEligible = true,
                holdTimedOut = false,
            )

        assertTrue(release)
    }

    @Test
    fun wakeReacquireHoldWaitsBeforeTimeoutForPreSessionFix() {
        val release =
            shouldReleaseWakeReacquireHold(
                holdMarkerUntilFreshFix = true,
                fixFromCurrentTrackingSession = false,
                wakeSnapEligible = true,
                wakeReleaseEligible = true,
                holdTimedOut = false,
            )

        assertFalse(release)
    }

    @Test
    fun wakeReacquireHoldReleasesAfterTimeoutForFreshPreSessionFix() {
        val release =
            shouldReleaseWakeReacquireHold(
                holdMarkerUntilFreshFix = true,
                fixFromCurrentTrackingSession = false,
                wakeSnapEligible = false,
                wakeReleaseEligible = true,
                holdTimedOut = true,
            )

        assertTrue(release)
    }

    @Test
    fun interactiveWakeSessionStartsOnlyWhenTrackingBecomesInteractive() {
        val shouldStart =
            shouldStartInteractiveWakeSession(
                wasInteractiveTrackingActive = false,
                shouldTrackLocation = true,
                screenState = LocationScreenState.INTERACTIVE,
            )

        assertTrue(shouldStart)
    }

    @Test
    fun interactiveWakeSessionDoesNotRestartWhileAlreadyInteractive() {
        val shouldStart =
            shouldStartInteractiveWakeSession(
                wasInteractiveTrackingActive = true,
                shouldTrackLocation = true,
                screenState = LocationScreenState.INTERACTIVE,
            )

        assertFalse(shouldStart)
    }

    @Test
    fun wakeSessionResolvesWhenFreshCurrentSessionFixArrives() {
        val shouldResolve =
            shouldResolveWakeSessionFromAcceptedFix(
                activeWakeSessionId = 3L,
                fixFromCurrentTrackingSession = true,
                wakeSnapEligible = true,
            )

        assertTrue(shouldResolve)
    }

    @Test
    fun wakeSessionDoesNotResolveWithoutActiveSession() {
        val shouldResolve =
            shouldResolveWakeSessionFromAcceptedFix(
                activeWakeSessionId = 0L,
                fixFromCurrentTrackingSession = true,
                wakeSnapEligible = true,
            )

        assertFalse(shouldResolve)
    }

    @Test
    fun wakeSessionDoesNotResolveFromNonCurrentFix() {
        val shouldResolve =
            shouldResolveWakeSessionFromAcceptedFix(
                activeWakeSessionId = 5L,
                fixFromCurrentTrackingSession = false,
                wakeSnapEligible = true,
            )

        assertFalse(shouldResolve)
    }

    @Test
    fun postWakePredictionHoldStaysActiveUntilDeadline() {
        val active =
            isPostWakePredictionHoldActive(
                nowElapsedMs = 10_400L,
                holdUntilElapsedMs = 10_500L,
            )

        assertTrue(active)
    }

    @Test
    fun postWakePredictionHoldStopsAtDeadline() {
        val active =
            isPostWakePredictionHoldActive(
                nowElapsedMs = 10_500L,
                holdUntilElapsedMs = 10_500L,
            )

        assertFalse(active)
    }

    @Test
    fun correctionClampBypassUsesWakeRelease() {
        val bypass =
            shouldBypassCorrectionClamp(
                releaseFromWakeHold = true,
                previousAcceptedFixGapMs = 1_000L,
                expectedGpsIntervalMs = 3_000L,
            )

        assertTrue(bypass)
    }

    @Test
    fun correctionClampBypassUsesStaleGap() {
        val bypass =
            shouldBypassCorrectionClamp(
                releaseFromWakeHold = false,
                previousAcceptedFixGapMs = 12_000L,
                expectedGpsIntervalMs = 3_000L,
            )

        assertTrue(bypass)
    }

    @Test
    fun correctionClampBypassStaysOffForNormalGap() {
        val bypass =
            shouldBypassCorrectionClamp(
                releaseFromWakeHold = false,
                previousAcceptedFixGapMs = 2_500L,
                expectedGpsIntervalMs = 3_000L,
            )

        assertFalse(bypass)
    }

    @Test
    fun correctionClampBypassStaysOffForModerateLongGap() {
        val bypass =
            shouldBypassCorrectionClamp(
                releaseFromWakeHold = false,
                previousAcceptedFixGapMs = 8_000L,
                expectedGpsIntervalMs = 3_000L,
            )

        assertFalse(bypass)
    }

    @Test
    fun wakeReacquireCooldownInactiveWhenThereWasNoPreviousStart() {
        val active =
            isWakeReacquireCooldownActive(
                nowElapsedMs = 100_000L,
                lastStartedAtElapsedMs = Long.MIN_VALUE,
                cooldownMs = 60_000L,
            )

        assertFalse(active)
    }

    @Test
    fun wakeReacquireCooldownActiveInsideCooldownWindow() {
        val active =
            isWakeReacquireCooldownActive(
                nowElapsedMs = 100_000L,
                lastStartedAtElapsedMs = 50_500L,
                cooldownMs = 60_000L,
            )

        assertTrue(active)
    }

    @Test
    fun wakeReacquireCooldownInactiveAfterCooldownWindow() {
        val active =
            isWakeReacquireCooldownActive(
                nowElapsedMs = 120_000L,
                lastStartedAtElapsedMs = 60_000L,
                cooldownMs = 60_000L,
            )

        assertFalse(active)
    }

    @Test
    fun validLatLongHelperRejectsNaNLongitude() {
        val latLong =
            toValidLatLongOrNull(
                latitude = 48.8566,
                longitude = Double.NaN,
            )

        assertEquals(null, latLong)
    }

    @Test
    fun validLatLongHelperReturnsLatLongForFiniteCoordinates() {
        val latLong =
            toValidLatLongOrNull(
                latitude = 48.8566,
                longitude = 2.3522,
            )

        assertEquals(48.8566, latLong?.latitude ?: 0.0, 0.0001)
        assertEquals(2.3522, latLong?.longitude ?: 0.0, 0.0001)
    }

    @Test
    fun renderedMarkerRecentersWhenFollowModeDriftsFromMapCenter() {
        val shouldCenter =
            shouldCenterOnRenderedMarker(
                shouldFollowPosition = true,
                target = LatLong(48.8566, 2.3522),
                currentCenter = LatLong(48.8576, 2.3532),
            )

        assertTrue(shouldCenter)
    }

    @Test
    fun renderedMarkerDoesNotRecenterWhilePanning() {
        val shouldCenter =
            shouldCenterOnRenderedMarker(
                shouldFollowPosition = false,
                target = LatLong(48.8566, 2.3522),
                currentCenter = LatLong(48.8576, 2.3532),
            )

        assertFalse(shouldCenter)
    }

    @Test
    fun renderedMarkerSkipsRecenterWhenAlreadyCentered() {
        val target = LatLong(48.8566, 2.3522)
        val shouldCenter =
            shouldCenterOnRenderedMarker(
                shouldFollowPosition = true,
                target = target,
                currentCenter = LatLong(48.8566, 2.3522),
            )

        assertFalse(shouldCenter)
    }

    @Test
    fun wakeAnchorSeedUsesRecentAccurateFix() {
        val anchor =
            resolveWakeAnchorSeedFromFixOrNull(
                latLong = LatLong(48.8566, 2.3522),
                fixElapsedMs = 95_000L,
                receivedAtElapsedMs = 100_000L,
                accuracyM = 12f,
                maxAgeMs = 10_000L,
                maxAccuracyM = 35f,
                speedMps = 1.4f,
                bearingDeg = 90f,
            )

        assertNotNull(anchor)
        assertEquals(95_000L, anchor?.fixElapsedMs)
        assertEquals(1.4f, anchor?.speedMps ?: 0f, 0.0001f)
    }

    @Test
    fun wakeAnchorSeedRejectsStaleFix() {
        val anchor =
            resolveWakeAnchorSeedFromFixOrNull(
                latLong = LatLong(48.8566, 2.3522),
                fixElapsedMs = 70_000L,
                receivedAtElapsedMs = 100_000L,
                accuracyM = 12f,
                maxAgeMs = 10_000L,
                maxAccuracyM = 35f,
            )

        assertNull(anchor)
    }

    @Test
    fun wakeAnchorMaxAgeFollowsIntervalWithinBounds() {
        assertEquals(9_000L, computeWakeAnchorMaxAgeMs(3_000L))
        assertEquals(45_000L, computeWakeAnchorMaxAgeMs(15_000L))
        assertEquals(60_000L, computeWakeAnchorMaxAgeMs(60_000L))
    }

    @Test
    fun warmReturnAnchorAllowsLongerStationaryCacheThanMovingCache() {
        assertEquals(30_000L, computeWarmReturnAnchorMaxAgeMs(expectedGpsIntervalMs = 3_000L, speedMps = 0f))
        assertEquals(12_000L, computeWarmReturnAnchorMaxAgeMs(expectedGpsIntervalMs = 3_000L, speedMps = 1.2f))
        assertEquals(45_000L, computeWarmReturnAnchorMaxAgeMs(expectedGpsIntervalMs = 15_000L, speedMps = 1.2f))
    }
}
