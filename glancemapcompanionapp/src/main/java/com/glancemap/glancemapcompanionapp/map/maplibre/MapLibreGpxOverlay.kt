package com.glancemap.glancemapcompanionapp.map.maplibre

import com.glancemap.glancemapcompanionapp.map.PhoneMapRouteBounds
import com.glancemap.glancemapcompanionapp.map.PhoneMapRouteSegment
import com.glancemap.glancemapcompanionapp.map.boundsOrNull
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

internal fun Style.renderGpxTrack(
    segments: List<PhoneMapRouteSegment>,
    isVisible: Boolean,
) {
    val featureCollection =
        FeatureCollection.fromFeatures(
            segments.map(PhoneMapRouteSegment::toGeoJsonFeature),
        )
    val source = getSourceAs<GeoJsonSource>(GPX_TRACK_SOURCE_ID)
    if (source == null) {
        addSource(GeoJsonSource(GPX_TRACK_SOURCE_ID, featureCollection))
    } else {
        source.setGeoJson(featureCollection)
    }

    val visibility = if (isVisible && segments.isNotEmpty()) Property.VISIBLE else Property.NONE
    val layer = getLayerAs<LineLayer>(GPX_TRACK_LAYER_ID)
    if (layer == null) {
        addLayer(
            LineLayer(GPX_TRACK_LAYER_ID, GPX_TRACK_SOURCE_ID).withProperties(
                lineCap(Property.LINE_CAP_ROUND),
                lineColor(GPX_TRACK_COLOR),
                lineJoin(Property.LINE_JOIN_ROUND),
                lineWidth(GPX_TRACK_LINE_WIDTH),
                visibility(visibility),
            ),
        )
    } else {
        layer.setProperties(visibility(visibility))
    }
}

internal fun MapLibreMap.fitGpxTrackBounds(
    mapView: MapView,
    segments: List<PhoneMapRouteSegment>,
    onFitted: () -> Unit,
) {
    val bounds = segments.boundsOrNull()?.toMapLibreBounds() ?: return
    mapView.fitBoundsWhenLaidOut(this, bounds, onFitted)
}

private fun PhoneMapRouteSegment.toGeoJsonFeature(): Feature =
    Feature.fromGeometry(
        LineString.fromLngLats(
            points.map { point -> Point.fromLngLat(point.longitude, point.latitude) },
        ),
    )

private fun PhoneMapRouteBounds.toMapLibreBounds(): LatLngBounds {
    val latitudePadding = ((north - south) / 20.0).coerceAtLeast(MINIMUM_ROUTE_BOUND_PADDING_DEGREES)
    val longitudePadding = ((east - west) / 20.0).coerceAtLeast(MINIMUM_ROUTE_BOUND_PADDING_DEGREES)
    return LatLngBounds
        .Builder()
        .include(
            LatLng(
                (south - latitudePadding).coerceAtLeast(MAP_LATITUDE_LIMIT_MIN),
                (west - longitudePadding).coerceAtLeast(MAP_LONGITUDE_LIMIT_MIN),
            ),
        ).include(
            LatLng(
                (north + latitudePadding).coerceAtMost(MAP_LATITUDE_LIMIT_MAX),
                (east + longitudePadding).coerceAtMost(MAP_LONGITUDE_LIMIT_MAX),
            ),
        ).build()
}

private fun MapView.fitBoundsWhenLaidOut(
    map: MapLibreMap,
    bounds: LatLngBounds,
    onFitted: () -> Unit,
) {
    post {
        if (isDestroyed) return@post
        if (width == 0 || height == 0) {
            fitBoundsWhenLaidOut(map, bounds, onFitted)
            return@post
        }
        map.moveCamera(
            CameraUpdateFactory.newLatLngBounds(
                bounds,
                (ROUTE_FIT_PADDING_DP * resources.displayMetrics.density).toInt(),
            ),
        )
        onFitted()
    }
}

private const val GPX_TRACK_LAYER_ID = "companion-gpx-track-line"
private const val GPX_TRACK_SOURCE_ID = "companion-gpx-track"
private const val GPX_TRACK_COLOR = "#0066CC"
private const val GPX_TRACK_LINE_WIDTH = 5f
private const val MAP_LATITUDE_LIMIT_MAX = 85.0
private const val MAP_LATITUDE_LIMIT_MIN = -85.0
private const val MAP_LONGITUDE_LIMIT_MAX = 180.0
private const val MAP_LONGITUDE_LIMIT_MIN = -180.0
private const val MINIMUM_ROUTE_BOUND_PADDING_DEGREES = 0.005
private const val ROUTE_FIT_PADDING_DP = 48
