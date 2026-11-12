package com.glancemap.glancemapwearos.core.service.location.policy

import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationForegroundPolicyTest {
    private val idleInputs =
        NavigationRuntimeInputs(
            isNavigateScreen = false,
            screenState = LocationScreenState.SCREEN_OFF,
            isScreenResumed = false,
            hasLocationPermission = true,
            offlineMode = false,
            generalGpsInAmbient = false,
            recordingActive = false,
            recordingPaused = false,
            recordingAutoPaused = false,
            recordingGpsEnabled = true,
            turnByTurnActive = false,
            turnByTurnPaused = false,
            turnByTurnGpsEnabled = true,
            turnByTurnGpsInAmbient = true,
        )

    @Test
    fun recordingKeepsForegroundProtectionAcrossDisplayStatesWithStayOff() {
        for (screenState in LocationScreenState.entries) {
            assertEquals(
                screenState.name,
                KeepAliveNotificationMode.LOCATION_FOREGROUND,
                mode(idleInputs.copy(recordingActive = true, screenState = screenState)),
            )
        }
    }

    @Test
    fun combinedRecordingGuidanceKeepsForegroundProtectionWithStayOff() {
        assertEquals(
            KeepAliveNotificationMode.LOCATION_FOREGROUND,
            mode(idleInputs.copy(recordingActive = true, turnByTurnActive = true)),
        )
    }

    @Test
    fun autoPausedRecordingRetainsProtectionForItsGpsMonitoringDemand() {
        assertEquals(
            KeepAliveNotificationMode.LOCATION_FOREGROUND,
            mode(idleInputs.copy(recordingActive = true, recordingAutoPaused = true)),
        )
    }

    @Test
    fun standaloneGuidanceRetainsExistingProtectionInVisibleAmbientAndBackgroundModes() {
        val guidance = idleInputs.copy(turnByTurnActive = true)
        val states =
            listOf(
                guidance,
                guidance.copy(isNavigateScreen = true),
                guidance.copy(
                    isNavigateScreen = true,
                    screenState = LocationScreenState.INTERACTIVE,
                    isScreenResumed = true,
                ),
            )
        for (inputs in states) {
            assertEquals(KeepAliveNotificationMode.LOCATION_FOREGROUND, mode(inputs))
        }
    }

    @Test
    fun pauseAndSessionEndRestoreStayOffWhileRemainingGuidanceStaysProtected() {
        val recording = idleInputs.copy(recordingActive = true)

        assertEquals(KeepAliveNotificationMode.LOCATION_FOREGROUND, mode(recording))
        assertEquals(KeepAliveNotificationMode.OFF, mode(recording.copy(recordingPaused = true)))
        assertEquals(KeepAliveNotificationMode.OFF, mode(idleInputs))
        assertEquals(
            KeepAliveNotificationMode.LOCATION_FOREGROUND,
            mode(recording.copy(recordingPaused = true, turnByTurnActive = true)),
        )
    }

    @Test
    fun manualStayRetainsItsPinnedAndInteractiveLocationBehaviorAfterSessionEnd() {
        assertEquals(KeepAliveNotificationMode.PINNED_NOTIFICATION, mode(idleInputs, stay = true))
        val navigate =
            idleInputs.copy(
                isNavigateScreen = true,
                screenState = LocationScreenState.INTERACTIVE,
                isScreenResumed = true,
            )

        assertEquals(KeepAliveNotificationMode.LOCATION_FOREGROUND, mode(navigate, stay = true))
        assertEquals(KeepAliveNotificationMode.OFF, mode(navigate))
    }

    @Test
    fun sessionProtectionDoesNotOverrideDisabledLocationDemand() {
        val recording = idleInputs.copy(recordingActive = true)
        val disabled =
            listOf(
                recording.copy(hasLocationPermission = false),
                recording.copy(offlineMode = true),
                recording.copy(recordingGpsEnabled = false),
                idleInputs.copy(turnByTurnActive = true, turnByTurnGpsInAmbient = false),
                idleInputs.copy(turnByTurnActive = true, turnByTurnPaused = true),
            )
        for (inputs in disabled) {
            assertEquals(KeepAliveNotificationMode.OFF, mode(inputs))
        }
    }

    private fun mode(
        inputs: NavigationRuntimeInputs,
        stay: Boolean = false,
    ): KeepAliveNotificationMode {
        val demand = navigationRuntimeDemand(inputs)
        return locationKeepAliveNotificationMode(
            keepAppOpen = stay,
            trackingEnabled = demand.trackingEnabled,
            screenState = inputs.screenState,
            backgroundGpsEnabled = demand.backgroundGpsEnabled,
            runtimeReason = demand.reason,
        )
    }
}
