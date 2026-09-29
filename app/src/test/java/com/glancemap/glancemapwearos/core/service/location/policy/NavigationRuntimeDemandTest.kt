package com.glancemap.glancemapwearos.core.service.location.policy

import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import com.glancemap.glancemapwearos.presentation.features.recording.isPausedForAutoResumeMonitoring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationRuntimeDemandTest {
    @Test
    fun normalNavigateVisibleTracksWithoutBackgroundGpsDemand() {
        val demand =
            demand(
                isNavigateScreen = true,
                screenState = LocationScreenState.INTERACTIVE,
                isScreenResumed = true,
            )

        assertTrue(demand.trackingEnabled)
        assertFalse(demand.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.NAVIGATE_VISIBLE, demand.reason)
    }

    @Test
    fun normalNonNavigateDoesNotTrack() {
        val demand =
            demand(
                isNavigateScreen = false,
                screenState = LocationScreenState.INTERACTIVE,
                isScreenResumed = true,
            )

        assertFalse(demand.trackingEnabled)
        assertFalse(demand.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.IDLE, demand.reason)
    }

    @Test
    fun elevationProfileTemporarilyTracksOnlyWhileInteractive() {
        val interactive =
            demand(
                isNavigateScreen = false,
                gpxElevationProfileOpen = true,
            )
        val screenOff =
            demand(
                isNavigateScreen = false,
                screenState = LocationScreenState.SCREEN_OFF,
                isScreenResumed = false,
                gpxElevationProfileOpen = true,
            )

        assertTrue(interactive.trackingEnabled)
        assertFalse(interactive.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.GPX_ELEVATION_PROFILE, interactive.reason)
        assertFalse(screenOff.trackingEnabled)
        assertFalse(screenOff.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.IDLE, screenOff.reason)
    }

    @Test
    fun recordingActiveForcesTrackingAndBackgroundGps() {
        val demand =
            demand(
                isNavigateScreen = false,
                screenState = LocationScreenState.SCREEN_OFF,
                isScreenResumed = false,
                recordingActive = true,
                recordingPaused = false,
            )

        assertTrue(demand.trackingEnabled)
        assertTrue(demand.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.RECORDING, demand.reason)
    }

    @Test
    fun recordingAndTurnByTurnUsesCombinedDemand() {
        val demand =
            demand(
                isNavigateScreen = true,
                screenState = LocationScreenState.INTERACTIVE,
                isScreenResumed = true,
                recordingActive = true,
                recordingPaused = false,
                turnByTurnActive = true,
            )

        assertTrue(demand.trackingEnabled)
        assertTrue(demand.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.RECORDING_GUIDANCE, demand.reason)
    }

    @Test
    fun autoPausedRecordingUsesSlowerRecordingDemandWithoutTurnByTurn() {
        val demand =
            demand(
                isNavigateScreen = false,
                screenState = LocationScreenState.SCREEN_OFF,
                isScreenResumed = false,
                recordingActive = true,
                recordingAutoPaused = true,
            )

        assertTrue(demand.trackingEnabled)
        assertTrue(demand.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.RECORDING_AUTO_PAUSED, demand.reason)
    }

    @Test
    fun autoPausedRecordingKeepsTurnByTurnAtFullDemand() {
        val demand =
            demand(
                recordingActive = true,
                recordingAutoPaused = true,
                turnByTurnActive = true,
            )

        assertEquals(NavigationRuntimeDemandReason.RECORDING_GUIDANCE, demand.reason)
    }

    @Test
    fun manuallyPausedRecordingUsesAutoPauseDemandOnlyWhenEnabled() {
        val pausedState = TraceRecordingUiState(active = true, paused = true)
        val monitoring = pausedState.isPausedForAutoResumeMonitoring(autoPauseEnabled = true)
        val monitoringDemand =
            demand(
                isNavigateScreen = false,
                screenState = LocationScreenState.SCREEN_OFF,
                isScreenResumed = false,
                recordingActive = pausedState.active,
                recordingPaused = pausedState.paused && !monitoring,
                recordingAutoPaused = monitoring,
            )

        assertTrue(monitoringDemand.trackingEnabled)
        assertTrue(monitoringDemand.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.RECORDING_AUTO_PAUSED, monitoringDemand.reason)

        val fullyPaused = pausedState.isPausedForAutoResumeMonitoring(autoPauseEnabled = false)
        val pausedDemand =
            demand(
                isNavigateScreen = false,
                screenState = LocationScreenState.SCREEN_OFF,
                isScreenResumed = false,
                recordingActive = pausedState.active,
                recordingPaused = pausedState.paused && !fullyPaused,
                recordingAutoPaused = fullyPaused,
            )

        assertFalse(pausedDemand.trackingEnabled)
        assertFalse(pausedDemand.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.IDLE, pausedDemand.reason)
    }

    @Test
    fun recordingPausedFallsBackToDefault() {
        val demand =
            demand(
                isNavigateScreen = false,
                screenState = LocationScreenState.SCREEN_OFF,
                isScreenResumed = false,
                recordingActive = true,
                recordingPaused = true,
            )

        assertFalse(demand.trackingEnabled)
        assertFalse(demand.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.IDLE, demand.reason)
    }

    @Test
    fun generalAmbientGpsDoesNotKeepGpsAliveOutsideNavigate() {
        val demand =
            demand(
                isNavigateScreen = false,
                screenState = LocationScreenState.SCREEN_OFF,
                isScreenResumed = false,
                generalGpsInAmbient = true,
            )

        assertFalse(demand.trackingEnabled)
        assertFalse(demand.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.IDLE, demand.reason)
    }

    @Test
    fun turnByTurnVisibleNavigateTracksWithoutAmbientSetting() {
        val demand =
            demand(
                isNavigateScreen = true,
                screenState = LocationScreenState.INTERACTIVE,
                isScreenResumed = true,
                turnByTurnActive = true,
                turnByTurnGpsInAmbient = false,
            )

        assertTrue(demand.trackingEnabled)
        assertFalse(demand.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.GUIDANCE_VISIBLE, demand.reason)
    }

    @Test
    fun turnByTurnVisibleNavigateFallsBackToNormalNavigateWhenGuidanceGpsIsOff() {
        val demand =
            demand(
                isNavigateScreen = true,
                screenState = LocationScreenState.INTERACTIVE,
                isScreenResumed = true,
                turnByTurnActive = true,
                turnByTurnGpsEnabled = false,
                turnByTurnGpsInAmbient = false,
            )

        assertTrue(demand.trackingEnabled)
        assertFalse(demand.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.NAVIGATE_VISIBLE, demand.reason)
    }

    @Test
    fun turnByTurnScreenOffRequiresGuidanceAmbientSetting() {
        val demand =
            demand(
                isNavigateScreen = true,
                screenState = LocationScreenState.SCREEN_OFF,
                isScreenResumed = false,
                turnByTurnActive = true,
                turnByTurnGpsInAmbient = false,
            )

        assertFalse(demand.trackingEnabled)
        assertFalse(demand.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.IDLE, demand.reason)
    }

    @Test
    fun turnByTurnScreenOffRequiresGuidanceAmbientSettingEvenIfLifecycleResumed() {
        val demand =
            demand(
                isNavigateScreen = true,
                screenState = LocationScreenState.SCREEN_OFF,
                isScreenResumed = true,
                turnByTurnActive = true,
                turnByTurnGpsInAmbient = false,
            )

        assertFalse(demand.trackingEnabled)
        assertFalse(demand.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.IDLE, demand.reason)
    }

    @Test
    fun turnByTurnScreenOffDoesNotTrackWhenGuidanceGpsIsOffEvenIfAmbientIsEnabled() {
        val demand =
            demand(
                isNavigateScreen = true,
                screenState = LocationScreenState.SCREEN_OFF,
                isScreenResumed = false,
                turnByTurnActive = true,
                turnByTurnGpsEnabled = false,
                turnByTurnGpsInAmbient = true,
            )

        assertFalse(demand.trackingEnabled)
        assertFalse(demand.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.IDLE, demand.reason)
    }

    @Test
    fun turnByTurnGuidanceAmbientKeepsGpsInScreenOff() {
        val demand =
            demand(
                isNavigateScreen = true,
                screenState = LocationScreenState.SCREEN_OFF,
                isScreenResumed = false,
                turnByTurnActive = true,
                turnByTurnGpsInAmbient = true,
            )

        assertTrue(demand.trackingEnabled)
        assertTrue(demand.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.GUIDANCE_AMBIENT, demand.reason)
    }

    @Test
    fun turnByTurnOutsideNavigateRequiresGuidanceAmbientSetting() {
        val withoutSetting =
            demand(
                isNavigateScreen = false,
                screenState = LocationScreenState.INTERACTIVE,
                isScreenResumed = true,
                turnByTurnActive = true,
                turnByTurnGpsInAmbient = false,
            )
        val withSetting =
            demand(
                isNavigateScreen = false,
                screenState = LocationScreenState.INTERACTIVE,
                isScreenResumed = true,
                turnByTurnActive = true,
                turnByTurnGpsInAmbient = true,
            )

        assertFalse(withoutSetting.trackingEnabled)
        assertFalse(withoutSetting.backgroundGpsEnabled)
        assertTrue(withSetting.trackingEnabled)
        assertTrue(withSetting.backgroundGpsEnabled)
        assertEquals(NavigationRuntimeDemandReason.GUIDANCE_BACKGROUND, withSetting.reason)
    }

    @Test
    fun keepPermissionAndOfflineGatesAllDemand() {
        assertFalse(demand(hasLocationPermission = false, recordingActive = true).trackingEnabled)
        assertFalse(demand(offlineMode = true, recordingActive = true).trackingEnabled)
    }

    @Suppress("LongParameterList") // Named defaults keep each policy scenario concise and readable.
    private fun demand(
        isNavigateScreen: Boolean = true,
        screenState: LocationScreenState = LocationScreenState.INTERACTIVE,
        isScreenResumed: Boolean = true,
        hasLocationPermission: Boolean = true,
        offlineMode: Boolean = false,
        generalGpsInAmbient: Boolean = false,
        recordingActive: Boolean = false,
        recordingPaused: Boolean = false,
        recordingAutoPaused: Boolean = false,
        recordingGpsEnabled: Boolean = true,
        turnByTurnActive: Boolean = false,
        turnByTurnPaused: Boolean = false,
        turnByTurnGpsEnabled: Boolean = true,
        turnByTurnGpsInAmbient: Boolean = false,
        gpxElevationProfileOpen: Boolean = false,
    ): NavigationRuntimeDemand =
        navigationRuntimeDemand(
            NavigationRuntimeInputs(
                isNavigateScreen = isNavigateScreen,
                screenState = screenState,
                isScreenResumed = isScreenResumed,
                hasLocationPermission = hasLocationPermission,
                offlineMode = offlineMode,
                generalGpsInAmbient = generalGpsInAmbient,
                recordingActive = recordingActive,
                recordingPaused = recordingPaused,
                recordingAutoPaused = recordingAutoPaused,
                recordingGpsEnabled = recordingGpsEnabled,
                turnByTurnActive = turnByTurnActive,
                turnByTurnPaused = turnByTurnPaused,
                turnByTurnGpsEnabled = turnByTurnGpsEnabled,
                turnByTurnGpsInAmbient = turnByTurnGpsInAmbient,
                gpxElevationProfileOpen = gpxElevationProfileOpen,
            ),
        )
}
