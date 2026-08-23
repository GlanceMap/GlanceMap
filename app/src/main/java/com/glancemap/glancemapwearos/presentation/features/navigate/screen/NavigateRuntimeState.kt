package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.core.service.location.model.isNonInteractive
import com.glancemap.glancemapwearos.core.service.location.model.resolveLocationScreenState
import com.glancemap.glancemapwearos.core.service.location.policy.NavigationRuntimeDemand
import com.glancemap.glancemapwearos.core.service.location.policy.NavigationRuntimeInputs
import com.glancemap.glancemapwearos.core.service.location.policy.navigationRuntimeDemand
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState

internal data class NavigateRuntimeState(
    val screenState: LocationScreenState,
    val shouldTrackLocation: Boolean,
    val backgroundGpsEnabled: Boolean,
    val reason: String,
)

@Composable
internal fun rememberNavigateRuntimeState(
    isAmbient: Boolean,
    isDeviceInteractive: Boolean,
    isScreenResumed: Boolean,
    hasLocationPermission: Boolean,
    offlineMode: Boolean,
    generalGpsInAmbient: Boolean,
    traceRecordingState: TraceRecordingUiState,
    recordingScreenOnGpsEnabled: Boolean,
    recordingScreenOffGpsEnabled: Boolean,
    turnByTurnScreenOnGpsEnabled: Boolean,
    turnByTurnScreenOffGpsEnabled: Boolean,
    turnByTurnActive: Boolean,
    turnByTurnPaused: Boolean,
    turnByTurnGpsInAmbient: Boolean,
    locationViewModel: LocationViewModel,
): NavigateRuntimeState {
    val screenState =
        remember(isAmbient, isDeviceInteractive) {
            resolveLocationScreenState(
                isAmbient = isAmbient,
                isDeviceInteractive = isDeviceInteractive,
            )
        }
    val recordingGpsEnabled =
        if (screenState.isNonInteractive) {
            recordingScreenOffGpsEnabled
        } else {
            recordingScreenOnGpsEnabled
        }
    val turnByTurnGpsEnabled =
        if (screenState.isNonInteractive) {
            turnByTurnScreenOffGpsEnabled
        } else {
            turnByTurnScreenOnGpsEnabled
        }
    val recordingRuntimePaused = traceRecordingState.paused && !traceRecordingState.autoPaused
    val runtimeDemand =
        navigationRuntimeDemand(
            NavigationRuntimeInputs(
                isNavigateScreen = true,
                screenState = screenState,
                isScreenResumed = isScreenResumed,
                hasLocationPermission = hasLocationPermission,
                offlineMode = offlineMode,
                generalGpsInAmbient = generalGpsInAmbient,
                recordingActive = traceRecordingState.active,
                recordingPaused = recordingRuntimePaused,
                recordingAutoPaused = traceRecordingState.autoPaused,
                recordingGpsEnabled = recordingGpsEnabled,
                turnByTurnActive = turnByTurnActive,
                turnByTurnPaused = turnByTurnPaused,
                turnByTurnGpsEnabled = turnByTurnGpsEnabled,
                turnByTurnGpsInAmbient = turnByTurnGpsInAmbient,
            ),
        )
    NavigateRuntimeEffects(
        screenState = screenState,
        runtimeDemand = runtimeDemand,
        locationViewModel = locationViewModel,
    )

    return NavigateRuntimeState(
        screenState = screenState,
        shouldTrackLocation = runtimeDemand.trackingEnabled,
        backgroundGpsEnabled = runtimeDemand.backgroundGpsEnabled,
        reason = runtimeDemand.reason,
    )
}

@Composable
private fun NavigateRuntimeEffects(
    screenState: LocationScreenState,
    runtimeDemand: NavigationRuntimeDemand,
    locationViewModel: LocationViewModel,
) {
    LaunchedEffect(
        screenState,
        runtimeDemand.trackingEnabled,
        runtimeDemand.backgroundGpsEnabled,
        runtimeDemand.reason,
    ) {
        locationViewModel.syncRuntimeState(
            screenState = screenState,
            trackingEnabled = runtimeDemand.trackingEnabled,
            backgroundGpsEnabled = runtimeDemand.backgroundGpsEnabled,
            runtimeReason = runtimeDemand.reason,
        )
    }
}
