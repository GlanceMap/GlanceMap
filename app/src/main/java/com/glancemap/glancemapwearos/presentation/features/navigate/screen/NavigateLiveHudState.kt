package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import com.glancemap.glancemapwearos.core.maps.mapZoomScaleStepsMeters
import com.glancemap.glancemapwearos.core.maps.nearestMetricScaleStepIndex
import com.glancemap.glancemapwearos.core.service.diagnostics.ScreenOffActivityDiagnostics
import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.core.service.location.model.isInteractive
import com.glancemap.glancemapwearos.presentation.features.maps.MapHolder
import com.glancemap.glancemapwearos.presentation.features.maps.RotatableMarker
import com.glancemap.glancemapwearos.presentation.formatting.UnitFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView

internal data class NavigateLiveHudState(
    val scaleIndicator: ScaleIndicatorUi?,
    val showScaleBar: Boolean,
    val liveElevationLabel: String?,
    val liveDistanceLabel: String?,
)

@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
internal fun rememberNavigateLiveHudState(
    enabled: Boolean,
    screenState: LocationScreenState,
    mapHolder: MapHolder?,
    mapView: MapView?,
    currentZoomLevel: Int,
    zoomMin: Int,
    zoomMax: Int,
    zoomMinScaleMeters: Int,
    zoomMaxScaleMeters: Int,
    isMetric: Boolean,
    navMode: NavMode,
    liveElevationEnabled: Boolean,
    liveDistanceEnabled: Boolean,
    locationMarker: RotatableMarker?,
    lastKnownLocation: LatLong?,
    visibleMapSizePx: IntSize,
): NavigateLiveHudState {
    var scaleIndicator by remember(mapView, isMetric) { mutableStateOf<ScaleIndicatorUi?>(null) }
    var hasSeenInitialZoomState by remember { mutableStateOf(false) }
    var showScaleBar by remember { mutableStateOf(false) }
    var liveElevationLabel by remember(mapHolder, isMetric) { mutableStateOf<String?>(null) }
    var liveDistanceLabel by remember(isMetric) { mutableStateOf<String?>(null) }

    val preferredScaleMeters =
        preferredScaleMetersForZoomLevel(
            currentZoomLevel = currentZoomLevel,
            zoomMin = zoomMin,
            zoomMax = zoomMax,
            zoomMinScaleMeters = zoomMinScaleMeters,
            zoomMaxScaleMeters = zoomMaxScaleMeters,
        )

    LaunchedEffect(enabled, mapView, isMetric, preferredScaleMeters) {
        scaleIndicator =
            if (enabled) {
                mapView?.let {
                    calculateScaleIndicator(
                        mapView = it,
                        isMetric = isMetric,
                        preferredScaleMeters = preferredScaleMeters,
                    )
                }
            } else {
                null
            }
    }

    LaunchedEffect(enabled, currentZoomLevel, mapView, isMetric, preferredScaleMeters) {
        if (!enabled || currentZoomLevel <= 0) return@LaunchedEffect
        if (!hasSeenInitialZoomState) {
            hasSeenInitialZoomState = true
            return@LaunchedEffect
        }
        scaleIndicator =
            mapView?.let {
                calculateScaleIndicator(
                    mapView = it,
                    isMetric = isMetric,
                    preferredScaleMeters = preferredScaleMeters,
                )
            }
        if (scaleIndicator == null) return@LaunchedEffect
        showScaleBar = true
        delay(5_000L)
        showScaleBar = false
    }

    LaunchedEffect(
        enabled,
        screenState,
        navMode,
        liveElevationEnabled,
        liveDistanceEnabled,
        mapHolder,
        mapView,
        locationMarker,
        lastKnownLocation,
        isMetric,
        visibleMapSizePx,
    ) {
        val activeMapHolder = mapHolder
        val activeMapView = mapView
        if (activeMapHolder == null || activeMapView == null) {
            liveElevationLabel = null
            liveDistanceLabel = null
            return@LaunchedEffect
        }
        if (
            !shouldPollNavigateLiveHud(
                enabled = enabled,
                screenState = screenState,
                navMode = navMode,
                liveElevationEnabled = liveElevationEnabled,
                liveDistanceEnabled = liveDistanceEnabled,
            )
        ) {
            liveElevationLabel = null
            liveDistanceLabel = null
            return@LaunchedEffect
        }

        var lastElevationSampleCenter: LatLong? = null
        while (isActive) {
            ScreenOffActivityDiagnostics.recordLiveHudTick()
            val visibleScreenCenter =
                resolveVisibleScreenCenterLatLong(
                    mapView = activeMapView,
                    visibleHeightPx = visibleMapSizePx.height,
                )
            val elevationCenter = visibleScreenCenter ?: activeMapView.model.mapViewPosition.center
            if (liveElevationEnabled) {
                val previousCenter = lastElevationSampleCenter
                val movedMeters =
                    if (previousCenter != null) {
                        navigateHaversineMeters(
                            lat1 = previousCenter.latitude,
                            lon1 = previousCenter.longitude,
                            lat2 = elevationCenter.latitude,
                            lon2 = elevationCenter.longitude,
                        )
                    } else {
                        Double.POSITIVE_INFINITY
                    }
                val shouldResampleElevation =
                    previousCenter == null ||
                        liveElevationLabel == null ||
                        movedMeters >= LIVE_ELEVATION_RESAMPLE_DISTANCE_METERS

                if (shouldResampleElevation) {
                    val sampledMeters =
                        withContext(Dispatchers.Default) {
                            activeMapHolder.renderer.sampleElevationMeters(
                                lat = elevationCenter.latitude,
                                lon = elevationCenter.longitude,
                            )
                        }
                    liveElevationLabel = sampledMeters?.let { meters ->
                        val (value, unit) = UnitFormatter.formatElevation(meters, isMetric)
                        "$value $unit"
                    } ?: "--"
                    lastElevationSampleCenter = elevationCenter
                }
            } else {
                liveElevationLabel = null
                lastElevationSampleCenter = null
            }

            val liveDistanceOrigin =
                resolveLiveDistanceOrigin(
                    currentMarkerLatLong = locationMarker?.latLong,
                    fallbackLatLong = lastKnownLocation,
                )
            if (liveDistanceEnabled && liveDistanceOrigin != null) {
                val straightDistanceMeters =
                    visibleScreenCenter?.let { target ->
                        resolveLiveDistanceMeters(
                            origin = liveDistanceOrigin,
                            target = target,
                        )
                    }
                liveDistanceLabel = straightDistanceMeters?.let { formatLiveDistanceLabel(it, isMetric) }
            } else {
                liveDistanceLabel = null
            }
            delay(320L)
        }
    }

    return NavigateLiveHudState(
        scaleIndicator = scaleIndicator,
        showScaleBar = showScaleBar,
        liveElevationLabel = liveElevationLabel,
        liveDistanceLabel = liveDistanceLabel,
    )
}

internal fun shouldPollNavigateLiveHud(
    enabled: Boolean,
    screenState: LocationScreenState,
    navMode: NavMode,
    liveElevationEnabled: Boolean,
    liveDistanceEnabled: Boolean,
): Boolean =
    enabled &&
        screenState.isInteractive &&
        navMode == NavMode.PANNING &&
        (liveElevationEnabled || liveDistanceEnabled)

internal fun preferredScaleMetersForZoomLevel(
    currentZoomLevel: Int,
    zoomMin: Int,
    zoomMax: Int,
    zoomMinScaleMeters: Int,
    zoomMaxScaleMeters: Int,
): Int? {
    val farthestScaleMeters = maxOf(zoomMinScaleMeters, zoomMaxScaleMeters)
    val closestScaleMeters = minOf(zoomMinScaleMeters, zoomMaxScaleMeters)
    val levelsFromClosest = zoomMax - currentZoomLevel
    val closestScaleIndex = nearestMetricScaleStepIndex(closestScaleMeters)
    return when {
        currentZoomLevel == zoomMin -> farthestScaleMeters
        levelsFromClosest !in 0 until CLOSE_ZOOM_SCALE_LABEL_COUNT -> null
        else -> mapZoomScaleStepsMeters.getOrNull(closestScaleIndex + levelsFromClosest)
    }
}

private const val CLOSE_ZOOM_SCALE_LABEL_COUNT = 5

private const val LIVE_ELEVATION_RESAMPLE_DISTANCE_METERS = 3.0
