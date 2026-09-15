package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.core.service.diagnostics.EnergyDiagnostics
import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.core.service.location.model.isInteractive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigateVisualWorkPolicyTest {
    @Test
    fun navigateTimeChipClockRunsOnlyForVisibleInteractiveTimeDisplay() {
        assertTrue(
            shouldRunNavigateTimeChipClock(
                visible = true,
                isScreenInteractive = true,
                showTime = true,
            ),
        )
        assertFalse(
            shouldRunNavigateTimeChipClock(
                visible = true,
                isScreenInteractive = false,
                showTime = true,
            ),
        )
        assertFalse(
            shouldRunNavigateTimeChipClock(
                visible = false,
                isScreenInteractive = true,
                showTime = true,
            ),
        )
    }

    @Test
    fun navigateTimeChipClockStopsWhenStaticRecordingStatusReplacesTime() {
        assertFalse(
            shouldRunNavigateTimeChipClock(
                visible = true,
                isScreenInteractive = true,
                showTime = false,
            ),
        )
    }

    @Test
    fun orientationVisualLoopRunsOnlyForInteractiveFollowModes() {
        assertTrue(
            shouldRunOrientationVisualLoop(
                compassInteractive = LocationScreenState.INTERACTIVE.isInteractive,
                navMode = NavMode.COMPASS_FOLLOW,
            ),
        )
        assertFalse(
            shouldRunOrientationVisualLoop(
                compassInteractive = LocationScreenState.SCREEN_OFF.isInteractive,
                navMode = NavMode.COMPASS_FOLLOW,
            ),
        )
        assertFalse(
            shouldRunOrientationVisualLoop(
                compassInteractive = LocationScreenState.AMBIENT.isInteractive,
                navMode = NavMode.NORTH_UP_FOLLOW,
            ),
        )
        assertTrue(
            shouldRunOrientationVisualLoop(
                compassInteractive = LocationScreenState.INTERACTIVE.isInteractive,
                navMode = NavMode.NORTH_UP_FOLLOW,
            ),
        )
    }

    @Test
    fun panningDoesNotRunOrientationVisualLoop() {
        assertFalse(
            shouldRunOrientationVisualLoop(
                compassInteractive = true,
                navMode = NavMode.PANNING,
            ),
        )
    }

    @Test
    fun liveHudPollingRequiresInteractivePanningWithAnEnabledFeature() {
        assertTrue(
            shouldPollNavigateLiveHud(
                enabled = true,
                screenState = LocationScreenState.INTERACTIVE,
                navMode = NavMode.PANNING,
                liveElevationEnabled = true,
                liveDistanceEnabled = false,
            ),
        )
        assertFalse(
            shouldPollNavigateLiveHud(
                enabled = true,
                screenState = LocationScreenState.SCREEN_OFF,
                navMode = NavMode.PANNING,
                liveElevationEnabled = true,
                liveDistanceEnabled = true,
            ),
        )
    }

    @Test
    fun screenOffStopsOverlayRefreshWithoutDisablingFullDiagnostics() {
        EnergyDiagnostics.configure(captureActive = true, fullDiagnostics = true)
        try {
            assertTrue(
                shouldRefreshMarkerMotionDebugOverlay(
                    gpsDebugTelemetry = true,
                    gpsDebugTelemetryPopupEnabled = true,
                    offlineMode = false,
                    screenState = LocationScreenState.INTERACTIVE,
                ),
            )
            assertFalse(
                shouldRefreshMarkerMotionDebugOverlay(
                    gpsDebugTelemetry = true,
                    gpsDebugTelemetryPopupEnabled = true,
                    offlineMode = false,
                    screenState = LocationScreenState.SCREEN_OFF,
                ),
            )
            assertTrue(EnergyDiagnostics.shouldRecordSample("periodic"))
        } finally {
            EnergyDiagnostics.setEnabled(false)
        }
    }

    @Test
    fun recordingAndGuidanceHaveNoScreenOffVisualLoopOverride() {
        assertFalse(
            shouldRunOrientationVisualLoop(
                compassInteractive = LocationScreenState.SCREEN_OFF.isInteractive,
                navMode = NavMode.COMPASS_FOLLOW,
            ),
        )
        assertFalse(
            shouldPollNavigateLiveHud(
                enabled = true,
                screenState = LocationScreenState.SCREEN_OFF,
                navMode = NavMode.PANNING,
                liveElevationEnabled = true,
                liveDistanceEnabled = true,
            ),
        )
    }

    @Test
    fun panningDistanceGuideProjectionRunsOnlyWhenInteractive() {
        assertTrue(
            shouldRunPanningDistanceGuideProjection(
                isScreenInteractive = true,
                navMode = NavMode.PANNING,
                liveDistanceEnabled = true,
                suppressLiveMetricsForPoi = false,
            ),
        )
        assertFalse(
            shouldRunPanningDistanceGuideProjection(
                isScreenInteractive = false,
                navMode = NavMode.PANNING,
                liveDistanceEnabled = true,
                suppressLiveMetricsForPoi = false,
            ),
        )
    }

    @Test
    fun searchingNavButtonPulseRunsOnlyWhenInteractive() {
        assertTrue(shouldRunSearchingNavButtonPulse(isScreenInteractive = true, isSearching = true))
        assertFalse(shouldRunSearchingNavButtonPulse(isScreenInteractive = false, isSearching = true))
    }

    @Test
    fun compassConeLayerDoesNotUpdateWhileNoninteractive() {
        assertTrue(shouldUpdateCompassConeLayer(compassInteractive = true))
        assertFalse(shouldUpdateCompassConeLayer(compassInteractive = false))
    }
}
