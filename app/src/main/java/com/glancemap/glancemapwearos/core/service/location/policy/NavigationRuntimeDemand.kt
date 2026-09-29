package com.glancemap.glancemapwearos.core.service.location.policy

import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.core.service.location.model.isInteractive
import com.glancemap.glancemapwearos.core.service.location.model.isNonInteractive

data class NavigationRuntimeDemand(
    val trackingEnabled: Boolean,
    val backgroundGpsEnabled: Boolean,
    val reason: String,
)

data class NavigationRuntimeInputs(
    val isNavigateScreen: Boolean,
    val screenState: LocationScreenState,
    val isScreenResumed: Boolean,
    val hasLocationPermission: Boolean,
    val offlineMode: Boolean,
    val generalGpsInAmbient: Boolean,
    val recordingActive: Boolean,
    val recordingPaused: Boolean,
    val recordingAutoPaused: Boolean,
    val recordingGpsEnabled: Boolean,
    val turnByTurnActive: Boolean,
    val turnByTurnPaused: Boolean,
    val turnByTurnGpsEnabled: Boolean,
    val turnByTurnGpsInAmbient: Boolean,
    val gpxElevationProfileOpen: Boolean = false,
)

private data class ActiveRuntimeDemand(
    val recording: Boolean,
    val guidance: Boolean,
    val guidanceBackground: Boolean,
    val generalBackground: Boolean,
    val backgroundGps: Boolean,
    val backgroundGpsMode: Boolean,
    val navigateVisible: Boolean,
    val guidanceOutsideNavigate: Boolean,
    val elevationProfileVisible: Boolean,
)

fun navigationRuntimeDemand(inputs: NavigationRuntimeInputs): NavigationRuntimeDemand =
    when {
        !inputs.hasLocationPermission ->
            NavigationRuntimeDemand(false, false, NavigationRuntimeDemandReason.NO_PERMISSION)
        inputs.offlineMode ->
            NavigationRuntimeDemand(false, false, NavigationRuntimeDemandReason.OFFLINE)
        else -> activeNavigationRuntimeDemand(inputs)
    }

private fun activeNavigationRuntimeDemand(inputs: NavigationRuntimeInputs): NavigationRuntimeDemand {
    val demand = inputs.activeDemand()
    val trackingEnabled =
        demand.navigateVisible ||
            demand.backgroundGpsMode ||
            demand.recording ||
            demand.guidanceOutsideNavigate ||
            demand.elevationProfileVisible

    return NavigationRuntimeDemand(
        trackingEnabled = trackingEnabled,
        backgroundGpsEnabled = demand.backgroundGps,
        reason = demand.reason(inputs.recordingAutoPaused),
    )
}

private fun NavigationRuntimeInputs.activeDemand(): ActiveRuntimeDemand {
    val recordingDemand = recordingActive && !recordingPaused && recordingGpsEnabled
    val guidanceDemand = turnByTurnActive && !turnByTurnPaused && turnByTurnGpsEnabled
    val guidanceBackgroundDemand = guidanceDemand && turnByTurnGpsInAmbient
    val generalBackgroundDemand = isNavigateScreen && generalGpsInAmbient
    val backgroundGpsEnabled =
        generalBackgroundDemand ||
            guidanceBackgroundDemand ||
            recordingDemand
    val backgroundGpsModeActive = backgroundGpsEnabled && screenState.isNonInteractive
    val navigateVisibleDemand = isNavigateScreen && isScreenResumed && screenState.isInteractive
    val guidanceOutsideNavigateDemand = !isNavigateScreen && guidanceBackgroundDemand
    val elevationProfileVisibleDemand = elevationProfileVisible()
    return ActiveRuntimeDemand(
        recording = recordingDemand,
        guidance = guidanceDemand,
        guidanceBackground = guidanceBackgroundDemand,
        generalBackground = generalBackgroundDemand,
        backgroundGps = backgroundGpsEnabled,
        backgroundGpsMode = backgroundGpsModeActive,
        navigateVisible = navigateVisibleDemand,
        guidanceOutsideNavigate = guidanceOutsideNavigateDemand,
        elevationProfileVisible = elevationProfileVisibleDemand,
    )
}

private fun NavigationRuntimeInputs.elevationProfileVisible(): Boolean =
    gpxElevationProfileOpen &&
        isScreenResumed &&
        screenState.isInteractive

private fun ActiveRuntimeDemand.reason(recordingAutoPaused: Boolean): String =
    if (recording) {
        when {
            guidance -> NavigationRuntimeDemandReason.RECORDING_GUIDANCE
            recordingAutoPaused -> NavigationRuntimeDemandReason.RECORDING_AUTO_PAUSED
            else -> NavigationRuntimeDemandReason.RECORDING
        }
    } else {
        nonRecordingReason()
    }

private fun ActiveRuntimeDemand.nonRecordingReason(): String =
    when {
        guidanceOutsideNavigate -> NavigationRuntimeDemandReason.GUIDANCE_BACKGROUND
        backgroundGpsMode && guidanceBackground -> NavigationRuntimeDemandReason.GUIDANCE_AMBIENT
        backgroundGpsMode && generalBackground -> NavigationRuntimeDemandReason.GENERAL_AMBIENT
        guidance && navigateVisible -> NavigationRuntimeDemandReason.GUIDANCE_VISIBLE
        navigateVisible -> NavigationRuntimeDemandReason.NAVIGATE_VISIBLE
        elevationProfileVisible -> NavigationRuntimeDemandReason.GPX_ELEVATION_PROFILE
        else -> NavigationRuntimeDemandReason.IDLE
    }

object NavigationRuntimeDemandReason {
    const val NO_PERMISSION = "no_permission"
    const val OFFLINE = "offline"
    const val IDLE = "idle"
    const val NAVIGATE_VISIBLE = "navigate_visible"
    const val GENERAL_AMBIENT = "general_ambient"
    const val GUIDANCE_AMBIENT = "guidance_ambient"
    const val GUIDANCE_BACKGROUND = "guidance_background"
    const val GUIDANCE_VISIBLE = "guidance_visible"
    const val GPX_ELEVATION_PROFILE = "gpx_elevation_profile"
    const val RECORDING = "recording"
    const val RECORDING_AUTO_PAUSED = "recording_auto_paused"
    const val RECORDING_GUIDANCE = "recording_guidance"
}
