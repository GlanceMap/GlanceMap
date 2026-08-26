package com.glancemap.glancemapwearos.presentation.features.offline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.glancemap.glancemapwearos.core.maps.GeoBounds
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxTrackDetails
import com.glancemap.glancemapwearos.presentation.features.maps.MapFileState
import com.glancemap.glancemapwearos.presentation.features.maps.MapViewModel
import com.glancemap.glancemapwearos.presentation.features.maps.MapZoomChangeAttribution
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView

@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun OfflineStartCenteringEffect(
    isOfflineMode: Boolean,
    mapView: MapView,
    mapViewModel: MapViewModel,
    selectedMapPath: String?,
    activeGpxDetails: List<GpxTrackDetails>,
    skipInitialCentering: Boolean = false,
    enabled: Boolean = isOfflineMode,
) {
    val mapFiles by mapViewModel.mapFiles.collectAsState()
    val selectedMapArea =
        remember(mapFiles, selectedMapPath) {
            mapFiles.selectedOfflineMapArea(selectedMapPath)
        }

    LaunchedEffect(enabled) {
        if (!enabled) {
            mapViewModel.resetOfflineStartCenterTracking()
        }
    }

    LaunchedEffect(
        enabled,
        selectedMapPath,
        selectedMapArea,
        activeGpxDetails,
        skipInitialCentering,
        mapViewModel,
        mapView,
    ) {
        if (!enabled) return@LaunchedEffect
        val forceStartupCenter =
            mapViewModel.shouldForceOfflineStartCenter(
                selectedMapPath = selectedMapPath,
                activeGpxDetails = activeGpxDetails,
            )
        if (!skipInitialCentering && !forceStartupCenter) {
            mapViewModel.restoreOfflineViewport(selectedMapPath, activeGpxDetails)?.let { (center, zoomLevel) ->
                MapZoomChangeAttribution.prepare(mapView, "offline_viewport_restore")
                mapView.model.mapViewPosition.setZoomLevel(zoomLevel.toByte(), false)
                mapView.setCenter(center)
                mapViewModel.markOfflineStartCenterHandled(selectedMapPath, activeGpxDetails)
                return@LaunchedEffect
            }
        }
        if (!mapViewModel.shouldApplyOfflineStartCenter(selectedMapPath, activeGpxDetails)) {
            return@LaunchedEffect
        }
        if (skipInitialCentering) {
            mapViewModel.finishOfflineStartCenter(selectedMapPath, activeGpxDetails, forceStartupCenter)
            return@LaunchedEffect
        }

        if (selectedMapPath?.isNotBlank() == true && selectedMapArea == null) {
            return@LaunchedEffect
        }

        val targetCenter = resolveOfflineStartCenter(selectedMapArea, activeGpxDetails)
        targetCenter?.let { mapView.setCenter(it) }
        mapViewModel.finishOfflineStartCenter(selectedMapPath, activeGpxDetails, forceStartupCenter)
    }
}

private fun MapViewModel.finishOfflineStartCenter(
    selectedMapPath: String?,
    activeGpxDetails: List<GpxTrackDetails>,
    forceStartupCenter: Boolean,
) {
    markOfflineStartCenterHandled(selectedMapPath, activeGpxDetails)
    if (forceStartupCenter) {
        consumeForcedOfflineStartCenter(selectedMapPath, activeGpxDetails)
    }
}

internal data class SelectedOfflineMapArea(
    val bounds: GeoBounds,
    val center: LatLong,
)

internal fun resolveOfflineStartCenter(
    selectedMapArea: SelectedOfflineMapArea?,
    activeGpxDetails: List<GpxTrackDetails>,
): LatLong? {
    val mapCenter = selectedMapArea?.center
    val activeGpxOnSelectedMap =
        if (selectedMapArea != null) {
            activeGpxDetails.filter { it.overlaps(selectedMapArea.bounds) }
        } else {
            activeGpxDetails
        }

    return resolveActiveGpxCenter(activeGpxOnSelectedMap) ?: mapCenter
}

private fun List<MapFileState>.selectedOfflineMapArea(selectedMapPath: String?): SelectedOfflineMapArea? =
    firstOrNull { it.path == selectedMapPath }
        ?.bounds
        ?.toSelectedOfflineMapArea()

private fun GeoBounds.toSelectedOfflineMapArea(): SelectedOfflineMapArea =
    SelectedOfflineMapArea(
        bounds = this,
        center =
            LatLong(
                (minLat + maxLat) * 0.5,
                (minLon + maxLon) * 0.5,
            ),
    )

private fun GpxTrackDetails.overlaps(bounds: GeoBounds): Boolean =
    points.any { point ->
        point.latitude.isFinite() &&
            point.longitude.isFinite() &&
            point.latitude in bounds.minLat..bounds.maxLat &&
            point.longitude in bounds.minLon..bounds.maxLon
    }

private fun resolveActiveGpxCenter(activeGpxDetails: List<GpxTrackDetails>): LatLong? {
    if (activeGpxDetails.isEmpty()) return null

    var minLat = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    var minLon = Double.POSITIVE_INFINITY
    var maxLon = Double.NEGATIVE_INFINITY
    var hasPoint = false

    activeGpxDetails.forEach { track ->
        track.points.forEach { point ->
            if (!point.latitude.isFinite() || !point.longitude.isFinite()) return@forEach
            hasPoint = true
            if (point.latitude < minLat) minLat = point.latitude
            if (point.latitude > maxLat) maxLat = point.latitude
            if (point.longitude < minLon) minLon = point.longitude
            if (point.longitude > maxLon) maxLon = point.longitude
        }
    }

    if (!hasPoint) return null
    return LatLong(
        (minLat + maxLat) * 0.5,
        (minLon + maxLon) * 0.5,
    )
}
