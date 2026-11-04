package com.glancemap.glancemapcompanionapp.map

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val EARTH_RADIUS_METERS = 6_371_008.8

/**
 * A tool panel resizes the map from its top-left corner while the renderer keeps the camera center,
 * so the visible map would slide by half the size change. Returns the center that keeps the map in
 * place, or null when nothing moved or the camera has no ground scale.
 */
internal fun phoneMapCenterKeepingVisibleMap(
    camera: PhoneMapCameraSnapshot,
    oldWidthPx: Int,
    oldHeightPx: Int,
    newWidthPx: Int,
    newHeightPx: Int,
): PhoneMapCoordinate? {
    val metersPerPixel = camera.groundResolutionMetersPerPixel?.takeIf { it > 0.0 }
    val sizesKnown = listOf(oldWidthPx, oldHeightPx, newWidthPx, newHeightPx).all { it > 0 }
    // Screen offset from the old center to the new one; y grows downward.
    val rightPx = (newWidthPx - oldWidthPx) / 2.0
    val upPx = -(newHeightPx - oldHeightPx) / 2.0
    val moved = rightPx != 0.0 || upPx != 0.0
    return metersPerPixel?.takeIf { sizesKnown && moved }?.let { scale ->
        // The map bearing is the azimuth of screen-up.
        val bearing = camera.bearingDegrees * PI / 180.0
        val northMeters = (upPx * cos(bearing) - rightPx * sin(bearing)) * scale
        val eastMeters = (upPx * sin(bearing) + rightPx * cos(bearing)) * scale
        val latitude = camera.latitude + northMeters / EARTH_RADIUS_METERS * 180.0 / PI
        val longitude =
            camera.longitude + eastMeters / (EARTH_RADIUS_METERS * cos(camera.latitude * PI / 180.0)) * 180.0 / PI
        PhoneMapCoordinate(latitude, longitude).takeIf(PhoneMapCoordinate::isValidSearchPoint)
    }
}

/** The parts of the tool panel that change the map size; a resize without a panel change is a rotation. */
internal data class PhoneMapPanelLayoutKey(
    val open: Boolean,
    val mode: MapToolPanelMode,
    val splitFraction: Float,
)

internal fun MapToolPanelState.layoutKey(): PhoneMapPanelLayoutKey =
    PhoneMapPanelLayoutKey(activeTool != null && mode != MapToolPanelMode.CLOSED, mode, splitFraction)
