package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxTrackDetails
import com.glancemap.glancemapwearos.presentation.features.maps.MapViewModel
import com.glancemap.glancemapwearos.presentation.features.offline.OfflineStartCenteringEffect
import com.glancemap.glancemapwearos.presentation.features.poi.PoiNavigateTarget
import kotlinx.coroutines.delay
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView

@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun NavigateStartupCenteringEffects(
    offlineMode: Boolean,
    shouldTrackLocation: Boolean,
    locationMarkerLatLong: LatLong?,
    lastKnownLocation: LatLong?,
    retainedLocationAnchor: RetainedLocationAnchor?,
    startupMapFallbackState: StartupMapFallbackState,
    onStartupMapFallbackEvent: (StartupMapFallbackEvent) -> Unit,
    navigateTarget: PoiNavigateTarget?,
    pendingPoiFocusTarget: PoiNavigateTarget?,
    mapView: MapView,
    mapViewModel: MapViewModel,
    selectedMapPath: String?,
    activeGpxDetails: List<GpxTrackDetails>,
): LatLong? {
    val gpsStartupMapCenteringPending =
        shouldWaitForOnlineStartupMapFallback(
            OnlineStartupMapFallbackInput(
                offlineMode = offlineMode,
                shouldTrackLocation = shouldTrackLocation,
                locationMarkerLatLong = locationMarkerLatLong,
                retainedLocationAnchor = retainedLocationAnchor,
                lastKnownLocation = lastKnownLocation,
                startupMapFallbackState = startupMapFallbackState,
                navigateTarget = navigateTarget,
                pendingPoiFocusTarget = pendingPoiFocusTarget,
            ),
        )
    LaunchedEffect(gpsStartupMapCenteringPending) {
        if (!gpsStartupMapCenteringPending) return@LaunchedEffect
        delay(NORMAL_STARTUP_MAP_FALLBACK_GRACE_MS)
        onStartupMapFallbackEvent(StartupMapFallbackEvent.TIMER_EXPIRED)
    }
    val gpsStartupMapCenteringActive =
        shouldStartOnlineStartupMapCentering(
            OnlineStartupMapFallbackInput(
                offlineMode = offlineMode,
                shouldTrackLocation = shouldTrackLocation,
                locationMarkerLatLong = locationMarkerLatLong,
                retainedLocationAnchor = retainedLocationAnchor,
                lastKnownLocation = lastKnownLocation,
                startupMapFallbackState = startupMapFallbackState,
                navigateTarget = navigateTarget,
                pendingPoiFocusTarget = pendingPoiFocusTarget,
            ),
        )

    LaunchedEffect(navigateTarget, pendingPoiFocusTarget) {
        if (navigateTarget != null || pendingPoiFocusTarget != null) {
            onStartupMapFallbackEvent(StartupMapFallbackEvent.CANCELLED)
        }
    }

    OfflineStartCenteringEffect(
        isOfflineMode = offlineMode,
        mapView = mapView,
        mapViewModel = mapViewModel,
        selectedMapPath = selectedMapPath,
        activeGpxDetails = activeGpxDetails,
        skipInitialCentering = navigateTarget != null || pendingPoiFocusTarget != null,
        enabled = offlineMode || gpsStartupMapCenteringActive,
        onInitialCenteringApplied =
            if (!offlineMode && gpsStartupMapCenteringActive) {
                { onStartupMapFallbackEvent(StartupMapFallbackEvent.CENTERING_APPLIED) }
            } else {
                null
            },
        deferWhenNoCenter = !offlineMode && gpsStartupMapCenteringActive,
    )

    return if (offlineMode) {
        null
    } else {
        locationMarkerLatLong ?: retainedLocationAnchor?.latLong ?: lastKnownLocation
    }
}

private const val NORMAL_STARTUP_MAP_FALLBACK_GRACE_MS = 15_000L

internal data class OnlineStartupMapFallbackInput(
    val offlineMode: Boolean,
    val shouldTrackLocation: Boolean,
    val locationMarkerLatLong: LatLong?,
    val retainedLocationAnchor: RetainedLocationAnchor?,
    val lastKnownLocation: LatLong?,
    val startupMapFallbackState: StartupMapFallbackState,
    val navigateTarget: PoiNavigateTarget?,
    val pendingPoiFocusTarget: PoiNavigateTarget?,
)

internal fun shouldWaitForOnlineStartupMapFallback(
    input: OnlineStartupMapFallbackInput,
): Boolean =
    !input.offlineMode &&
        input.shouldTrackLocation &&
        input.startupMapFallbackState == StartupMapFallbackState.WAITING &&
        input.locationMarkerLatLong == null &&
        input.retainedLocationAnchor == null &&
        input.lastKnownLocation == null &&
        input.navigateTarget == null &&
        input.pendingPoiFocusTarget == null

internal fun shouldStartOnlineStartupMapCentering(
    input: OnlineStartupMapFallbackInput,
): Boolean =
    !input.offlineMode &&
        input.shouldTrackLocation &&
        input.startupMapFallbackState == StartupMapFallbackState.READY &&
        input.locationMarkerLatLong == null &&
        input.retainedLocationAnchor == null &&
        input.lastKnownLocation == null &&
        input.navigateTarget == null &&
        input.pendingPoiFocusTarget == null
