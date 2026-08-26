package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.presentation.features.maps.MapZoomChangeAttribution
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.view.MapView

internal fun fitMapViewToPreviewPoints(
    mapView: MapView,
    points: List<LatLong>,
    zoomMin: Int,
    zoomMax: Int,
) {
    if (points.isEmpty()) return
    val widthPx = mapView.width.toDouble()
    val heightPx = mapView.height.toDouble()
    if (widthPx <= 0.0 || heightPx <= 0.0) return

    val minLat = points.minOf { it.latitude }
    val maxLat = points.maxOf { it.latitude }
    val minLon = points.minOf { it.longitude }
    val maxLon = points.maxOf { it.longitude }
    val center =
        LatLong(
            (minLat + maxLat) / 2.0,
            (minLon + maxLon) / 2.0,
        )

    val usableWidth = maxOf(96.0, widthPx * 0.68)
    val usableHeight = maxOf(96.0, heightPx * 0.52)
    val tileSize = mapView.model.displayModel.tileSize
    val horizontalPaddingPx = 28.0
    val verticalPaddingPx = 36.0

    var chosenZoom = zoomMin.coerceAtMost(zoomMax)
    for (zoom in zoomMax downTo zoomMin) {
        val mapSize = MercatorProjection.getMapSize(zoom.toByte(), tileSize)
        val spanX =
            points.maxOf { MercatorProjection.longitudeToPixelX(it.longitude, mapSize) } -
                points.minOf { MercatorProjection.longitudeToPixelX(it.longitude, mapSize) } +
                horizontalPaddingPx
        val spanY =
            points.maxOf { MercatorProjection.latitudeToPixelY(it.latitude, mapSize) } -
                points.minOf { MercatorProjection.latitudeToPixelY(it.latitude, mapSize) } +
                verticalPaddingPx
        if (spanX <= usableWidth && spanY <= usableHeight) {
            chosenZoom = zoom
            break
        }
    }

    mapView.setCenter(center)
    MapZoomChangeAttribution.prepare(mapView, "route_preview_fit")
    mapView.model.mapViewPosition.setZoomLevel(chosenZoom.toByte(), false)
}
