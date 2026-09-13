package com.glancemap.glancemapcompanionapp.map

import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow

/** MapLibre Native uses a 512-pixel camera world; Mapsforge uses its configured tile size. */
internal const val PHONE_MAPLIBRE_CAMERA_TILE_SIZE_PX = 512.0

private const val PHONE_EARTH_CIRCUMFERENCE_METERS = 40_075_016.686

internal fun phoneGroundResolutionMetersPerPixel(
    latitudeDegrees: Double,
    zoom: Double,
    tileSizePx: Double,
): Double {
    val safeLatitude = latitudeDegrees.coerceIn(-85.0, 85.0)
    return PHONE_EARTH_CIRCUMFERENCE_METERS * cos(Math.toRadians(safeLatitude)) /
        (tileSizePx * 2.0.pow(zoom))
}

internal fun phoneMapsforgeZoomForGroundResolution(
    latitudeDegrees: Double,
    groundResolutionMetersPerPixel: Double,
    tileSizePx: Double,
): Double =
    log2(
        PHONE_EARTH_CIRCUMFERENCE_METERS *
            cos(Math.toRadians(latitudeDegrees.coerceIn(-85.0, 85.0))) /
            (tileSizePx * groundResolutionMetersPerPixel),
    )

internal fun phoneMapLibreZoomForGroundResolution(
    latitudeDegrees: Double,
    groundResolutionMetersPerPixel: Double,
    pixelRatio: Double,
): Double =
    log2(
        PHONE_EARTH_CIRCUMFERENCE_METERS *
            cos(Math.toRadians(latitudeDegrees.coerceIn(-85.0, 85.0))) /
            (PHONE_MAPLIBRE_CAMERA_TILE_SIZE_PX * pixelRatio * groundResolutionMetersPerPixel),
    )

internal fun phoneMapsforgeZoomForMapLibreZoom(
    mapLibreZoom: Double,
    mapsforgeTileSizePx: Double,
    mapLibrePixelRatio: Double,
): Double =
    mapLibreZoom +
        log2(
            PHONE_MAPLIBRE_CAMERA_TILE_SIZE_PX * mapLibrePixelRatio / mapsforgeTileSizePx,
        )
