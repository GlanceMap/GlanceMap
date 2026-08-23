package com.glancemap.trailcore.map

import com.glancemap.trailcore.geo.GeoPoint

/** The user's rendering choice, independent of a particular map SDK or provider. */
enum class MapMode {
    ONLINE,
    OFFLINE,
}

enum class MapOrientation {
    NORTH_UP,
    HEADING_UP,
}

/** Camera values expressed as user intent rather than a renderer-specific camera object. */
data class MapCameraIntent(
    val center: GeoPoint? = null,
    val zoomLevel: Double? = null,
    val orientation: MapOrientation = MapOrientation.NORTH_UP,
    val followLocation: Boolean = false,
) {
    init {
        require(zoomLevel == null || (zoomLevel.isFinite() && zoomLevel >= 0.0)) {
            "Zoom level must be a non-negative, finite value when available."
        }
    }
}

data class MapContentVisibility(
    val gpxTracks: Boolean = true,
    val routes: Boolean = true,
    val pois: Boolean = true,
)

data class MapAppearancePreferences(
    val hillshadeEnabled: Boolean = false,
    val slopeOverlayEnabled: Boolean = false,
)

/** Shared, renderer-free map state that applications can persist and adapt to their renderer. */
data class MapIntent(
    val mode: MapMode = MapMode.ONLINE,
    val camera: MapCameraIntent = MapCameraIntent(),
    val content: MapContentVisibility = MapContentVisibility(),
    val appearance: MapAppearancePreferences = MapAppearancePreferences(),
)
