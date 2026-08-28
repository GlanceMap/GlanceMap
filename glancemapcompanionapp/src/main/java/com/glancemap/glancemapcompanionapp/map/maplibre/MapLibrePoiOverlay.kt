package com.glancemap.glancemapcompanionapp.map.maplibre

import android.graphics.PointF
import com.glancemap.glancemapcompanionapp.map.PhoneMapPoi
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Renders semantic POIs with one reusable MapLibre GeoJSON source and circle layer. */
internal fun Style.renderPois(
    pois: List<PhoneMapPoi>,
    isVisible: Boolean,
) {
    val featureCollection = FeatureCollection.fromFeatures(pois.map(PhoneMapPoi::toGeoJsonFeature))
    val source = getSourceAs<GeoJsonSource>(POI_SOURCE_ID)
    if (source == null) {
        addSource(GeoJsonSource(POI_SOURCE_ID, featureCollection))
    } else {
        source.setGeoJson(featureCollection)
    }

    val layerVisibility = if (isVisible && pois.isNotEmpty()) Property.VISIBLE else Property.NONE
    val layer = getLayerAs<CircleLayer>(POI_LAYER_ID)
    if (layer == null) {
        addLayer(
            CircleLayer(POI_LAYER_ID, POI_SOURCE_ID).withProperties(
                circleColor(POI_MARKER_COLOR),
                circleRadius(POI_MARKER_RADIUS),
                circleStrokeColor(POI_MARKER_STROKE_COLOR),
                circleStrokeWidth(POI_MARKER_STROKE_WIDTH),
                visibility(layerVisibility),
            ),
        )
    } else {
        layer.setProperties(visibility(layerVisibility))
    }
}

internal fun MapLibreMap.poiIdAt(screenPoint: PointF): String? =
    queryRenderedFeatures(screenPoint, POI_LAYER_ID)
        .firstOrNull()
        ?.getStringProperty(POI_ID_PROPERTY)

private fun PhoneMapPoi.toGeoJsonFeature(): Feature =
    Feature
        .fromGeometry(
            Point.fromLngLat(location.longitude, location.latitude),
        ).also { feature ->
            feature.addStringProperty(POI_ID_PROPERTY, id)
        }

private const val POI_ID_PROPERTY = "id"
private const val POI_LAYER_ID = "companion-poi-circle"
private const val POI_MARKER_COLOR = "#1976D2"
private const val POI_MARKER_RADIUS = 7f
private const val POI_MARKER_STROKE_COLOR = "#FFFFFF"
private const val POI_MARKER_STROKE_WIDTH = 2f
private const val POI_SOURCE_ID = "companion-poi"
