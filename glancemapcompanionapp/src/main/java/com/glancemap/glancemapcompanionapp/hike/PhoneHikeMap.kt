package com.glancemap.glancemapcompanionapp.hike

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.unit.IntSize
import com.glancemap.glancemapcompanionapp.map.*
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.geo.haversineDistanceMeters
import kotlinx.coroutines.delay

internal fun phoneHikeMapMarkers(ui: PhoneHikeUiState): List<PhoneMapPointSelectionMarker> {
    val model = ui.model ?: return emptyList()
    val plan = ui.plan ?: return emptyList()
    val checkpoints =
        model.orderedCheckpoints(plan).map { (_, point) ->
            PhoneMapPointSelectionMarker(PhoneMapPointSelectionMarkerKind.CHECKPOINT, PhoneMapCoordinate(point.coordinate.latitude, point.coordinate.longitude))
        }
    val inspection =
        ui.cursor?.takeIf { plan.inspecting }?.let { point ->
            PhoneMapPointSelectionMarker(PhoneMapPointSelectionMarkerKind.HIKE_INSPECTION, PhoneMapCoordinate(point.coordinate.latitude, point.coordinate.longitude))
        }
    return checkpoints + listOfNotNull(inspection)
}

/** Keep inspection in view without sending a native camera command for every profile pixel. */
internal fun phoneHikeShouldPan(
    camera: PhoneMapCameraSnapshot,
    viewport: IntSize,
    point: GeoPoint,
): Boolean {
    val resolution = camera.groundResolutionMetersPerPixel ?: return false
    val radiusMeters = minOf(viewport.width, viewport.height) * .30 * resolution
    return radiusMeters > 0.0 && haversineDistanceMeters(GeoPoint(camera.latitude, camera.longitude), point) > radiusMeters
}

@Composable
internal fun PhoneHikeMapEffects(
    controller: PhoneHikeViewModel?,
    ui: PhoneHikeUiState,
    location: PhoneMapLocation?,
    camera: PhoneMapCameraSnapshot,
    viewport: IntSize,
    visible: Boolean,
    onPan: (PhoneMapCoordinate) -> Unit,
    onReturnToLive: () -> Unit,
) {
    val latestCamera by rememberUpdatedState(camera)
    val latestPan by rememberUpdatedState(onPan)
    val latestReturn by rememberUpdatedState(onReturnToLive)
    var lastPanAt by remember { mutableLongStateOf(0L) }
    var handledRequest by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(controller, location) { controller?.updateLocation(location) }
    val inspecting = ui.plan?.inspecting == true
    val cursor = ui.cursor.takeIf { inspecting }
    LaunchedEffect(ui.model, ui.cameraRequest, inspecting, cursor?.segmentIndex, cursor?.fraction, viewport, visible) {
        val model = ui.model ?: return@LaunchedEffect
        if (!visible) return@LaunchedEffect
        val newRequest = ui.cameraRequest != handledRequest
        if (!inspecting && !newRequest) return@LaunchedEffect
        if (!inspecting && newRequest && ui.cameraReturnToLive) {
            handledRequest = ui.cameraRequest
            latestReturn()
            return@LaunchedEffect
        }
        if (!inspecting && !ui.data.previewSelected && controller?.matchesGuidance(model) == true) {
            handledRequest = ui.cameraRequest
            return@LaunchedEffect
        }
        val point = cursor ?: ui.currentPosition ?: model.atDistance(0.0) ?: return@LaunchedEffect
        delay((lastPanAt + 120L - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        if (newRequest || phoneHikeShouldPan(latestCamera, viewport, point.coordinate)) {
            latestPan(PhoneMapCoordinate(point.coordinate.latitude, point.coordinate.longitude))
            lastPanAt = SystemClock.elapsedRealtime()
        }
        handledRequest = ui.cameraRequest
    }
}
