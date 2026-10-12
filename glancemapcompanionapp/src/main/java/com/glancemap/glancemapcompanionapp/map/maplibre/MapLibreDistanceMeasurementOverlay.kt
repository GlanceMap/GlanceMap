package com.glancemap.glancemapcompanionapp.map.maplibre

import com.glancemap.glancemapcompanionapp.map.PhoneMapDistanceMeasurement
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Endpoint labels use the shared point-selection markers above this connecting segment. */
internal fun Style.renderDistanceMeasurement(measurement: PhoneMapDistanceMeasurement?) {
    val lineFeatures =
        measurement
            ?.let { value ->
                listOf(
                    Feature.fromGeometry(
                        LineString.fromLngLats(
                            listOf(
                                Point.fromLngLat(value.first.longitude, value.first.latitude),
                                Point.fromLngLat(value.second.longitude, value.second.latitude),
                            ),
                        ),
                    ),
                )
            }.orEmpty()
    val lineSource = getSourceAs<GeoJsonSource>(DISTANCE_LINE_SOURCE_ID)
    if (lineSource == null) {
        addSource(GeoJsonSource(DISTANCE_LINE_SOURCE_ID, FeatureCollection.fromFeatures(lineFeatures)))
    } else {
        lineSource.setGeoJson(FeatureCollection.fromFeatures(lineFeatures))
    }

    val layerVisibility = if (measurement == null) Property.NONE else Property.VISIBLE
    val lineLayer = getLayerAs<LineLayer>(DISTANCE_LINE_LAYER_ID)
    if (lineLayer == null) {
        val layer =
            LineLayer(DISTANCE_LINE_LAYER_ID, DISTANCE_LINE_SOURCE_ID).withProperties(
                lineCap(Property.LINE_CAP_ROUND),
                lineColor(DISTANCE_LINE_COLOR),
                lineJoin(Property.LINE_JOIN_ROUND),
                lineOpacity(0.9f),
                lineWidth(4f),
                visibility(layerVisibility),
            )
        if (getLayer(POINT_SELECTION_LAYER_ID) == null) {
            addLayer(layer)
        } else {
            addLayerBelow(layer, POINT_SELECTION_LAYER_ID)
        }
    } else {
        lineLayer.setProperties(
            lineColor(DISTANCE_LINE_COLOR),
            lineOpacity(0.9f),
            lineWidth(4f),
            visibility(layerVisibility),
        )
    }
}

private const val DISTANCE_LINE_LAYER_ID = "companion-distance-measurement-line"
private const val DISTANCE_LINE_SOURCE_ID = "companion-distance-measurement-line"
private const val DISTANCE_LINE_COLOR = "#0284C7"
