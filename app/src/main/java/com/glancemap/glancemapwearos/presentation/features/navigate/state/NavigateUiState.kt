package com.glancemap.glancemapwearos.presentation.features.navigate

import org.mapsforge.core.model.LatLong

// ✅ Define NavMode ONCE, publicly, in a shared file/package.
enum class NavMode { COMPASS_FOLLOW, NORTH_UP_FOLLOW, PANNING }

enum class GpsFixIndicatorState { UNAVAILABLE, SEARCHING, POOR, LOST, GOOD }

enum class StartupMapFallbackState {
    WAITING,
    READY,
    CANCELLED,
    COMPLETED,
}

enum class StartupMapFallbackEvent {
    TIMER_EXPIRED,
    CENTERING_APPLIED,
    CANCELLED,
}

data class RetainedLocationAnchor(
    val latLong: LatLong,
    val fixElapsedRealtimeMs: Long,
    val accuracyM: Float,
    val sourceEpoch: Long,
    val sourceModeName: String? = null,
    val isAcceptedFix: Boolean = true,
)

data class NavigateUiState(
    val navMode: NavMode = NavMode.COMPASS_FOLLOW,
    val showCalibrationDialog: Boolean = false,
    val currentZoomLevel: Int = 0,
    val lastKnownLocation: LatLong? = null,
    val retainedLocationAnchor: RetainedLocationAnchor? = null,
    val startupMapFallbackState: StartupMapFallbackState = StartupMapFallbackState.WAITING,
)
